import threading
import time
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from sakura import admin
from sakura.main import create_app
from sakura.objects import CosObjects
from sakura.storage import CHUNK_SIZE, Store
from test_api import album, photo, start, uploaded
from test_cos import FakeCos


def cloud(tmp_path):
    store = Store(tmp_path / 'data', reserve=0)
    fake = FakeCos()
    store.objects = CosObjects(fake, 'test-123', 'sakura-romance')
    client = TestClient(create_app(store))
    token = client.post('/api/bind', json={'invite': store.invite(), 'name': 'test'}).json()['token']
    headers = {'Authorization': 'Bearer ' + token}
    return store, fake, client, headers, album(client, headers)


def wait_complete(client, headers, uid):
    for _ in range(200):
        status = client.get(f'/api/uploads/{uid}', headers=headers).json()
        if status['status'] == 'complete':
            return
        time.sleep(0.01)
    pytest.fail('Finalization did not finish')


def test_slow_cos_completion_returns_promptly_without_blocking_lock(tmp_path):
    store, fake, client, headers, aid = cloud(tmp_path)
    uid = start(client, headers, aid, photo()).json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=headers, content=photo())
    entered, release = threading.Event(), threading.Event()
    original = fake.upload_file

    def slow(**kwargs):
        entered.set()
        assert release.wait(5)
        return original(**kwargs)

    fake.upload_file = slow
    with ThreadPoolExecutor(2) as pool:
        future = pool.submit(client.post, f'/api/uploads/{uid}/complete', headers=headers)
        try:
            assert entered.wait(2)
            assert future.result(timeout=1).status_code == 202
            assert pool.submit(client.post, '/api/lock', headers=headers).result(timeout=1).status_code == 200
            assert client.post(f'/api/uploads/{uid}/complete', headers=headers).status_code == 202
            assert client.delete(f'/api/uploads/{uid}', headers=headers).status_code == 409
        finally:
            release.set()
    wait_complete(client, headers, uid)
    assert client.post(f'/api/uploads/{uid}/complete', headers=headers).status_code == 200


def test_restore_cos_retries_owned_incomplete_target(tmp_path):
    store, fake, client, headers, aid = cloud(tmp_path)
    mid = uploaded(client, headers, aid)
    admin.backup_cos(store, 'snapshot')
    original = fake.copy_object
    count = 0

    def interrupted(**kwargs):
        nonlocal count
        count += 1
        if count == 2:
            raise RuntimeError('injected interruption')
        return original(**kwargs)

    destination = tmp_path / 'restored'
    with patch.object(fake, 'copy_object', interrupted), pytest.raises(RuntimeError):
        admin.restore_cos(store, 'snapshot', destination, 'sakura-restored-test')
    with pytest.raises(ValueError, match='未完成'):
        Store(destination, reserve=0)
    admin.restore_cos(store, 'snapshot', destination, 'sakura-restored-test')
    recovered = Store(destination, reserve=0)
    with recovered.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM devices WHERE active=1').fetchone()[0] == 0
    assert fake.files['sakura-restored-test/media/' + mid] == photo()


def test_prune_keeps_failed_row_and_finishes_other_cleanup(tmp_path):
    store, fake, client, headers, aid = cloud(tmp_path)
    good, bad = 'a' * 32, 'b' * 32
    with store.connect() as db:
        db.executemany('INSERT INTO garbage VALUES(?)', [(good,), (bad,)])
    orphan = store.root / 'uploads' / 'orphan'
    orphan.write_bytes(b'x')
    original = fake.delete_object

    def failed(**kwargs):
        if kwargs['Key'].endswith(bad):
            from qcloud_cos.cos_exception import CosClientError
            raise CosClientError('injected deletion failure')
        return original(**kwargs)

    with patch.object(fake, 'delete_object', failed), patch.object(admin, 'Store', return_value=store), patch('sys.argv', ['admin', 'prune']):
        admin.main()
    with store.connect() as db:
        assert [r[0] for r in db.execute('SELECT id FROM garbage')] == [bad]
    assert not orphan.exists()


def test_prune_releases_abandoned_saving_upload(tmp_path):
    store = Store(tmp_path / 'data', reserve=0)
    client = TestClient(create_app(store))
    token = client.post('/api/bind', json={'invite': store.invite(), 'name': 'test'}).json()['token']
    headers = {'Authorization': 'Bearer ' + token}
    aid = album(client, headers)
    uid = start(client, headers, aid, photo()).json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=headers, content=photo())
    with store.connect() as db:
        db.execute("UPDATE uploads SET status='saving',updated=? WHERE id=?", (time.time() - 2 * 86400, uid))
        assert store.usage(db)[1] == len(photo())

    with patch.object(admin, 'Store', return_value=store), patch('sys.argv', ['admin', 'prune']):
        admin.main()

    with store.connect() as db:
        row = db.execute('SELECT status FROM uploads WHERE id=?', (uid,)).fetchone()
        assert row['status'] == 'cancelled'
        assert store.usage(db)[1] == 0
    assert not (store.root / 'uploads' / uid).exists()


