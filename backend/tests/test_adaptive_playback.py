from pathlib import Path
from test_api import env
from test_playback_copies import video


def prepared(store, row):
    from sakura.playback import PlaybackCopies
    copies = PlaybackCopies(store.root)
    bundle = copies.adaptive_path(row['id'])
    bundle.mkdir(parents=True)
    (bundle/'master.m3u8').write_text('#EXTM3U\n480.m3u8\n')
    (bundle/'480.m3u8').write_text('#EXTM3U\n480-00000.ts\n#EXT-X-ENDLIST\n')
    (bundle/'480-00000.ts').write_bytes(b'private-segment')
    copies.write_adaptive(row, 'ready', [{'id':'480','label':'流畅','width':854,'height':480,'bandwidth':1200000}], True)
    return copies


def test_adaptive_is_requested_on_demand_and_legacy_is_unchanged(env):
    store, client, a, _, _, mid, row = video(env)
    from sakura.playback import PlaybackCopies
    copies = PlaybackCopies(store.root)
    url = f'/api/media/{mid}/playback/adaptive'
    assert client.get(url,headers=a).json()['status']=='pending'
    assert not copies.adaptive_requested(row)
    assert client.post(url,headers=a).json()['status']=='pending'
    assert copies.adaptive_requested(row)
    prepared(store,row)
    state=client.get(url,headers=a).json()
    assert state['status']=='ready' and state['qualities'][0]['id']=='480' and state['original_compatible']
    assert client.get(f'/api/media/{mid}/playback',headers=a).json()['status']=='pending'
    assert client.post(url+'?retry=true',headers=a).json()['status']=='ready'


def test_hls_manifest_and_segments_enforce_access_cleanup_and_source_identity(env):
    store, client, a, b, aid, mid, row = video(env)
    copies=prepared(store,row)
    base=f'/api/media/{mid}/playback/hls/'
    for asset in ('master.m3u8','480.m3u8','480-00000.ts'):
        assert client.get(base+asset).status_code==401
        assert client.get(base+asset,headers=a).status_code==200
    assert client.get(base+'480-00000.ts',headers=a).content==b'private-segment'
    assert client.get(base+'480-00000.ts',headers=a).headers['cache-control']=='no-store'
    assert client.get(base+'bad-name.ts',headers=a).status_code==404
    assert client.get(base+'%2E%2E%2Fsecret',headers=a).status_code==404
    client.put('/api/password',headers=a,json={'new_password':'secret'})
    a['X-Unlock-Token']=client.post('/api/unlock',headers=a,json={'password':'secret'}).json()['unlock_token']
    client.patch(f'/api/albums/{aid}',headers=a,json={'name':'private','protected':True})
    for asset in ('master.m3u8','480-00000.ts'):
        assert client.get(base+asset,headers=b).status_code==423
    a['X-Unlock-Token']=client.post('/api/unlock',headers=a,json={'password':'secret'}).json()['unlock_token']
    assert client.get(base+'master.m3u8',headers=a).status_code==200
    from sakura.storage import digest
    with store.connect() as db:
        db.execute('UPDATE devices SET active=0 WHERE token=?',(digest(a['Authorization'][7:]),))
    assert client.get(base+'480-00000.ts',headers=a).status_code==401
    row['sha256']='0'*64;copies.write_adaptive(row,'ready',[],False)
    assert client.get(base+'master.m3u8',headers=b).status_code==423
    with store.connect() as db:
        db.execute('UPDATE albums SET protected=0 WHERE id=?',(aid,))
    assert client.get(base+'master.m3u8',headers=b).status_code==409
    client.delete(f'/api/media/{mid}',headers=b)
    assert not copies.adaptive_path(mid).exists()
    assert not (copies.root/(mid+'.hls.json')).exists()


def test_failed_adaptive_requires_explicit_retry(env):
    store,client,a,_,_,mid,row=video(env)
    from sakura.playback import PlaybackCopies
    copies=PlaybackCopies(store.root);copies.write_adaptive(row,'failed')
    url=f'/api/media/{mid}/playback/adaptive'
    assert client.post(url,headers=a).json()['status']=='failed'
    assert client.post(url+'?retry=true',headers=a).json()['status']=='pending'


