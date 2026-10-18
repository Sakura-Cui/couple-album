"""Local emulator acceptance fixture; never import or deploy this module in production."""
import os
import asyncio
import hashlib
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from sakura.storage import Store
from sakura.main import create_app
from sakura.playback import PlaybackCopies

root = Path(__file__).resolve().parents[1]
# Separate disk QA data from older object-storage emulation fixtures.
os.environ['SAKURA_STORAGE'] = 'local'
store = Store(Path(os.environ.get('SAKURA_QA_DATA', root / '.tools' / 'qa-disk-data')), quota=8 * 1024**3, reserve=0)
app = create_app(store)
request_counts = {'upload_status_get': 0}
fixture_video_sha = hashlib.sha256((root / '.tools' / 'qa-fixtures' / 'qa-video.mp4').read_bytes()).hexdigest()
qa_delays = {'lists': 0, 'chunks': 0, 'fail_lists': False, 'images': 0, 'fail_images': False, 'receive_delay': 0, 'complete_delay': 0}

class SlowUploadBody:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        async def throttled_receive():
            message = await receive()
            if message.get('body'):
                await asyncio.sleep(qa_delays['receive_delay'])
            return message
        slow = scope['type'] == 'http' and scope.get('method') == 'PUT' and scope.get('path', '').endswith('/chunks')
        await self.app(scope, throttled_receive if slow else receive, send)

app.add_middleware(SlowUploadBody)

@app.middleware('http')
async def count_upload_status_requests(request, call_next):
    if request.method == 'GET' and request.url.path.startswith('/api/uploads/') and request.url.path.count('/') == 3:
        request_counts['upload_status_get'] += 1
    is_list = request.method == 'GET' and (request.url.path == '/api/albums' or (request.url.path.startswith('/api/albums/') and request.url.path.endswith('/media')))
    if is_list:
        await asyncio.sleep(qa_delays['lists'])
        if qa_delays['fail_lists']:
            from fastapi.responses import JSONResponse
            return JSONResponse({'detail': 'QA simulated failure'}, status_code=503)
    if request.method == 'GET' and request.url.path.startswith('/api/media/') and request.url.path.endswith('/content'):
        await asyncio.sleep(qa_delays['images'])
        if qa_delays['fail_images']:
            from fastapi.responses import JSONResponse
            return JSONResponse({'detail': 'QA image failure'}, status_code=503)
    if request.method == 'PUT' and request.url.path.endswith('/chunks'):
        await asyncio.sleep(qa_delays['chunks'])
    response = await call_next(request)
    if request.method == 'POST' and request.url.path.endswith('/complete') and response.status_code == 200:
        # This exact generated H.264/yuv420p fixture is compatible; worker behavior is tested separately.
        with store.connect() as db:
            rows = db.execute("SELECT * FROM media WHERE sha256=? AND mime LIKE 'video/%'", (fixture_video_sha,)).fetchall()
        for row in rows:
            PlaybackCopies(store.root).write(row, 'original')
    if request.method == 'POST' and request.url.path.endswith('/complete'):
        await asyncio.sleep(qa_delays['complete_delay'])
    return response

@app.post('/qa/delays')
def qa_set_delays(lists: float = 0, chunks: float = 0, fail_lists: bool = False, images: float = 0, fail_images: bool = False, receive_delay: float = 0, complete_delay: float = 0):
    qa_delays.update(lists=min(10, max(0, lists)), chunks=min(10, max(0, chunks)), fail_lists=fail_lists, images=min(10, max(0, images)), fail_images=fail_images, receive_delay=min(1, max(0, receive_delay)), complete_delay=min(10, max(0, complete_delay)))
    return dict(qa_delays)

@app.get('/qa/request-counts')
def qa_request_counts():
    return dict(request_counts)

backend_hash = hashlib.sha256(b''.join(path.read_bytes() for path in sorted((root / 'backend/sakura').glob('*.py')))).hexdigest()

@app.get('/qa/build')
def qa_build():
    return {'backend_sha256': backend_hash, 'storage': 'local'}

if __name__ == '__main__':
    import uvicorn
    # This is a temporary localhost-only test invitation, not a production secret.
    invite = Path(os.environ['SAKURA_QA_DATA']) / 'qa-invite' if 'SAKURA_QA_DATA' in os.environ else root / '.tools' / 'qa-invite'
    invite.write_text(store.invite())
    invite.chmod(0o600)
    uvicorn.run(app, host='127.0.0.1', port=18444, ssl_keyfile=str(root / '.tools' / 'qa-tls' / 'key.pem'),
                ssl_certfile=str(root / '.tools' / 'qa-tls' / 'cert.pem'), access_log=False)
