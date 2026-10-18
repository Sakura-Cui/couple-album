import hashlib
import io
import struct
import uuid

from PIL import Image

from test_api import env, album

LIVE_ID = 'f46909a0-386e-46d7-b394-95c08692715b'


def apple_photo(identifier=LIVE_ID):
    value = identifier.upper().encode() + b'\0'
    note = b'Apple iOS\0\0\x01MM' + struct.pack('>H', 1)
    note += struct.pack('>HHII', 17, 2, len(value), 32) + b'\0' * 4 + value
    exif = Image.Exif()
    exif[34665] = {37500: note}
    output = io.BytesIO()
    Image.new('RGB', (40, 30), '#e49ab0').save(output, 'JPEG', exif=exif)
    return output.getvalue()


def atom(kind, data):
    return struct.pack('>I4s', 8 + len(data), kind) + data


def paired_video(identifier=LIVE_ID):
    key = b'com.apple.quicktime.content.identifier'
    keys = atom(b'keys', b'\0' * 4 + struct.pack('>I', 1) + struct.pack('>I4s', len(key) + 8, b'mdta') + key)
    value = atom(b'data', struct.pack('>II', 1, 0) + identifier.encode())
    ilst = atom(b'ilst', atom(struct.pack('>I', 1), value))
    return atom(b'ftyp', b'qt  ' + b'\0' * 4) + atom(b'mdat', b'video fixture') + atom(b'moov', atom(b'meta', b'\0' * 4 + keys + ilst))


def upload(client, headers, aid, name, mime, data):
    response = client.post('/api/uploads', headers=headers, json={
        'album_id': aid, 'request_id': uuid.uuid4().hex, 'name': name, 'mime': mime,
        'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    assert response.status_code == 201, response.text
    mid = response.json()['id']
    assert client.put(f'/api/uploads/{mid}/chunks?offset=0', headers=headers, content=data).status_code == 200
    assert client.post(f'/api/uploads/{mid}/complete', headers=headers).status_code == 200
    return mid


def test_live_photo_reports_missing_video_and_finds_verified_pair(env):
    _, client, (a, _) = env
    aid = album(client, a)
    still = upload(client, a, aid, 'IMG_1001.JPG', 'image/jpeg', apple_photo())
    response = client.get(f'/api/media/{still}/live', headers=a)
    assert response.status_code == 200
    assert response.json() == {'live': True, 'motion_id': None}
    # Same-name video from a different capture is not a valid Live Photo pair.
    upload(client, a, aid, 'IMG_1001.MOV', 'video/quicktime', paired_video(str(uuid.uuid4())))
    assert client.get(f'/api/media/{still}/live', headers=a).json()['motion_id'] is None
    motion = upload(client, a, aid, 'IMG_1001.mov', 'video/quicktime', paired_video())
    assert client.get(f'/api/media/{still}/live', headers=a).json() == {'live': True, 'motion_id': motion, 'motion_mirrored': False, 'motion_duration_ms': None}


def test_live_pair_does_not_cross_albums_or_expose_locked_media(env):
    _, client, (a, b) = env
    aid = album(client, a)
    other = album(client, a, '其他相册')
    still = upload(client, a, aid, 'IMG_1001.JPG', 'image/jpeg', apple_photo())
    motion = upload(client, a, other, 'IMG_1001.MOV', 'video/quicktime', paired_video())
    assert client.get(f'/api/media/{still}/live', headers=a).json()['motion_id'] is None
    assert client.patch(f'/api/media/{motion}', headers=a, json={'album_id': aid}).status_code == 200
    assert client.get(f'/api/media/{still}/live', headers=a).json()['motion_id'] == motion
    assert client.get(f'/api/media/{still}/live').status_code == 401
    assert client.put('/api/password', headers=a, json={'new_password': 'live-secret'}).status_code == 200
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a, json={'password': 'live-secret'}).json()['unlock_token']
    assert client.patch(f'/api/albums/{aid}', headers=a, json={'name': '实况相册', 'protected': True}).status_code == 200
    assert client.get(f'/api/media/{still}/live', headers=b).status_code == 423
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a, json={'password': 'live-secret'}).json()['unlock_token']
    assert client.get(f'/api/media/{still}/live', headers=a).json()['motion_id'] == motion