def test_worker_recovers_interrupted_bundle_and_keeps_original(env, monkeypatch):
    from sakura.playback import PlaybackWorker
    store,client,a,_,_,mid,row=video(env)
    original=(store.root/'media'/mid).read_bytes()
    worker=PlaybackWorker(store.root,reserve=0)
    worker.copies.write_adaptive(row,'processing')
    staging=worker.copies.adaptive_path(mid).with_suffix('.hls.tmp')
    staging.mkdir();(staging/'partial.ts').write_bytes(b'partial')
    info={'streams':[{'index':0,'codec_type':'video','codec_name':'h264','pix_fmt':'yuv420p','width':1080,'height':1920,'avg_frame_rate':'30/1'}],'format':{'duration':'8'}}
    def probe(path):
        if path.suffix!='.m3u8':return info
        short=int(path.stem);long={480:854,720:1280,1080:1920}[short]
        return {'streams':[{'codec_type':'video','codec_name':'h264','width':short,'height':long}],'format':{'duration':'8'}}
    monkeypatch.setattr(worker,'probe',probe)
    commands=[]
    def encode(command,timeout):
        commands.append(command);playlist=Path(command[-1]);playlist.write_text('#EXTM3U\n#EXT-X-ENDLIST\n')
        (playlist.parent/(playlist.stem+'-00000.ts')).write_bytes(b'segment')
    monkeypatch.setattr(worker,'encode',encode)
    worker.process_adaptive(row)
    state=worker.copies.adaptive_status(row)
    assert state['status']=='ready' and [q['id'] for q in state['qualities']]==['480','720','1080']
    assert len([c for c in commands if '-c:v' in c])==3
    assert all('-hls_time' in c and 'expr:gte(t,n_forced*4)' in c for c in commands if '-c:v' in c)
    assert not staging.exists() and (store.root/'media'/mid).read_bytes()==original
    assert '1080.m3u8' in client.get(f'/api/media/{mid}/playback/hls/master.m3u8',headers=a).text


def test_worker_failure_never_publishes_partial_segments(env,monkeypatch):
    from sakura.playback import PlaybackWorker
    store,_,_,_,_,mid,row=video(env)
    worker=PlaybackWorker(store.root,reserve=0)
    worker.copies.write_adaptive(row,'pending')
    monkeypatch.setattr(worker,'probe',lambda p:{'streams':[{'codec_type':'video','codec_name':'h264','width':1280,'height':720}],'format':{'duration':'8'}})
    def fail(*args):raise RuntimeError('encoder interrupted')
    monkeypatch.setattr(worker,'encode',fail)
    worker.process_adaptive(row)
    assert worker.copies.adaptive_status(row)['status']=='failed'
    assert not worker.copies.adaptive_path(mid).exists()
    assert not worker.copies.adaptive_path(mid).with_suffix('.hls.tmp').exists()


