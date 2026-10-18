import base64
import io
import time

import httpx
import pytest
from PIL import Image
from fastapi.testclient import TestClient

from sakura.main import create_app
from sakura.messages import VivoPush
from sakura.storage import Store


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv('SAKURA_NOTIFY_KEY', 'sender-test-secret')
    store = Store(tmp_path, quota=1024**2, reserve=0)
    app = create_app(store)
    client = TestClient(app)
    phones = []
    for name in ('老婆宝宝', '我的手机'):
        bound = client.post('/api/bind', json={'invite': store.invite(), 'name': name}).json()
        phones.append((bound['device_id'], {'Authorization': 'Bearer ' + bound['token']}))
    return store, app, client, phones, {'Authorization': 'Bearer sender-test-secret'}


def payload(env, **values):
    return dict(request_id='letter-1', device_id=env[3][0][0], text='老婆宝宝，今天也很想你。', **values)


def test_sender_is_separate_from_device_credentials(env):
    _, _, client, phones, sender = env
    assert client.post('/api/notify', json=payload(env)).status_code == 401
    assert client.post('/api/notify', headers=phones[0][1], json=payload(env)).status_code == 401
    assert client.get('/api/messages', headers=sender).status_code == 401
    assert client.get('/api/messages').status_code == 401
    assert len(client.get('/api/notify/devices', headers=sender).json()) == 2


def test_message_persists_without_push_registration_and_is_idempotent(env):
    store, app, client, phones, sender = env
    first = client.post('/api/notify', headers=sender, json=payload(env))
    assert first.status_code == 200
    assert first.json()['push']['state'] == 'not_registered'
    again = client.post('/api/notify', headers=sender, json=payload(env))
    assert again.json() == first.json()
    data = payload(env); data['text'] = '另一个内容'
    assert client.post('/api/notify', headers=sender, json=data).status_code == 409
    with store.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM letters').fetchone()[0] == 1
        assert store.usage(db)[0] > 0
    restarted = TestClient(create_app(store))
    assert restarted.get('/api/messages', headers=phones[0][1]).json()['items'][0]['text'] == payload(env)['text']


def test_only_recipient_can_read_message_or_image_and_album_password_is_independent(env):
    _, _, client, phones, sender = env
    raw = io.BytesIO(); Image.new('RGB', (16, 16), 'pink').save(raw, 'PNG')
    result = client.post('/api/notify', headers=sender, json=payload(env, image_base64=base64.b64encode(raw.getvalue()).decode())).json()
    mid = result['id']; path = f'/api/messages/{mid}'
    assert client.get('/api/messages', headers=phones[1][1]).json()['items'] == []
    for suffix in ('', '/image'):
        assert client.get(path + suffix, headers=phones[1][1]).status_code == 404
        assert client.get(path + suffix, headers=phones[0][1]).status_code == 200
    assert client.get(path + '/image', headers=phones[0][1]).headers['content-type'] == 'image/jpeg'
    assert client.put(path + '/read', headers=phones[1][1]).status_code == 404
    client.put('/api/password', headers=phones[1][1], json={'new_password': 'password123'})
    assert client.get(path, headers=phones[0][1]).status_code == 200
    assert client.get(path + '/image', headers=phones[0][1]).status_code == 200
    assert client.put(path + '/read', headers=phones[0][1]).status_code == 200
    unlocked = client.post('/api/unlock', headers=phones[0][1], json={'password': 'password123'}).json()['unlock_token']
    headers = {**phones[0][1], 'X-Unlock-Token': unlocked}
    assert client.put(path + '/read', headers=headers).status_code == 200
    first_read = client.get(path, headers=headers).json()['read_at']
    client.put(path + '/read', headers=headers)
    assert client.get(path, headers=headers).json()['read_at'] == first_read


def test_registration_and_retry_do_not_duplicate_accepted_notification(env):
    _, app, client, phones, sender = env
    class Push:
        configured = True; mode = 1; calls = 0
        def send(self, letter, reg):
            self.calls += 1
            assert reg == 'vivo-device-token'
            return 'accepted', 'vivo-task', ''
    app.state.push = Push()
    mid = client.post('/api/notify', headers=sender, json=payload(env)).json()['id']
    assert client.put('/api/push/registration', headers=phones[0][1], json={'reg_id': 'vivo-device-token', 'enabled': True}).status_code == 200
    for _ in range(2):
        response = client.post(f'/api/notify/{mid}/retry', headers=sender)
        assert response.json()['push']['state'] == 'accepted'
    assert app.state.push.calls == 1
    assert client.post('/api/notify', headers=sender, json=payload(env)).json()['id'] == mid
    assert app.state.push.calls == 1


