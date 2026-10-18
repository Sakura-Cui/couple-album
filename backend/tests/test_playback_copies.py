import json
from pathlib import Path

from test_api import env, album, uploaded


def video(env):
    store, client, (a, b) = env
    aid = album(client, a)
    mid = uploaded(client, a, aid, b'\x00\x00\x00\x18ftypqt  '+b'\0'*32, mime='video/quicktime')
    with store.connect() as db:
        row = dict(db.execute('SELECT * FROM media WHERE id=?', (mid,)).fetchone())
    return store, client, a, b, aid, mid, row


def ready(store, row, content=b'playback-only-bytes'):
    from sakura.playback import PlaybackCopies
    copies = PlaybackCopies(store.root)
    copies.root.mkdir(exist_ok=True)
    copies.path(row['id']).write_bytes(content)
    copies.write(row, 'ready')
    return copies


def test_copy_status_range_and_original_are_separate(env):
    store, client, a, _, _, mid, row = video(env)
    original = client.get(f'/api/media/{mid}/content', headers=a).content
    assert client.get(f'/api/media/{mid}/playback', headers=a).json()['status'] == 'pending'
    assert client.get(f'/api/media/{mid}/playback/content', headers=a).status_code == 409
    ready(store, row)
    assert client.get(f'/api/media/{mid}/playback', headers=a).json() == {'status': 'ready', 'size': 19}
    response = client.get(f'/api/media/{mid}/playback/content', headers={**a,'Range':'bytes=2-7'})
    assert response.status_code == 206
    assert response.content == b'ayback'
    assert response.headers['content-range'] == 'bytes 2-7/19'
    assert response.headers['content-type'] == 'video/mp4'
    assert client.get(f'/api/media/{mid}/playback/content', headers={**a,'Range':'bytes=999-'}).status_code == 416
    assert client.get(f'/api/media/{mid}/content', headers=a).content == original


def test_copy_requires_current_device_album_unlock_and_membership(env):
    store, client, a, b, aid, mid, row = video(env)
    ready(store, row)
    for suffix in ('playback','playback/content'):
        assert client.get(f'/api/media/{mid}/{suffix}').status_code == 401
    client.put('/api/password', headers=a, json={'new_password':'test-secret'})
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a,json={'password':'test-secret'}).json()['unlock_token']
    client.patch(f'/api/albums/{aid}', headers=a, json={'name':'private','protected':True})
    for suffix in ('playback','playback/content'):
        assert client.get(f'/api/media/{mid}/{suffix}', headers=b).status_code == 423
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a,json={'password':'test-secret'}).json()['unlock_token']
    assert client.get(f'/api/media/{mid}/playback/content',headers=a).status_code == 200
    from sakura.storage import digest
    with store.connect() as db:
        db.execute('UPDATE devices SET active=0 WHERE token=?', (digest(a['Authorization'][7:]),))
    assert client.get(f'/api/media/{mid}/playback/content',headers=a).status_code == 401
    assert client.get(f'/api/media/{mid}/playback/content',headers=b).status_code == 423


def test_copy_cleanup_and_mismatched_source_never_serves(env):
    store, client, a, _, _, mid, row = video(env)
    copies = ready(store, row)
    row['sha256'] = '0'*64
    copies.write(row, 'ready')
    assert client.get(f'/api/media/{mid}/playback',headers=a).json()['status'] == 'pending'
    assert client.get(f'/api/media/{mid}/playback/content',headers=a).status_code == 409
    client.delete(f'/api/media/{mid}',headers=a)
    assert not copies.path(mid).exists()
    assert not (copies.root/f'{mid}.json').exists()
    assert client.get(f'/api/media/{mid}/playback/content',headers=a).status_code == 404


def test_worker_selects_10bit_hdr_and_caps_portrait_resolution():
    from sakura.playback import needs_copy, transcode_command
    plain = {'codec_name':'h264','pix_fmt':'yuv420p','width':1280,'height':720}
    assert not needs_copy({'streams':[dict(plain,codec_type='video')]})
    hdr = dict(plain,codec_type='video',codec_name='hevc',pix_fmt='yuv420p10le',width=3840,height=2160,color_transfer='arib-std-b67')
    assert needs_copy({'streams':[hdr]})
    command=transcode_command(Path('source'),Path('copy.mp4'),hdr,{'index':1})
    filters=command[command.index('-vf')+1]
    assert 'tonemap=' in filters and 'bt709' in filters and '1920' in filters and '1080' in filters
    assert command[command.index('-map')+1]=='0:v:0'
    assert '0:1' in command and 'yuv420p' in command and '+faststart' in command
    assert command[command.index('-r')+1]=='30'
    sixty=transcode_command(Path('source'),Path('copy.mp4'),dict(hdr,avg_frame_rate='60000/1001'))
    assert sixty[sixty.index('-r')+1]=='60'
    assert sixty[sixty.index('-level:v')+1]=='4.2'
    # Variable-rate iPhone footage can report a 120fps time base but average 28fps.
    variable=transcode_command(Path('source'),Path('copy.mp4'),dict(hdr,avg_frame_rate='1381/50',r_frame_rate='120/1'))
    assert variable[variable.index('-r')+1]=='30'


