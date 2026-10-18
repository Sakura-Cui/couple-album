"""Local-only showcase using real APIs, synthetic media, and disposable data."""
import asyncio
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
os.environ['SAKURA_STORAGE'] = 'local'
os.environ['SAKURA_NOTIFY_KEY'] = secrets.token_urlsafe(32)

from fastapi.testclient import TestClient
from sakura.main import create_app
from sakura.storage import Store
from sakura.playback import PlaybackCopies

data = Path(os.environ.get('SAKURA_SHOWCASE_DATA', ROOT / '.tools/showcase-data')).resolve()
if data.exists():
    raise RuntimeError('Use a fresh showcase directory; existing data is never cleared automatically.')
store = Store(data, reserve=0)
app = create_app(store)
image_delay = 0.0


@app.middleware('http')
async def slow_demo_images(request, call_next):
    if image_delay and request.url.path.endswith('/content'):
        await asyncio.sleep(image_delay)
    return await call_next(request)


client = TestClient(app)
token = client.post('/api/bind', json={'invite': store.invite(), 'name': '演示内容准备'}).json()['token']
auth = {'Authorization': 'Bearer ' + token}


def album(name, protected=False):
    response = client.post('/api/albums', headers=auth, json={'name': name, 'protected': protected})
    response.raise_for_status()
    return response.json()['id']


def upload(aid, path, mime, title):
    content = path.read_bytes()
    response = client.post('/api/uploads', headers=auth, json={
        'request_id': secrets.token_hex(8), 'album_id': aid, 'name': title,
        'mime': mime, 'size': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
    response.raise_for_status()
    uid = response.json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=auth, content=content).raise_for_status()
    completed = client.post(f'/api/uploads/{uid}/complete', headers=auth)
    completed.raise_for_status()
    return completed.json()['media_id']


first = album('甜蜜日常')
second = album('心动收藏')
album('未来的旅行')
resources = ROOT / 'android/app/src/main/res/drawable-nodpi'
upload(first, resources / 'sakura_romance_icon.png', 'image/png', '樱花与我们的日常.png')
upload(first, resources / 'encrypted_album_cover.png', 'image/png', '珍藏的心动瞬间.png')
upload(second, resources / 'sakura_romance_icon.png', 'image/png', '专属回忆.png')
video = data / 'showcase-fixture.mp4'
subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                'testsrc2=size=720x1280:rate=30', '-t', '6', '-c:v', 'libx264',
                '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(video)], check=True)
mid = upload(first, video, 'video/mp4', '演示视频（合成测试画面）.mp4')
with store.connect() as db:
    row = dict(db.execute('SELECT * FROM media WHERE id=?', (mid,)).fetchone())
PlaybackCopies(data).write(row, 'original')
copies = PlaybackCopies(data)
bundle = copies.adaptive_path(mid)
bundle.mkdir(parents=True, exist_ok=True)
manifest = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-INDEPENDENT-SEGMENTS']
qualities = []
for quality, label, width, height, bitrate in [('480', '流畅', 480, 854, 1000000), ('720', '高清', 720, 1280, 1900000)]:
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', str(video),
        '-vf', f'scale={width}:{height}', '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p',
        '-g', '60', '-sc_threshold', '0', '-an', '-f', 'hls', '-hls_time', '2', '-hls_playlist_type', 'vod',
        '-hls_segment_filename', str(bundle / (quality + '-%05d.ts')), str(bundle / (quality + '.m3u8'))], check=True)
    manifest += [f'#EXT-X-STREAM-INF:BANDWIDTH={bitrate},RESOLUTION={width}x{height}', quality + '.m3u8']
    qualities.append({'id': quality, 'label': label, 'width': width, 'height': height})
(bundle / 'master.m3u8').write_text('\n'.join(manifest) + '\n')
copies.write_adaptive(row, 'ready', qualities, original_compatible=True)
client.put('/api/password', headers=auth, json={'new_password': 'showcase-only-password'}).raise_for_status()
auth['X-Unlock-Token'] = client.post('/api/unlock', headers=auth, json={'password': 'showcase-only-password'}).json()['unlock_token']
album('只属于我们的秘密', protected=True)
client.put('/api/profile', headers=auth, json={'signature': '把平凡的日子，收藏成我们的故事'}).raise_for_status()

@app.post('/showcase/delay')
def delay(seconds: float = 0):
    global image_delay
    image_delay = max(0, min(seconds, 5))
    return {'seconds': image_delay}


@app.post('/showcase/letter')
def letter():
    with store.connect() as db:
        device = db.execute("SELECT id FROM devices WHERE name='公开演示设备' AND active=1").fetchone()
    response = client.post('/api/notify', headers={'Authorization': 'Bearer ' + os.environ['SAKURA_NOTIFY_KEY']}, json={
        'request_id': 'showcase-letter-' + device['id'], 'device_id': device['id'],
        'title': '把今天的小事讲给你听', 'text': '这是用于项目展示的合成消息。\n希望每一个普通的日子，都值得一起收藏。'})
    response.raise_for_status()
    return {'stored': True}


if __name__ == '__main__':
    import uvicorn
    (ROOT / '.tools/showcase-invite').write_text(store.invite())
    uvicorn.run(app, host='127.0.0.1', port=18444,
                ssl_keyfile=str(ROOT / '.tools/qa-tls/key.pem'),
                ssl_certfile=str(ROOT / '.tools/qa-tls/cert.pem'), access_log=False)
