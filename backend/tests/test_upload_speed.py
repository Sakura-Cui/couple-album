import hashlib
from unittest.mock import patch

from fastapi.testclient import TestClient

from sakura.main import create_app
from sakura.storage import CHUNK_SIZE
from test_api import env, album, photo, start


def deferred(client, headers, aid, content):
    return client.post('/api/uploads', headers=headers, json={
        'request_id': 'deferred', 'album_id': aid, 'name': 'photo.png',
        'mime': 'image/png', 'size': len(content)})


def test_deferred_hash_resume_restart_and_idempotence(env):
    store, client, (a, _) = env
    content = photo()
    aid = album(client, a)
    started = deferred(client, a, aid, content)
    assert started.status_code == 201, started.text
    uid = started.json()['id']
    assert client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=a, content=content[:50]).json()['offset'] == 50
    with TestClient(create_app(store)) as restarted:
        assert restarted.get(f'/api/uploads/{uid}', headers=a).json()['offset'] == 50
        assert restarted.put(f'/api/uploads/{uid}/chunks?offset=50', headers=a, content=content[50:]).status_code == 200
        assert restarted.post(f'/api/uploads/{uid}/complete', headers=a).status_code == 422
        assert restarted.get(f'/api/albums/{aid}/media', headers=a).json() == []
        sha = hashlib.sha256(content).hexdigest()
        for _ in range(2):
            assert restarted.post(f'/api/uploads/{uid}/complete', headers=a, json={'sha256': sha}).status_code == 200
        assert restarted.get(f'/api/media/{uid}/content', headers=a).content == content
        assert deferred(restarted, a, aid, content).json()['id'] == uid
        assert restarted.post(f'/api/uploads/{uid}/complete', headers=a, json={'sha256': '0'*64}).status_code == 409
    with store.connect() as db:
        assert db.execute('SELECT sha256 FROM media WHERE id=?', (uid,)).fetchone()[0] == sha


def test_deferred_hash_mismatch_never_publishes(env):
    store, client, (a, _) = env
    content = photo()
    aid = album(client, a)
    uid = deferred(client, a, aid, content).json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=a, content=content)
    assert client.post(f'/api/uploads/{uid}/complete', headers=a, json={'sha256': '0'*64}).status_code == 422
    assert client.get(f'/api/albums/{aid}/media', headers=a).json() == []
    assert client.get(f'/api/uploads/{uid}', headers=a).json()['status'] == 'pending'
    assert not (store.root / 'media' / uid).exists()


def test_larger_chunks_are_durable_and_duplicate_retry_is_safe(env):
    store, client, (a, _) = env
    store.max_file = 32 * 1024**2
    store.quota = 64 * 1024**2
    content = b'\x00\x00\x00\x18ftypisom' + b'x' * (16 * 1024**2 - 12)
    uid = start(client, a, album(client, a), content, mime='video/mp4').json()['id']
    with patch('sakura.main.os.fsync', wraps=__import__('os').fsync) as sync:
        for _ in range(2):
            result = client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=a, content=content)
            assert result.status_code == 200, result.text
            assert result.json()['offset'] == len(content)
        assert sync.call_count == 1
    assert client.post(f'/api/uploads/{uid}/complete', headers=a).status_code == 200
    assert (store.root / 'media' / uid).read_bytes() == content
    assert CHUNK_SIZE == 16 * 1024**2
    storage = client.get('/api/storage', headers=a).json()
    assert storage['deferred_sha256'] is True
    assert storage['chunk_size'] == len(content)


def test_chunk_limit_does_not_expand_thumbnail_limit(env):
    _, client, (a, _) = env
    assert client.put('/api/uploads/no/chunks?offset=0', headers=a, content=b'x'*(16*1024**2+1)).status_code == 413
    assert client.put('/api/media/no/thumbnail', headers=a, content=b'x'*(4*1024**2+1)).status_code == 413


def test_old_app_empty_complete_body_remains_compatible(env):
    _, client, (a, _) = env
    content = photo()
    uid = start(client, a, album(client, a), content).json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=a, content=content)
    assert client.post(f'/api/uploads/{uid}/complete', headers=a, json={}).status_code == 200
