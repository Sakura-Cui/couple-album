"""Disposable, local-only original-video streaming fixture. Never deploy."""
import asyncio
import hashlib
import os
import sys
from pathlib import Path
from fastapi.responses import StreamingResponse
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
from sakura.main import create_app
from sakura.storage import Store
from sakura.playback import PlaybackCopies
sample = Path(os.environ.get('SAKURA_QA_ORIGINAL', ROOT / 'artifacts/original-video-1.2.17/limited-original.mp4'))
store = Store(Path(os.environ['SAKURA_QA_DATA']), reserve=0)
mid, aid = '7' * 32, 'a' * 32
with store.connect() as db:
    db.execute('INSERT OR REPLACE INTO albums(id,name,created) VALUES(?,?,?)', (aid, '原片播放验收', 1))
    target = store.root / 'media' / mid
    if not target.exists(): target.symlink_to(sample.resolve())
    sha = hashlib.file_digest(sample.open('rb'), 'sha256').hexdigest()
    db.execute('INSERT OR REPLACE INTO media VALUES(?,?,?,?,?,?,?,?)', (mid, aid, 'original.mp4', 'video/mp4', sample.stat().st_size, sha, 1, 0))
    PlaybackCopies(store.root).write(db.execute('SELECT * FROM media WHERE id=?', (mid,)).fetchone(), 'original')
from PIL import Image
photo_id = '8' * 32
photo_path = store.root / 'media' / photo_id
Image.new('RGB', (80, 80), '#e49ab0').save(photo_path, format='PNG')
(store.root / 'thumbnails' / photo_id).write_bytes(photo_path.read_bytes())
with store.connect() as db:
    db.execute('INSERT OR REPLACE INTO media VALUES(?,?,?,?,?,?,?,?)', (photo_id, aid, 'next.png', 'image/png', photo_path.stat().st_size, hashlib.sha256(photo_path.read_bytes()).hexdigest(), 0, 1))
app = create_app(store)
# This fixture deliberately exercises the legacy MP4 path.
from fastapi.responses import JSONResponse
@app.middleware('http')
async def legacy_playback_fixture(request, call_next):
    if request.url.path.endswith('/playback/adaptive'):
        return JSONResponse({'status': 'unsupported'})
    return await call_next(request)
rate = int(os.environ.get('SAKURA_QA_BYTES_PER_SECOND', '16384'))
sent = 0
requests = 0
ranges = []
fail_after = 0
@app.post('/qa/reset')
def reset():
    global sent, requests, rate, fail_after
    sent = requests = fail_after = 0
    ranges.clear()
    with store.lock(), store.connect() as db:
        db.execute('UPDATE albums SET protected=0')
        db.execute('UPDATE settings SET password=NULL,version=version+1')
        db.execute('DELETE FROM unlocks')
        db.execute('UPDATE devices SET active=0')
    return {'ok': True}
@app.get('/qa/invite')
def invite(): return {'invite': store.invite()}
@app.get('/qa/state')
def state(): return {'sent': sent, 'requests': requests, 'size': sample.stat().st_size}
@app.post('/qa/stream')
def controls(bytes_per_second: int = 0, truncate_after: int = 0):
    global rate, fail_after
    rate, fail_after = max(0, bytes_per_second), max(0, truncate_after)
    return {'ok': True}
@app.get('/qa/ranges')
def range_state(): return {'ranges': ranges}
@app.middleware('http')
async def throttle(request, call_next):
    global requests
    response = await call_next(request)
    if request.url.path == '/api/media/' + mid + '/content' and response.status_code in (200, 206):
        requests += 1
        ranges.append(request.headers.get("range", ""))
        async def body():
            global sent
            delivered = 0
            async for chunk in response.body_iterator:
                for offset in range(0, len(chunk), 16384):
                    part = chunk[offset:offset+16384]
                    if rate: await asyncio.sleep(len(part) / rate)
                    sent += len(part)
                    delivered += len(part)
                    yield part
                    if fail_after and delivered >= fail_after: return
        return StreamingResponse(body(), status_code=response.status_code, headers=dict(response.headers))
    return response
if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=18449, ssl_keyfile=str(ROOT/'.tools/qa-tls/key.pem'), ssl_certfile=str(ROOT/'.tools/qa-tls/cert.pem'), access_log=False)
