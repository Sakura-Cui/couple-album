"""Run with python -m sakura.admin. No secret is accepted as a command argument."""
import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import time
import tempfile
import re
from pathlib import Path
from contextlib import closing

from .storage import Store
from .objects import CosObjects


def reset_restored_credentials(db):
    """Restores must never reactivate previously revoked devices or invitations."""
    db.execute('UPDATE devices SET active=0')
    db.execute('DELETE FROM unlocks')
    db.execute('DELETE FROM invites')


def file_hash(path):
    with path.open('rb') as file:
        return hashlib.file_digest(file, 'sha256').hexdigest()


def backup(store, destination):
    destination = Path(destination).resolve()
    if destination == store.root or store.root in destination.parents:
        raise ValueError('备份目录不能位于数据目录内')
    if destination.exists():
        raise ValueError('备份目标必须是不存在的新目录')
    with store.lock(), store.connect() as db:
        size = db.execute('SELECT COALESCE(SUM(size),0) FROM media').fetchone()[0]
        destination.parent.mkdir(parents=True, exist_ok=True)
        if shutil.disk_usage(destination.parent).free < size + store.reserve:
            raise ValueError('备份目标空间不足，必须保留配置的安全剩余空间')
        destination.mkdir(mode=0o700)
        try:
            with closing(sqlite3.connect(destination / 'sakura.sqlite')) as target:
                with target:
                    db.backup(target)
                    target.execute("DELETE FROM uploads WHERE status!='complete'")
            for kind in ('media', 'thumbnails'):
                (destination / kind).mkdir(mode=0o700)
            for row in db.execute('SELECT id,thumbnail FROM media'):
                with (destination / 'media' / row['id']).open('wb') as output:
                    for data in store.objects.read('media', row['id']):
                        output.write(data)
                if row['thumbnail']:
                    with (destination / 'thumbnails' / row['id']).open('wb') as output:
                        for data in store.objects.read('thumbnails', row['id']):
                            output.write(data)
            manifest = {str(path.relative_to(destination)): file_hash(path) for path in destination.rglob('*') if path.is_file()}
            (destination / 'manifest.json').write_text(json.dumps({'version': 1, 'files': manifest}, indent=2))
            (destination / 'COMPLETE').write_text('Sakura backup v1\n')
        except BaseException:
            # Keep incomplete material for diagnosis; restore refuses it.
            raise
    return destination


