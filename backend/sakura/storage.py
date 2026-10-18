import fcntl
import hashlib
import os
import secrets
import shutil
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path

from argon2 import PasswordHasher
from .objects import CosObjects, LocalObjects
from qcloud_cos.cos_exception import CosClientError, CosServiceError

PASSWORDS = PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1)
CHUNK_SIZE = 16 * 1024 * 1024
PARALLEL_CHUNK_SIZE = 1024 * 1024
PARALLEL_UPLOADS = 3
PARALLEL_WINDOW = 12
THUMBNAIL_SIZE = 4 * 1024 * 1024


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS settings (
 id INTEGER PRIMARY KEY CHECK(id=1), password TEXT, version INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO settings(id) VALUES(1);
CREATE TABLE IF NOT EXISTS invites(token TEXT PRIMARY KEY, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS devices (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, token TEXT UNIQUE NOT NULL,
 active INTEGER NOT NULL DEFAULT 1, created REAL NOT NULL,
 failures INTEGER NOT NULL DEFAULT 0, blocked_until REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS unlocks (
 token TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id),
 version INTEGER NOT NULL, expires REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS albums (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, cover_id TEXT, created REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS media (
 id TEXT PRIMARY KEY, album_id TEXT NOT NULL REFERENCES albums(id),
 name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL,
 sha256 TEXT NOT NULL, created REAL NOT NULL, thumbnail INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS media_album_time ON media(album_id, created DESC, id DESC);
CREATE TABLE IF NOT EXISTS push_devices (
 device_id TEXT PRIMARY KEY REFERENCES devices(id), reg_id TEXT NOT NULL,
 enabled INTEGER NOT NULL DEFAULT 0, updated REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS letters (
 seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, request_id TEXT NOT NULL UNIQUE,
 fingerprint TEXT NOT NULL, device_id TEXT NOT NULL REFERENCES devices(id), title TEXT NOT NULL,
 text TEXT NOT NULL, image BLOB, has_image INTEGER NOT NULL, size INTEGER NOT NULL,
 created REAL NOT NULL, read_at REAL, push_state TEXT NOT NULL DEFAULT 'pending',
 push_started REAL NOT NULL DEFAULT 0, push_task TEXT NOT NULL DEFAULT '', push_error TEXT NOT NULL DEFAULT '', push_mode INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS letters_recipient ON letters(device_id,seq DESC);
CREATE TABLE IF NOT EXISTS garbage (id TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS uploads (
 id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id),
 request_id TEXT NOT NULL, album_id TEXT NOT NULL REFERENCES albums(id),
 name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL,
 sha256 TEXT NOT NULL, offset INTEGER NOT NULL DEFAULT 0,
 status TEXT NOT NULL DEFAULT 'pending', created REAL NOT NULL, updated REAL NOT NULL,
 UNIQUE(device_id, request_id)
);
CREATE TABLE IF NOT EXISTS upload_parts (
 upload_id TEXT NOT NULL REFERENCES uploads(id) ON DELETE CASCADE,
 offset INTEGER NOT NULL, size INTEGER NOT NULL,
 PRIMARY KEY(upload_id, offset)
);
CREATE TRIGGER IF NOT EXISTS upload_parts_finished AFTER UPDATE OF status ON uploads
WHEN NEW.status NOT IN ('pending', 'saving')
BEGIN DELETE FROM upload_parts WHERE upload_id=NEW.id; END;
-- Backup copies delete pending uploads using raw SQLite connections without foreign_keys.
CREATE TRIGGER IF NOT EXISTS upload_parts_deleted AFTER DELETE ON uploads
BEGIN DELETE FROM upload_parts WHERE upload_id=OLD.id; END;
"""


class Store:
    def __init__(self, root=None, quota=None, reserve=None, max_file=None):
        storage = os.environ.get('SAKURA_STORAGE', 'local')
        if storage not in ('local', 'cos'):
            raise ValueError('SAKURA_STORAGE 只支持 local 或 cos，当前部署应使用 local')
        self.root = Path(root or os.environ.get('SAKURA_DATA', './data')).resolve()
        if (self.root / '.restore.json').exists() and not (self.root / '.restore-complete').exists():
            raise ValueError('恢复未完成，请先重试 restore-cos，禁止启动此数据目录')
        self.quota = quota if quota is not None else int(os.environ.get('SAKURA_QUOTA_BYTES', 8 * 1024**3))
        self.reserve = reserve if reserve is not None else int(os.environ.get('SAKURA_RESERVE_BYTES', 12 * 1024**3))
        self.max_file = max_file if max_file is not None else int(os.environ.get('SAKURA_MAX_FILE_BYTES', 2 * 1024**3))
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        for folder in ('media', 'uploads', 'thumbnails'):
            (self.root / folder).mkdir(exist_ok=True, mode=0o700)
        self.objects = CosObjects() if storage == 'cos' else LocalObjects(self.root)
        self.staging_limit = int(os.environ.get('SAKURA_STAGING_BYTES', 4 * 1024**3))
        with self.lock():
            with self.connect() as db:
                db.executescript(SCHEMA)
                # Keep added columns and their legacy-order backfill atomic on failed upgrades.
                db.execute("BEGIN IMMEDIATE")
                if 'inbox_owner' not in {r[1] for r in db.execute('PRAGMA table_info(devices)')}:
                    db.execute('ALTER TABLE devices ADD COLUMN inbox_owner TEXT')
                db.execute('UPDATE devices SET inbox_owner=id WHERE inbox_owner IS NULL')
                db.execute('CREATE INDEX IF NOT EXISTS devices_inbox_owner ON devices(inbox_owner)')
                if 'inbox_owner' not in {r[1] for r in db.execute('PRAGMA table_info(invites)')}:
                    db.execute('ALTER TABLE invites ADD COLUMN inbox_owner TEXT')
                settings_columns = {r[1] for r in db.execute('PRAGMA table_info(settings)')}
                if 'signature' not in settings_columns:
                    db.execute('ALTER TABLE settings ADD COLUMN signature TEXT')
                if 'signature_version' not in settings_columns:
                    db.execute('ALTER TABLE settings ADD COLUMN signature_version INTEGER NOT NULL DEFAULT 0')
                album_columns = {r[1] for r in db.execute('PRAGMA table_info(albums)')}
                if 'protected' not in album_columns:
                    db.execute('ALTER TABLE albums ADD COLUMN protected INTEGER NOT NULL DEFAULT 0')
                    # Existing global protection becomes album protection without exposing old media.
                    if db.execute('SELECT password FROM settings WHERE id=1').fetchone()[0]:
                        db.execute('UPDATE albums SET protected=1')
                if 'pinned' not in album_columns:
                    db.execute('ALTER TABLE albums ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0')
                if 'sort_order' not in album_columns:
                    db.execute('ALTER TABLE albums ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0')
                    ids = db.execute('SELECT id FROM albums ORDER BY created DESC,id DESC').fetchall()
                    db.executemany('UPDATE albums SET sort_order=? WHERE id=?', ((i, row['id']) for i, row in enumerate(ids)))
                if 'album_revision' not in {r[1] for r in db.execute('PRAGMA table_info(settings)')}:
                    db.execute('ALTER TABLE settings ADD COLUMN album_revision INTEGER NOT NULL DEFAULT 0')
                db.execute('CREATE INDEX IF NOT EXISTS albums_position ON albums(pinned DESC,sort_order,id)')
                db.execute('CREATE TABLE IF NOT EXISTS morning_cache (key TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched REAL NOT NULL)')
                db.execute('CREATE TABLE IF NOT EXISTS morning_love (device_id TEXT NOT NULL, day TEXT NOT NULL, love TEXT NOT NULL, PRIMARY KEY(device_id,day))')
                if 'error' not in {r[1] for r in db.execute('PRAGMA table_info(uploads)')}:
                    db.execute("ALTER TABLE uploads ADD COLUMN error TEXT NOT NULL DEFAULT ''")
                db.execute('CREATE TABLE IF NOT EXISTS usage_totals (id INTEGER PRIMARY KEY, used INTEGER, pending INTEGER, unwritten INTEGER)')
                db.execute('''INSERT OR REPLACE INTO usage_totals VALUES(1,
                    (SELECT COALESCE(SUM(size),0) FROM media)+(SELECT COALESCE(SUM(size),0) FROM letters),
                    (SELECT COALESCE(SUM(size),0) FROM uploads WHERE status IN ('pending','saving')),
                    (SELECT COALESCE(SUM(size-offset),0) FROM uploads WHERE status IN ('pending','saving')))''')
                # Triggers keep counters correct for API, administration, and restored databases.
                for table in ('media', 'uploads', 'letters'):
                    for operation in ('INSERT', 'DELETE', 'UPDATE'):
                        updates = []
                        for row, sign in [('OLD', '-') , ('NEW', '+')]:
                            if (row == 'OLD' and operation == 'INSERT') or (row == 'NEW' and operation == 'DELETE'):
                                continue
                            if table in ('media', 'letters'):
                                updates.append(f'UPDATE usage_totals SET used=used{sign}{row}.size WHERE id=1;')
                            else:
                                active = f"({row}.status IN ('pending','saving'))"
                                updates.append(f'UPDATE usage_totals SET pending=pending{sign}{active}*{row}.size, unwritten=unwritten{sign}{active}*({row}.size-{row}.offset) WHERE id=1;')
                        db.execute(f'CREATE TRIGGER IF NOT EXISTS usage_{table}_{operation} AFTER {operation} ON {table} BEGIN {" ".join(updates)} END')

    @contextmanager
    def lock(self):
        with (self.root / '.write.lock').open('a') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)

    @contextmanager
    def connect(self, cross_thread=False):
        db = sqlite3.connect(self.root / 'sakura.sqlite', timeout=30, check_same_thread=not cross_thread)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA foreign_keys=ON')
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def invite(self, hours=24, share_with=None):
        token = secrets.token_urlsafe(32)
        with self.lock(), self.connect() as db:
            owner = None
            if share_with is not None:
                device = db.execute('SELECT inbox_owner FROM devices WHERE id=? AND active=1', (share_with,)).fetchone()
                if device is None:
                    raise ValueError('共享收件箱的设备不存在或已撤销')
                owner = device['inbox_owner']
            db.execute('DELETE FROM invites WHERE expires < ?', (time.time(),))
            db.execute('INSERT INTO invites(token,expires,inbox_owner) VALUES(?,?,?)', (digest(token), time.time() + hours * 3600, owner))
        return token

    def usage(self, db):
        return tuple(db.execute('SELECT used,pending,unwritten FROM usage_totals WHERE id=1').fetchone())

    def disk_available(self, db, additional=0):
        unwritten = self.usage(db)[2]
        return shutil.disk_usage(self.root).free - unwritten - additional >= self.reserve

    def delete_objects(self, key):
        """Idempotent deletion; transient COS failures remain in the garbage queue."""
        try:
            self.objects.delete('media', key)
            self.objects.delete('thumbnails', key)
            for suffix in ('.mp4', '.tmp.mp4', '.json', '.error.log', '.hls.json', '.hls.json.tmp', '.live.mp4', '.live.tmp.mp4', '.live.json', '.live.json.tmp'):
                (self.root / 'playback' / (key + suffix)).unlink(missing_ok=True)
            for suffix in ('.hls', '.hls.tmp'):
                shutil.rmtree(self.root / 'playback' / (key + suffix), ignore_errors=True)
            return True
        except (CosClientError, CosServiceError):
            return False

    def space_available(self, db, additional=0):
        used, pending, unwritten = self.usage(db)
        return (used + pending + additional <= self.quota and pending + additional <= self.staging_limit and
                shutil.disk_usage(self.root).free - unwritten - additional >= self.reserve)