def test_timeout_is_unknown_and_requires_explicit_retry(env):
    _, app, client, phones, sender = env
    class Push:
        configured = True; mode = 1; calls = 0
        def send(self, letter, reg):
            self.calls += 1
            raise httpx.ReadTimeout('private internal request details')
    app.state.push = Push()
    client.put('/api/push/registration', headers=phones[0][1], json={'reg_id': 'reg', 'enabled': True})
    first = client.post('/api/notify', headers=sender, json=payload(env)).json()
    assert first['stored'] and first['push']['state'] == 'unknown'
    assert 'private' not in str(first)
    client.post('/api/notify', headers=sender, json=payload(env))
    assert app.state.push.calls == 1


def test_revoked_or_disabled_devices_are_not_pushed(env):
    store, _, client, phones, sender = env
    assert client.put('/api/push/registration', headers=phones[0][1], json={'enabled': True}).status_code == 422
    client.put('/api/push/registration', headers=phones[0][1], json={'reg_id': 'reg', 'enabled': False})
    first = client.post('/api/notify', headers=sender, json=payload(env)).json()
    assert first['push']['state'] == 'disabled'
    with store.connect() as db: db.execute('UPDATE devices SET active=0 WHERE id=?', (phones[0][0],))
    assert client.post(f"/api/notify/{first['id']}/retry", headers=sender).json()['push']['state'] == 'revoked'
    assert client.get('/api/messages', headers=phones[0][1]).status_code == 401
    assert client.put('/api/push/registration', headers=phones[0][1], json={'enabled': False}).status_code == 401


def test_content_size_image_validation_and_quota(env):
    store, _, client, _, sender = env
    data = payload(env); data['text'] = ''
    assert client.post('/api/notify', headers=sender, json=data).status_code == 422
    assert client.post('/api/notify', headers=sender, json=payload(env, image_base64='not base64')).status_code == 415
    assert client.post('/api/notify', headers=sender, content=b'x' * (6 * 1024**2 + 1)).status_code == 413
    store.quota = 1
    assert client.post('/api/notify', headers=sender, json=payload(env)).status_code == 507


def test_cursor_paging_stays_stable_when_new_messages_arrive(env):
    _, _, client, phones, sender = env
    for n in range(4):
        data = payload(env); data['request_id'] = f'letter-{n}'
        client.post('/api/notify', headers=sender, json=data)
    first = client.get('/api/messages?limit=2', headers=phones[0][1]).json()
    data = payload(env); data['request_id'] = 'newest'; client.post('/api/notify', headers=sender, json=data)
    second = client.get('/api/messages?limit=2&before=' + str(first['next_before']), headers=phones[0][1]).json()
    assert len({x['id'] for x in first['items'] + second['items']}) == 4
    assert second['next_before'] is None


def test_vivo_auth_signature_and_notification_contract(monkeypatch):
    monkeypatch.setenv('VIVO_APP_ID', '10004'); monkeypatch.setenv('VIVO_APP_KEY', 'test-key')
    monkeypatch.setenv('VIVO_APP_SECRET', 'test-secret'); monkeypatch.setenv('VIVO_PUSH_MODE', '1')
    calls = []
    def post(url, **kwargs):
        import hashlib
        calls.append((url, kwargs))
        request = httpx.Request('POST', url)
        data = kwargs['json']
        if url.endswith('/auth'):
            assert data['sign'] == hashlib.md5(f"10004test-key{data['timestamp']}test-secret".encode()).hexdigest()
            return httpx.Response(200, request=request, json={'result': 0, 'authToken': 'auth-secret'})
        assert kwargs['headers'] == {'authToken': 'auth-secret'}
        assert data['pushMode'] == 1 and data['category'] == 'IM' and data['skipType'] == 1
        assert data['clientCustomMap'] == {'sakura_message_id': 'a' * 32}
        assert len(data['content']) == 100 and data['content'].startswith('[图片]')
        assert 'appSecret' not in data
        return httpx.Response(200, request=request, json={'result': 0, 'taskId': '123'})
    monkeypatch.setattr(httpx, 'post', post)
    service = VivoPush()
    letter = {'id': 'a'*32, 'title': '老婆宝宝', 'text': '想你'*100, 'has_image': 1}
    assert service.send(letter, 'reg')[0] == 'accepted'
    service.send(letter, 'reg')
    assert len(calls) == 3


