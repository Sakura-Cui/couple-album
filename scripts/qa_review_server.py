"""Isolated localhost-only regression fixture. Never deploy this module."""
import sys
import os
import asyncio
from fastapi.responses import JSONResponse
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from sakura.main import create_app
from sakura.storage import Store
root = Path(__file__).resolve().parents[1]
store = Store(Path(os.environ.get('SAKURA_QA_DATA', root / '.tools/qa-review-data')), quota=1024**3, reserve=0)
app = create_app(store)

slow_started = False

@app.middleware('http')
async def local_weather(request, call_next):
    if request.url.path == '/api/morning/briefs':
        return JSONResponse({'detail':'QA weather disabled'}, status_code=503)
    return await call_next(request)

@app.post('/qa/slow')
async def slow():
    global slow_started
    slow_started = True
    await asyncio.sleep(5)
    return {'old_response':True}

@app.post('/qa/slow-started')
def started():
    return {'started':slow_started}

@app.post('/qa/reset')
def reset():
    global slow_started
    slow_started = False
    with store.lock(), store.connect() as db:
        db.execute('UPDATE devices SET active=0')
        db.execute('DELETE FROM unlocks')
        db.execute('DELETE FROM uploads')
        db.execute('DELETE FROM media')
        db.execute('DELETE FROM albums')
        db.execute('UPDATE settings SET password=NULL,version=5')
    return {'invite':store.invite()}

@app.post('/qa/rebind')
def rebind():
    with store.lock(), store.connect() as db:
        db.execute('UPDATE devices SET active=0')
        db.execute('DELETE FROM unlocks')
        db.execute('UPDATE settings SET version=0')
    return {'invite':store.invite()}

@app.post('/qa/media')
def seed():
    with store.lock(), store.connect() as db:
        aid='a'*32
        db.execute('INSERT INTO albums(id,name,created) VALUES(?,?,?)',(aid,'分页回归',1000))
        for i in range(61):
            db.execute('INSERT INTO media(id,album_id,name,mime,size,sha256,created) VALUES(?,?,?,?,?,?,?)',
                       (f'{i:032x}',aid,f'{i}.png','image/png',1,'0'*64,1000.123456))
    return {'album_id':aid}

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=18447, ssl_keyfile=str(root/'.tools/qa-tls/key.pem'),
                ssl_certfile=str(root/'.tools/qa-tls/cert.pem'), access_log=False)
