from pathlib import Path
from unittest.mock import patch
import pytest

from fastapi.testclient import TestClient

from sakura.admin import backup, restore
from sakura.main import create_app
from sakura.storage import Store
from test_api import album, photo, uploaded


def test_deployment_defaults_use_disk_without_cloud_credentials(tmp_path, monkeypatch):
    root = Path(__file__).resolve().parents[2]
    values = dict(line.split('=', 1) for line in (root / 'deploy/.env.example').read_text().splitlines()
                  if line and not line.startswith('#'))
    assert values['SAKURA_STORAGE'] == 'local'
    assert int(values['SAKURA_QUOTA_BYTES']) == 8 * 1024**3
    assert 'SAKURA_BACKUP_DIR' in values
    assert not any(name.startswith('SAKURA_COS_') for name in values)
    monkeypatch.setenv('SAKURA_STORAGE', values['SAKURA_STORAGE'])
    with patch('sakura.storage.CosObjects', side_effect=AssertionError('Disk mode must not initialize COS')):
        assert Store(tmp_path, reserve=0).objects.remote is False


def test_disk_media_survives_restart_and_backup_restore(tmp_path, monkeypatch):
    monkeypatch.setenv('SAKURA_STORAGE', 'local')
    store = Store(tmp_path / 'data', reserve=0)
    client = TestClient(create_app(store))
    token = client.post('/api/bind', json={'invite': store.invite(), 'name': '手机一'}).json()['token']
    headers = {'Authorization': 'Bearer ' + token}
    aid = album(client, headers)
    photo_id = uploaded(client, headers, aid)
    video = b'\x00\x00\x00\x18ftypisom' + b'x' * 512
    video_id = uploaded(client, headers, aid, video, request_id='video', mime='video/mp4')
    assert client.put(f'/api/media/{video_id}/thumbnail', headers=headers, content=photo()).status_code == 200
    assert (store.root / 'media' / photo_id).read_bytes() == photo()
    assert (store.root / 'media' / video_id).read_bytes() == video
    assert not list((store.root / 'uploads').iterdir())
    assert len(list((store.root / 'thumbnails').iterdir())) == 2

    restarted = Store(store.root, reserve=0)
    client = TestClient(create_app(restarted))
    response = client.get(f'/api/media/{video_id}/content', headers={**headers, 'Range': 'bytes=8-20'})
    assert response.status_code == 206 and response.content == video[8:21]
    assert client.get(f'/api/media/{photo_id}/content').status_code == 401

    snapshot = backup(restarted, tmp_path / 'backups' / 'snapshot')
    recovered = Store(restore(snapshot, tmp_path / 'restored'), reserve=0)
    clone = TestClient(create_app(recovered))
    assert clone.get(f'/api/media/{photo_id}/content', headers=headers).status_code == 401
    fresh_token = clone.post('/api/bind', json={'invite': recovered.invite(), 'name': '恢复手机'}).json()['token']
    fresh = {'Authorization': 'Bearer ' + fresh_token}
    assert clone.get(f'/api/media/{video_id}/content', headers=fresh).content == video
    assert clone.get(f'/api/media/{video_id}/thumbnail', headers=fresh).status_code == 200
    assert clone.delete(f'/api/media/{photo_id}', headers=fresh).status_code == 200
    assert not (recovered.root / 'media' / photo_id).exists()
    assert not (recovered.root / 'thumbnails' / photo_id).exists()
    # Deleting recovered content must not change the original data or backup.
    assert (store.root / 'media' / photo_id).is_file()
    assert (snapshot / 'media' / photo_id).is_file()


def test_invalid_storage_mode_does_not_create_data(tmp_path, monkeypatch):
    monkeypatch.setenv('SAKURA_STORAGE', 'typo')
    target = tmp_path / 'invalid'
    with pytest.raises(ValueError, match='SAKURA_STORAGE'):
        Store(target, reserve=0)
    assert not target.exists()