def test_shared_inbox_reads_history_images_and_read_status_without_crossing_people(env):
    store, _, client, phones, sender = env
    wife, mine = phones
    raw = io.BytesIO(); Image.new('RGB', (16, 16), 'pink').save(raw, 'PNG')
    picture = base64.b64encode(raw.getvalue()).decode()
    old = client.post('/api/notify', headers=sender, json={
        'request_id': 'mine-old', 'device_id': mine[0], 'text': '我的历史消息', 'image_base64': picture}).json()['id']
    wife_letter = client.post('/api/notify', headers=sender, json=payload(env)).json()['id']
    invite = store.invite(share_with=mine[0])
    bound = client.post('/api/bind', json={'invite': invite, 'name': '我的第二台', 'inbox_owner': wife[0]})
    assert bound.status_code == 200
    other = (bound.json()['device_id'], {'Authorization': 'Bearer ' + bound.json()['token']})
    new = client.post('/api/notify', headers=sender, json={
        'request_id': 'mine-new', 'device_id': other[0], 'text': '另一台的消息'}).json()['id']
    client = TestClient(create_app(Store(store.root, quota=1024**2, reserve=0)))
    for _, auth in (mine, other):
        first = client.get('/api/messages?limit=1', headers=auth).json()
        second = client.get('/api/messages?limit=1&before=' + str(first['next_before']), headers=auth).json()
        assert [x['id'] for x in first['items'] + second['items']] == [new, old]
        assert client.get(f'/api/messages/{old}/image', headers=auth).status_code == 200
        assert client.get(f'/api/messages/{wife_letter}', headers=auth).status_code == 404
        assert client.put(f'/api/messages/{wife_letter}/read', headers=auth).status_code == 404
    assert client.put(f'/api/messages/{old}/read', headers=other[1]).status_code == 200
    read_at = client.get(f'/api/messages/{old}', headers=mine[1]).json()['read_at']
    assert read_at is not None
    assert client.put(f'/api/messages/{old}/read', headers=mine[1]).status_code == 200
    assert client.get(f'/api/messages/{old}', headers=other[1]).json()['read_at'] == read_at
    assert [x['id'] for x in client.get('/api/messages', headers=wife[1]).json()['items']] == [wife_letter]
    for suffix in ('', '/image'):
        assert client.get(f'/api/messages/{old}' + suffix, headers=wife[1]).status_code == 404
    with store.connect() as db:
        db.execute('UPDATE devices SET active=0 WHERE id=?', (mine[0],))
    assert client.get('/api/messages', headers=mine[1]).status_code == 401
    assert client.get(f'/api/messages/{old}', headers=other[1]).status_code == 200
    with pytest.raises(ValueError):
        store.invite(share_with=mine[0])


def test_shared_inbox_invite_rejects_missing_or_fully_revoked_owner(env):
    store, _, client, phones, _ = env
    with pytest.raises(ValueError):
        store.invite(share_with='missing')
    invite = store.invite(share_with=phones[1][0])
    with store.connect() as db:
        db.execute('UPDATE devices SET active=0 WHERE id=?', (phones[1][0],))
    assert client.post('/api/bind', json={'invite': invite, 'name': '过期共享对象'}).status_code == 403


def test_old_database_upgrade_preserves_devices_invites_and_private_messages(tmp_path):
    import sqlite3
    from sakura.storage import SCHEMA, digest
    db = sqlite3.connect(tmp_path / 'sakura.sqlite')
    db.executescript(SCHEMA)
    for device_id, token in [('a' * 32, 'old-wife-token'), ('b' * 32, 'old-my-token')]:
        db.execute('INSERT INTO devices(id,name,token,created) VALUES(?,?,?,?)', (device_id, device_id, digest(token), 1))
    db.execute('INSERT INTO invites VALUES(?,?)', (digest('old-invitation-token-123456'), time.time() + 1000))
    db.execute('INSERT INTO letters(id,request_id,fingerprint,device_id,title,text,has_image,size,created) VALUES(?,?,?,?,?,?,?,?,?)',
               ('c' * 32, 'old-message', 'fingerprint', 'a' * 32, '旧消息', '保留', 0, 6, 1))
    db.commit(); db.close()
    client = TestClient(create_app(Store(tmp_path, reserve=0)))
    wife = {'Authorization': 'Bearer old-wife-token'}
    mine = {'Authorization': 'Bearer old-my-token'}
    assert client.get('/api/session', headers=wife).status_code == 200
    assert client.get('/api/session', headers=mine).status_code == 200
    assert client.get('/api/messages', headers=wife).json()['items'][0]['text'] == '保留'
    assert client.get('/api/messages', headers=mine).json()['items'] == []
    assert client.post('/api/bind', json={'invite': 'old-invitation-token-123456', 'name': '旧邀请仍有效'}).status_code == 200
    assert client.get('/api/session', headers=wife).json()['signature'] is None
