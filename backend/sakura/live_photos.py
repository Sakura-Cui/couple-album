"""Read Apple's Live Photo identifiers without decoding or changing the originals."""
import io
import struct
import uuid
from functools import lru_cache
from collections import defaultdict
from pathlib import PurePath

from PIL import Image, UnidentifiedImageError


def identifier(value):
    try:
        return str(uuid.UUID(value.strip('\0')))
    except (ValueError, AttributeError):
        return None


def photo_identifier(source):
    try:
        with Image.open(source) as image:
            note = image.getexif().get_ifd(34665).get(37500, b'')
        if not note.startswith(b'Apple iOS\0\0\x01MM'):
            return None
        count = struct.unpack_from('>H', note, 14)[0]
        for index in range(count):
            tag, kind, length, offset = struct.unpack_from('>HHII', note, 16 + index * 12)
            if tag == 17 and kind == 2 and 32 <= length <= 64 and offset + length <= len(note):
                return identifier(note[offset:offset + length].decode('ascii'))
    except (OSError, ValueError, TypeError, struct.error, UnicodeError, UnidentifiedImageError):
        pass
    return None


def atoms(data, start=0, end=None):
    end = len(data) if end is None else end
    while start + 8 <= end:
        size, kind = struct.unpack_from('>I4s', data, start)
        header = 8
        if size == 1:
            if start + 16 > end:
                return
            size = struct.unpack_from('>Q', data, start + 8)[0]
            header = 16
        elif size == 0:
            size = end - start
        if size < header or start + size > end:
            return
        yield kind, start + header, start + size
        start += size


def metadata_identifier(data, start, end):
    # ISO meta is a FullBox; Apple QuickTime meta starts directly with child atoms.
    children = list(atoms(data, start + 4 if data[start:start + 4] == b'\0' * 4 else start, end))
    key_index = None
    for kind, first, last in children:
        if kind != b'keys' or first + 8 > last:
            continue
        count = struct.unpack_from('>I', data, first + 4)[0]
        for index, (namespace, key_start, key_end) in enumerate(atoms(data, first + 8, last), 1):
            if index > count:
                break
            if namespace == b'mdta' and data[key_start:key_end] == b'com.apple.quicktime.content.identifier':
                key_index = struct.pack('>I', index)
    if key_index is None:
        return None
    for kind, first, last in children:
        if kind != b'ilst':
            continue
        for key, value_start, value_end in atoms(data, first, last):
            if key != key_index:
                continue
            for value_kind, text_start, text_end in atoms(data, value_start, value_end):
                if value_kind == b'data' and 8 < text_end - text_start <= 72:
                    return identifier(data[text_start + 8:text_end].decode('utf-8'))
    return None


def movie_metadata(read, size):
    offset = 0
    # Skip mdat by its declared length; never download or decode the video payload.
    for _ in range(4096):
        if offset + 8 > size:
            break
        header = read(offset, min(offset + 15, size - 1))
        if len(header) < 8:
            break
        length, kind = struct.unpack_from('>I4s', header)
        header_size = 8
        if length == 1:
            if len(header) < 16:
                break
            length = struct.unpack_from('>Q', header, 8)[0]
            header_size = 16
        elif length == 0:
            length = size - offset
        if length < header_size or offset + length > size:
            break
        if kind == b'moov':
            if length > 16 * 1024 * 1024:
                return b''
            data = read(offset + header_size, offset + length - 1)
            return data
        offset += length
    return b''


def movie_identifier(read, size):
    data = movie_metadata(read, size)
    containers = list(atoms(data))
    for child, first, last in list(containers):
        if child == b'udta':
            containers.extend(atoms(data, first, last))
    for child, first, last in containers:
        if child == b'meta':
            value = metadata_identifier(data, first, last)
            if value:
                return value
    return None


