import hashlib
import io
import shutil
import time
import json

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from sakura.admin import backup, restore
from sakura.main import create_app
from sakura.storage import CHUNK_SIZE, Store


@pytest.fixture
def env(tmp_path):
    store = Store(tmp_path / 'data', quota=16 * 1024**2, reserve=0, max_file=8 * 1024**2)
    client = TestClient(create_app(store))
    headers = []
    for name in ('樱', '你'):
        result = client.post('/api/bind', json={'invite': store.invite(), 'name': name})
        assert result.status_code == 200
        headers.append({'Authorization': 'Bearer ' + result.json()['token']})
    return store, client, headers


def photo():
    data = io.BytesIO()
    Image.new('RGB', (80, 60), '#e49ab0').save(data, 'PNG')
    return data.getvalue()


def album(client, headers, name='云南旅行'):
    result = client.post('/api/albums', headers=headers, json={'name': name})
    assert result.status_code == 201, result.text
    return result.json()['id']


def start(client, headers, album_id, content, request_id='first', mime='image/png'):
    return client.post('/api/uploads', headers=headers, json={
        'album_id': album_id, 'request_id': request_id, 'name': '回忆.png', 'size': len(content),
        'sha256': hashlib.sha256(content).hexdigest(), 'mime': mime})


def uploaded(client, headers, album_id, content=None, request_id='first', mime='image/png'):
    content = content or photo()
    response = start(client, headers, album_id, content, request_id, mime)
    assert response.status_code == 201, response.text
    uid = response.json()['id']
    for offset in range(0, len(content), CHUNK_SIZE):
        result = client.put(f'/api/uploads/{uid}/chunks?offset={offset}', content=content[offset:offset+CHUNK_SIZE], headers=headers)
        assert result.status_code == 200, result.text
    done = client.post(f'/api/uploads/{uid}/complete', headers=headers)
    assert done.status_code in (200, 202), done.text
    if done.status_code == 202:
        for _ in range(200):
            state = client.get(f'/api/uploads/{uid}', headers=headers).json()
            if state['status'] == 'complete':
                break
            assert not state.get('error'), state
            time.sleep(0.01)
        else:
            raise AssertionError('COS finalization did not finish')
    return done.json()['media_id']


def test_private_routes_require_binding(env):
    _, client, _ = env
    for path in ('/api/albums', '/api/storage', '/api/media/no/content', '/api/media/no/thumbnail'):
        assert client.get(path).status_code == 401


def test_invitation_one_use_limit_and_revocation(env):
    store, client, headers = env
    third_invite = store.invite()
    third = client.post('/api/bind', json={'invite': third_invite, 'name': '我的第二台'})
    assert third.status_code == 200, third.text
    third_headers = {'Authorization': 'Bearer ' + third.json()['token']}
    assert client.post('/api/bind', json={'invite': third_invite, 'name': '重用'}).status_code == 403
    aid = album(client, headers[0])
    for auth in (*headers, third_headers):
        assert client.get('/api/session', headers=auth).status_code == 200
        assert client.get('/api/albums', headers=auth).json()[0]['id'] == aid
    invite = store.invite()
    fourth = client.post('/api/bind', json={'invite': invite, 'name': '第四台'})
    assert fourth.status_code == 409
    assert fourth.json()['detail'] == '已绑定三台设备，请先在服务器撤销旧设备'
    with store.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM devices WHERE active=1').fetchone()[0] == 3
        db.execute('UPDATE devices SET active=0 WHERE name=?', ('你',))
    assert client.get('/api/session', headers=headers[1]).status_code == 401
    assert client.post('/api/bind', json={'invite': invite, 'name': '新手机'}).status_code == 200
    assert client.post('/api/bind', json={'invite': invite, 'name': '重用'}).status_code == 403
    for auth in (headers[0], third_headers):
        assert client.get('/api/session', headers=auth).status_code == 200
    with store.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM devices WHERE active=1').fetchone()[0] == 3


