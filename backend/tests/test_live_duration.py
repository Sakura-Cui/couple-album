import struct
import pytest
from test_live_photos import atom

@pytest.mark.parametrize('version',[0,1])
def test_live_duration_reads_movie_timescale_without_decoding(version):
    from sakura.live_photos import movie_duration_ms
    body=(b'\x01'+b'\0'*19+struct.pack('>IQ',600,1800)) if version else (b'\0'*12+struct.pack('>II',600,1800))
    data=atom(b'moov',atom(b'mvhd',body))
    assert movie_duration_ms(lambda start,end:data[start:end+1],len(data))==3000

@pytest.mark.parametrize('body',[b'',b'\0'*20,b'\x02'+b'\0'*31,b'\0'*12+struct.pack('>II',600,0xffffffff)])
def test_unknown_live_duration_is_not_treated_as_short(body):
    from sakura.live_photos import movie_duration_ms
    data=atom(b'moov',atom(b'mvhd',body))
    assert movie_duration_ms(lambda start,end:data[start:end+1],len(data)) is None