def movie_duration_ms(read, size):
    data = movie_metadata(read, size)
    for kind, first, last in atoms(data):
        if kind != b'mvhd' or first >= last or data[first] not in (0, 1):
            continue
        version = data[first]
        offset = first + (20 if version else 12)
        width = 8 if version else 4
        if offset + 4 + width > last:
            return None
        timescale = struct.unpack_from('>I', data, offset)[0]
        duration = struct.unpack_from('>Q' if version else '>I', data, offset + 4)[0]
        if timescale and 0 < duration < (1 << (width * 8)) - 1:
            return max(1, duration * 1000 // timescale)
    return None


def movie_mirrored(read, size):
    data = movie_metadata(read, size)
    for kind, first, last in atoms(data):
        if kind != b'trak':
            continue
        matrix = None
        video = False
        for kind, first, last in atoms(data, first, last):
            if kind == b'tkhd' and first < last and data[first] in (0, 1):
                offset = first + (52 if data[first] == 1 else 40)
                if offset + 36 <= last:
                    matrix = struct.unpack_from('>9i', data, offset)
            elif kind == b'mdia':
                for child, start, end in atoms(data, first, last):
                    if child == b'hdlr' and start + 12 <= end:
                        video = data[start + 8:start + 12] == b'vide'
        if video and matrix:
            # Rotation alone cannot represent a reflection (negative determinant).
            return matrix[0] * matrix[4] - matrix[1] * matrix[3] < 0
    return False


class LivePhotos:
    def __init__(self, objects):
        self.objects = objects
        # Original content is immutable; album membership and authorization are not cached.
        self.capture_id = lru_cache(maxsize=1024)(self._capture_id)
        self._mirrored = lru_cache(maxsize=1024)(self._motion_mirrored)
        self._duration = lru_cache(maxsize=1024)(self._motion_duration)

    def motion_duration_ms(self, row):
        return self._duration(row['id'], row['sha256'], row['size'])

    def _motion_duration(self, media_id, sha256, size):
        try:
            return movie_duration_ms(lambda start, end: b''.join(self.objects.read('media', media_id, start, end)), size)
        except (OSError, ValueError, struct.error):
            return None

    def motion_mirrored(self, row):
        return self._mirrored(row['id'], row['sha256'], row['size'])

    def _motion_mirrored(self, media_id, sha256, size):
        try:
            return movie_mirrored(lambda start, end: b''.join(self.objects.read('media', media_id, start, end)), size)
        except (OSError, ValueError, struct.error):
            return False

    def _capture_id(self, media_id, sha256, mime, size):
        def read(start, end):
            return b''.join(self.objects.read('media', media_id, start, end))
        try:
            if mime in ('image/jpeg', 'image/heic', 'image/heif'):
                if not self.objects.remote:
                    return photo_identifier(self.objects.root / 'media' / media_id)
                if size <= 64 * 1024 * 1024:
                    return photo_identifier(io.BytesIO(read(0, size - 1)))
            elif mime in ('video/quicktime', 'video/mp4'):
                return movie_identifier(read, size)
        except (OSError, ValueError, struct.error, UnicodeError):
            pass
        return None

    def pairs(self, db, album_id, rows=None):
        rows = list(rows) if rows is not None else db.execute('SELECT * FROM media WHERE album_id=? ORDER BY created DESC,id DESC', (album_id,)).fetchall()
        videos = defaultdict(list)
        photos = defaultdict(list)
        for row in rows:
            if row['mime'] in ('video/quicktime', 'video/mp4'):
                capture = self.capture_id(row['id'], row['sha256'], row['mime'], row['size'])
                if capture:
                    videos[capture].append(row)
        if not videos:
            return {}
        for row in rows:
            if row['mime'] in ('image/jpeg', 'image/heic', 'image/heif'):
                capture = self.capture_id(row['id'], row['sha256'], row['mime'], row['size'])
                if capture in videos:
                    photos[capture].append(row)
        result = {}
        for capture, stills in photos.items():
            movies = videos[capture]
            # Names only disambiguate already-verified captures, including edited originals.
            for photo in stills:
                stem = PurePath(photo['name']).stem.casefold()
                matches = [v for v in movies if PurePath(v['name']).stem.casefold() == stem]
                owners = [p for p in stills if PurePath(p['name']).stem.casefold() == stem]
                if len(matches) == len(owners) == 1:
                    result[photo['id']] = matches[0]['id']
            remaining_photos = [p for p in stills if p['id'] not in result]
            used = set(result.values())
            remaining_videos = [v for v in movies if v['id'] not in used]
            if len(remaining_photos) == len(remaining_videos) == 1:
                result[remaining_photos[0]['id']] = remaining_videos[0]['id']
        return result

    def resolve(self, db, photo):
        if not photo['mime'].startswith('image/'):
            return {'live': False, 'motion_id': None}
        capture = self.capture_id(photo['id'], photo['sha256'], photo['mime'], photo['size'])
        if not capture:
            return {'live': False, 'motion_id': None}
        return {'live': True, 'motion_id': self.pairs(db, photo['album_id']).get(photo['id'])}
