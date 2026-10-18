"""Private inbox and explicit, idempotent single-device vivo notification delivery."""
import base64
import binascii
import hashlib
import io
import json
import os
import secrets
import threading
import time

import httpx
from fastapi import Depends, Header, HTTPException, Query, Response
from PIL import Image, ImageOps, UnidentifiedImageError
from pydantic import BaseModel, Field, model_validator

MESSAGE_BODY_LIMIT = 6 * 1024 * 1024
IMAGE_LIMIT = 4 * 1024 * 1024


class LetterInput(BaseModel):
    request_id: str = Field(min_length=1, max_length=64, pattern=r'^[A-Za-z0-9_-]+$')
    device_id: str = Field(pattern=r'^[0-9a-f]{32}$')
    title: str = Field(default='老婆宝宝，有你的悄悄话', min_length=1, max_length=40)
    text: str = Field(default='', max_length=4000)
    image_base64: str = Field(default='', max_length=6 * 1024 * 1024)

    @model_validator(mode='after')
    def content_required(self):
        self.title, self.text = self.title.strip(), self.text.strip()
        if not self.title or not (self.text or self.image_base64):
            raise ValueError('请填写标题，以及文字或图片')
        return self


class Registration(BaseModel):
    reg_id: str = Field(default='', max_length=512, pattern=r'^[A-Za-z0-9_-]*$')
    enabled: bool

    @model_validator(mode='after')
    def token_required(self):
        if self.enabled and not self.reg_id:
            raise ValueError('开启推送需要有效的 regId')
        return self


def prepare_image(encoded):
    if not encoded:
        return None
    try:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > IMAGE_LIMIT:
            raise HTTPException(413, '图片最多 4 MiB')
        with Image.open(io.BytesIO(raw)) as source:
            if source.format not in {'JPEG', 'PNG', 'WEBP'} or source.width * source.height > 20_000_000:
                raise HTTPException(415, '请使用 2000 万像素以内的 JPEG、PNG 或 WebP 图片')
            source.seek(0)
            source = ImageOps.exif_transpose(source)
            source.thumbnail((1920, 1920))
            output = io.BytesIO()
            source.convert('RGB').save(output, 'JPEG', quality=85)
            return output.getvalue()
    except (ValueError, binascii.Error, OSError, UnidentifiedImageError, Image.DecompressionBombError):
        raise HTTPException(415, '图片编码或内容无效') from None


class VivoPush:
    def __init__(self):
        self.app_id = os.getenv('VIVO_APP_ID', '')
        self.app_key = os.getenv('VIVO_APP_KEY', '')
        self.secret = os.getenv('VIVO_APP_SECRET', '')
        self.mode = int(os.getenv('VIVO_PUSH_MODE', '1'))
        if self.mode not in (0, 1):
            raise ValueError('VIVO_PUSH_MODE 必须为 0 或 1')
        self.token, self.expires = '', 0
        self.lock = threading.Lock()

    @property
    def configured(self):
        return bool(self.app_id and self.app_key and self.secret)

    def auth(self):
        with self.lock:
            if self.token and self.expires > time.time():
                return self.token
            timestamp = int(time.time() * 1000)
            sign = hashlib.md5(f'{self.app_id}{self.app_key}{timestamp}{self.secret}'.encode()).hexdigest()
            result = httpx.post('https://api-push.vivo.com.cn/message/auth', json={
                'appId': int(self.app_id), 'appKey': self.app_key, 'timestamp': timestamp, 'sign': sign,
            }, timeout=10).raise_for_status().json()
            if result.get('result') != 0 or not result.get('authToken'):
                raise ValueError(f"vivo 鉴权失败（{result.get('result', 'invalid_response')}）")
            self.token, self.expires = result['authToken'], time.time() + 3600
            return self.token

    def send(self, letter, reg_id):
        payload = {
            'appId': int(self.app_id), 'regId': reg_id, 'title': letter['title'],
            'content': (('[图片] ' if letter['has_image'] else '') + letter['text'])[:100] or '[图片]',
            'notifyType': 4, 'timeToLive': 86400, 'skipType': 1, 'networkType': -1,
            'category': 'IM', 'pushMode': self.mode, 'requestId': letter['id'],
            'clientCustomMap': {'sakura_message_id': letter['id']},
        }
        token = self.auth()
        result = httpx.post('https://api-push.vivo.com.cn/message/send', headers={'authToken': token},
                            json=payload, timeout=15).raise_for_status().json()
        if result.get('result') == 0 and result.get('taskId') is not None:
            return 'accepted', str(result['taskId']), ''
        return 'rejected', '', f"vivo 返回错误码 {result.get('result', 'invalid_response')}"


