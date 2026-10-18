import io
import time

import pytest
from PIL import Image

from test_api import album, env, start, uploaded


@pytest.mark.parametrize('mime', ['image/heic', 'image/heif'])
def test_heif_upload_preview_preserves_original(env, mime):
    store, client, headers = env
    data = io.BytesIO()
    Image.new('RGB', (3000, 1800), '#e49ab0').save(data, 'HEIF')
    original = data.getvalue()
    aid = album(client, headers[0])
    mid = uploaded(client, headers[0], aid, original, mime=mime)
    assert client.get(f'/api/media/{mid}/content', headers=headers[0]).content == original
    preview = client.get(f'/api/media/{mid}/thumbnail', headers=headers[0])
    assert preview.status_code == 200
    assert preview.headers['content-type'] == 'image/jpeg'
    with Image.open(io.BytesIO(preview.content)) as image:
        assert image.format == 'JPEG'
        assert image.size == (2560, 1536)
    assert client.get(f'/api/media/{mid}/thumbnail').status_code == 401
    assert client.delete(f'/api/media/{mid}', headers=headers[0]).status_code == 200
    assert not (store.root / 'media' / mid).exists()
    assert not (store.root / 'thumbnails' / mid).exists()


def test_corrupt_heic_rejected(env):
    store, client, headers = env
    aid = album(client, headers[0])
    content = b'broken heic file'
    response = start(client, headers[0], aid, content, mime='image/heic')
    assert response.status_code == 201
    uid = response.json()['id']
    assert client.put(f'/api/uploads/{uid}/chunks?offset=0', headers=headers[0], content=content).status_code == 200
    client.post(f'/api/uploads/{uid}/complete', headers=headers[0])
    for _ in range(200):
        state = client.get(f'/api/uploads/{uid}', headers=headers[0]).json()
        if state.get('error'):
            break
        time.sleep(0.01)
    assert state['status'] == 'pending'
    assert '图片损坏或格式不受支持' in state['error']
    with store.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM media').fetchone()[0] == 0