def test_two_devices_album_lifecycle(env):
    _, client, (a, b) = env
    aid = album(client, a)
    assert client.get('/api/albums', headers=b).json()[0]['name'] == '云南旅行'
    assert client.patch(f'/api/albums/{aid}', headers=b, json={'name': '大理的夏天'}).status_code == 200
    mid = uploaded(client, a, aid)
    assert client.delete(f'/api/albums/{aid}', headers=b).status_code == 409
    assert client.put(f'/api/albums/{aid}/cover', headers=b, json={'media_id': mid}).status_code == 200
    assert client.get('/api/albums', headers=a).json()[0]['display_cover'] == mid
    second = album(client, b, '日常')
    assert client.patch(f'/api/media/{mid}', headers=b, json={'album_id': second}).status_code == 200
    assert client.get(f'/api/albums/{aid}/media', headers=a).json() == []
    assert client.delete(f'/api/albums/{aid}', headers=a).status_code == 200
    assert client.delete(f'/api/media/{mid}', headers=b).status_code == 200
    assert client.get(f'/api/media/{mid}/content', headers=a).status_code == 404


def test_three_devices_share_media_and_album_password(env):
    store, client, (a, b) = env
    bound = client.post('/api/bind', json={'invite': store.invite(), 'name': '我的第二台'})
    assert bound.status_code == 200
    c = {'Authorization': 'Bearer ' + bound.json()['token']}
    aid = album(client, a)
    mid = uploaded(client, c, aid)
    for auth in (a, b, c):
        assert client.get(f'/api/albums/{aid}/media', headers=auth).json()[0]['id'] == mid
        assert client.get(f'/api/media/{mid}/content', headers=auth).content == photo()
    assert client.put('/api/password', headers=a, json={'new_password': 'shared-pass'}).status_code == 200
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a, json={'password': 'shared-pass'}).json()['unlock_token']
    assert client.patch(f'/api/albums/{aid}', headers=a, json={'name': '共享加密相册', 'protected': True}).status_code == 200
    for auth in (a, b, c):
        assert client.get(f'/api/media/{mid}/content', headers=auth).status_code == 423
        auth['X-Unlock-Token'] = client.post('/api/unlock', headers=auth, json={'password': 'shared-pass'}).json()['unlock_token']
        assert client.get(f'/api/media/{mid}/content', headers=auth).content == photo()
    assert client.post('/api/lock', headers=c).status_code == 200
    assert client.get(f'/api/media/{mid}/content', headers=c).status_code == 423
    for auth in (a, b):
        assert client.get(f'/api/media/{mid}/content', headers=auth).status_code == 200


def test_blank_album_rejected(env):
    _, client, (a, _) = env
    assert client.post('/api/albums', headers=a, json={'name': '  '}).status_code == 422


def test_album_password_enforced_for_content_and_thumbnails(env):
    _, client, (a, b) = env
    aid = album(client, a)
    mid = uploaded(client, a, aid)
    assert client.put('/api/password', headers=a, json={'new_password': 'sakura-first'}).status_code == 200
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a, json={'password': 'sakura-first'}).json()['unlock_token']
    assert client.patch(f'/api/albums/{aid}', headers=a, json={'name': '私人', 'protected': True}).status_code == 200
    assert client.get('/api/albums', headers=b).status_code == 200
    for path in (f'/api/albums/{aid}/media', f'/api/media/{mid}/content', f'/api/media/{mid}/thumbnail'):
        assert client.get(path, headers=b).status_code == 423
    for headers in (a, b):
        result = client.post('/api/unlock', headers=headers, json={'password': 'sakura-first'})
        headers['X-Unlock-Token'] = result.json()['unlock_token']
        assert client.get(f'/api/media/{mid}/content', headers=headers).status_code == 200
    assert client.put('/api/password', headers=b, json={'current_password': 'sakura-first', 'new_password': 'sakura-second'}).status_code == 200
    assert client.get(f'/api/media/{mid}/content', headers=a).status_code == 423
    assert client.get('/api/session', headers=b).json()['album_unlocked'] is False
    assert client.put('/api/password', headers=b, json={'current_password': 'wrong', 'new_password': ''}).status_code == 403
    b['X-Unlock-Token'] = client.post('/api/unlock', headers=b, json={'password': 'sakura-second'}).json()['unlock_token']
    assert client.patch(f'/api/albums/{aid}', headers=b, json={'name': '普通', 'protected': False}).status_code == 200
    assert client.put('/api/password', headers=b, json={'current_password': 'sakura-second', 'new_password': ''}).status_code == 200
    assert client.get('/api/albums', headers=a).status_code == 200