def restore(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if destination.exists():
        raise ValueError('恢复目标必须是不存在的新目录；禁止覆盖在线数据')
    if not (source / 'COMPLETE').is_file():
        raise ValueError('不是已完成的备份')
    manifest = json.loads((source / 'manifest.json').read_text())
    if manifest.get('version') != 1 or 'sakura.sqlite' not in manifest['files']:
        raise ValueError('不支持的备份格式')
    for name, expected in manifest['files'].items():
        if name != 'sakura.sqlite' and not re.fullmatch(r'(media|thumbnails)/[a-f0-9]{32}', name):
            raise ValueError('备份包含无效文件名')
        path = (source / name).resolve()
        if source not in path.parents or not path.is_file() or path.is_symlink() or file_hash(path) != expected:
            raise ValueError(f'备份校验失败: {name}')
    destination.mkdir(parents=True, mode=0o700)
    for name in manifest['files']:
        output = destination / name
        output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copyfile(source / name, output)
    with closing(sqlite3.connect(destination / 'sakura.sqlite')) as db, db:
        if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('数据库完整性校验失败')
        # A backup must not resurrect a revoked device or a used invitation.
        reset_restored_credentials(db)
    return destination


def backup_cos(store, snapshot):
    """Server-side object copies; only SQLite + manifest use local temporary space."""
    if not store.objects.remote:
        raise ValueError('backup-cos 需要 SAKURA_STORAGE=cos')
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', snapshot):
        raise ValueError('快照名仅可包含字母、数字、下划线及短横线')
    kind = f'backups/{snapshot}'
    # Refuse to overwrite completed snapshots; a failed/partial snapshot needs a new name.
    try:
        store.objects.size(kind, 'manifest.json')
    except Exception as error:
        from qcloud_cos.cos_exception import CosServiceError
        if not (isinstance(error, CosServiceError) and error.get_status_code() == 404):
            raise
    else:
        raise ValueError('快照名已存在，请使用新名称')
    with store.lock(), store.connect() as db, tempfile.TemporaryDirectory(dir=store.root, prefix='.snapshot-') as temp:
        root = Path(temp)
        database = root / 'sakura.sqlite'
        with closing(sqlite3.connect(database)) as target:
            with target:
                db.backup(target)
                target.execute("DELETE FROM uploads WHERE status!='complete'")
        files = {'sakura.sqlite': {'sha256': file_hash(database), 'size': database.stat().st_size}}
        for row in db.execute('SELECT id,size,sha256,thumbnail FROM media'):
            for category in ('media', 'thumbnails') if row['thumbnail'] else ('media',):
                store.objects.copy_to(category, row['id'], store.objects, f'{kind}/{category}', row['id'])
                if category == 'media':
                    checksum, size = row['sha256'], row['size']
                else:
                    checksum, size = hashlib.sha256(), 0
                    for chunk in store.objects.read(category, row['id']):
                        checksum.update(chunk); size += len(chunk)
                    checksum = checksum.hexdigest()
                files[f'{category}/{row["id"]}'] = {'sha256': checksum, 'size': size}
        store.objects.put(kind, 'sakura.sqlite', database, 'application/octet-stream')
        manifest = root / 'manifest.json'
        manifest.write_text(json.dumps({'version': 2, 'files': files}, indent=2))
        store.objects.put(kind, 'manifest.json', manifest, 'application/json')
        complete = root / 'COMPLETE'
        complete.write_text(file_hash(manifest))
        store.objects.put(kind, 'COMPLETE', complete, 'text/plain')
    return f'{store.objects.prefix}/{kind}'


def restore_cos(store, snapshot, destination, new_prefix):
    if not store.objects.remote:
        raise ValueError('restore-cos 需要 COS 模式')
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', snapshot):
        raise ValueError('无效快照名')
    destination = Path(destination).resolve()
    # Generate a separate namespace, never overwrite the active media namespace.
    if not re.fullmatch(r'sakura-restored-[a-zA-Z0-9_-]+', new_prefix) or new_prefix == store.objects.prefix:
        raise ValueError('新前缀必须使用 sakura-restored- 开头，且不可等于在线前缀')
    target = CosObjects(store.objects.client, store.objects.bucket, new_prefix)
    kind = f'backups/{snapshot}'
    manifest_bytes = b''.join(store.objects.read(kind, 'manifest.json'))
    expected = b''.join(store.objects.read(kind, 'COMPLETE')).decode().strip()
    if hashlib.sha256(manifest_bytes).hexdigest() != expected:
        raise ValueError('备份清单校验失败')
    manifest = json.loads(manifest_bytes)
    if manifest.get('version') != 2 or 'sakura.sqlite' not in manifest['files']:
        raise ValueError('备份格式不受支持')
    for name in manifest['files']:
        if name != 'sakura.sqlite' and not re.fullmatch(r'(media|thumbnails)/[a-f0-9]{32}', name):
            raise ValueError('备份包含无效文件名')
    identity = {'bucket': store.objects.bucket, 'source': store.objects.prefix,
                'snapshot': snapshot, 'target': new_prefix, 'manifest': expected}
    journal = destination / '.restore.json'
    if destination.exists():
        if (destination / '.restore-complete').exists() or not journal.is_file() or json.loads(journal.read_text()) != identity:
            raise ValueError('目标不属于本次未完成的恢复，拒绝覆盖')
    else:
        listing = target.client.list_objects(Bucket=target.bucket, Prefix=new_prefix + '/', MaxKeys=1)
        if listing.get('Contents'):
            raise ValueError('恢复对象前缀非空，拒绝覆盖')
        destination.parent.mkdir(parents=True, exist_ok=True)
        # Publish the ownership journal with the new directory, before any object copies.
        with tempfile.TemporaryDirectory(dir=destination.parent, prefix='.restore-') as temp:
            staging = Path(temp) / 'data'
            staging.mkdir(mode=0o700)
            (staging / '.restore.json').write_text(json.dumps(identity))
            staging.rename(destination)
    # A retry revalidates and overwrites only objects owned by this restore job.
    # The active prefix is never switched, and Store rejects incomplete directories.
    for name, metadata in manifest['files'].items():
        if name != 'sakura.sqlite' and not re.fullmatch(r'(media|thumbnails)/[a-f0-9]{32}', name):
            raise ValueError('备份包含无效文件名')
        checksum, size = hashlib.sha256(), 0
        output = (destination / name).open('wb') if name == 'sakura.sqlite' else None
        try:
            for chunk in store.objects.read(kind, name):
                checksum.update(chunk); size += len(chunk)
                if output:
                    output.write(chunk)
        finally:
            if output:
                output.close()
        if checksum.hexdigest() != metadata['sha256'] or size != metadata['size']:
            raise ValueError(f'备份对象校验失败: {name}')
        if name != 'sakura.sqlite':
            category, key = name.split('/')
            store.objects.copy_to(kind, name, target, category, key)
    with closing(sqlite3.connect(destination / 'sakura.sqlite')) as db, db:
        if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('恢复的数据库校验失败')
        reset_restored_credentials(db)
    (destination / '.restore-complete').write_text(expected)
    return f'恢复完成。数据目录: {destination}; SAKURA_COS_PREFIX={new_prefix}。原服务未改动，需重新绑定设备。'


def main():
    parser = argparse.ArgumentParser(description='Sakura Romance 管理工具')
    sub = parser.add_subparsers(dest='command', required=True)
    invite = sub.add_parser('invite', help='生成有效期 24 小时、仅可使用一次的邀请凭证')
    invite.add_argument('--share-with', help='与指定有效设备共享悄悄话收件箱，新设备仍使用独立登录凭证')
    sub.add_parser('devices', help='列出设备，不包含授权 Token')
    sub.add_parser('reset-password', help='关闭空间密码并撤销所有解锁会话')
    revoke = sub.add_parser('revoke')
    revoke.add_argument('device_id')
    back = sub.add_parser('backup')
    back.add_argument('destination')
    recover = sub.add_parser('restore')
    recover.add_argument('source')
    recover.add_argument('destination')
    cos_back = sub.add_parser('backup-cos', help='在当前 COS 桶中创建独立快照，产生对象复制与存储费用')
    cos_back.add_argument('snapshot')
    cos_recover = sub.add_parser('restore-cos', help='校验快照并恢复到新目录、新 COS 前缀，不覆盖在线数据')
    cos_recover.add_argument('snapshot')
    cos_recover.add_argument('destination')
    cos_recover.add_argument('new_prefix')
    sub.add_parser('prune', help='清理过期上传任务，以及已删除媒体的遗留文件')
    args = parser.parse_args()
    os.umask(0o077)
    if args.command == 'restore':
        print(restore(args.source, args.destination))
        return
    store = Store()
    if args.command == 'invite':
        print(store.invite(share_with=args.share_with))
    elif args.command == 'backup':
        print(backup(store, args.destination))
    elif args.command == 'backup-cos':
        print(backup_cos(store, args.snapshot))
    elif args.command == 'restore-cos':
        print(restore_cos(store, args.snapshot, args.destination, args.new_prefix))
    else:
        with store.lock(), store.connect() as db:
            if args.command == 'devices':
                for row in db.execute('SELECT id,name,active,created,inbox_owner FROM devices'):
                    print(json.dumps(dict(row), ensure_ascii=False))
            elif args.command == 'revoke':
                db.execute('UPDATE devices SET active=0 WHERE id=?', (args.device_id,))
                db.execute('DELETE FROM unlocks WHERE device_id=?', (args.device_id,))
            elif args.command == 'reset-password':
                db.execute('UPDATE settings SET password=NULL,version=version+1 WHERE id=1')
                db.execute('DELETE FROM unlocks')
            elif args.command == 'prune':
                now = time.time()
                stale = db.execute(
                    "SELECT id FROM uploads WHERE (status='pending' AND updated<?) OR (status='saving' AND updated<?)",
                    (now - 7 * 86400, now - 86400)
                ).fetchall()
                for row in stale:
                    db.execute("UPDATE uploads SET status='cancelled',updated=? WHERE id=?", (now, row['id']))
                    if store.objects.remote:
                        db.execute('INSERT OR IGNORE INTO garbage VALUES(?)', (row['id'],))
                db.commit()
                failed = 0
                for row in db.execute('SELECT id FROM garbage').fetchall():
                    if store.delete_objects(row['id']):
                        db.execute('DELETE FROM garbage WHERE id=?', (row['id'],))
                        db.commit()
                    else:
                        failed += 1
                pending = {r[0] for r in db.execute("SELECT id FROM uploads WHERE status IN ('pending','saving')")}
                media = {r[0] for r in db.execute('SELECT id FROM media')}
                for kind in ('uploads', 'media', 'thumbnails'):
                    valid = pending if kind == 'uploads' else media | pending
                    for path in (store.root / kind).iterdir():
                        if path.is_file() and path.name not in valid:
                            path.unlink()
                print(f'已清理 {len(stale)} 个过期上传任务及无引用文件；{failed} 个对象组待下次重试')


if __name__ == '__main__':
    main()