def test_first_segment_is_playable_before_encoding_finishes_without_a_second_video_encode(env,monkeypatch):
    import threading,time
    from sakura.playback import PlaybackWorker
    store,client,a,_,_,mid,row=video(env)
    worker=PlaybackWorker(store.root,reserve=0)
    first=threading.Event();release=threading.Event();commands=[]
    info={'streams':[{'index':0,'codec_type':'video','codec_name':'h264','pix_fmt':'yuv420p','width':1080,'height':1920,'avg_frame_rate':'30/1'}],'format':{'duration':'8'}}
    def probe(path):
        if path.suffix not in ('.ts','.m3u8','.mp4'):return info
        short=480 if path.suffix in ('.ts','.mp4') else int(path.stem)
        return {'streams':[{'codec_type':'video','codec_name':'h264','width':short,'height':{480:854,720:1280,1080:1920}[short]}],'format':{'duration':'8'}}
    def encode(command,timeout):
        commands.append(command);path=Path(command[-1])
        if '-c' in command and command[command.index('-c')+1]=='copy':path.write_bytes(b'remuxed');return
        segment=path.parent/(path.stem+'-00000.ts')
        segment.write_bytes(b'complete-segment');path.write_text('#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXTINF:8,\n'+segment.name+'\n')
        if path.stem=='480':first.set();assert release.wait(8)
        path.write_text(path.read_text()+'#EXT-X-ENDLIST\n')
    monkeypatch.setattr(worker,'probe',probe);monkeypatch.setattr(worker,'encode',encode)
    thread=threading.Thread(target=worker.process_adaptive,args=(row,));thread.start()
    try:
        assert first.wait(5)
        deadline=time.monotonic()+5
        while time.monotonic()<deadline and not worker.copies.adaptive_status(row).get('preview_ready'):time.sleep(.02)
        state=client.get(f'/api/media/{mid}/playback/adaptive',headers=a).json()
        assert state['status']=='processing' and state['preview_ready']
        base=f'/api/media/{mid}/playback/hls/'
        assert client.get(base+'master.m3u8',headers=a).status_code==200
        assert client.get(base+'480.m3u8',headers=a).status_code==200
        assert client.get(base+'480-00000.ts',headers=a).content==b'complete-segment'
        assert client.get(base+'480.m3u8').status_code==401
        assert client.get(base+'720.m3u8',headers=a).status_code==409
        playlist=worker.copies.adaptive_path(mid).with_suffix('.hls.tmp')/'480.m3u8'
        playlist.write_text(playlist.read_text()+'# second manifest version\n')
        assert '# second manifest version' in client.get(base+'480.m3u8',headers=a).text
        assert thread.is_alive() and not worker.copies.path(mid).exists()
    finally:release.set();thread.join(8)
    assert not thread.is_alive()
    assert worker.copies.adaptive_status(row)['status']=='ready'
    assert worker.copies.status(row)['status']=='ready'
    assert len([c for c in commands if '-c:v' in c])==3
    assert len([c for c in commands if '-c' in c and c[c.index('-c')+1]=='copy'])==1


def test_new_uploads_start_quality_pipeline_without_waiting_for_first_open(env,monkeypatch):
    import pytest
    from sakura.playback import PlaybackWorker
    store,_,_,_,_,_,row=video(env);worker=PlaybackWorker(store.root,reserve=0);calls=[]
    class Stop(Exception):pass
    def adaptive(item):calls.append(item['id']);raise Stop()
    monkeypatch.setattr(worker,'process_adaptive',adaptive)
    monkeypatch.setattr(worker,'process',lambda item:pytest.fail('Legacy video transcode must not run'))
    with pytest.raises(Stop):worker.run()
    assert calls==[row['id']]


def test_failed_higher_quality_keeps_completed_base_playable(env,monkeypatch):
    from sakura.playback import PlaybackWorker
    store,client,a,_,_,mid,row=video(env);worker=PlaybackWorker(store.root,reserve=0)
    info={'streams':[{'codec_type':'video','codec_name':'h264','pix_fmt':'yuv420p','width':1080,'height':1920}],'format':{'duration':'8'}}
    monkeypatch.setattr(worker,'probe',lambda path:info)
    def encode(command,timeout):
        path=Path(command[-1])
        if path.stem=='720':raise RuntimeError('higher quality failed')
        if path.suffix=='.mp4':path.write_bytes(b'remuxed');return
        (path.parent/'480-00000.ts').write_bytes(b'complete')
        path.write_text('#EXTM3U\n#EXTINF:8,\n480-00000.ts\n#EXT-X-ENDLIST\n')
    monkeypatch.setattr(worker,'encode',encode);worker.process_adaptive(row)
    state=worker.copies.adaptive_status(row)
    assert state['status']=='failed' and state['preview_ready']
    assert [q['id'] for q in state['qualities']]==['480']
    base=f'/api/media/{mid}/playback/hls/'
    assert client.get(base+'master.m3u8',headers=a).status_code==200
    assert client.get(base+'480-00000.ts',headers=a).content==b'complete'
    assert client.get(base+'720.m3u8',headers=a).status_code==409
    assert worker.copies.status(row)['status']=='ready'
