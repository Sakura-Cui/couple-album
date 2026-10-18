from test_api import env, album, uploaded, photo, start
from sakura.storage import Store


def unlock(client, headers):
    response = client.post('/api/unlock', headers=headers, json={'password': 'album-pass'})
    assert response.status_code == 200
    return {**headers, 'X-Unlock-Token': response.json()['unlock_token']}


def test_album_isolation_and_all_content_paths(env):
    store, client, (one, two) = env
    public = album(client, one)
    private = album(client, one, '私人')
    media = uploaded(client, one, private)
    assert client.put('/api/password', headers=one, json={'new_password': 'album-pass'}).status_code == 200
    trusted = unlock(client, one)
    assert client.patch(f'/api/albums/{private}', headers=trusted, json={'name': '私人', 'protected': True}).status_code == 200
    trusted = unlock(client, one)
    listing = client.get('/api/albums', headers=two)
    assert listing.status_code == 200
    record = next(a for a in listing.json() if a['id'] == private)
    assert record['protected'] and record['display_cover'] is None and record['cover_id'] is None
    assert client.get(f'/api/albums/{public}/media', headers=two).status_code == 200
    for path in (f'/api/albums/{private}/media', f'/api/media/{media}/content', f'/api/media/{media}/thumbnail'):
        assert client.get(path, headers=two).status_code == 423, path
        assert client.get(path, headers=trusted).status_code == 200, path
    assert client.get(f'/api/media/{media}/content', headers={**two, 'X-Unlock-Token': trusted['X-Unlock-Token']}).status_code == 423
    assert client.patch(f'/api/media/{media}', headers=two, json={'album_id': public}).status_code == 423
    assert client.delete(f'/api/media/{media}', headers=two).status_code == 423
    assert client.put(f'/api/media/{media}/thumbnail', headers=two, content=photo()).status_code == 423
    assert client.patch(f'/api/albums/{private}', headers=two, json={'name': '绕过', 'protected': False}).status_code == 423
    assert start(client, two, private, photo()).status_code == 423
    public_media = uploaded(client, two, public, request_id='public')
    assert client.patch(f'/api/media/{public_media}', headers=two, json={'album_id': private}).status_code == 423
    assert client.put('/api/password', headers=one, json={'current_password': 'album-pass', 'new_password': ''}).status_code == 409
    assert client.post('/api/lock', headers=one).status_code == 200
    assert client.get(f'/api/media/{media}/content', headers=trusted).status_code == 423
    assert client.get(f'/api/albums/{public}/media', headers=trusted).status_code == 200


def test_pending_upload_and_password_rotation(env):
    _, client, (one, two) = env
    public = album(client, one)
    uid = start(client, one, public, photo()).json()['id']
    client.put('/api/password', headers=one, json={'new_password': 'album-pass'})
    trusted = unlock(client, one)
    client.patch(f'/api/albums/{public}', headers=trusted, json={'name': '私人', 'protected': True})
    assert client.get(f'/api/uploads/{uid}', headers=one).status_code == 423
    assert client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=one, content=photo()).status_code == 423
    assert client.post(f'/api/uploads/{uid}/complete', headers=one).status_code == 423
    trusted = unlock(client, one)
    assert client.get(f'/api/uploads/{uid}', headers=trusted).status_code == 200
    assert client.put('/api/password', headers=one, json={'current_password': 'album-pass', 'new_password': 'new-password'}).status_code == 200
    assert client.get(f'/api/uploads/{uid}', headers=trusted).status_code == 423
    assert client.get('/api/session', headers=two).json()['album_password_enabled'] is True
    assert client.get('/api/session', headers=two).json()['password_enabled'] is False


def test_protection_needs_password_and_legacy_migration(env):
    store, client, (one, _) = env
    assert client.post('/api/albums', headers=one, json={'name': '私人', 'protected': True}).status_code == 409
    aid = album(client, one)
    client.put('/api/password', headers=one, json={'new_password': 'album-pass'})
    with store.connect() as db:
        db.execute('ALTER TABLE albums DROP COLUMN protected')
    Store(store.root, reserve=0)
    with store.connect() as db:
        assert db.execute('SELECT protected FROM albums WHERE id=?', (aid,)).fetchone()[0] == 1
    # Reopening after choosing a public album must not protect it again.
    with store.connect() as db:
        db.execute('UPDATE albums SET protected=0')
    Store(store.root, reserve=0)
    with store.connect() as db:
        assert db.execute('SELECT protected FROM albums WHERE id=?', (aid,)).fetchone()[0] == 0
