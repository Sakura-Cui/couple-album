from pathlib import Path
from test_api import env, album
from test_live_photos import upload, apple_photo, quicktime_video


def live_video(env):
    store, client, (auth, other) = env
    aid = album(client, auth)
    still = upload(client, auth, aid, 'capture.JPG', 'image/jpeg', apple_photo())
    motion = upload(client, auth, aid, 'capture.MOV', 'video/quicktime', quicktime_video())
    with store.connect() as db:
        row = dict(db.execute('SELECT * FROM media WHERE id=?', (motion,)).fetchone())
    return store, client, auth, other, aid, still, motion, row


def test_live_fallback_never_reuses_480p_and_is_queued_only_on_request(env):
    from sakura.playback import PlaybackCopies
    store, client, auth, _, _, _, motion, row = live_video(env)
    base = PlaybackCopies(store.root)
    base.root.mkdir(exist_ok=True)
    base.path(motion).write_bytes(b'low-resolution')
    base.write(row, 'ready')
    url = f'/api/media/{motion}/playback'
    assert client.get(url+'?live=true', headers=auth).json()['status'] == 'pending'
    assert client.get(url+'/content?live=true', headers=auth).status_code == 409
    hd = PlaybackCopies(store.root, live=True)
    assert not hd.path(motion).exists()
    assert client.post(url+'/retry?live=true', headers=auth).json()['status'] == 'pending'
    hd.path(motion).write_bytes(b'high-resolution')
    hd.write(row, 'ready')
    assert client.get(url+'/content?live=true', headers={**auth, 'Range':'bytes=0-3'}).content == b'high'
    assert client.get(url+'/content', headers=auth).content == b'low-resolution'
    assert client.get(url+'/content?live=true').status_code == 401
    assert client.delete(f'/api/media/{motion}', headers=auth).status_code == 200
    assert not hd.path(motion).exists()
    assert not (hd.root/(motion+'.live.json')).exists()


def test_live_fallback_respects_locked_album_and_original_identity(env):
    from sakura.playback import PlaybackCopies
    store, client, auth, other, aid, _, motion, row = live_video(env)
    hd = PlaybackCopies(store.root, live=True)
    hd.root.mkdir(exist_ok=True);hd.path(motion).write_bytes(b'HD');hd.write(row, 'ready')
    row['sha256'] = '0'*64;hd.write(row, 'ready')
    assert client.get(f'/api/media/{motion}/playback/content?live=true', headers=auth).status_code == 409
    client.put('/api/password', headers=auth, json={'new_password':'secret'})
    auth['X-Unlock-Token'] = client.post('/api/unlock', headers=auth, json={'password':'secret'}).json()['unlock_token']
    assert client.patch(f'/api/albums/{aid}', headers=auth, json={'name':'locked','protected':True}).status_code == 200
    for suffix, method in [('playback?live=true','get'),('playback/retry?live=true','post'),('playback/content?live=true','get')]:
        assert getattr(client,method)(f'/api/media/{motion}/'+suffix, headers=other).status_code == 423


def test_live_transcode_preserves_dimensions_frames_and_timestamps(env, monkeypatch):
    from sakura.playback import PlaybackWorker
    store, _, _, _, _, _, _, row = live_video(env)
    worker=PlaybackWorker(store.root, reserve=0)
    source={'streams':[{'codec_type':'video','codec_name':'hevc','pix_fmt':'yuv420p','width':1920,'height':1440,'avg_frame_rate':'720/47','nb_frames':'48'}], 'format':{'duration':'3.133'}}
    output={'streams':[{'codec_type':'video','codec_name':'h264','pix_fmt':'yuv420p','width':1920,'height':1440,'nb_frames':'48'}], 'format':{'duration':'3.133'}}
    monkeypatch.setattr(worker,'probe',lambda p:output if p.suffix=='.mp4' else source)
    commands=[]
    def encode(command,timeout):
        commands.append(command);Path(command[-1]).write_bytes(b'HD copy')
    monkeypatch.setattr(worker,'encode',encode)
    worker.process(row, live=True)
    from sakura.playback import PlaybackCopies
    assert PlaybackCopies(store.root,live=True).status(row)['status']=='ready'
    c=commands[0]
    assert '-r' not in c and '-level:v' not in c
    assert c[c.index('-fps_mode')+1]=='passthrough'
    assert c[c.index('-enc_time_base:v')+1]=='-1'
    assert '1920' not in c[c.index('-vf')+1] and '1080' not in c[c.index('-vf')+1]
    assert c[c.index('-crf')+1]=='18'


def test_requested_live_copy_has_worker_priority_over_adaptive(env,monkeypatch):
    from sakura.playback import PlaybackWorker, PlaybackCopies
    store, _, _, _, _, _, _, row=live_video(env)
    worker=PlaybackWorker(store.root,reserve=0)
    PlaybackCopies(store.root,live=True).write(row,'pending')
    calls=[]
    class Stop(Exception):pass
    def process(item,live=False):calls.append((item['id'],live));raise Stop()
    monkeypatch.setattr(worker,'process',process)
    import pytest
    with pytest.raises(Stop):worker.run()
    assert calls==[(row['id'],True)]


def test_apple_display_crop_and_edit_list_are_not_treated_as_quality_loss(env,monkeypatch):
    from sakura.playback import PlaybackWorker, PlaybackCopies
    store, _, _, _, _, _, _, row=live_video(env)
    worker=PlaybackWorker(store.root,reserve=0)
    source={'streams':[{'codec_type':'video','codec_name':'hevc','pix_fmt':'yuv420p','width':1920,'height':1440,'nb_frames':'48','avg_frame_rate':'720/47'}], 'format':{'duration':'3.133'}}
    # Apple crop/rotation and edit lists change coded dimensions/count, not displayed frames.
    output={'streams':[{'codec_type':'video','codec_name':'h264','pix_fmt':'yuv420p','width':1308,'height':1744,'nb_frames':'47'}], 'format':{'duration':'3.005'}}
    monkeypatch.setattr(worker,'probe',lambda p:output if p.suffix=='.mp4' else source)
    monkeypatch.setattr(worker,'encode',lambda c,t:Path(c[-1]).write_bytes(b'HD'))
    worker.process(row,live=True)
    assert PlaybackCopies(store.root,live=True).status(row)['status']=='ready'
