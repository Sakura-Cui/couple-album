import hashlib
import os

import pytest

from test_api import album, start
from test_api import env  # shared isolated fixture
from sakura.storage import CHUNK_SIZE


@pytest.mark.skipif(os.environ.get('SAKURA_LARGE_TEST') != '1', reason='Opt-in 2 GiB disk / I/O acceptance test')
def test_near_two_gibibytes_resumable_stream(env, tmp_path):
    store, client, (headers, partner) = env
    size = 2 * 1024**3 - 19
    store.max_file = 2 * 1024**3
    store.quota = 4 * 1024**3
    source = tmp_path / 'large.mp4'
    with source.open('wb') as file:
        file.write(b'\x00\x00\x00\x18ftypisom' + b'\0'*12)
        file.truncate(size)
    with source.open('rb') as file:
        sha = hashlib.file_digest(file, 'sha256').hexdigest()
    aid = album(client, headers)
    result = client.post('/api/uploads', headers=headers, json={'request_id': 'large', 'album_id': aid,
        'name': 'large.mp4', 'mime': 'video/mp4', 'size': size, 'sha256': sha})
    assert result.status_code == 201
    uid = result.json()['id']
    with source.open('rb') as file:
        offset = 0
        while offset < size:
            data = file.read(CHUNK_SIZE)
            result = client.put(f'/api/uploads/{uid}/chunks?offset={offset}', content=data, headers=headers)
            assert result.status_code == 200
            offset = result.json()['offset']
    assert client.post(f'/api/uploads/{uid}/complete', headers=headers).status_code == 200
    response = client.get(f'/api/media/{uid}/content', headers={**partner, 'Range': f'bytes={size-16}-'})
    assert response.status_code == 206 and response.content == b'\0'*16
    assert client.get('/api/storage', headers=headers).json()['used'] == size
