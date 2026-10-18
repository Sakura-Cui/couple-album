"""Private, disposable H.264 playback copies; originals and album rows stay unchanged."""
import fcntl
import json
import logging
import os
import shutil
import sqlite3
import subprocess
import time
from contextlib import closing, contextmanager
from concurrent.futures import ThreadPoolExecutor
from fractions import Fraction
from pathlib import Path

LOG = logging.getLogger(__name__)


class PlaybackCopies:
    def __init__(self, root, live=False):
        self.root = Path(root) / 'playback'
        self.suffix = '.live' if live else ''

    def path(self, media_id):
        return self.root / (media_id + self.suffix + '.mp4')

    def status(self, row):
        if not row['mime'].startswith('video/'):
            return {'status': 'original'}
        try:
            data = json.loads((self.root / (row['id'] + self.suffix + '.json')).read_text())
            if data['sha256'] != row['sha256']:
                return {'status': 'pending'}
            state = data['status']
            if state == 'ready':
                size = self.path(row['id']).stat().st_size
                return {'status': 'ready', 'size': size} if size else {'status': 'pending'}
            if state in ('original', 'processing', 'failed'):
                return {'status': state}
        except (OSError, ValueError, KeyError):
            pass
        return {'status': 'pending'}

    def write(self, row, status):
        self.root.mkdir(exist_ok=True, mode=0o700)
        destination = self.root / (row['id'] + self.suffix + '.json')
        temp = destination.with_suffix('.json.tmp')
        temp.write_text(json.dumps({'sha256': row['sha256'], 'status': status, 'updated': time.time()}))
        temp.replace(destination)

    def adaptive_path(self, media_id):
        return self.root / (media_id + '.hls')

    def adaptive_requested(self, row):
        return (self.root / (row['id'] + '.hls.json')).exists()

    def adaptive_status(self, row):
        try:
            data = json.loads((self.root / (row['id'] + '.hls.json')).read_text())
            if data['sha256'] != row['sha256']:
                return {'status': 'pending'}
            state = data['status']
            if state == 'ready' or data.get('preview_ready'):
                bundle = self.adaptive_path(row['id'])
                if state == 'processing':
                    staging = bundle.with_suffix('.hls.tmp')
                    if staging.is_dir():
                        bundle = staging
                if not (bundle / 'master.m3u8').is_file() or not data['qualities']:
                    return {'status': 'pending'}
                return {'status': state, 'qualities': data['qualities'], 'original_compatible': data['original_compatible'],
                        'preview_ready': bool(data.get('preview_ready'))}
            if state in ('pending', 'processing', 'failed'):
                return {'status': state}
        except (OSError, ValueError, KeyError):
            pass
        return {'status': 'pending'}

    def write_adaptive(self, row, status, qualities=None, original_compatible=False, preview_ready=False):
        self.root.mkdir(exist_ok=True, mode=0o700)
        destination = self.root / (row['id'] + '.hls.json')
        temp = destination.with_suffix('.json.tmp')
        temp.write_text(json.dumps({'sha256': row['sha256'], 'status': status, 'updated': time.time(),
                                   'qualities': qualities or [], 'original_compatible': original_compatible, 'preview_ready': preview_ready}))
        temp.replace(destination)


def needs_copy(info):
    video = next((s for s in info['streams'] if s['codec_type'] == 'video'), None)
    audio = next((s for s in info['streams'] if s['codec_type'] == 'audio'), None)
    return bool(video and (video['codec_name'] != 'h264' or video.get('pix_fmt') != 'yuv420p'
                          or max(video['width'], video['height']) > 1920
                          or video.get('color_transfer') in ('arib-std-b67', 'smpte2084')
                          or (audio and audio['codec_name'] != 'aac')))


def transcode_command(source, destination, video, audio=None, live=False):
    # FFmpeg autorotates before these filters; cap landscape and portrait independently.
    filters = "scale=w='if(gte(iw,ih),min(1920,iw),min(1080,iw))':h='if(gte(iw,ih),min(1080,ih),min(1920,ih))':force_original_aspect_ratio=decrease:force_divisible_by=2"
    if live:
        filters = 'scale=trunc(iw/2)*2:trunc(ih/2)*2'
    if video.get('color_transfer') in ('arib-std-b67', 'smpte2084'):
        filters += ',zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=mobius:desat=2,zscale=t=bt709:m=bt709:r=limited'
    filters += ',format=yuv420p,setsar=1'
    fps = 60 if Fraction(video.get('avg_frame_rate', '0/1')) > Fraction(301, 10) else 30
    command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
            '-threads', '1', '-filter_threads', '1', '-i', str(source),
            '-map', '0:v:0', *(['-map', f"0:{audio['index']}"] if audio else ['-an']), '-map_metadata', '-1', '-map_chapters', '-1',
            '-vf', filters, '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '22',
            '-maxrate', '8M' if fps == 60 else '5M', '-bufsize', '16M' if fps == 60 else '10M',
            '-profile:v', 'high', '-level:v', '4.2' if fps == 60 else '4.1',
            '-pix_fmt', 'yuv420p', '-r', str(fps), '-threads', '1',
            '-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709', '-color_range', 'tv',
            '-c:a', 'aac', '-b:a', '128k', '-ac', '2', '-movflags', '+faststart', str(destination)]
    if live:
        # Keep every original frame and its timing; do not force a 30fps timeline.
        for flag in ('-r', '-level:v'):
            index = command.index(flag)
            del command[index:index + 2]
        for flag, value in (('-crf', '18'), ('-maxrate', '12M'), ('-bufsize', '24M')):
            command[command.index(flag) + 1] = value
        command[-1:-1] = ['-fps_mode', 'passthrough', '-enc_time_base:v', '-1']
    return command


