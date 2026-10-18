from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from sakura.main import create_app
from sakura.storage import Store
from test_api import env, album, start

PART = 1024 * 1024


def setup_upload(env):
    store, client, (auth, other) = env
    content = b"\x00\x00\x00\x18ftypisom" + b'a' * (PART - 12) + b'b' * PART + b'c' * PART + b'd' * 37
    uid = start(client, auth, album(client, auth), content, mime='video/mp4').json()['id']
    return store, client, auth, other, uid, content


def send(client, auth, uid, content, offset, parallel=True):
    return client.put(f'/api/uploads/{uid}/chunks?offset={offset}&parallel={str(parallel).lower()}',
                      headers=auth, content=content[offset:offset + PART])


def test_out_of_order_parts_only_advance_contiguous_prefix(env):
    store, client, auth, _, uid, content = setup_upload(env)
    for offset in (2 * PART, PART):
        result = send(client, auth, uid, content, offset)
        assert result.status_code == 200, result.text
        assert result.json() == {'offset': 0, 'received_offset': offset + PART}
    assert client.post(f'/api/uploads/{uid}/complete', headers=auth).status_code == 409
    assert send(client, auth, uid, content, 0).json()['offset'] == 3 * PART
    assert send(client, auth, uid, content, 3 * PART).json()['offset'] == len(content)
    assert client.post(f'/api/uploads/{uid}/complete', headers=auth).status_code == 200
    assert (store.root / 'media' / uid).read_bytes() == content
    with store.connect() as db:
        assert db.execute('SELECT count(*) FROM upload_parts').fetchone()[0] == 0


def test_restart_and_old_client_resume_preserve_tail(env):
    store, client, auth, _, uid, content = setup_upload(env)
    assert send(client, auth, uid, content, 2 * PART).status_code == 200
    restarted_store = Store(store.root, quota=store.quota, reserve=0, max_file=store.max_file)
    with TestClient(create_app(restarted_store)) as restarted:
        assert restarted.get(f'/api/uploads/{uid}', headers=auth).json()['offset'] == 0
        conflict = restarted.put(f'/api/uploads/{uid}/chunks?offset=0', headers=auth, content=b'x' * (3 * PART))
        assert conflict.status_code == 409
        assert send(restarted, auth, uid, content, 0, parallel=False).json()['offset'] == PART
        assert (store.root / 'uploads' / uid).stat().st_size == 3 * PART
        result = restarted.put(f'/api/uploads/{uid}/chunks?offset={PART}', headers=auth, content=content[PART:])
        assert result.json()['offset'] == len(content)
        assert restarted.post(f'/api/uploads/{uid}/complete', headers=auth).status_code == 200
    assert (store.root / 'media' / uid).read_bytes() == content


def test_lost_response_is_idempotent_and_conflicting_retry_rejected(env):
    _, client, auth, _, uid, content = setup_upload(env)
    with patch('sakura.main.os.fsync', wraps=__import__('os').fsync) as sync:
        first = send(client, auth, uid, content, PART)
        duplicate = send(client, auth, uid, content, PART)
        assert first.status_code == duplicate.status_code == 200
        assert first.json() == duplicate.json()
        assert sync.call_count == 1
    assert client.put(f'/api/uploads/{uid}/chunks?offset={PART}&parallel=true', headers=auth, content=b'x' * PART).status_code == 409
    assert send(client, auth, uid, content, 0).json()['offset'] == 2 * PART
    assert send(client, auth, uid, content, PART).json()['received_offset'] == 2 * PART


def test_concurrent_requests_produce_exact_original(env):
    store, client, auth, _, uid, content = setup_upload(env)
    with ThreadPoolExecutor(max_workers=3) as workers:
        results = list(workers.map(lambda offset: send(client, auth, uid, content, offset), (0, PART, 2 * PART)))
    assert all(result.status_code == 200 for result in results)
    assert client.get(f'/api/uploads/{uid}', headers=auth).json()['offset'] == 3 * PART
    assert send(client, auth, uid, content, 3 * PART).status_code == 200
    assert client.post(f'/api/uploads/{uid}/complete', headers=auth).status_code == 200
    assert (store.root / 'media' / uid).read_bytes() == content


def test_bounds_authorization_quota_and_cancel(env):
    store, client, auth, other, uid, content = setup_upload(env)
    capability = client.get('/api/storage', headers=auth).json()
    assert capability['parallel_chunk_size'] == PART
    assert capability['parallel_uploads'] == 3
    assert capability['parallel_window'] == 12
    assert send(client, other, uid, content, PART).status_code == 404
    assert client.put(f'/api/uploads/{uid}/chunks?offset=1&parallel=true', headers=auth, content=content[1:1 + PART]).status_code == 422
    assert client.put(f'/api/uploads/{uid}/chunks?offset=0&parallel=true', headers=auth, content=b'x' * (PART + 1)).status_code == 413
    assert client.put(f'/api/uploads/{uid}/chunks?offset=0&parallel=true', headers=auth, content=b'x').status_code == 422
    assert send(client, auth, uid, content, PART).status_code == 200
    assert client.get('/api/storage', headers=auth).json()['reserved'] == len(content)
    assert client.delete(f'/api/uploads/{uid}', headers=auth).status_code == 200
    assert send(client, auth, uid, content, 0).status_code == 409
    assert not (store.root / 'uploads' / uid).exists()
    with store.connect() as db:
        assert db.execute('SELECT count(*) FROM upload_parts').fetchone()[0] == 0
        assert store.usage(db) == (0, 0, 0)


