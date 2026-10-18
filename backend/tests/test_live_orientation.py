"""QuickTime display matrices, including reflections omitted by Media3 1.6.1."""
import struct
import pytest
from test_api import env, album
from test_live_photos import atom, apple_photo, paired_video, upload

MATRICES = [(1,0,0,1), (0,1,-1,0), (-1,0,0,-1), (0,-1,1,0),
            (-1,0,0,1), (1,0,0,-1), (0,1,1,0), (0,-1,-1,0)]


def track(matrix, version=0, handler=b'vide'):
    a,b,c,d = matrix
    header = bytes([version]) + b'\0' * ((52 if version else 40) - 1)
    tkhd = atom(b'tkhd', header + struct.pack('>9i', a*65536,b*65536,0,c*65536,d*65536,0,0,0,1<<30) + b'\0'*8)
    mdia = atom(b'mdia', atom(b'hdlr', b'\0'*8 + handler + b'\0'*12))
    return atom(b'trak', tkhd + mdia)


def movie(matrix, version=0):
    original = paired_video()
    offset = original.index(b'moov') - 4
    return original[:offset] + atom(b'moov', original[offset+8:] + track(matrix, version))


@pytest.mark.parametrize('matrix', MATRICES)
@pytest.mark.parametrize('version', [0,1])
def test_only_reflections_require_live_compatibility(matrix, version):
    from sakura.live_photos import movie_mirrored
    data = movie(matrix, version)
    reads = []
    def read(start, end):
        reads.append((start,end))
        return data[start:end+1]
    a,b,c,d = matrix
    assert movie_mirrored(read, len(data)) == (a*d-b*c < 0)
    assert not any(start < data.index(b'moov') - 4 and end-start+1 > 16 for start,end in reads)


def test_audio_track_and_malformed_boxes_do_not_claim_video_reflection():
    from sakura.live_photos import movie_mirrored
    for data in [b'', atom(b'moov', b'bad'), atom(b'moov', atom(b'trak',atom(b'tkhd',b'\0'))),
                 atom(b'moov', track(MATRICES[4],handler=b'soun')+track(MATRICES[0]))]:
        assert not movie_mirrored(lambda start,end:data[start:end+1],len(data))


def test_reflection_api_auth_and_immutable_metadata_cache(env):
    from sakura.live_photos import LivePhotos
    store,client,(auth,other)=env
    aid=album(client,auth)
    still=upload(client,auth,aid,'capture.JPG','image/jpeg',apple_photo())
    motion=upload(client,auth,aid,'capture.MOV','video/quicktime',movie(MATRICES[7]))
    path=f'/api/media/{still}/live'
    assert client.get(path,headers=auth).json()=={'live':True,'motion_id':motion,'motion_mirrored':True,'motion_duration_ms':None}
    assert client.get(path).status_code==401
    with store.connect() as db:row=dict(db.execute('SELECT * FROM media WHERE id=?',(motion,)).fetchone())
    live=LivePhotos(store.objects)
    assert live.motion_mirrored(row)
    original=(store.root/'media'/motion).read_bytes()
    assert original==movie(MATRICES[7])
    client.put('/api/password',headers=auth,json={'new_password':'secret'})
    auth['X-Unlock-Token']=client.post('/api/unlock',headers=auth,json={'password':'secret'}).json()['unlock_token']
    client.patch(f'/api/albums/{aid}',headers=auth,json={'name':'locked','protected':True})
    assert client.get(path,headers=other).status_code==423
    # Cached orientation is tied to content identity, also for remote range reads.
    remote=store.objects;remote.remote=True
    live=LivePhotos(remote)
    assert live.motion_mirrored(row)
    (store.root/'media'/motion).write_bytes(movie(MATRICES[0]))
    assert live.motion_mirrored(row)
    row['sha256']='changed'
    assert not live.motion_mirrored(row)


def test_mirror_metadata_skips_sparse_64bit_video_payload():
    from sakura.live_photos import movie_mirrored
    tail=atom(b'moov',track(MATRICES[7]))
    size=5*1024**3
    prefix=struct.pack('>I4sQ',1,b'mdat',size)
    reads=[]
    def read(start,end):
        reads.append((start,end))
        return prefix[start:end+1] if start<len(prefix) else tail[start-size:end-size+1]
    assert movie_mirrored(read,size+len(tail))
    assert sum(end-start+1 for start,end in reads)<1024


def test_oversized_movie_metadata_is_not_read():
    from sakura.live_photos import movie_mirrored
    size=17*1024**2
    prefix=struct.pack('>I4s',size,b'moov')+b'\0'*8
    reads=[]
    def read(start,end):
        reads.append((start,end));return prefix[start:end+1]
    assert not movie_mirrored(read,size)
    assert reads==[(0,15)]
