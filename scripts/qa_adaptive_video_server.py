"""Local-only adaptive playback fixture using the exact item-55 original and generated HLS."""
import asyncio,hashlib,os,shutil,sys,time
from pathlib import Path
from fastapi.responses import StreamingResponse,JSONResponse
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'backend'))
from sakura.main import create_app
from sakura.storage import Store
from sakura.playback import PlaybackCopies
store=Store(Path(os.environ['SAKURA_QA_DATA']),reserve=0)
mid='7'*32;aid='a'*32
source=ROOT/'artifacts/original-video-1.2.17/original-55.mp4'
with store.connect() as db:
 db.execute('INSERT OR REPLACE INTO albums(id,name,created) VALUES(?,?,?)',(aid,'清晰度播放验收',1))
 target=store.root/'media'/mid
 if not target.exists():target.symlink_to(source.resolve())
 db.execute('INSERT OR REPLACE INTO media VALUES(?,?,?,?,?,?,?,?)',(mid,aid,'1790932852.MP4','video/mp4',source.stat().st_size,hashlib.file_digest(source.open('rb'),'sha256').hexdigest(),1,0))
# Real encoded outputs were copied from a disposable encoder container.
copies=PlaybackCopies(store.root)
origin=ROOT/'artifacts/adaptive-video-1.2.18/generated/playback'
copies.root.mkdir(exist_ok=True)
shutil.copytree(origin/(mid+'.hls'),copies.adaptive_path(mid),dirs_exist_ok=True)
shutil.copy2(origin/(mid+'.hls.json'),copies.root/(mid+'.hls.json'))
legacy=copies.path(mid)
if not legacy.exists():legacy.symlink_to((origin.parent/'legacy.mp4').resolve())
with store.connect() as db:copies.write(db.execute('SELECT * FROM media WHERE id=?',(mid,)).fetchone(),'ready')
app=create_app(store);rate=500000;sent=0;paths=[];ready_at=0.0;playlist_requests=0
@app.post('/qa/preview')
def preview(enabled:bool=False,segments:int=2):
 bundle=copies.adaptive_path(mid);staging=bundle.with_suffix('.hls.tmp')
 with store.lock(),store.connect() as db:
  row=db.execute('SELECT * FROM media WHERE id=?',(mid,)).fetchone()
  metadata=__import__('json').loads((origin/(mid+'.hls.json')).read_text())
  if enabled:
   shutil.copytree(bundle,staging,dirs_exist_ok=True)
   lines=(origin/(mid+'.hls')/'480.m3u8').read_text().splitlines()
   output=[];count=0
   for line in lines:
    if line=='#EXT-X-ENDLIST':break
    output.append(line.replace('PLAYLIST-TYPE:VOD','PLAYLIST-TYPE:EVENT'))
    if line.endswith('.ts'):
     count+=1
     if count>=segments:break
   pending=staging/'480.m3u8.tmp';pending.write_text('\n'.join(output)+'\n');pending.replace(staging/'480.m3u8')
   master=(bundle/'master.m3u8').read_text().splitlines();index=master.index('480.m3u8')
   (staging/'master.m3u8').write_text('\n'.join(master[:index+1])+'\n')
   copies.write_adaptive(row,'processing',metadata['qualities'][:1],metadata['original_compatible'],preview_ready=True)
   copies.write(row,'processing')
  else:
   copies.write_adaptive(row,'ready',metadata['qualities'],metadata['original_compatible'])
   copies.write(row,'ready');shutil.rmtree(staging,ignore_errors=True)
 return {'ok':True}
@app.post('/qa/delay')
def delay(seconds:float=0):
 global ready_at
 ready_at=time.monotonic()+seconds
 return {'ok':True}
@app.get('/qa/invite')
def invite():
 global sent
 sent=0;paths.clear()
 with store.lock(),store.connect() as db:db.execute('UPDATE devices SET active=0')
 return {'invite':store.invite()}
@app.post('/qa/stream')
def stream(bytes_per_second:int=500000):
 global rate
 rate=max(0,bytes_per_second)
 return {'ok':True}
@app.get('/qa/state')
def state():return {'sent':sent,'paths':paths,'size':source.stat().st_size,'playlist_requests':playlist_requests}
@app.middleware('http')
async def throttle(request,call_next):
 global playlist_requests
 if request.url.path.endswith('/480.m3u8'):playlist_requests+=1
 response=await call_next(request)
 if request.url.path.endswith('/playback/adaptive') and response.status_code==200 and time.monotonic()<ready_at:
  return JSONResponse({'status':'processing'})
 if request.url.path.startswith('/api/media/') and (request.url.path.endswith('.ts') or request.url.path.endswith('/content')) and response.status_code in (200,206):
  paths.append(request.url.path)
  async def body():
   global sent
   async for chunk in response.body_iterator:
    for offset in range(0,len(chunk),16384):
     part=chunk[offset:offset+16384]
     if rate:await asyncio.sleep(len(part)/rate)
     sent+=len(part);yield part
  return StreamingResponse(body(),status_code=response.status_code,headers=dict(response.headers))
 return response
if __name__=='__main__':
 import uvicorn
 uvicorn.run(app,host='127.0.0.1',port=18450,ssl_keyfile=str(ROOT/'.tools/qa-tls/key.pem'),ssl_certfile=str(ROOT/'.tools/qa-tls/cert.pem'),access_log=False)
