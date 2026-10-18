import io
from pathlib import Path

from fastapi.testclient import TestClient

from sakura.admin import backup, backup_cos, restore_cos
from sakura.main import create_app
from sakura.objects import CosObjects
from sakura.storage import Store
from test_api import album, photo, uploaded, start


class FakeCos:
    def __init__(self):
        self.files = {}

    def upload_file(self, **kwargs):
        assert kwargs['MAXThread'] == 1
        assert kwargs['EnableMD5'] is True
        self.files[kwargs['Key']] = Path(kwargs['LocalFilePath']).read_bytes()

    def head_object(self, **kwargs):
        if kwargs['Key'] not in self.files:
            from qcloud_cos.cos_exception import CosServiceError
            raise CosServiceError('HEAD', {'code': 'NoSuchKey'}, 404)
        return {'Content-Length': len(self.files[kwargs['Key']])}

    def get_object(self, **kwargs):
        data = self.files[kwargs['Key']]
        start, end = kwargs['Range'][6:].split('-')
        stream = io.BytesIO(data[int(start):int(end)+1 if end else None])
        return {'Body': type('Body', (), {'get_raw_stream': lambda _: stream})()}

    def delete_object(self, **kwargs):
        self.files.pop(kwargs['Key'], None)

    def copy_object(self, **kwargs):
        self.files[kwargs['Key']] = self.files[kwargs['CopySource']['Key']]

    def list_objects(self, **kwargs):
        return {'Contents': [{'Key': key} for key in self.files if key.startswith(kwargs['Prefix'])]}


def test_cos_upload_read_range_backup_delete_without_local_media(tmp_path):
    store = Store(tmp_path / 'data', quota=100 * 1024**3, reserve=0)
    fake = FakeCos()
    store.objects = CosObjects(fake, 'private-bucket-123', 'sakura-romance')
    client = TestClient(create_app(store))
    token = client.post('/api/bind', json={'invite': store.invite(), 'name': '测试'}).json()['token']
    headers = {'Authorization': 'Bearer ' + token}
    mid = uploaded(client, headers, album(client, headers))
    assert list((store.root / 'media').iterdir()) == []
    assert list((store.root / 'uploads').iterdir()) == []
    assert list((store.root / 'thumbnails').iterdir()) == []
    assert fake.files['sakura-romance/media/' + mid] == photo()
    result = client.get(f'/api/media/{mid}/content', headers={**headers, 'Range': 'bytes=2-12'})
    assert result.status_code == 206 and result.content == photo()[2:13]
    assert client.get(f'/api/media/{mid}/thumbnail', headers=headers).status_code == 200
    assert client.get(f'/api/media/{mid}/content').status_code == 401
    target = backup(store, tmp_path / 'backup')
    assert (target / 'media' / mid).read_bytes() == photo()
    assert client.delete(f'/api/media/{mid}', headers=headers).status_code == 200
    assert not fake.files


def test_cos_snapshot_and_restore_do_not_overwrite_active_space(tmp_path):
    store = Store(tmp_path / 'data', reserve=0)
    fake = FakeCos()
    store.objects = CosObjects(fake, 'private-bucket-123', 'sakura-romance')
    client = TestClient(create_app(store))
    token = client.post('/api/bind', json={'invite': store.invite(), 'name': '测试'}).json()['token']
    headers = {'Authorization': 'Bearer ' + token}
    mid = uploaded(client, headers, album(client, headers))
    assert start(client, headers, album(client, headers), photo(), request_id='unfinished').status_code == 201
    backup_cos(store, 'test-snapshot')
    client.delete(f'/api/media/{mid}', headers=headers)
    assert 'sakura-romance/media/' + mid not in fake.files
    assert fake.files['sakura-romance/backups/test-snapshot/media/' + mid] == photo()
    restore_cos(store, 'test-snapshot', tmp_path / 'restored', 'sakura-restored-test')
    assert fake.files['sakura-restored-test/media/' + mid] == photo()
    assert 'sakura-romance/media/' + mid not in fake.files
    assert (tmp_path / 'restored' / 'sakura.sqlite').is_file()
    restored = Store(tmp_path / 'restored', reserve=0)
    with restored.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM uploads WHERE status!='complete'").fetchone()[0] == 0
