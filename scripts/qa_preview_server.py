"""Local-only delayed thumbnail fixture. Never deploy to production."""
import asyncio
import io
import os
import sys
import uuid
from pathlib import Path
from PIL import Image
from fastapi.responses import Response, StreamingResponse
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'backend'))
os.environ['SAKURA_STORAGE'] = 'local'
from sakura.main import create_app
from sakura.storage import Store
store = Store(root / '.tools/qa-preview-data', quota=1024**3, reserve=0)
app = create_app(store)
gate = asyncio.Event()
session_gate = asyncio.Event()
session_gate.set()
counts = {}
preview_ids = []
preview_gates = {}
fail = False
stream_images = False

@app.middleware('http')
async def previews(request, call_next):
    if request.url.path == '/api/session':
        await session_gate.wait()
    if request.method == 'GET' and request.url.path.endswith('/thumbnail'):
        counts[request.url.path] = counts.get(request.url.path, 0) + 1
        await preview_gates.get(request.url.path.split("/")[-2], gate).wait()
        if fail:
            return Response('Service Unavailable', status_code=503)
    if request.url.path == '/api/morning/briefs':
        return Response('QA disabled', status_code=503)
    response = await call_next(request)
    if stream_images and request.method == 'GET' and request.url.path.endswith('/content') and response.status_code == 200:
        async def slow_body():
            async for chunk in response.body_iterator:
                for offset in range(0, len(chunk), 8192):
                    await asyncio.sleep(.08)
                    yield chunk[offset:offset+8192]
        return StreamingResponse(slow_body(), status_code=response.status_code, headers=dict(response.headers))
    return response

@app.post('/qa/reset')
def reset():
    global fail, stream_images
    stream_images = False
    counts.clear(); gate.clear(); session_gate.set(); preview_ids.clear(); preview_gates.clear(); fail = False
    aid = uuid.uuid4().hex
    with store.lock(), store.connect() as db:
        db.execute('UPDATE devices SET active=0')
        for table in ('unlocks', 'uploads', 'media', 'albums'):
            db.execute('DELETE FROM '+table)
        db.execute('INSERT INTO albums(id,name,created) VALUES(?,?,?)', (aid, '预览缓存验收', 1000))
        for i in range(36):
            mid = uuid.uuid4().hex
            preview_ids.insert(0, mid)
            preview_gates[mid] = asyncio.Event()
            out = io.BytesIO()
            Image.new('RGB', (80,80), (200,80+i*3,120)).save(out, format='JPEG')
            data = out.getvalue()
            (store.root/'thumbnails'/mid).write_bytes(data)
            original = data
            if i == 35:
                full = io.BytesIO()
                Image.frombytes('RGB', (1024, 768), os.urandom(1024*768*3)).save(full, format='JPEG', quality=85)
                original = full.getvalue()
            (store.root/'media'/mid).write_bytes(original)
            db.execute('INSERT INTO media(id,album_id,name,mime,size,sha256,created,thumbnail) VALUES(?,?,?,?,?,?,?,1)', (mid,aid,f'preview-{i}.jpg','image/jpeg',len(original),'0'*64,1000+i))
    return {'invite':store.invite(), 'album':aid}

@app.get('/qa/preview-counts')
def state():
    return {'total':sum(counts.values()), 'unique':len(counts), 'released':gate.is_set()}

@app.post('/qa/release-previews')
def release(limit: int = 36):
    for mid in preview_ids[:max(0, limit)]:
        preview_gates[mid].set()
    if limit >= len(preview_ids):
        gate.set()
    return {'ok':True}

@app.post('/qa/fail-previews')
def fail_previews(enabled: bool = True):
    global fail
    fail = enabled
    release()
    return {'ok':True}

@app.post('/qa/hold-session')
def hold_session():
    session_gate.clear()
    return {'ok':True}

@app.post('/qa/release-session')
def release_session():
    session_gate.set()
    return {'ok':True}

@app.post('/qa/prepend-media')
def prepend_media():
    with store.lock(), store.connect() as db:
        previous = db.execute('SELECT * FROM media ORDER BY created DESC LIMIT 1').fetchone()
        for i in range(15):
            mid = uuid.uuid4().hex
            preview_ids.insert(0, mid)
            preview_gates[mid] = asyncio.Event()
            for directory in ('media', 'thumbnails'):
                (store.root/directory/mid).write_bytes((store.root/directory/previous['id']).read_bytes())
            db.execute('INSERT INTO media(id,album_id,name,mime,size,sha256,created,thumbnail) VALUES(?,?,?,?,?,?,?,1)',
                (mid,previous['album_id'],f'new-{i}.jpg','image/jpeg',previous['size'],'0'*64,2000+i))
    return {'first':preview_ids[0]}

@app.post('/qa/stream-images')
def set_stream_images(enabled: bool = True):
    global stream_images
    stream_images = enabled
    return {'ok':True}

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=18448, ssl_keyfile=str(root/'.tools/qa-tls/key.pem'), ssl_certfile=str(root/'.tools/qa-tls/cert.pem'), access_log=False)