def test_ordinary_photo_remains_still_and_deleted_pair_disappears(env):
    _, client, (a, _) = env
    aid = album(client, a)
    output = io.BytesIO()
    Image.new('RGB', (20, 20)).save(output, 'JPEG')
    still = upload(client, a, aid, 'ordinary.jpg', 'image/jpeg', output.getvalue())
    assert client.get(f'/api/media/{still}/live', headers=a).json() == {'live': False, 'motion_id': None}
    live = upload(client, a, aid, 'IMG_1001.JPG', 'image/jpeg', apple_photo())
    motion = upload(client, a, aid, 'IMG_1001.MOV', 'video/quicktime', paired_video())
    assert client.delete(f'/api/media/{motion}', headers=a).status_code == 200
    assert client.get(f'/api/media/{live}/live', headers=a).json()['motion_id'] is None


def test_renamed_live_pair_is_matched_by_identifier(env):
    _, client, (a, _) = env
    aid = album(client, a)
    still = upload(client, a, aid, 'mbc_live_photo_export.JPG', 'image/jpeg', apple_photo())
    motion = upload(client, a, aid, 'original_1234.MOV', 'video/quicktime', paired_video())
    assert client.get(f'/api/media/{still}/live', headers=a).json()['motion_id'] == motion


def test_malformed_metadata_is_ignored():
    from sakura.live_photos import movie_identifier, photo_identifier
    assert photo_identifier(io.BytesIO(b'not an image')) is None
    for payload in (b'', b'\x00' * 8, struct.pack('>I4s', 1, b'moov'), atom(b'moov', b'broken')):
        assert movie_identifier(lambda start, end: payload[start:end + 1], len(payload)) is None


def test_pair_identifier_supports_remote_objects_and_large_movie_offsets(tmp_path):
    from sakura.live_photos import LivePhotos, movie_identifier
    from sakura.objects import LocalObjects
    root = tmp_path / 'media'
    root.mkdir()
    (root / 'photo').write_bytes(apple_photo())
    objects = LocalObjects(tmp_path)
    objects.remote = True
    assert LivePhotos(objects).capture_id('photo', 'hash', 'image/jpeg', len(apple_photo())) == LIVE_ID
    # A sparse 64-bit mdat must be skipped without reading its multi-GB payload.
    payload = paired_video()
    moov_offset = payload.index(b'moov') - 4
    tail = payload[moov_offset:]
    mdat_size = 5 * 1024 ** 3
    prefix = struct.pack('>I4sQ', 1, b'mdat', mdat_size)
    reads = []
    def read(start, end):
        reads.append((start, end))
        if start < len(prefix):
            return prefix[start:end + 1]
        return tail[start - mdat_size:end - mdat_size + 1]
    assert movie_identifier(read, mdat_size + len(tail)) == LIVE_ID
    assert sum(end - start + 1 for start, end in reads) < 1024


def quicktime_video(identifier=LIVE_ID):
    payload = paired_video(identifier)
    start = payload.index(b'meta') - 4
    # Apple MOV uses a QuickTime meta atom without FullBox version/flags.
    meta = atom(b'meta', payload[start + 12:])
    return payload[:payload.index(b'moov') - 4] + atom(b'moov', meta)


def test_real_quicktime_layout_and_heic_pair(env):
    from pillow_heif import register_heif_opener
    register_heif_opener(thumbnails=False)
    _, client, (a, _) = env
    aid = album(client, a)
    output = io.BytesIO()
    with Image.open(io.BytesIO(apple_photo())) as image:
        image.save(output, 'HEIF', exif=image.info['exif'])
    still = upload(client, a, aid, 'IMG_1001.HEIC', 'image/heic', output.getvalue())
    motion = upload(client, a, aid, 'IMG_1001.MOV', 'video/quicktime', quicktime_video())
    assert client.get(f'/api/media/{still}/live', headers=a).json() == {'live': True, 'motion_id': motion, 'motion_mirrored': False, 'motion_duration_ms': None}


def test_grouped_list_hides_only_verified_motion_before_pagination(env):
    store, client, (a, _) = env
    aid = album(client, a)
    still = upload(client, a, aid, 'capture.JPG', 'image/jpeg', apple_photo())
    motion = upload(client, a, aid, 'capture.MOV', 'video/quicktime', quicktime_video())
    ordinary = upload(client, a, aid, 'unrelated.MOV', 'video/quicktime', paired_video(str(uuid.uuid4())))
    path = f'/api/albums/{aid}/media'
    first = client.get(path, headers=a, params={'live': True, 'limit': 1}).json()
    second = client.get(path, headers=a, params={'live': True, 'limit': 1, 'before': first[-1]['cursor']}).json()
    assert [r['id'] for r in first + second] == [ordinary, still]
    assert second[0]['live'] is True and second[0]['motion_id'] == motion
    assert client.get(path, headers=a, params={'live': True, 'before': second[-1]['cursor']}).json() == []
    assert len(client.get(path, headers=a).json()) == 3  # Older clients retain file-level semantics.
    assert client.get('/api/albums?live=true', headers=a).json()[0]['count'] == 2
    assert client.get(path+'?live=true', headers={'Authorization': 'Bearer invalid'}).status_code == 401