def test_worker_resumes_after_interruption_and_keeps_failed_output_private(env, monkeypatch):
    from sakura.playback import PlaybackWorker
    store, client, a, _, _, mid, row = video(env)
    worker=PlaybackWorker(store.root,reserve=0)
    worker.copies.root.mkdir(exist_ok=True)
    worker.copies.write(row,'processing')
    worker.copies.path(mid).with_suffix('.tmp.mp4').write_bytes(b'partial')
    monkeypatch.setattr(worker,'probe',lambda p:{'streams':[{'codec_type':'video','codec_name':'hevc','pix_fmt':'yuv420p10le','width':3840,'height':2160}],'format':{'duration':'1'}})
    def fail(*args, **kwargs):
        raise RuntimeError('decoder failed')
    monkeypatch.setattr(worker,'encode',fail)
    worker.process(row)
    assert worker.copies.status(row)['status']=='failed'
    assert not worker.copies.path(mid).with_suffix('.tmp.mp4').exists()
    assert client.get(f'/api/media/{mid}/playback/content',headers=a).status_code==409


def test_retry_requeues_only_failed_copies_and_checks_access(env):
    from sakura.playback import PlaybackCopies
    store, client, a, b, aid, mid, row = video(env)
    copies = PlaybackCopies(store.root)
    copies.write(row, 'failed')
    url = f'/api/media/{mid}/playback/retry'
    assert client.post(url).status_code == 401
    assert client.post(url, headers=a).json()['status'] == 'pending'
    copies.write(row, 'processing')
    assert client.post(url, headers=a).json()['status'] == 'processing'
    ready(store, row)
    assert client.post(url, headers=a).json()['status'] == 'ready'
    assert copies.path(mid).read_bytes() == b'playback-only-bytes'
    client.put('/api/password', headers=a, json={'new_password':'test-secret'})
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a,json={'password':'test-secret'}).json()['unlock_token']
    client.patch(f'/api/albums/{aid}', headers=a, json={'name':'private','protected':True})
    assert client.post(url, headers=b).status_code == 423
    from sakura.storage import digest
    with store.connect() as db:
        db.execute('UPDATE devices SET active=0 WHERE token=?', (digest(a['Authorization'][7:]),))
    assert client.post(url, headers=a).status_code == 401


def test_iphone_spatial_audio_uses_embedded_aac_track(env, monkeypatch):
    from sakura.playback import PlaybackWorker
    store, _, _, _, _, _, row = video(env)
    worker = PlaybackWorker(store.root, reserve=0)
    source_info = {'streams':[
        {'index':0,'codec_type':'video','codec_name':'hevc','pix_fmt':'yuv420p10le','width':2160,'height':3840},
        {'index':1,'codec_type':'audio','codec_tag_string':'apac'},
        {'index':2,'codec_type':'audio','codec_name':'aac','channels':2}], 'format':{'duration':'1'}}
    output_info = {'streams':[{'codec_type':'video','codec_name':'h264','pix_fmt':'yuv420p','width':1080,'height':1920}], 'format':{'duration':'1'}}
    monkeypatch.setattr(worker,'probe',lambda p: output_info if p.suffix == '.mp4' else source_info)
    commands = []
    def encode(command, timeout):
        commands.append(command)
        Path(command[-1]).write_bytes(b'copy')
    monkeypatch.setattr(worker,'encode',encode)
    worker.process(row)
    assert worker.copies.status(row)['status'] == 'ready'
    assert '0:2' in commands[0]
    assert '0:a:0?' not in commands[0]


def test_worker_backfills_missing_thumbnail_without_reencoding_copy(env, monkeypatch):
    from sakura.playback import PlaybackWorker
    store, client, a, _, _, mid, row = video(env)
    ready(store, row)
    worker = PlaybackWorker(store.root, reserve=0)
    from PIL import Image
    commands = []
    def encode(command, timeout):
        commands.append(command)
        Image.new('RGB',(160,90),'pink').save(command[-1], 'JPEG')
    monkeypatch.setattr(worker,'encode',encode)
    worker.make_thumbnail(row)
    assert len(commands) == 1 and str(worker.copies.path(mid)) in commands[0]
    assert client.get(f'/api/media/{mid}/thumbnail',headers=a).status_code == 200
    with store.connect() as db:
        assert db.execute('SELECT thumbnail FROM media WHERE id=?',(mid,)).fetchone()[0] == 1
    assert worker.copies.path(mid).read_bytes() == b'playback-only-bytes'



def test_compatible_high_bitrate_original_is_not_transcoded_for_bandwidth():
    from sakura.playback import needs_copy
    assert not needs_copy({'streams': [{'codec_type': 'video', 'codec_name': 'h264',
        'pix_fmt': 'yuv420p', 'width': 1080, 'height': 1920, 'bit_rate': '15823877'}],
        'format': {'bit_rate': '15884991'}})
