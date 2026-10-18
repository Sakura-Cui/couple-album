"""Isolated upload recovery fixture; localhost only, never deploy to production."""
import asyncio
import json
import sys
import os
from pathlib import Path
from fastapi import Request
from fastapi.responses import JSONResponse
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from sakura.storage import Store
from sakura.main import create_app
root = Path(__file__).resolve().parents[1]
store = Store(Path(os.environ.get('SAKURA_QA_DATA', root / '.tools/qa-upload-data')), quota=1024**3, reserve=0)
app = create_app(store)
faults = {'chunk_delay': 0.0, 'fail_chunks': 0, 'lose_replies': 0, 'fail_delete': False, 'create_delay': 0.0, 'hold_chunks': False, 'chunk_delays': {}}
counts = {'chunk_calls': 0, 'max_chunk_bytes': 0, 'status_gets': 0, 'active_chunks': 0, 'max_active_chunks': 0, 'parallel_calls': 0, 'started_offsets': [], 'finished_offsets': []}

@app.middleware('http')
async def inject(request: Request, call_next):
    path = request.url.path
    if request.method == 'DELETE' and path.startswith('/api/uploads/') and faults['fail_delete']:
        return JSONResponse({'detail': 'QA temporarily offline'}, 503)
    if request.method == 'GET' and path.startswith('/api/uploads/'):
        counts['status_gets'] += 1
    if request.method == 'PUT' and path.endswith('/chunks'):
        offset = request.query_params.get('offset', '0')
        counts['started_offsets'].append(int(offset))
        counts['chunk_calls'] += 1
        counts['max_chunk_bytes'] = max(counts['max_chunk_bytes'], int(request.headers.get('content-length', 0)))
        counts['active_chunks'] += 1
        counts['max_active_chunks'] = max(counts['max_active_chunks'], counts['active_chunks'])
        if request.query_params.get('parallel') == 'true': counts['parallel_calls'] += 1
        try:
            while faults['hold_chunks']:
                await asyncio.sleep(0.02)
            await asyncio.sleep(faults['chunk_delays'].get(offset, faults['chunk_delay']))
            if faults['fail_chunks'] > 0:
                faults['fail_chunks'] -= 1
                return JSONResponse({'detail': 'QA broken upload connection'}, 503)
            result = await call_next(request)
            if faults['lose_replies'] > 0:
                faults['lose_replies'] -= 1
                return JSONResponse({'detail': 'QA response lost after durable write'}, 503)
            return result
        finally:
            counts['active_chunks'] -= 1
            counts['finished_offsets'].append(int(offset))
    result = await call_next(request)
    if request.method == 'POST' and path == '/api/uploads':
        await asyncio.sleep(faults['create_delay'])
    return result

@app.post('/qa/upload-faults')
async def configure(request: Request):
    data = await request.json()
    if data.get("reset_counts"):
        for key in counts: counts[key] = [] if isinstance(counts[key], list) else 0
    faults.update(chunk_delay=0.0, fail_chunks=0, lose_replies=0, fail_delete=False, create_delay=0.0, hold_chunks=False, chunk_delays={})
    for key in faults:
        if key in data: faults[key] = data[key]
    return faults

@app.get('/qa/upload-counts')
def stats(): return counts

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=18446, ssl_keyfile=str(root / '.tools/qa-tls/key.pem'),
                ssl_certfile=str(root / '.tools/qa-tls/cert.pem'), access_log=False)