def test_password_rate_limit_and_background_lock(env):
    store, client, (a, b) = env
    client.put('/api/password', headers=a, json={'new_password': 'sakura-first'})
    for _ in range(5):
        assert client.post('/api/unlock', headers=a, json={'password': 'wrong'}).status_code == 403
    assert client.post('/api/unlock', headers=a, json={'password': 'sakura-first'}).status_code == 429
    b['X-Unlock-Token'] = client.post('/api/unlock', headers=b, json={'password': 'sakura-first'}).json()['unlock_token']
    assert client.post('/api/lock', headers=b).status_code == 200
    assert client.get('/api/session', headers=b).json()['album_unlocked'] is False
    with store.connect() as db:
        assert not db.execute('SELECT password FROM settings').fetchone()[0] == 'sakura-first'


def test_resume_duplicate_chunks_and_complete(env):
    store, client, (a, b) = env
    aid, content = album(client, a), photo()
    uid = start(client, a, aid, content).json()['id']
    assert client.get(f'/api/albums/{aid}/media', headers=b).json() == []
    assert client.get(f'/api/uploads/{uid}', headers=b).status_code == 404
    assert client.post(f'/api/uploads/{uid}/complete', headers=a).status_code == 409
    first = content[:50]
    assert client.put(f'/api/uploads/{uid}/chunks?offset=0', content=first, headers=a).json()['offset'] == 50
    assert client.put(f'/api/uploads/{uid}/chunks?offset=0', content=first, headers=a).json()['offset'] == 50
    assert client.put(f'/api/uploads/{uid}/chunks?offset=0', content=b'x'*50, headers=a).status_code == 409
    restarted = TestClient(create_app(store))
    assert restarted.get(f'/api/uploads/{uid}', headers=a).json()['offset'] == 50
    assert restarted.put(f'/api/uploads/{uid}/chunks?offset=70', content=b'x', headers=a).status_code == 409
    assert restarted.put(f'/api/uploads/{uid}/chunks?offset=50', content=content[50:], headers=a).status_code == 200
    for _ in range(2):
        assert restarted.post(f'/api/uploads/{uid}/complete', headers=a).json()['media_id'] == uid
    assert len(restarted.get(f'/api/albums/{aid}/media', headers=b).json()) == 1
    assert start(client, a, aid, content).json()['id'] == uid
    assert start(client, a, aid, content + b'x').status_code == 409


def test_upload_cancel_releases_reserved_space(env):
    _, client, (a, _) = env
    aid, content = album(client, a), photo()
    uid = start(client, a, aid, content).json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', content=content[:30], headers=a)
    assert client.get('/api/storage', headers=a).json()['reserved'] == len(content)
    assert client.delete(f'/api/uploads/{uid}', headers=a).status_code == 200
    assert client.get('/api/storage', headers=a).json()['reserved'] == 0
    assert client.post(f'/api/uploads/{uid}/complete', headers=a).status_code == 409


def test_hash_validation(env):
    _, client, (a, _) = env
    content = photo()
    uid = start(client, a, album(client, a), content).json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', content=b'x'*len(content), headers=a)
    assert client.post(f'/api/uploads/{uid}/complete', headers=a).status_code == 422