def test_edited_and_original_pairs_share_capture_but_match_their_own_video(env):
    _, client, (a, _) = env
    aid = album(client, a)
    original = upload(client, a, aid, 'IMG_1001.JPG', 'image/jpeg', apple_photo())
    edited = upload(client, a, aid, 'IMG_E1001.JPG', 'image/jpeg', apple_photo())
    original_motion = upload(client, a, aid, 'IMG_1001.MOV', 'video/quicktime', quicktime_video())
    edited_motion = upload(client, a, aid, 'IMG_E1001.MOV', 'video/quicktime', quicktime_video())
    assert client.get(f'/api/media/{original}/live', headers=a).json()['motion_id'] == original_motion
    assert client.get(f'/api/media/{edited}/live', headers=a).json()['motion_id'] == edited_motion
    rows = client.get(f'/api/albums/{aid}/media?live=true', headers=a).json()
    assert {r['id'] for r in rows} == {original, edited}


def test_ambiguous_verified_videos_remain_visible(env):
    _, client, (a, _) = env
    aid = album(client, a)
    still = upload(client, a, aid, 'renamed.JPG', 'image/jpeg', apple_photo())
    for name in ('one.MOV', 'two.MOV'):
        upload(client, a, aid, name, 'video/quicktime', quicktime_video())
    assert client.get(f'/api/media/{still}/live', headers=a).json()['motion_id'] is None
    assert len(client.get(f'/api/albums/{aid}/media?live=true', headers=a).json()) == 3


def test_grouped_move_delete_and_legacy_file_operations(env):
    store, client, (a, _) = env
    aid = album(client, a)
    target = album(client, a, 'target')
    still = upload(client, a, aid, 'capture.JPG', 'image/jpeg', apple_photo())
    motion = upload(client, a, aid, 'capture.MOV', 'video/quicktime', quicktime_video())
    assert client.patch(f'/api/media/{still}?live=true', headers=a, json={'album_id':target}).status_code == 200
    assert client.get(f'/api/albums/{aid}/media', headers=a).json() == []
    assert client.get(f'/api/media/{still}/live', headers=a).json()['motion_id'] == motion
    assert client.delete(f'/api/media/{still}?live=true', headers=a).status_code == 200
    assert client.get(f'/api/albums/{target}/media', headers=a).json() == []
    assert not (store.root/'media'/motion).exists()
    still = upload(client, a, aid, 'capture.JPG', 'image/jpeg', apple_photo())
    motion = upload(client, a, aid, 'capture.MOV', 'video/quicktime', quicktime_video())
    assert client.delete(f'/api/media/{still}', headers=a).status_code == 200
    assert client.get(f'/api/media/{motion}/content', headers=a).status_code == 200


def test_grouped_media_protection_and_pair_removal(env):
    _, client, (a, b) = env
    aid = album(client, a)
    still = upload(client, a, aid, 'capture.JPG', 'image/jpeg', apple_photo())
    motion = upload(client, a, aid, 'capture.MOV', 'video/quicktime', quicktime_video())
    client.put('/api/password', headers=a, json={'new_password':'live-secret'})
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a, json={'password':'live-secret'}).json()['unlock_token']
    assert client.patch(f'/api/albums/{aid}', headers=a, json={'name':'private','protected':True}).status_code == 200
    assert client.get(f'/api/albums/{aid}/media?live=true', headers=b).status_code == 423
    assert client.delete(f'/api/media/{still}?live=true', headers=b).status_code == 423
    assert client.patch(f'/api/media/{still}?live=true', headers=b, json={'album_id':aid}).status_code == 423
    a['X-Unlock-Token'] = client.post('/api/unlock', headers=a, json={'password':'live-secret'}).json()['unlock_token']
    assert client.delete(f'/api/media/{motion}', headers=a).status_code == 200
    rows = client.get(f'/api/albums/{aid}/media?live=true', headers=a).json()
    assert rows[0]['id'] == still and rows[0]['motion_id'] is None
