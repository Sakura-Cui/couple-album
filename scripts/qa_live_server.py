"""Local-only Live Photo playback fixture; never deploy this script."""
import hashlib
import io
import struct
import sys
import os
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
from sakura.main import create_app, thumbnail
from sakura.storage import Store, digest, PASSWORDS

store = Store(Path(os.environ.get('SAKURA_QA_DATA', ROOT / '.tools/qa-live-data')), quota=1024**3, reserve=0)
identifier = b'F46909A0-386E-46D7-B394-95C08692715B\0'
note = b'Apple iOS\0\0\x01MM' + struct.pack('>H', 1)
note += struct.pack('>HHII', 17, 2, len(identifier), 32) + b'\0' * 4 + identifier
exif = Image.Exif()
exif[34665] = {37500: note}
photo = io.BytesIO()
Image.new('RGB', (480, 320), '#e49ab0').save(photo, 'JPEG', exif=exif)
ordinary = io.BytesIO()
Image.new('RGB', (480, 320)).save(ordinary, 'JPEG')
with store.connect() as db:
    db.execute('DELETE FROM media')
    db.execute('DELETE FROM albums')
    db.execute('DELETE FROM unlocks')
    db.execute('DELETE FROM devices')
    db.execute('INSERT INTO devices(id,name,token,created) VALUES(?,?,?,?)', ('qa-live', '实况验收', digest('qa-live-only'), 1))
    for aid in ('a' * 32, 'b' * 32, 'c' * 32):
        db.execute('INSERT INTO albums(id,name,created) VALUES(?,?,?)', (aid, '实况验收', 1))
    db.execute('UPDATE settings SET password=? WHERE id=1', (PASSWORDS.hash('qa-password'),))
    db.execute('UPDATE albums SET protected=1 WHERE id=?', ('c' * 32,))
    fixtures = [('1' * 32, 'a' * 32, 'capture.JPG', 'image/jpeg', photo.getvalue()),
                ('2' * 32, 'a' * 32, 'capture.MOV', 'video/quicktime', (ROOT / '.tools/player-comparison/samples/live.MOV').read_bytes()),
                ('3' * 32, 'b' * 32, 'missing.JPG', 'image/jpeg', photo.getvalue()),
                ('4' * 32, 'b' * 32, 'ordinary.JPG', 'image/jpeg', ordinary.getvalue()),
                ('5' * 32, 'a' * 32, 'original.MOV', 'video/quicktime', (ROOT / '.tools/player-comparison/samples/IMG_2713.MOV').read_bytes()),
                ('6' * 32, 'c' * 32, 'protected.MOV', 'video/quicktime', (ROOT / '.tools/player-comparison/samples/IMG_2713.MOV').read_bytes())]
    for mid, aid, name, mime, data in fixtures:
        source = store.root / 'media' / mid
        source.write_bytes(data)
        if mime.startswith('image/'):
            thumbnail(source, store.root / 'thumbnails' / mid)
        db.execute('INSERT INTO media VALUES(?,?,?,?,?,?,?,?)', (mid, aid, name, mime, len(data), hashlib.sha256(data).hexdigest(), 1, int(mime.startswith('image/'))))
app = create_app(store)
# This fixture deliberately exercises the legacy MP4 path.
from fastapi.responses import JSONResponse
@app.middleware('http')
async def legacy_playback_fixture(request, call_next):
    if request.url.path.endswith('/playback/adaptive'):
        return JSONResponse({'status': 'unsupported'})
    return await call_next(request)
# Local regression controls for a failed copy followed by a successful regeneration.
import time
from fastapi import Request
from sakura.playback import PlaybackCopies
copies = PlaybackCopies(store.root)
retry_ready_at = 0.0
@app.get('/qa/playback/invite')
def video_invite():
    with store.lock(), store.connect() as db:
        db.execute("UPDATE devices SET active=0 WHERE id<>'qa-live'")
    return {'invite':store.invite()}

@app.post('/qa/playback/reset')
def reset_copy():
    global retry_ready_at
    retry_ready_at = 0.0
    with store.connect() as db:
        copies.write(db.execute('SELECT * FROM media WHERE id=?', ('5'*32,)).fetchone(), 'failed')
    return {'ok':True}

@app.middleware('http')
async def finish_test_copy(request: Request, call_next):
    global retry_ready_at
    path = '/api/media/' + '5'*32 + '/playback'
    if request.url.path == path and retry_ready_at and time.monotonic() >= retry_ready_at:
        with store.connect() as db:
            copies.write(db.execute('SELECT * FROM media WHERE id=?', ('5'*32,)).fetchone(), 'ready')
        retry_ready_at = 0.0
    response = await call_next(request)
    if request.url.path == path + '/retry' and response.status_code == 200:
        retry_ready_at = time.monotonic() + 1
    return response

# Delay fresh HTTP range requests to reproduce buffering after the first frame.
content_delay = 0.0
@app.post('/qa/playback/delay')
def delay_playback(seconds: float = 0):
    global content_delay
    content_delay = max(0, min(seconds, 3))
    return {'seconds': content_delay}

@app.middleware('http')
async def delayed_video_range(request: Request, call_next):
    if content_delay and request.method == 'GET' and request.url.path.endswith('/content'):
        import asyncio
        await asyncio.sleep(content_delay)
    return await call_next(request)

# Optional throttling for real Media3 startup-buffer progress tests.
from fastapi.responses import StreamingResponse
stream_delay = 0.0
@app.post('/qa/playback/stream')
def stream_playback(seconds: float = 0):
    global stream_delay
    stream_delay = max(0, min(seconds, .2))
    return {'seconds':stream_delay}

@app.middleware('http')
async def streamed_video(request: Request, call_next):
    response = await call_next(request)
    if stream_delay and request.method == 'GET' and request.url.path.endswith('/content') and response.status_code in (200, 206):
        import asyncio
        pause = stream_delay
        async def body():
            async for chunk in response.body_iterator:
                for offset in range(0, len(chunk), 16384):
                    await asyncio.sleep(pause)
                    yield chunk[offset:offset+16384]
        return StreamingResponse(body(), status_code=response.status_code, headers=dict(response.headers))
    return response

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=18448, ssl_keyfile=str(ROOT / '.tools/qa-tls/key.pem'),
                ssl_certfile=str(ROOT / '.tools/qa-tls/cert.pem'), access_log=False)
