import re

from fastapi import HTTPException
from fastapi.responses import Response, StreamingResponse


def stream_media(store, copies, live_copies, check_access, check_media, fail, media_id, auth, range_value=None, thumb=False, playback=False, adaptive_asset=None, live=False):
    with store.connect() as db:
        check_access(db, *auth)
        row = check_media(db, media_id, auth)
        if thumb and not row['thumbnail']:
            fail(404, '暂无封面')
    kind = 'thumbnails' if thumb else 'playback' if playback else 'media'
    if adaptive_asset:
        with store.lock():
            adaptive = copies.adaptive_status(row)
            preview = adaptive.get('preview_ready', False)
            if store.objects.remote or (adaptive['status'] != 'ready' and not preview):
                fail(409, '清晰度副本尚未就绪')
            if not re.fullmatch(r'(master|480|720|1080)\.m3u8|(480|720|1080)-[0-9]{5}\.ts', adaptive_asset):
                fail(404, '文件不存在')
            bundle = copies.adaptive_path(media_id)
            if adaptive['status'] == 'processing' and bundle.with_suffix('.hls.tmp').is_dir():
                bundle = bundle.with_suffix('.hls.tmp')
            if preview and adaptive_asset != 'master.m3u8' and not adaptive_asset.startswith('480'):
                fail(409, '该清晰度尚在准备中')
            path = bundle / adaptive_asset
            if adaptive_asset == 'master.m3u8' and adaptive['status'] == 'ready' and (bundle / 'master-ready.m3u8').is_file():
                path = bundle / 'master-ready.m3u8'
            if not path.is_file():
                fail(404, '文件不存在')
            mime = 'application/vnd.apple.mpegurl' if adaptive_asset.endswith('.m3u8') else 'video/mp2t'
            if adaptive_asset.endswith('.m3u8'):
                return Response(path.read_bytes(), media_type=mime, headers={'Cache-Control': 'no-store'})
            row = dict(row, id=bundle.name + '/' + adaptive_asset, mime=mime, size=path.stat().st_size)
    elif playback:
        selected = live_copies if live else copies
        if store.objects.remote or selected.status(row)['status'] != 'ready':
            fail(409, '播放副本尚未就绪')
        row = dict(row, id=selected.path(media_id).name, mime='video/mp4', size=selected.path(media_id).stat().st_size)
    try:
        size = store.objects.size(kind, row['id']) if thumb else row['size']
    except FileNotFoundError:
        fail(404, '文件不存在，请联系管理员检查备份')
    status = 200
    start, end = 0, size - 1
    headers = {'Accept-Ranges': 'bytes'}
    if adaptive_asset:
        headers['Cache-Control'] = 'no-store'
    if range_value:
        match = re.fullmatch(r'bytes=(\d*)-(\d*)', range_value)
        if not match or not any(match.groups()):
            raise HTTPException(416, '无效的范围请求', headers={'Content-Range': f'bytes */{size}'})
        first, last = match.groups()
        if first:
            start, end = int(first), min(int(last), size - 1) if last else size - 1
        else:
            start = max(0, size - int(last))
        if start >= size or start > end:
            raise HTTPException(416, '范围超出文件', headers={'Content-Range': f'bytes */{size}'})
        status = 206
        headers['Content-Range'] = f'bytes {start}-{end}/{size}'
    headers['Content-Length'] = str(end - start + 1)
    def stream():
        def adaptive_stream():
            # An in-flight request can straddle the atomic staging-directory rename.
            try:
                file = path.open('rb')
            except FileNotFoundError:
                file = (copies.adaptive_path(media_id) / adaptive_asset).open('rb')
            with file:
                file.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    data = file.read(min(256 * 1024, remaining))
                    if not data:
                        return
                    remaining -= len(data)
                    yield data
        source = adaptive_stream() if adaptive_asset else store.objects.read(kind, row['id'], start, end)
        try:
            # Starlette can resume this serialized iterator on different pool threads.
            with store.connect(cross_thread=True) as db:
                for data in source:
                    try:
                        if playback or adaptive_asset:
                            check_media(db, media_id, auth)
                        else:
                            check_access(db, *auth)
                    except HTTPException:
                        return
                    yield data
        finally:
            source.close()
    return StreamingResponse(stream(), status_code=status, media_type='image/jpeg' if thumb else row['mime'], headers=headers)

