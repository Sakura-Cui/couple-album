import hashlib
import io
import math
import os
import re
import secrets
import time
from pathlib import Path
from typing import Annotated
from concurrent.futures import ThreadPoolExecutor
from functools import partial
from contextlib import asynccontextmanager

from argon2.exceptions import VerificationError
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from PIL import Image, UnidentifiedImageError
from pillow_heif import register_heif_opener
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool
from qcloud_cos.cos_exception import CosClientError, CosServiceError

from .live_photos import LivePhotos
from .media_response import stream_media
from .playback import PlaybackCopies
from .messages import install_messages, MESSAGE_BODY_LIMIT
from .morning import MorningService, MorningInput, MorningUnavailable
from .storage import CHUNK_SIZE, PARALLEL_CHUNK_SIZE, PARALLEL_UPLOADS, PARALLEL_WINDOW, THUMBNAIL_SIZE, PASSWORDS, Store, digest

register_heif_opener(thumbnails=False)
Image.MAX_IMAGE_PIXELS = 200_000_000
IMAGE_MIMES = {'image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/heic', 'image/heif'}
VIDEO_MIMES = {'video/mp4', 'video/quicktime', 'video/webm'}


class Bind(BaseModel):
    invite: str = Field(min_length=20, max_length=100)
    name: str = Field(min_length=1, max_length=80)


class ProfileInput(BaseModel):
    signature: str = Field(max_length=20)
    initialize: bool = False


class Password(BaseModel):
    password: str = Field(min_length=1, max_length=128)


class ChangePassword(BaseModel):
    current_password: str = Field(default='', max_length=128)
    new_password: str = Field(default='', max_length=128)


