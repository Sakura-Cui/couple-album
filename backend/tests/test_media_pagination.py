import pytest
from fastapi.testclient import TestClient
from sakura.main import create_app
from sakura.storage import Store


@pytest.fixture
def pages(tmp_path):
    store = Store(tmp_path, reserve=0)
    client = TestClient(create_app(store))
    headers = []
    for name in ('reader', 'partner'):
        token = client.post('/api/bind', json={'invite': store.invite(), 'name': name}).json()['token']
        headers.append({'Authorization': 'Bearer ' + token})
    aid = client.post('/api/albums', headers=headers[0], json={'name': 'source'}).json()['id']
    target = client.post('/api/albums', headers=headers[0], json={'name': 'destination'}).json()['id']
    # Equal timestamps exercise the ID tie-breaker; fractional seconds must survive the cursor.
    with store.connect() as db:
        for i in range(61):
            db.execute('INSERT INTO media(id,album_id,name,mime,size,sha256,created) VALUES(?,?,?,?,?,?,?)',
                       (f'{i:032x}', aid, f'{i}.png', 'image/png', 1, '0'*64, 1000.123456))
    return store, client, headers, aid, target


@pytest.mark.parametrize('mutation', ['delete_first', 'move_first', 'delete_anchor', 'move_anchor', 'insert'])
def test_cursor_never_skips_existing_media_after_partner_changes(pages, mutation):
    store, client, (reader, partner), aid, target = pages
    path = f'/api/albums/{aid}/media'
    first = client.get(path, headers=reader, params={'limit': 30}).json()
    assert 'cursor' in first[-1]
    original = {f'{i:032x}' for i in range(61)}
    victim = first[-1 if mutation.endswith('anchor') else 0]['id']
    if mutation.startswith('delete'):
        assert client.delete(f'/api/media/{victim}', headers=partner).status_code == 200
        original.remove(victim)
    elif mutation.startswith('move'):
        assert client.patch(f'/api/media/{victim}', headers=partner, json={'album_id': target}).status_code == 200
        original.remove(victim)
    else:
        with store.connect() as db:
            db.execute('INSERT INTO media(id,album_id,name,mime,size,sha256,created) VALUES(?,?,?,?,?,?,?)',
                       ('f'*32, aid, 'new.png', 'image/png', 1, '0'*64, 2000.5))
    seen = [row['id'] for row in first]
    cursor = first[-1]['cursor']
    for _ in range(4):
        response = client.get(path, headers=reader, params={'limit':30, 'before':cursor})
        assert response.status_code == 200
        page = response.json()
        seen.extend(row['id'] for row in page)
        if len(page) < 30:
            break
        cursor = page[-1]['cursor']
    else:
        pytest.fail('Pagination did not terminate')
    assert original <= set(seen)
    assert len(seen) == len(set(seen))
    assert len(page) == 1
    assert client.get(path, headers=reader, params={'before':page[-1]['cursor']}).json() == []


def test_legacy_offset_and_protected_album_still_work(pages):
    _, client, (reader, partner), aid, _ = pages
    path = f'/api/albums/{aid}/media'
    first = client.get(path, headers=reader, params={'limit':30}).json()
    legacy = client.get(path, headers=reader, params={'offset':30, 'limit':30}).json()
    assert len(legacy) == 30
    assert not {r['id'] for r in first} & {r['id'] for r in legacy}
    client.put('/api/password', headers=reader, json={'new_password':'review-password'})
    reader['X-Unlock-Token'] = client.post('/api/unlock', headers=reader, json={'password':'review-password'}).json()['unlock_token']
    assert client.patch(f'/api/albums/{aid}', headers=reader, json={'name':'private','protected':True}).status_code == 200
    assert client.get(path, headers=partner, params={'before':first[-1]['cursor']}).status_code == 423


@pytest.mark.parametrize('cursor', ['garbage', 'nan:'+'0'*32, 'inf:'+'0'*32, '1:invalid'])
def test_invalid_cursor_is_rejected(pages, cursor):
    _, client, (reader, _), aid, _ = pages
    assert client.get(f'/api/albums/{aid}/media', headers=reader, params={'before':cursor}).status_code == 422