def test_fsync_failure_never_acknowledges_part(env):
    _, client, auth, _, uid, content = setup_upload(env)
    with patch('sakura.main.os.fsync', side_effect=OSError('disk failure')):
        with pytest.raises(OSError): send(client, auth, uid, content, PART)
    assert client.get(f'/api/uploads/{uid}', headers=auth).json()['offset'] == 0
    assert send(client, auth, uid, content, 0).json()['offset'] == PART
    assert send(client, auth, uid, content, PART).json()['offset'] == 2 * PART


def test_backup_excludes_unfinished_parts_even_without_foreign_keys(env, tmp_path):
    import sqlite3
    from contextlib import closing
    from sakura.admin import backup
    store, client, auth, _, uid, content = setup_upload(env)
    assert send(client, auth, uid, content, PART).status_code == 200
    destination = backup(store, tmp_path / 'snapshot')
    with closing(sqlite3.connect(destination / 'sakura.sqlite')) as db:
        assert db.execute('SELECT count(*) FROM uploads').fetchone()[0] == 0
        assert db.execute('SELECT count(*) FROM upload_parts').fetchone()[0] == 0
        assert db.execute('PRAGMA foreign_key_check').fetchall() == []
    assert send(client, auth, uid, content, 0).json()['offset'] == 2 * PART


def test_upgrade_adds_receipts_without_resetting_existing_upload(env):
    store, client, auth, _, uid, content = setup_upload(env)
    assert send(client, auth, uid, content, 0, parallel=False).json()['offset'] == PART
    with store.connect() as db:
        db.execute('DROP TRIGGER upload_parts_finished')
        db.execute('DROP TRIGGER upload_parts_deleted')
        db.execute('DROP TABLE upload_parts')
    upgraded = Store(store.root, quota=store.quota, reserve=0, max_file=store.max_file)
    with TestClient(create_app(upgraded)) as restarted:
        assert restarted.get(f'/api/uploads/{uid}', headers=auth).json()['offset'] == PART
        assert send(restarted, auth, uid, content, 2 * PART).json()['offset'] == PART
        assert send(restarted, auth, uid, content, PART).json()['offset'] == 3 * PART
        assert send(restarted, auth, uid, content, 3 * PART).status_code == 200
        assert restarted.post(f'/api/uploads/{uid}/complete', headers=auth).status_code == 200
    assert (store.root / 'media' / uid).read_bytes() == content


def test_failed_legacy_resume_keeps_durable_parallel_tail(env):
    store, client, auth, _, uid, content = setup_upload(env)
    assert send(client, auth, uid, content, 2 * PART).status_code == 200
    with patch('sakura.main.os.fsync', side_effect=OSError('disk failure')):
        with pytest.raises(OSError): send(client, auth, uid, content, 0, parallel=False)
    assert (store.root / 'uploads' / uid).stat().st_size == 3 * PART
    assert send(client, auth, uid, content, 2 * PART).status_code == 200
    assert send(client, auth, uid, content, 0).json()['offset'] == PART
    assert send(client, auth, uid, content, PART).json()['offset'] == 3 * PART
    assert send(client, auth, uid, content, 3 * PART).status_code == 200
    assert client.post(f'/api/uploads/{uid}/complete', headers=auth).status_code == 200
    assert (store.root / 'media' / uid).read_bytes() == content


def test_larger_receive_window_survives_restart_and_stays_bounded(env):
    store, client, (auth, _) = env
    store.max_file = 16 * PART
    content = b"\x00\x00\x00\x18ftypisom" + b'w' * (13 * PART - 12)
    uid = start(client, auth, album(client, auth), content, mime='video/mp4').json()['id']
    # The first chunk is missing while three requests refill the durable tail.
    for offset in range(PART, 12 * PART, PART):
        response = send(client, auth, uid, content, offset)
        assert response.status_code == 200, response.text
        assert response.json() == {'offset': 0, 'received_offset': offset + PART}
    assert send(client, auth, uid, content, 12 * PART).status_code == 409
    assert client.post(f'/api/uploads/{uid}/complete', headers=auth).status_code == 409
    restarted_store = Store(store.root, quota=store.quota, reserve=0, max_file=store.max_file)
    with TestClient(create_app(restarted_store)) as restarted:
        assert restarted.get(f'/api/uploads/{uid}', headers=auth).json()['offset'] == 0
        assert send(restarted, auth, uid, content, 11 * PART).status_code == 200
        assert send(restarted, auth, uid, content, 0).json()['offset'] == 12 * PART
        assert send(restarted, auth, uid, content, 12 * PART).json()['offset'] == len(content)
        assert restarted.post(f'/api/uploads/{uid}/complete', headers=auth).status_code == 200
    assert (store.root / 'media' / uid).read_bytes() == content