def test_thumbnail_respects_outstanding_disk_reservations(tmp_path):
    store, fake, client, headers, aid = cloud(tmp_path)
    mid = uploaded(client, headers, aid)
    assert start(client, headers, aid, b'x' * (2 * CHUNK_SIZE), request_id='pending').status_code == 201
    with patch('shutil.disk_usage', return_value=type('Usage', (), {'free': CHUNK_SIZE + 100})()):
        assert client.put(f'/api/media/{mid}/thumbnail', headers=headers, content=photo()).status_code == 507


def test_original_media_read_uses_database_size(tmp_path):
    store, fake, client, headers, aid = cloud(tmp_path)
    mid = uploaded(client, headers, aid)
    with patch.object(fake, 'head_object', side_effect=AssertionError('Unnecessary HEAD')):
        assert client.get(f'/api/media/{mid}/content', headers=headers).content == photo()


def test_save_failure_can_retry_without_resending_file(tmp_path):
    store, fake, client, headers, aid = cloud(tmp_path)
    uid = start(client, headers, aid, photo()).json()['id']
    client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=headers, content=photo())
    with patch.object(fake, 'upload_file', side_effect=RuntimeError('do not expose backend secrets')):
        assert client.post(f'/api/uploads/{uid}/complete', headers=headers).status_code == 202
        for _ in range(200):
            state = client.get(f'/api/uploads/{uid}', headers=headers).json()
            if state['error']:
                break
            time.sleep(0.01)
        assert state['status'] == 'pending' and state['offset'] == len(photo())
        assert 'secrets' not in state['error']
    # A new app instance models a restarted server; durable offset remains authoritative.
    with store.lock(), store.connect() as db:
        db.execute("UPDATE uploads SET status='saving' WHERE id=?", (uid,))
    restarted = TestClient(create_app(store))
    assert restarted.post(f'/api/uploads/{uid}/complete', headers=headers).status_code == 202
    wait_complete(restarted, headers, uid)
    assert restarted.get(f'/api/media/{uid}/content', headers=headers).content == photo()


def test_stream_reuses_connection_but_revokes_between_chunks(tmp_path):
    store, fake, client, headers, aid = cloud(tmp_path)
    content = b'\x00\x00\x00\x18ftypisom' + b'x' * (1024 * 1024)
    mid = uploaded(client, headers, aid, content, mime='video/mp4')
    original_read = store.objects.read
    original_connect = store.connect
    count = 0

    @contextmanager
    def counted(*args, **kwargs):
        nonlocal count
        count += 1
        with original_connect(*args, **kwargs) as db:
            yield db

    def revoked(*args, **kwargs):
        source = original_read(*args, **kwargs)
        try:
            yield next(source)
            with original_connect() as db:
                db.execute('UPDATE devices SET active=0')
            yield from source
        finally:
            source.close()

    with patch.object(store, 'connect', counted), patch.object(store.objects, 'read', revoked):
        result = client.get(f'/api/media/{mid}/content', headers=headers)
    assert result.content == content[:256 * 1024]
    assert count == 4  # device, access, metadata, and one streaming connection


def test_usage_counters_follow_cancel_move_delete_and_rollback(tmp_path):
    store, fake, client, headers, aid = cloud(tmp_path)
    mid = uploaded(client, headers, aid)
    pending = start(client, headers, aid, photo(), request_id='pending').json()['id']
    with store.connect() as db:
        assert store.usage(db) == (len(photo()), len(photo()), len(photo()))
    client.put(f'/api/uploads/{pending}/chunks?offset=0', headers=headers, content=photo())
    with store.connect() as db:
        assert store.usage(db) == (len(photo()), len(photo()), 0)
    client.delete(f'/api/uploads/{pending}', headers=headers)
    with pytest.raises(RuntimeError), store.connect() as db:
        db.execute('DELETE FROM media WHERE id=?', (mid,))
        raise RuntimeError('rollback')
    with store.connect() as db:
        assert store.usage(db) == (len(photo()), 0, 0)
    client.delete(f'/api/media/{mid}', headers=headers)
    with store.connect() as db:
        assert store.usage(db) == (0, 0, 0)