class AlbumInput(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    protected: bool | None = None


class AlbumPosition(BaseModel):
    pinned: bool
    before_id: str | None = Field(default=None, max_length=32)
    revision: int = Field(ge=0)


class CoverInput(BaseModel):
    media_id: str | None = None


class MoveInput(BaseModel):
    album_id: str


class UploadInput(BaseModel):
    request_id: str = Field(min_length=1, max_length=100)
    album_id: str
    name: str = Field(min_length=1, max_length=255)
    mime: str
    size: int = Field(gt=0)
    sha256: str = Field(default='', pattern=r'^[0-9a-f]{64}$')


class CompleteInput(BaseModel):
    sha256: str | None = Field(default=None, pattern=r'^[0-9a-f]{64}$')


def fail(code, detail):
    raise HTTPException(code, detail)


def row_or_404(row):
    if row is None:
        fail(404, '内容不存在或已删除')
    return row


def thumbnail(source, destination):
    try:
        with Image.open(source) as img:
            if img.width * img.height > Image.MAX_IMAGE_PIXELS:
                fail(415, '图片像素过大，最多支持 2 亿像素')
            img.seek(0)
            if img.format in {'JPEG', 'MPO'}:
                # Reduce in the decoder before allocating a full-resolution raster.
                img.draft('RGB', (1280, 1280))
            if img.width * img.height > 40_000_000:
                fail(415, '此图片格式最多支持 4000 万像素，高像素照片请使用 JPEG 格式')
            # HEIF uses this JPEG for full-screen preview as well as the grid.
            edge = 2560 if img.format == 'HEIF' else 640
            img.thumbnail((edge, edge))
            img.convert('RGB').save(destination, 'JPEG', quality=82)
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        fail(415, '图片损坏或格式不受支持')


def create_app(store=None):
    store = store or Store()
    finalizer = ThreadPoolExecutor(max_workers=1, thread_name_prefix='sakura-save')
    saving = set()
    copies = PlaybackCopies(store.root)
    live_copies = PlaybackCopies(store.root, live=True)

    @asynccontextmanager
    async def lifespan(app):
        yield
        await run_in_threadpool(finalizer.shutdown, wait=True)

    app = FastAPI(title='Sakura Romance', version='1.2.26', docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.store = store
    app.state.morning = MorningService(store)

    @app.exception_handler(CosClientError)
    @app.exception_handler(CosServiceError)
    async def cos_error(request, error):
        return JSONResponse({'detail': '对象存储暂时无法访问，请稍后重试；已上传内容会保留'}, 503)

    @app.middleware('http')
    async def bounds(request, call_next):
        limit = MESSAGE_BODY_LIMIT if request.url.path == '/api/notify' else CHUNK_SIZE if '/chunks' in request.url.path else THUMBNAIL_SIZE if request.url.path.endswith('/thumbnail') else 16384
        if request.method in {'POST', 'PUT', 'PATCH'}:
            length = request.headers.get('content-length')
            if length is None or not length.isdigit():
                return JSONResponse({'detail': '需要有效的 Content-Length'}, 411)
            if int(length) > limit:
                return JSONResponse({'detail': '请求体超过限制'}, 413)
        try:
            response = await call_next(request)
        except OSError as error:
            if error.errno == 28:
                return JSONResponse({'detail': '服务器磁盘空间不足'}, 507)
            raise
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        return response

    def device(authorization: Annotated[str | None, Header()] = None):
        if not authorization or not authorization.startswith('Bearer '):
            fail(401, '请先绑定设备')
        with store.connect() as db:
            row = db.execute('SELECT * FROM devices WHERE token=? AND active=1', (digest(authorization[7:]),)).fetchone()
        if row is None:
            fail(401, '设备授权已失效，请重新绑定')
        return dict(row)

    def check_access(db, who, unlock):
        if not db.execute('SELECT 1 FROM devices WHERE id=? AND active=1', (who['id'],)).fetchone():
            fail(401, '设备授权已失效')

    def check_album(db, album_id, auth):
        check_access(db, *auth)
        album = row_or_404(db.execute('SELECT * FROM albums WHERE id=?', (album_id,)).fetchone())
        if album['protected']:
            require_album_unlock(db, auth)
        return album

    def require_album_unlock(db, auth):
        settings = db.execute('SELECT * FROM settings WHERE id=1').fetchone()
        if not settings['password']:
            fail(409, '请先设置加密相册密码')
        valid = db.execute('SELECT 1 FROM unlocks WHERE token=? AND device_id=? AND version=? AND expires>?',
                           (digest(auth[1] or ''), auth[0]['id'], settings['version'], time.time())).fetchone()
        if not valid:
            fail(423, '相册已锁定，请输入加密相册密码')

    def check_media(db, media_id, auth):
        media = row_or_404(db.execute('SELECT * FROM media WHERE id=?', (media_id,)).fetchone())
        check_album(db, media['album_id'], auth)
        return media

    def access(who=Depends(device), x_unlock_token: Annotated[str | None, Header()] = None):
        with store.connect() as db:
            check_access(db, who, x_unlock_token)
        return who, x_unlock_token

    install_messages(app, store, device, access)

    def verify(db, who, supplied, hashed):
        # Commit failed-attempt state even though the request will be rejected.
        current = db.execute('SELECT * FROM devices WHERE id=?', (who['id'],)).fetchone()
        if current['blocked_until'] > time.time():
            fail(429, '尝试过多，请在 15 分钟后重试')
        try:
            PASSWORDS.verify(hashed, supplied)
        except VerificationError:
            failures = current['failures'] + 1
            blocked = time.time() + 900 if failures >= 5 else 0
            db.execute('UPDATE devices SET failures=?,blocked_until=? WHERE id=?', (0 if blocked else failures, blocked, who['id']))
            db.commit()
            fail(403, '密码不正确')
        db.execute('UPDATE devices SET failures=0,blocked_until=0 WHERE id=?', (who['id'],))

    @app.get('/health')
    def health():
        return {'status': 'ok'}

    @app.post('/api/bind')
    def bind(data: Bind):
        with store.lock(), store.connect() as db:
            invite = db.execute('SELECT * FROM invites WHERE token=? AND expires>?', (digest(data.invite), time.time())).fetchone()
            if not invite:
                fail(403, '邀请凭证无效、已使用或已过期')
            if db.execute('SELECT COUNT(*) FROM devices WHERE active=1').fetchone()[0] >= 3:
                fail(409, '已绑定三台设备，请先在服务器撤销旧设备')
            device_id, token = secrets.token_hex(16), secrets.token_urlsafe(32)
            owner = invite['inbox_owner'] or device_id
            if invite['inbox_owner'] and not db.execute('SELECT 1 FROM devices WHERE inbox_owner=? AND active=1', (owner,)).fetchone():
                fail(403, '共享收件箱的设备授权已失效，请重新生成邀请')
            db.execute('INSERT INTO devices(id,name,token,created,inbox_owner) VALUES(?,?,?,?,?)', (device_id, data.name.strip() or '我们的手机', digest(token), time.time(), owner))
            db.execute('DELETE FROM invites WHERE token=?', (digest(data.invite),))
        return {'device_id': device_id, 'token': token}

    @app.get('/api/session')
    def session(who=Depends(device), x_unlock_token: Annotated[str | None, Header()] = None):
        with store.connect() as db:
            settings = db.execute('SELECT * FROM settings WHERE id=1').fetchone()
            unlocked = bool(db.execute('SELECT 1 FROM unlocks WHERE token=? AND device_id=? AND version=? AND expires>?',
                                      (digest(x_unlock_token or ''), who['id'], settings['version'], time.time())).fetchone())
        return {'password_enabled': False, 'album_password_enabled': bool(settings['password']), 'album_privacy': True,
                'album_unlocked': unlocked, 'version': settings['version'], 'device_name': who['name'],
                'signature': settings['signature'], 'signature_version': settings['signature_version'],
                'signature_import_allowed': settings['signature'] is None and who['id'] == os.getenv('SAKURA_SIGNATURE_SOURCE_DEVICE_ID')}

    @app.put('/api/profile')
    def profile(data: ProfileInput, who=Depends(device)):
        with store.lock(), store.connect() as db:
            check_access(db, who, '')
            settings = db.execute('SELECT signature,signature_version FROM settings WHERE id=1').fetchone()
            if data.initialize:
                if settings['signature'] is not None:
                    return dict(settings)
                if who['id'] != os.getenv('SAKURA_SIGNATURE_SOURCE_DEVICE_ID'):
                    fail(403, '请先由指定设备同步原有签名')
            db.execute('UPDATE settings SET signature=?,signature_version=signature_version+1 WHERE id=1', (data.signature.strip(),))
            return dict(db.execute('SELECT signature,signature_version FROM settings WHERE id=1').fetchone())

    @app.post('/api/unlock')
    def unlock(data: Password, who=Depends(device)):
        with store.lock(), store.connect() as db:
            settings = db.execute('SELECT * FROM settings WHERE id=1').fetchone()
            if not settings['password']:
                return {'unlock_token': '', 'version': settings['version']}
            verify(db, who, data.password, settings['password'])
            token = secrets.token_urlsafe(32)
            db.execute('DELETE FROM unlocks WHERE device_id=? OR expires<?', (who['id'], time.time()))
            db.execute('INSERT INTO unlocks VALUES(?,?,?,?)', (digest(token), who['id'], settings['version'], time.time() + 3600))
        return {'unlock_token': token, 'version': settings['version']}

    @app.post('/api/lock')
    def lock(who=Depends(device)):
        with store.lock(), store.connect() as db:
            db.execute('DELETE FROM unlocks WHERE device_id=?', (who['id'],))
        return {'ok': True}

    @app.put('/api/password')
    def password(data: ChangePassword, who=Depends(device)):
        if data.new_password and len(data.new_password) < 6:
            fail(422, '新密码至少需要 6 个字符')
        with store.lock(), store.connect() as db:
            settings = db.execute('SELECT * FROM settings WHERE id=1').fetchone()
            if settings['password']:
                verify(db, who, data.current_password, settings['password'])
            if not data.new_password and db.execute('SELECT 1 FROM albums WHERE protected=1').fetchone():
                fail(409, '请先将所有加密相册改为普通相册')
            db.execute('UPDATE settings SET password=?,version=version+1 WHERE id=1',
                       (PASSWORDS.hash(data.new_password) if data.new_password else None,))
            db.execute('DELETE FROM unlocks')
        return {'password_enabled': bool(data.new_password)}

    @app.get('/api/storage')
    def storage(auth=Depends(access)):
        with store.connect() as db:
            used, pending, _ = store.usage(db)
        return {'used': used, 'reserved': pending, 'quota': store.quota, 'max_file': store.max_file, 'chunk_size': CHUNK_SIZE, 'parallel_chunk_size': PARALLEL_CHUNK_SIZE, 'parallel_uploads': PARALLEL_UPLOADS, 'parallel_window': PARALLEL_WINDOW, 'deferred_sha256': True, 'storage': 'cos' if store.objects.remote else 'local'}

    def morning_result(data, who, test=False):
        try:
            result = app.state.morning.briefs(who['id'], data, force=test)
        except MorningUnavailable as error:
            fail(503, str(error))
        with store.connect() as db:
            if not db.execute('SELECT 1 FROM devices WHERE id=? AND active=1', (who['id'],)).fetchone():
                fail(401, '设备授权已失效')
        return result

    @app.post('/api/morning/briefs')
    def morning_briefs(data: MorningInput, who=Depends(device)):
        return morning_result(data, who)

    @app.post('/api/morning/test')
    def morning_test(data: MorningInput, who=Depends(device)):
        """Generate a fresh preview (60s debounce); does not consume the daily notification."""
        return morning_result(data, who, test=True)

    @app.get('/api/albums/order')
    def album_order(auth=Depends(access)):
        with store.connect() as db:
            return {'revision': db.execute('SELECT album_revision FROM settings WHERE id=1').fetchone()[0]}

    @app.put('/api/albums/{album_id}/position')
    def album_position(album_id: str, data: AlbumPosition, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            revision = db.execute('SELECT album_revision FROM settings WHERE id=1').fetchone()[0]
            if data.revision != revision:
                fail(409, '相册排列已变化，请刷新后重试')
            selected = db.execute('SELECT * FROM albums WHERE id=?', (album_id,)).fetchone()
            if selected is None:
                fail(409, '相册已删除，请刷新后重试')
            ids = [r['id'] for r in db.execute('SELECT id FROM albums WHERE pinned=? AND id<>? ORDER BY sort_order,id', (int(data.pinned), album_id))]
            if data.before_id is not None and data.before_id not in ids:
                fail(409, '目标相册或分区已变化，请刷新后重试')
            index = ids.index(data.before_id) if data.before_id is not None else len(ids)
            ids.insert(index, album_id)
            db.execute('UPDATE albums SET pinned=? WHERE id=?', (int(data.pinned), album_id))
            db.executemany('UPDATE albums SET sort_order=? WHERE id=?', enumerate(ids))
            db.execute('UPDATE settings SET album_revision=album_revision+1 WHERE id=1')
        return {'revision': revision + 1}

    @app.get('/api/albums')
    def albums(offset: int = Query(0, ge=0), limit: int = Query(30, ge=1, le=100), revision: int | None = Query(None, ge=0), live: bool = False, auth=Depends(access)):
        with store.connect() as db:
            db.execute("BEGIN")
            if revision is not None and revision != db.execute("SELECT album_revision FROM settings WHERE id=1").fetchone()[0]:
                fail(409, "相册排列已变化，请刷新后重试")
            rows = db.execute('''SELECT a.*, (SELECT COUNT(*) FROM media m WHERE m.album_id=a.id) count,
                COALESCE(a.cover_id,(SELECT id FROM media m WHERE m.album_id=a.id AND thumbnail=1 ORDER BY created DESC LIMIT 1)) display_cover
                FROM albums a ORDER BY pinned DESC,sort_order,id LIMIT ? OFFSET ?''', (limit, offset)).fetchall()
            result = []
            for row in rows:
                item = dict(row)
                if live:
                    try:
                        check_album(db, row['id'], auth)
                    except HTTPException as error:
                        if error.status_code != 423:
                            raise
                    else:
                        item['count'] -= len(set(live_photos.pairs(db, row['id']).values()))
                if row['protected']:
                    item.update(cover_id=None, display_cover=None)
                result.append(item)
            return result

    @app.post('/api/albums', status_code=201)
    def create_album(data: AlbumInput, auth=Depends(access)):
        name = data.name.strip()
        if not name:
            fail(422, '相册名称不能为空')
        album_id = secrets.token_hex(16)
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            position = db.execute('SELECT COALESCE(MIN(sort_order),0)-1 FROM albums WHERE pinned=0').fetchone()[0]
            if data.protected:
                require_album_unlock(db, auth)
            db.execute('INSERT INTO albums(id,name,created,sort_order,protected) VALUES(?,?,?,?,?)', (album_id, name, time.time(), position, int(bool(data.protected))))
            db.execute('UPDATE settings SET album_revision=album_revision+1 WHERE id=1')
        return {'id': album_id, 'name': name, 'protected': bool(data.protected)}

    @app.patch('/api/albums/{album_id}')
    def rename_album(album_id: str, data: AlbumInput, auth=Depends(access)):
        if not data.name.strip():
            fail(422, '相册名称不能为空')
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            check_album(db, album_id, auth)
            if data.protected is not None:
                if data.protected:
                    require_album_unlock(db, auth)
                previous = db.execute('SELECT protected FROM albums WHERE id=?', (album_id,)).fetchone()[0]
                db.execute('UPDATE albums SET protected=? WHERE id=?', (int(data.protected), album_id))
                if bool(previous) != data.protected:
                    db.execute('UPDATE settings SET version=version+1,album_revision=album_revision+1 WHERE id=1')
                    db.execute('DELETE FROM unlocks')
            db.execute('UPDATE albums SET name=? WHERE id=?', (data.name.strip(), album_id))
        return {'ok': True}

    @app.put('/api/albums/{album_id}/cover')
    def cover(album_id: str, data: CoverInput, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            check_album(db, album_id, auth)
            if data.media_id:
                row_or_404(db.execute('SELECT id FROM media WHERE id=? AND album_id=? AND thumbnail=1', (data.media_id, album_id)).fetchone())
            db.execute('UPDATE albums SET cover_id=? WHERE id=?', (data.media_id, album_id))
        return {'ok': True}

    @app.delete('/api/albums/{album_id}')
    def delete_album(album_id: str, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            check_album(db, album_id, auth)
            if db.execute('SELECT 1 FROM media WHERE album_id=?', (album_id,)).fetchone() or db.execute("SELECT 1 FROM uploads WHERE album_id=? AND status IN ('pending','saving')", (album_id,)).fetchone():
                fail(409, '请先移走或删除相册内容，并完成或取消上传')
            db.execute('DELETE FROM uploads WHERE album_id=?', (album_id,))
            db.execute('DELETE FROM albums WHERE id=?', (album_id,))
            db.execute('UPDATE settings SET album_revision=album_revision+1 WHERE id=1')
        return {'ok': True}

    @app.get('/api/albums/{album_id}/media')
    def media_list(album_id: str, offset: int = Query(0, ge=0), limit: int = Query(30, ge=1, le=100),
                   before: str | None = Query(None, min_length=1, max_length=100), live: bool = False, auth=Depends(access)):
        with store.connect() as db:
            check_album(db, album_id, auth)
            if before is None:
                rows = db.execute('SELECT * FROM media WHERE album_id=? ORDER BY created DESC,id DESC LIMIT ? OFFSET ?', (album_id, limit, offset))
            else:
                try:
                    created, media_id = before.rsplit(':', 1)
                    created = float(created)
                    if not math.isfinite(created) or not re.fullmatch(r'[0-9a-f]{32}', media_id) or offset:
                        raise ValueError()
                except ValueError:
                    fail(422, '无效的媒体分页游标')
                # Keep the timestamp in the cursor: the boundary row may have been deleted or moved.
                rows = db.execute('SELECT * FROM media WHERE album_id=? AND (created,id)<(?,?) ORDER BY created DESC,id DESC LIMIT ?',
                                  (album_id, created, media_id, limit))
            if live:
                # Group before applying the cursor/limit, so hidden MOVs cannot truncate a page.
                all_rows = db.execute('SELECT * FROM media WHERE album_id=? ORDER BY created DESC,id DESC', (album_id,)).fetchall()
                pairs = live_photos.pairs(db, album_id, all_rows)
                hidden = set(pairs.values())
                visible = [r for r in all_rows if r['id'] not in hidden]
                if before is not None:
                    visible = [r for r in visible if (r['created'], r['id']) < (created, media_id)]
                rows = [dict(r, live=r['id'] in pairs, motion_id=pairs.get(r['id'])) for r in visible[offset:offset + limit]]
            return [dict(row, cursor=f"{row['created']}:{row['id']}") for row in rows]

    @app.patch('/api/media/{media_id}')
    def move(media_id: str, data: MoveInput, live: bool = False, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            row = check_media(db, media_id, auth)
            check_album(db, data.album_id, auth)
            motion = live_photos.resolve(db, row)['motion_id'] if live else None
            for related_id in [media_id] + ([motion] if motion else []):
                check_media(db, related_id, auth)
                db.execute('UPDATE albums SET cover_id=NULL WHERE cover_id=?', (related_id,))
                db.execute('UPDATE media SET album_id=? WHERE id=?', (data.album_id, related_id))
        return {'ok': True}

    @app.delete('/api/media/{media_id}')
    def delete_media(media_id: str, live: bool = False, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            row = check_media(db, media_id, auth)
            motion = live_photos.resolve(db, row)['motion_id'] if live else None
            related = [media_id] + ([motion] if motion else [])
            for related_id in related:
                check_media(db, related_id, auth)
                db.execute('UPDATE albums SET cover_id=NULL WHERE cover_id=?', (related_id,))
                db.execute('DELETE FROM media WHERE id=?', (related_id,))
                if store.objects.remote:
                    db.execute('INSERT OR IGNORE INTO garbage VALUES(?)', (related_id,))
            db.commit()
            for related_id in related:
                if store.delete_objects(related_id):
                    db.execute('DELETE FROM garbage WHERE id=?', (related_id,))
        return {'ok': True}

    @app.post('/api/uploads', status_code=201)
    def upload(data: UploadInput, auth=Depends(access)):
        if data.size > store.max_file:
            fail(413, '文件超过单文件大小限制')
        if data.mime not in IMAGE_MIMES | VIDEO_MIMES:
            fail(415, '支持 JPG、PNG、WebP、GIF、HEIC、HEIF、MP4、MOV 和 WebM')
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            check_album(db, data.album_id, auth)
            old = db.execute('SELECT * FROM uploads WHERE device_id=? AND request_id=?', (auth[0]['id'], data.request_id)).fetchone()
            if old:
                if any(old[key] != getattr(data, key) for key in ('album_id', 'name', 'mime', 'size')) or (data.sha256 and old['sha256'] != data.sha256):
                    fail(409, '上传请求标识已用于其他文件')
                return dict(old)
            if not store.space_available(db, data.size):
                fail(507, '空间配额或服务器安全剩余空间不足')
            upload_id = secrets.token_hex(16)
            db.execute('''INSERT INTO uploads(id,device_id,request_id,album_id,name,mime,size,sha256,created,updated)
                          VALUES(?,?,?,?,?,?,?,?,?,?)''', (upload_id, auth[0]['id'], data.request_id, data.album_id,
                                                         data.name, data.mime, data.size, data.sha256, time.time(), time.time()))
            return dict(db.execute('SELECT * FROM uploads WHERE id=?', (upload_id,)).fetchone())

    def get_upload(db, upload_id, auth):
        row = row_or_404(db.execute('SELECT * FROM uploads WHERE id=? AND device_id=?', (upload_id, auth[0]['id'])).fetchone())
        check_album(db, row['album_id'], auth)
        return row

    @app.get('/api/uploads/{upload_id}')
    def upload_status(upload_id: str, auth=Depends(access)):
        with store.connect() as db:
            return dict(get_upload(db, upload_id, auth))

    def write_chunk(upload_id, offset, body, auth, parallel=False):
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            row = get_upload(db, upload_id, auth)
            if row['status'] != 'pending':
                fail(409, '上传已结束')
            received = offset + len(body)
            if received > row['size']:
                fail(413, '分片超出文件大小')
            if parallel and (offset % PARALLEL_CHUNK_SIZE or len(body) != min(PARALLEL_CHUNK_SIZE, row['size'] - offset)):
                fail(422, '并行分片位置或大小不正确')
            path = store.root / 'uploads' / upload_id
            def result(end):
                return dict(offset=end, received_offset=received) if parallel else {'offset': end}
            if offset < row['offset']:
                if received > row['offset']:
                    fail(409, '分片与已接收内容重叠')
                with path.open('rb') as file:
                    file.seek(offset)
                    if file.read(len(body)) != body:
                        fail(409, '重复分片内容不一致')
                return result(row['offset'])
            if offset > row['offset'] and (not parallel or offset > row['offset'] + (PARALLEL_WINDOW - 1) * PARALLEL_CHUNK_SIZE):
                fail(409, '请从服务器返回的 offset 继续上传')
            parts = db.execute('SELECT offset,size FROM upload_parts WHERE upload_id=? ORDER BY offset', (upload_id,)).fetchall()
            # A legacy sequential chunk can overlap durable, out-of-order parts.
            # Verify the overlap before writing; never destroy an acknowledged tail before commit.
            duplicate = False
            for part in parts:
                left, right = max(offset, part['offset']), min(received, part['offset'] + part['size'])
                if left < right:
                    with path.open('rb') as file:
                        file.seek(left)
                        if file.read(right - left) != body[left - offset:right - offset]:
                            fail(409, '重复分片内容不一致')
                    duplicate = duplicate or (left == offset and right == received)
            if duplicate and parallel:
                return result(row['offset'])
            if not store.space_available(db):
                fail(507, '服务器安全剩余空间不足')
            if not parallel:
                # Old clients expect exactly one chunk of progress per response.
                # Drop out-of-order receipts and safely resend that unconfirmed tail.
                db.execute('DELETE FROM upload_parts WHERE upload_id=?', (upload_id,))
            with path.open('r+b' if path.exists() else 'w+b') as file:
                file.seek(offset)
                file.write(body)
                if not parallel and not parts:
                    file.truncate()
                file.flush()
                os.fsync(file.fileno())
            # Data is durable before its receipt is committed. Uncommitted bytes can be resent.
            db.execute('INSERT INTO upload_parts VALUES(?,?,?) ON CONFLICT(upload_id,offset) DO UPDATE SET size=MAX(size,excluded.size)',
                       (upload_id, offset, len(body)))
            end = row['offset']
            for part in db.execute('SELECT offset,size FROM upload_parts WHERE upload_id=? ORDER BY offset', (upload_id,)):
                if part['offset'] > end:
                    break
                end = max(end, part['offset'] + part['size'])
            db.execute('DELETE FROM upload_parts WHERE upload_id=? AND offset+size<=?', (upload_id, end))
            db.execute('UPDATE uploads SET offset=?,updated=? WHERE id=?', (end, time.time(), upload_id))
            return result(end)

    @app.put('/api/uploads/{upload_id}/chunks')
    async def chunk(upload_id: str, request: Request, offset: int = Query(ge=0), parallel: bool = False, auth=Depends(access)):
        body = bytearray()
        limit = PARALLEL_CHUNK_SIZE if parallel else CHUNK_SIZE
        async for piece in request.stream():
            body.extend(piece)
            if len(body) > limit:
                fail(413, '分片过大')
        if not body:
            fail(422, '分片不能为空')
        return await run_in_threadpool(write_chunk, upload_id, offset, body, auth, parallel)

    def finalize(upload_id):
        try:
            with store.connect() as db:
                row = dict(db.execute('SELECT * FROM uploads WHERE id=?', (upload_id,)).fetchone())
            source = store.root / 'uploads' / upload_id
            destination = store.root / 'media' / upload_id
            # A crash after rename and before DB commit is recoverable on retry.
            source = source if source.exists() else destination
            if not source.exists() or source.stat().st_size != row['size']:
                fail(409, '上传文件不完整')
            with source.open('rb') as file:
                actual = hashlib.file_digest(file, 'sha256').hexdigest()
                file.seek(0)
                signature = file.read(32)
            if actual != row['sha256']:
                fail(422, '文件校验失败，请取消任务后重新上传')
            has_thumb = 0
            if row['mime'] in IMAGE_MIMES:
                thumbnail(source, store.root / 'thumbnails' / upload_id)
                has_thumb = 1
            elif not ((row['mime'] in {'video/mp4', 'video/quicktime'} and signature[4:8] == b'ftyp') or
                      (row['mime'] == 'video/webm' and signature[:4] == b'\x1aE\xdf\xa3')):
                fail(415, '视频文件签名与类型不匹配')
            if store.objects.remote:
                store.objects.put('media', upload_id, source, row['mime'])
                if has_thumb:
                    store.objects.put('thumbnails', upload_id, store.root / 'thumbnails' / upload_id, 'image/jpeg')
            else:
                store.objects.put('media', upload_id, source, row['mime'])
            with store.lock(), store.connect() as db:
                db.execute('INSERT INTO media VALUES(?,?,?,?,?,?,?,?)', (upload_id, row['album_id'], row['name'], row['mime'], row['size'], actual, time.time(), has_thumb))
                db.execute("UPDATE uploads SET status='complete',error='',updated=? WHERE id=?", (time.time(), upload_id))
                db.commit()
                if store.objects.remote:
                    source.unlink(missing_ok=True)
                    (store.root / 'thumbnails' / upload_id).unlink(missing_ok=True)
        except Exception as error:
            detail = error.detail if isinstance(error, HTTPException) else '保存失败，请重试；已上传文件保留'
            with store.lock(), store.connect() as db:
                db.execute("UPDATE uploads SET status='pending',error=? WHERE id=? AND status='saving'", (detail, upload_id))
            raise
        finally:
            with store.lock():
                saving.discard(upload_id)

    @app.post('/api/uploads/{upload_id}/complete')
    def complete(upload_id: str, data: CompleteInput | None = None, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            row = get_upload(db, upload_id, auth)
            if data is not None and data.sha256 and row['sha256'] and data.sha256 != row['sha256']:
                fail(409, '文件校验值与任务不一致')
            if not row['sha256'] and (data is None or data.sha256 is None):
                fail(422, '完成上传时必须提供 SHA-256')
            if row['status'] == 'complete':
                row_or_404(db.execute('SELECT id FROM media WHERE id=?', (upload_id,)).fetchone())
                return {'media_id': upload_id, 'status': 'complete'}
            if row['status'] not in ('pending', 'saving') or row['offset'] != row['size']:
                fail(409, '文件尚未上传完整或任务已取消')
            if upload_id in saving:
                return JSONResponse({'media_id': upload_id, 'status': 'saving'}, 202)
            if not row['sha256']:
                db.execute('UPDATE uploads SET sha256=? WHERE id=?', (data.sha256, upload_id))
            db.execute("UPDATE uploads SET status='saving',error='',updated=? WHERE id=?", (time.time(), upload_id))
            db.commit()
            saving.add(upload_id)
            if store.objects.remote:
                finalizer.submit(finalize, upload_id)
                return JSONResponse({'media_id': upload_id, 'status': 'saving'}, 202)
        finalize(upload_id)
        return {'media_id': upload_id, 'status': 'complete'}

    @app.delete('/api/uploads/{upload_id}')
    def cancel_upload(upload_id: str, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            check_access(db, *auth)
            row = get_upload(db, upload_id, auth)
            if row['status'] in ('complete', 'saving'):
                fail(409, '文件正在保存或已经完成，请保存完成后从相册删除')
            db.execute("UPDATE uploads SET status='cancelled',updated=? WHERE id=?", (time.time(), upload_id))
            if store.objects.remote:
                db.execute('INSERT OR IGNORE INTO garbage VALUES(?)', (upload_id,))
            db.commit()
            (store.root / 'uploads' / upload_id).unlink(missing_ok=True)
            if store.delete_objects(upload_id):
                db.execute('DELETE FROM garbage WHERE id=?', (upload_id,))
        return {'ok': True}

    @app.put('/api/media/{media_id}/thumbnail')
    async def set_thumbnail(media_id: str, request: Request, auth=Depends(access)):
        body = bytearray()
        async for piece in request.stream():
            body.extend(piece)
            if len(body) > THUMBNAIL_SIZE:
                fail(413, '缩略图过大')
        def save():
            with store.lock(), store.connect() as db:
                check_access(db, *auth)
                check_media(db, media_id, auth)
                if not store.disk_available(db, THUMBNAIL_SIZE):
                    fail(507, '服务器安全剩余空间不足')
                tmp = store.root / 'thumbnails' / (media_id + '.tmp')
                try:
                    thumbnail(io.BytesIO(body), tmp)
                    if store.objects.remote:
                        store.objects.put('thumbnails', media_id, tmp, 'image/jpeg')
                    else:
                        tmp.replace(store.root / 'thumbnails' / media_id)
                finally:
                    tmp.unlink(missing_ok=True)
                db.execute('UPDATE media SET thumbnail=1 WHERE id=?', (media_id,))
            return {'ok': True}
        return await run_in_threadpool(save)

    file_response = partial(stream_media, store, copies, live_copies, check_access, check_media, fail)

    live_photos = LivePhotos(store.objects)

    @app.get('/api/media/{media_id}/live')
    def live_photo(media_id: str, auth=Depends(access)):
        with store.connect() as db:
            row = check_media(db, media_id, auth)
            result = live_photos.resolve(db, row)
            if result['motion_id']:
                motion = check_media(db, result['motion_id'], auth)
                result['motion_mirrored'] = live_photos.motion_mirrored(motion)
                result['motion_duration_ms'] = live_photos.motion_duration_ms(motion)
            check_media(db, media_id, auth)
            return result

    @app.get('/api/media/{media_id}/playback')
    def playback_status(media_id: str, live: bool = False, auth=Depends(access)):
        with store.connect() as db:
            row = check_media(db, media_id, auth)
            return {'status': 'original'} if store.objects.remote else (live_copies if live else copies).status(row)

    @app.post('/api/media/{media_id}/playback/retry')
    def retry_playback(media_id: str, live: bool = False, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            row = check_media(db, media_id, auth)
            if store.objects.remote:
                return {'status': 'original'}
            selected = live_copies if live else copies
            if selected.status(row)['status'] == 'failed' or (live and selected.status(row)['status'] == 'pending'):
                selected.write(row, 'pending')
            return selected.status(row)

    @app.get('/api/media/{media_id}/playback/adaptive')
    def adaptive_status(media_id: str, auth=Depends(access)):
        with store.connect() as db:
            row = check_media(db, media_id, auth)
            return {'status': 'unsupported'} if store.objects.remote else copies.adaptive_status(row)

    @app.post('/api/media/{media_id}/playback/adaptive')
    def prepare_adaptive(media_id: str, retry: bool = False, auth=Depends(access)):
        with store.lock(), store.connect() as db:
            row = check_media(db, media_id, auth)
            if store.objects.remote or not row['mime'].startswith('video/'):
                return {'status': 'unsupported'}
            state = copies.adaptive_status(row)['status']
            if not copies.adaptive_requested(row) or state == 'pending' or (retry and state == 'failed'):
                copies.write_adaptive(row, 'pending')
            return copies.adaptive_status(row)

    @app.get('/api/media/{media_id}/playback/hls/{asset}')
    def adaptive_content(media_id: str, asset: str, range: Annotated[str | None, Header()] = None, auth=Depends(access)):
        return file_response(media_id, auth, range, playback=True, adaptive_asset=asset)

    @app.get('/api/media/{media_id}/playback/content')
    def playback_content(media_id: str, range: Annotated[str | None, Header()] = None, live: bool = False, auth=Depends(access)):
        return file_response(media_id, auth, range, playback=True, live=live)

    @app.get('/api/media/{media_id}/content')
    def content(media_id: str, range: Annotated[str | None, Header()] = None, auth=Depends(access)):
        return file_response(media_id, auth, range)

    @app.get('/api/media/{media_id}/thumbnail')
    def get_thumbnail(media_id: str, auth=Depends(access)):
        return file_response(media_id, auth, thumb=True)

    return app
