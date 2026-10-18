"""Disposable localhost-only shared profile fixture; never deploy."""
import os
import sys
import tempfile
from pathlib import Path
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'backend'))
from sakura.main import create_app, ProfileInput
from sakura.storage import Store
store = Store(tempfile.mkdtemp(prefix='sakura-shared-profile-'), reserve=0)
app = create_app(store)

@app.post('/qa/reset')
def reset():
    with store.lock(), store.connect() as db:
        db.execute('UPDATE devices SET active=0')
        db.execute('UPDATE settings SET signature=NULL,signature_version=0')
    return {'invite': store.invite()}

@app.post('/qa/migration-source')
def migration_source():
    with store.connect() as db:
        os.environ['SAKURA_SIGNATURE_SOURCE_DEVICE_ID'] = db.execute('SELECT id FROM devices WHERE active=1').fetchone()[0]
    return {'ok': True}

@app.post('/qa/signature')
def signature(data: ProfileInput):
    with store.lock(), store.connect() as db:
        db.execute('UPDATE settings SET signature=?,signature_version=signature_version+1', (data.signature,))
    return {'ok': True}

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=18449,
                ssl_keyfile=str(root / '.tools/qa-tls/key.pem'), ssl_certfile=str(root / '.tools/qa-tls/cert.pem'))