def install_messages(app, store, device, access):
    app.state.push = VivoPush()
    delivery_lock = threading.Lock()

    def sender(authorization: str | None = Header(default=None)):
        key = os.getenv('SAKURA_NOTIFY_KEY', '')
        if not key:
            raise HTTPException(503, '发送接口尚未配置')
        supplied = (authorization or '').removeprefix('Bearer ')
        if not authorization or not authorization.startswith('Bearer ') or not secrets.compare_digest(supplied.encode(), key.encode()):
            raise HTTPException(401, '发送密钥无效')

    def public(row):
        return {key: row[key] for key in ('id', 'title', 'text', 'created', 'read_at', 'has_image')}

    def status(message_id):
        with store.connect() as db:
            row = db.execute('SELECT * FROM letters WHERE id=?', (message_id,)).fetchone()
            return {'id': row['id'], 'device_id': row['device_id'], 'stored': True,
                    'push': {'state': 'unknown' if row['push_state'] == 'sending' and row['push_started'] < time.time() - 120 else row['push_state'], 'task_id': row['push_task'],
                             'error': row['push_error'], 'mode': row['push_mode']}}

    def deliver(message_id):
        # Only one sender runs at a time. Persist sending before the external side effect.
        with delivery_lock:
            with store.lock(), store.connect() as db:
                letter = db.execute('SELECT * FROM letters WHERE id=?', (message_id,)).fetchone()
                if not letter:
                    raise HTTPException(404, '消息不存在')
                if letter['push_state'] == 'accepted' or (letter['push_state'] == 'sending' and letter['push_started'] > time.time() - 120):
                    return status(message_id)
                target = db.execute('SELECT p.reg_id,p.enabled,d.active FROM devices d LEFT JOIN push_devices p '
                                    'ON p.device_id=d.id WHERE d.id=?', (letter['device_id'],)).fetchone()
                reason = ('revoked' if not target or not target['active'] else
                          'not_registered' if not target['reg_id'] else
                          'disabled' if not target['enabled'] else
                          'not_configured' if not app.state.push.configured else '')
                if reason:
                    db.execute('UPDATE letters SET push_state=? WHERE id=?', (reason, message_id))
                else:
                    db.execute("UPDATE letters SET push_state='sending',push_mode=?,push_error='',push_started=? WHERE id=?",
                               (app.state.push.mode, time.time(), message_id))
            if reason:
                return status(message_id)
            try:
                state, task, error = app.state.push.send(dict(letter), target['reg_id'])
            except (httpx.HTTPError, ValueError, KeyError, TypeError):
                # A timeout can happen after vivo accepted the request; do not blindly resend.
                state, task, error = 'unknown', '', '推送结果未确认，请先检查手机和控制台；消息已保存'
            with store.lock(), store.connect() as db:
                db.execute('UPDATE letters SET push_state=?,push_task=?,push_error=? WHERE id=?',
                           (state, task, error, message_id))
            return status(message_id)

    @app.get('/api/notify/devices', dependencies=[Depends(sender)])
    def recipients():
        with store.connect() as db:
            return [dict(row) for row in db.execute('SELECT d.id,d.name,COALESCE(p.enabled,0) AS push_enabled,'
                    'COALESCE(p.updated,0) AS push_updated FROM devices d LEFT JOIN push_devices p ON p.device_id=d.id '
                    'WHERE d.active=1 ORDER BY d.created')]

    @app.post('/api/notify', dependencies=[Depends(sender)])
    def notify(data: LetterInput):
        fingerprint = hashlib.sha256(data.model_dump_json().encode()).hexdigest()
        with store.connect() as db:
            old = db.execute('SELECT id,fingerprint FROM letters WHERE request_id=?', (data.request_id,)).fetchone()
        if old:
            if old['fingerprint'] != fingerprint:
                raise HTTPException(409, 'request_id 已被不同消息使用')
            return status(old['id'])
        picture = prepare_image(data.image_base64)
        size = len(picture or b'') + len(data.text.encode()) + len(data.title.encode())
        with store.lock(), store.connect() as db:
            old = db.execute('SELECT id,fingerprint FROM letters WHERE request_id=?', (data.request_id,)).fetchone()
            if old:
                if old['fingerprint'] != fingerprint:
                    raise HTTPException(409, 'request_id 已被不同消息使用')
                return status(old['id'])
            if not db.execute('SELECT 1 FROM devices WHERE id=? AND active=1', (data.device_id,)).fetchone():
                raise HTTPException(404, '收件设备不存在或已撤销')
            if db.execute('SELECT COUNT(*) FROM letters WHERE created>?', (time.time() - 60,)).fetchone()[0] >= 60:
                raise HTTPException(429, '发送过于频繁，请稍后再试')
            if not store.space_available(db, size):
                raise HTTPException(507, '存储空间不足')
            message_id = secrets.token_hex(16)
            db.execute('INSERT INTO letters(id,request_id,fingerprint,device_id,title,text,image,has_image,size,created) '
                       'VALUES(?,?,?,?,?,?,?,?,?,?)', (message_id, data.request_id, fingerprint, data.device_id,
                        data.title, data.text, picture, int(picture is not None), size, time.time()))
        return deliver(message_id)

    @app.get('/api/notify/{message_id}', dependencies=[Depends(sender)])
    def notify_status(message_id: str):
        with store.connect() as db:
            if not db.execute('SELECT 1 FROM letters WHERE id=?', (message_id,)).fetchone():
                raise HTTPException(404, '消息不存在')
        return status(message_id)

    @app.post('/api/notify/{message_id}/retry', dependencies=[Depends(sender)])
    def retry(message_id: str):
        return deliver(message_id)

    @app.put('/api/push/registration')
    def register(data: Registration, who=Depends(device)):
        with store.lock(), store.connect() as db:
            if not db.execute('SELECT 1 FROM devices WHERE id=? AND active=1', (who['id'],)).fetchone():
                raise HTTPException(401, '设备授权已失效')
            db.execute('INSERT INTO push_devices(device_id,reg_id,enabled,updated) VALUES(?,?,?,?) '
                       'ON CONFLICT(device_id) DO UPDATE SET reg_id=excluded.reg_id,enabled=excluded.enabled,updated=excluded.updated',
                       (who['id'], data.reg_id, int(data.enabled), time.time()))
        return {'ok': True, 'device_id': who['id']}

    @app.get('/api/messages')
    def inbox(before: int | None = Query(default=None, ge=1), limit: int = Query(default=30, ge=1, le=50), auth=Depends(access)):
        with store.connect() as db:
            rows = db.execute('SELECT seq,id,title,text,created,read_at,has_image FROM letters '
                              'WHERE device_id IN (SELECT id FROM devices WHERE inbox_owner=?) '
                              'AND (? IS NULL OR seq<?) ORDER BY seq DESC LIMIT ?', (auth[0]['inbox_owner'], before, before, limit + 1)).fetchall()
            return {'items': [public(row) for row in rows[:limit]],
                    'next_before': rows[limit-1]['seq'] if len(rows) > limit else None}

    def owned(message_id, who):
        with store.connect() as db:
            row = db.execute('SELECT * FROM letters WHERE id=? AND device_id IN '
                             '(SELECT id FROM devices WHERE inbox_owner=?)', (message_id, who['inbox_owner'])).fetchone()
        if not row:
            raise HTTPException(404, '消息不存在')
        return row

    @app.get('/api/messages/{message_id}')
    def detail(message_id: str, auth=Depends(access)):
        return public(owned(message_id, auth[0]))

    @app.put('/api/messages/{message_id}/read')
    def read(message_id: str, auth=Depends(access)):
        owned(message_id, auth[0])
        with store.lock(), store.connect() as db:
            db.execute('UPDATE letters SET read_at=COALESCE(read_at,?) WHERE id=?', (time.time(), message_id))
        return {'ok': True}

    @app.get('/api/messages/{message_id}/image')
    def image(message_id: str, auth=Depends(access)):
        row = owned(message_id, auth[0])
        if row['image'] is None:
            raise HTTPException(404, '消息没有图片')
        return Response(bytes(row['image']), media_type='image/jpeg')
