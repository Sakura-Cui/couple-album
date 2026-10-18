"""Disposable localhost-only inbox acceptance fixture; never deploy."""
import base64
import hashlib
import io
import os
from pathlib import Path
import secrets
import sys
import time

from PIL import Image
from fastapi import Header

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'backend'))
from sakura.main import create_app
from sakura.storage import Store,digest
from sakura.messages import prepare_image

store=Store(Path(os.environ.get('SAKURA_QA_DATA', ROOT/'.tools/qa-messages-data')), reserve=0)
with store.connect() as db:
    db.execute('UPDATE devices SET active=0')
    db.execute('UPDATE settings SET password=NULL,version=version+1')
    db.execute('DELETE FROM unlocks')
app=create_app(store)

@app.post('/qa/letter')
def letter(authorization: str = Header()):
    with store.connect() as db:
        device=db.execute('SELECT id FROM devices WHERE token=? AND active=1',(digest(authorization.removeprefix('Bearer ')),)).fetchone()
        assert device
        raw=io.BytesIO();Image.new('RGB',(600,400),'#eeb8cb').save(raw,'PNG')
        pic=prepare_image(base64.b64encode(raw.getvalue()).decode())
        mid=secrets.token_hex(16)
        db.execute('INSERT INTO letters(id,request_id,fingerprint,device_id,title,text,image,has_image,size,created) VALUES(?,?,?,?,?,?,?,?,?,?)',
                   (mid,mid,'qa',device['id'],'老婆宝宝，验收信','这封信有一张图片，也有我的想念。',pic,1,len(pic),time.time()))
    return {'id':mid}

if __name__=='__main__':
    import uvicorn
    path=Path(os.environ['SAKURA_QA_DATA'])/'qa-invite' if 'SAKURA_QA_DATA' in os.environ else ROOT/'.tools/qa-messages-invite';path.write_text(store.invite());path.chmod(0o600)
    uvicorn.run(app,host='127.0.0.1',port=18444,ssl_keyfile=str(ROOT/'.tools/qa-tls/key.pem'),ssl_certfile=str(ROOT/'.tools/qa-tls/cert.pem'),access_log=False)