@pytest.mark.parametrize('mime,body', [('image/png', b'not-image'), ('video/mp4', b'not-video')])
def test_invalid_file_type(env, mime, body):
    _, client, (a, _) = env
    uid = start(client, a, album(client, a), body, mime=mime).json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', content=body, headers=a)
    assert client.post(f'/api/uploads/{uid}/complete', headers=a).status_code == 415


def test_size_quota_and_disk_protection(env, monkeypatch):
    store, client, (a, _) = env
    aid = album(client, a)
    store.max_file = 100
    assert start(client, a, aid, photo()).status_code == 413
    store.max_file = 1024**3
    store.quota = 100
    assert start(client, a, aid, photo()).status_code == 507
    store.quota = 1024**3
    store.reserve = 1024
    monkeypatch.setattr(shutil, 'disk_usage', lambda _: type('Usage', (), {'free': 500})())
    assert start(client, a, aid, photo()).status_code == 507


def test_range_requests_and_thumbnail(env):
    _, client, (a, _) = env
    content = photo()
    mid = uploaded(client, a, album(client, a), content)
    url = f'/api/media/{mid}/content'
    for requested, expected in [('bytes=2-12', content[2:13]), ('bytes=-10', content[-10:]), ('bytes=10-', content[10:])]:
        result = client.get(url, headers={**a, 'Range': requested})
        assert result.status_code == 206
        assert result.content == expected
        assert int(result.headers['content-length']) == len(expected)
    for requested in ('bytes=999999-', 'bytes=5-2', 'bytes=0-1,4-5', 'bytes=-0'):
        assert client.get(url, headers={**a, 'Range': requested}).status_code == 416
    result = client.get(f'/api/media/{mid}/thumbnail', headers=a)
    assert result.headers['content-type'] == 'image/jpeg'
    assert result.headers['cache-control'] == 'no-store'
    assert result.content[:2] == b'\xff\xd8'


def test_pagination(env):
    _, client, (a, _) = env
    aid = album(client, a)
    for i in range(3):
        uploaded(client, a, aid, request_id=str(i))
    first = client.get(f'/api/albums/{aid}/media?limit=2', headers=a).json()
    second = client.get(f'/api/albums/{aid}/media?offset=2&limit=2', headers=a).json()
    assert len(first) == 2 and len(second) == 1
    assert len({x['id'] for x in first + second}) == 3


def test_backup_restore_and_corruption(env, tmp_path):
    store, client, (a, _) = env
    mid = uploaded(client, a, album(client, a))
    target = backup(store, tmp_path / 'backup')
    recovered = restore(target, tmp_path / 'recovered')
    assert (recovered / 'media' / mid).read_bytes() == photo()
    clone_store = Store(recovered, reserve=0)
    clone = TestClient(create_app(clone_store))
    assert clone.get('/api/albums', headers=a).status_code == 401
    result = clone.post('/api/bind', json={'invite': clone_store.invite(), 'name': '恢复设备'})
    fresh = {'Authorization': 'Bearer ' + result.json()['token']}
    assert clone.get(f'/api/media/{mid}/content', headers=fresh).content == photo()
    with pytest.raises(ValueError):
        restore(target, recovered)
    (target / 'media' / mid).write_bytes(b'corrupted')
    with pytest.raises(ValueError):
        restore(target, tmp_path / 'bad')


def test_revocation_interrupts_authorized_access(env):
    store, client, (a, _) = env
    client.put('/api/password', headers=a, json={'new_password': 'sakura-first'})
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a, json={'password': 'sakura-first'}).json()['unlock_token']
    with store.connect() as db:
        db.execute('UPDATE devices SET active=0')
    assert client.get('/api/albums', headers=a).status_code == 401