class PlaybackWorker:
    def __init__(self, root, reserve=None):
        self.root = Path(root)
        self.copies = PlaybackCopies(root)
        self.reserve = reserve if reserve is not None else int(os.environ.get('SAKURA_RESERVE_BYTES', 12 * 1024**3))

    @contextmanager
    def lock(self):
        with (self.root / '.write.lock').open('a') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)

    def connect(self, writable=False):
        mode = 'rw' if writable else 'ro'
        db = sqlite3.connect('file:' + str(self.root / 'sakura.sqlite') + '?mode=' + mode, uri=True, timeout=30)
        db.row_factory = sqlite3.Row
        return db

    def probe(self, source):
        result = subprocess.run(['ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(source)],
                                capture_output=True, check=True, timeout=60)
        return json.loads(result.stdout)

    def encode(self, command, timeout):
        # Bound diagnostics on disk, never include native error text in the API response.
        with (self.copies.root / (Path(command[-1]).name.split('.')[0] + '.error.log')).open('wb') as log:
            subprocess.run(command, stdout=subprocess.DEVNULL, stderr=log, check=True, timeout=timeout)

    def process(self, row, live=False):
        copies = PlaybackCopies(self.root, live=True) if live else self.copies
        mid = row['id']
        temp = copies.path(mid).with_suffix('.tmp.mp4')
        with self.lock(), closing(self.connect()) as db:
            if not db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                return
            copies.write(row, 'processing')
        try:
            source = self.root / 'media' / mid
            info = self.probe(source)
            if not live and not needs_copy(info):
                with self.lock(), closing(self.connect()) as db:
                    if db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                        copies.write(row, 'original')
                return
            video = next(s for s in info['streams'] if s['codec_type'] == 'video')
            duration = float(info.get('format', {}).get('duration', video.get('duration', 0)))
            if not 0 < duration <= 86400:
                raise ValueError('Invalid video duration')
            with closing(self.connect()) as db:
                unwritten = db.execute('SELECT unwritten FROM usage_totals WHERE id=1').fetchone()[0]
            budget = int(duration * (1_600_000 if live else 1_100_000)) + 256 * 1024**2
            if shutil.disk_usage(self.root).free - unwritten - budget < self.reserve:
                raise OSError('Insufficient reserved disk space')
            audio_tracks = [s for s in info['streams'] if s['codec_type'] == 'audio']
            # iPhone spatial recordings include unsupported APAC followed by a usable AAC track.
            audio = next((s for s in audio_tracks if s.get('codec_name') == 'aac'), None)
            audio = audio or next((s for s in audio_tracks if s.get('codec_name') not in (None, 'none', 'unknown')), None)
            if audio_tracks and audio is None:
                raise ValueError('No decodable audio track')
            self.encode(transcode_command(source, temp, video, audio, live=live), max(300, duration * 60))
            output = self.probe(temp)
            result = next(s for s in output['streams'] if s['codec_type'] == 'video')
            length = float(output['format']['duration'])
            if result['codec_name'] != 'h264' or result['pix_fmt'] != 'yuv420p' or (not live and max(result['width'], result['height']) > 1920) or abs(length - duration) > max(.5, duration * .01):
                raise ValueError('Playback copy validation failed')
            with self.lock(), closing(self.connect()) as db:
                if db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                    temp.replace(copies.path(mid))
                    copies.write(row, 'ready')
                    LOG.info('Playback copy ready: %s', mid)
        except Exception:
            LOG.exception('Playback copy failed: %s', mid)
            with self.lock(), closing(self.connect()) as db:
                if db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                    copies.write(row, 'failed')
        finally:
            temp.unlink(missing_ok=True)

    def process_adaptive(self, row):
        mid = row['id']
        bundle = self.copies.adaptive_path(mid)
        temp = bundle.with_suffix('.hls.tmp')
        legacy = self.copies.path(mid).with_suffix('.tmp.mp4')
        first_complete = False
        qualities = []
        compatible = False
        with self.lock(), closing(self.connect()) as db:
            if not db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                return
            self.copies.write_adaptive(row, 'processing')
        try:
            source = self.root / 'media' / mid
            info = self.probe(source)
            video = next(s for s in info['streams'] if s['codec_type'] == 'video')
            duration = float(info.get('format', {}).get('duration', video.get('duration', 0)))
            if not 0 < duration <= 86400:
                raise ValueError('Invalid video duration')
            audio_tracks = [s for s in info['streams'] if s['codec_type'] == 'audio']
            audio = next((s for s in audio_tracks if s.get('codec_name') == 'aac'), None)
            audio = audio or next((s for s in audio_tracks if s.get('codec_name') not in (None, 'none', 'unknown')), None)
            if audio_tracks and audio is None:
                raise ValueError('No decodable audio track')
            compatible = not needs_copy(info)
            profiles = [('480', '流畅', 854, 480, 1000000), ('720', '高清', 1280, 720, 1900000), ('1080', '超清', 1920, 1080, 2900000)]
            short_edge = min(video['width'], video['height'])
            profiles = [p for p in profiles if p[3] <= short_edge or p[0] == '480']
            with closing(self.connect()) as db:
                unwritten = db.execute('SELECT unwritten FROM usage_totals WHERE id=1').fetchone()[0]
            budget = int(duration * sum(p[4] + 96000 for p in profiles) / 8 * 1.2) + 256 * 1024**2
            if shutil.disk_usage(self.root).free - unwritten - budget < self.reserve:
                raise OSError('Insufficient reserved disk space')
            shutil.rmtree(temp, ignore_errors=True)
            temp.mkdir(mode=0o700)
            qualities = []
            manifest = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-INDEPENDENT-SEGMENTS']
            for quality, label, long_edge, short_edge, bitrate in profiles:
                playlist = temp / (quality + '.m3u8')
                command = transcode_command(source, playlist, video, audio)
                for flag, value in [('-r', '30'), ('-level:v', '4.1'), ('-maxrate', str(bitrate)), ('-bufsize', str(bitrate * 2)), ('-b:a', '96k')]:
                    command[command.index(flag) + 1] = value
                index = command.index('-vf') + 1
                command[index] = command[index].replace('1920', str(long_edge)).replace('1080', str(short_edge))
                command = command[:command.index('-movflags')] + ['-g', '120', '-keyint_min', '120', '-sc_threshold', '0',
                    '-force_key_frames', 'expr:gte(t,n_forced*4)', '-f', 'hls', '-hls_time', '4', '-hls_list_size', '0',
                    '-hls_playlist_type', 'event', '-hls_flags', 'independent_segments+temp_file',
                    '-hls_segment_filename', str(temp / (quality + '-%05d.ts')), str(playlist)]
                def publish_first_segment():
                    segment = temp / '480-00000.ts'
                    if not segment.is_file() or not playlist.is_file() or (temp / 'master.m3u8').exists():
                        return
                    output = self.probe(segment)
                    result = next(s for s in output['streams'] if s['codec_type'] == 'video')
                    if result['codec_name'] != 'h264':
                        raise ValueError('Invalid first segment')
                    bandwidth = int((bitrate + (96000 if audio else 0)) * 1.1)
                    preview = [{'id': quality, 'label': label, 'width': result['width'], 'height': result['height'], 'bandwidth': bandwidth}]
                    codecs = 'avc1.640029' + (',mp4a.40.2' if audio else '')
                    (temp / 'master.m3u8').write_text('\n'.join(manifest + [
                        f'#EXT-X-STREAM-INF:BANDWIDTH={bandwidth},RESOLUTION={result["width"]}x{result["height"]},CODECS="{codecs}"', quality + '.m3u8']) + '\n')
                    with self.lock(), closing(self.connect()) as db:
                        if db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                            self.copies.write_adaptive(row, 'processing', preview, compatible, preview_ready=True)
                if quality == '480':
                    # FFmpeg atomically publishes completed segments and an EVENT playlist.
                    with ThreadPoolExecutor(max_workers=1) as executor:
                        encoding = executor.submit(self.encode, command, max(300, duration * 60))
                        while not encoding.done():
                            publish_first_segment()
                            time.sleep(.2)
                        encoding.result()
                        publish_first_segment()
                else:
                    self.encode(command, max(300, duration * 60))
                output = self.probe(playlist)
                result = next(s for s in output['streams'] if s['codec_type'] == 'video')
                length = float(output['format']['duration'])
                if result['codec_name'] != 'h264' or abs(length - duration) > max(.5, duration * .01) or '#EXT-X-ENDLIST' not in playlist.read_text():
                    raise ValueError('Invalid adaptive output')
                bandwidth = int((bitrate + (96000 if audio else 0)) * 1.1)
                qualities.append({'id': quality, 'label': label, 'width': result['width'], 'height': result['height'], 'bandwidth': bandwidth})
                codecs = 'avc1.640029' + (',mp4a.40.2' if audio else '')
                manifest += [f'#EXT-X-STREAM-INF:BANDWIDTH={bandwidth},RESOLUTION={result["width"]}x{result["height"]},CODECS="{codecs}"', quality + '.m3u8']
                if quality == '480':
                    first_complete = True
                    # Older clients reuse the encoded base track; no second video encode.
                    self.encode(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-y', '-i', str(playlist),
                                 '-c', 'copy', '-movflags', '+faststart', str(legacy)], max(60, duration * 2))
                    if abs(float(self.probe(legacy)['format']['duration']) - duration) > max(.5, duration * .01):
                        raise ValueError('Invalid legacy remux')
                    with self.lock(), closing(self.connect()) as db:
                        if db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                            legacy.replace(self.copies.path(mid))
                            self.copies.write(row, 'ready')
            (temp / 'master-ready.m3u8').write_text('\n'.join(manifest) + '\n')
            with self.lock(), closing(self.connect()) as db:
                if db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                    shutil.rmtree(bundle, ignore_errors=True)
                    temp.replace(bundle)
                    self.copies.write_adaptive(row, 'ready', qualities, compatible)
                    LOG.info('Adaptive playback ready: %s', mid)
        except Exception:
            LOG.exception('Adaptive playback failed: %s', mid)
            with self.lock(), closing(self.connect()) as db:
                if db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone():
                    if first_complete:
                        shutil.rmtree(bundle, ignore_errors=True)
                        temp.replace(bundle)
                    self.copies.write_adaptive(row, 'failed', qualities[:1] if first_complete else [], compatible, preview_ready=first_complete)
        finally:
            shutil.rmtree(temp, ignore_errors=True)
            legacy.unlink(missing_ok=True)

    def make_thumbnail(self, row):
        mid = row['id']
        temp = self.root / 'thumbnails' / (mid + '.tmp.jpg')
        source = self.copies.path(mid) if self.copies.status(row)['status'] == 'ready' else self.root / 'media' / mid
        try:
            self.encode(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
                         '-threads', '1', '-i', str(source), '-map', '0:v:0', '-frames:v', '1',
                         '-vf', 'scale=640:640:force_original_aspect_ratio=decrease',
                         '-q:v', '3', '-threads', '1', str(temp)], 60)
            with self.lock(), closing(self.connect(writable=True)) as db:
                current = db.execute('SELECT thumbnail FROM media WHERE id=? AND sha256=?', (mid, row['sha256'])).fetchone()
                if current is not None and not current['thumbnail']:
                    temp.replace(self.root / 'thumbnails' / mid)
                    db.execute('UPDATE media SET thumbnail=1 WHERE id=?', (mid,))
                    db.commit()
        except Exception:
            LOG.exception('Video thumbnail failed: %s', mid)
        finally:
            temp.unlink(missing_ok=True)

    def run(self):
        self.copies.root.mkdir(exist_ok=True, mode=0o700)
        # One worker owns the queue, including after a container restart.
        with (self.root / '.playback-worker.lock').open('a') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            while True:
                with closing(self.connect()) as db:
                    rows = db.execute("SELECT * FROM media WHERE mime LIKE 'video/%' ORDER BY created DESC,id DESC").fetchall()
                live_copies = PlaybackCopies(self.root, live=True)
                requested = next((row for row in rows if (live_copies.root / (row['id'] + '.live.json')).exists()
                                  and live_copies.status(row)['status'] in ('pending', 'processing')), None)
                if requested is not None:
                    self.process(requested, live=True)
                    continue
                for row in rows:
                    state = self.copies.status(row)['status']
                    if not self.copies.adaptive_requested(row) and state not in ('original', 'ready'):
                        with self.lock(), closing(self.connect()) as db:
                            if db.execute('SELECT 1 FROM media WHERE id=? AND sha256=?', (row['id'], row['sha256'])).fetchone():
                                self.copies.write_adaptive(row, 'pending')
                    if self.copies.adaptive_requested(row):
                        adaptive = self.copies.adaptive_status(row)['status']
                        retry_at = (self.copies.root / (row['id'] + '.hls.json')).stat().st_mtime
                        if adaptive in ('pending', 'processing') or (adaptive == 'failed' and time.time() - retry_at >= 3600):
                            self.process_adaptive(row)
                            break
                        if not row['thumbnail']:
                            self.make_thumbnail(row)
                        continue
                    if not row['thumbnail']:
                        self.make_thumbnail(row)
                else:
                    time.sleep(1)


if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    PlaybackWorker(os.environ.get('SAKURA_DATA', '/data')).run()