def test_backup_restore_rejects_absolute_manifest_path(env, tmp_path):
    store, client, (a, _) = env
    uploaded(client, a, album(client, a))
    target = backup(store, tmp_path / 'backup')
    manifest = json.loads((target / 'manifest.json').read_text())
    first = next(name for name in manifest['files'] if name.startswith('media/'))
    manifest['files'][str(target / first)] = manifest['files'].pop(first)
    (target / 'manifest.json').write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match='无效文件名'):
        restore(target, tmp_path / 'unsafe')
    assert not (tmp_path / 'unsafe').exists()


@pytest.mark.parametrize('size,progressive', [((8000, 6000), False), ((16000, 12500), False), ((8000, 6000), True)])
def test_high_resolution_jpeg_keeps_original_and_decodes_small(env, monkeypatch, size, progressive):
    store, client, headers = env
    data = io.BytesIO()
    with Image.new('L', size, 160) as image:
        image.save(data, 'JPEG', progressive=progressive)
    content = data.getvalue()
    from PIL import JpegImagePlugin
    original_load = JpegImagePlugin.JpegImageFile.load
    def bounded_load(image, *args, **kwargs):
        assert image.width * image.height <= 40_000_000
        return original_load(image, *args, **kwargs)
    monkeypatch.setattr(JpegImagePlugin.JpegImageFile, 'load', bounded_load)
    aid = album(client, headers[0])
    uid = uploaded(client, headers[0], aid, content, mime='image/jpeg')
    assert client.get(f'/api/media/{uid}/content', headers=headers[0]).content == content
    response = client.get(f'/api/media/{uid}/thumbnail', headers=headers[0])
    assert response.status_code == 200
    with Image.open(io.BytesIO(response.content)) as preview:
        assert max(preview.size) <= 640
        preview.load()


def test_shared_signature_initial_import_latest_save_validation_and_restart(env, monkeypatch):
    store, client, (a, b) = env
    with store.connect() as db:
        wife_id = db.execute('SELECT id FROM devices WHERE name=?', ('樱',)).fetchone()[0]
    monkeypatch.setenv('SAKURA_SIGNATURE_SOURCE_DEVICE_ID', wife_id)
    c_result = client.post('/api/bind', json={'invite': store.invite(), 'name': '我的第二台'}).json()
    c = {'Authorization': 'Bearer ' + c_result['token']}
    assert client.get('/api/session', headers=a).json()['signature_import_allowed'] is True
    assert client.get('/api/session', headers=b).json()['signature_import_allowed'] is False
    assert client.put('/api/profile', headers=b, json={'signature': '我的旧签名', 'initialize': True}).status_code == 403
    first = client.put('/api/profile', headers=a, json={'signature': '原有签名💕', 'initialize': True})
    assert first.status_code == 200
    for auth in (a, b, c):
        session = client.get('/api/session', headers=auth).json()
        assert session['signature'] == '原有签名💕'
        assert session['signature_version'] == 1
    for n, auth in enumerate((b, c, a), 2):
        saved = client.put('/api/profile', headers=auth, json={'signature': f'新签名{n}'}).json()
        assert saved['signature_version'] == n
        for reader in (a, b, c):
            assert client.get('/api/session', headers=reader).json()['signature'] == f'新签名{n}'
    # A delayed legacy import must not replace an explicit edit.
    assert client.put('/api/profile', headers=a, json={'signature': '旧内容', 'initialize': True}).json()['signature'] == '新签名4'
    assert client.put('/api/profile', headers=a, json={'signature': '💕' * 20}).status_code == 200
    assert client.put('/api/profile', headers=a, json={'signature': '💕' * 21}).status_code == 422
    assert client.put('/api/profile', json={'signature': '未绑定'}).status_code == 401
    assert client.put('/api/profile', headers=c, json={'signature': '   '}).json()['signature'] == ''
    restarted = TestClient(create_app(Store(store.root, reserve=0)))
    assert restarted.get('/api/session', headers=b).json()['signature'] == ''
    with store.connect() as db:
        db.execute('UPDATE devices SET active=0 WHERE name=?', ('我的第二台',))
    assert restarted.put('/api/profile', headers=c, json={'signature': '已撤销'}).status_code == 401
