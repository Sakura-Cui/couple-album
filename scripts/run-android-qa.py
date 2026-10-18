"""Build current debug and test sources, then run the emulator acceptance test.

Requires the dedicated emulator and scripts/qa_server.py. Resets QA bindings only.
"""
from pathlib import Path
import hashlib
import json
import os
import ssl
import urllib.request
import subprocess
import sys
import re
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
from sakura.storage import Store

artifacts = ROOT / 'artifacts'
artifacts.mkdir(exist_ok=True)
report = artifacts / 'android-ui-test.txt'
report.write_text('NOT RUN: current source build pending\n')

def source_hash():
    digest = hashlib.sha256()
    for folder in ('android', 'backend', 'scripts'):
        for path in sorted((ROOT / folder).rglob('*')):
            if path.is_file() and path.suffix in {'.kt', '.kts', '.xml', '.py', '.sh', '.properties'} and not {'build', '.gradle', '.idea', '.kotlin', '__pycache__'}.intersection(path.parts) and path.name != 'local.properties':
                digest.update(str(path.relative_to(ROOT)).encode())
                digest.update(path.read_bytes())
    return digest.hexdigest()

before = source_hash()
backend_hash = hashlib.sha256(b''.join(p.read_bytes() for p in sorted((ROOT / 'backend/sakura').glob('*.py')))).hexdigest()
context = ssl.create_default_context(cafile=str(ROOT / '.tools/qa-tls/cert.pem'))
with urllib.request.urlopen('https://127.0.0.1:18444/qa/build', context=context, timeout=5) as response:
    backend = json.load(response)
with urllib.request.urlopen(urllib.request.Request('https://127.0.0.1:18444/qa/delays', data=b'{}', method='POST'), context=context, timeout=5) as response:
    response.read()
if backend.get('backend_sha256') != backend_hash or backend.get('storage') != 'local':
    raise RuntimeError('QA 后端版本不一致或未使用本地磁盘，请重启 scripts/qa_server.py')
gradle = ROOT / '.tools/gradle-8.11.1/bin/gradle'
if not gradle.exists():
    gradle = ROOT / 'android/gradlew'
env = dict(os.environ)
env.setdefault('ANDROID_HOME', str(ROOT / '.tools/android-sdk'))
with (artifacts / 'android-qa-build.txt').open('w') as log:
    subprocess.run([str(gradle), '--offline', '--no-daemon', 'assembleDebug', 'assembleDebugAndroidTest'],
                   cwd=ROOT / 'android', env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
if source_hash() != before:
    raise RuntimeError('构建期间源码发生变化，拒绝安装，请重新运行')
apks = [ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk',
        ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
provenance = {'source_sha256': before, 'backend_sha256': backend_hash, 'started_at': datetime.now(timezone.utc).isoformat(),
              'apks': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in apks},
              'fixtures': {p.name: {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in (ROOT / '.tools/qa-fixtures').glob('qa-*')}}
(artifacts / 'android-qa-provenance.json').write_text(json.dumps(provenance, indent=2))

adb = str(ROOT / '.tools/android-sdk/platform-tools/adb')
serial = os.environ.get('SAKURA_QA_SERIAL', 'emulator-5560')
avd = subprocess.run([adb, '-s', serial, 'emu', 'avd', 'name'], capture_output=True, text=True, timeout=10, check=True).stdout
if avd.splitlines()[0] != 'sakura-test':
    raise RuntimeError('拒绝操作非 sakura-test 验收模拟器')
os.environ['SAKURA_STORAGE'] = 'local'
store = Store(ROOT / '.tools/qa-disk-data', reserve=0)
with store.lock(), store.connect() as db:
    db.execute('UPDATE devices SET active=0')
    db.execute('UPDATE settings SET password=NULL,version=version+1')
    db.execute('DELETE FROM unlocks')

def command(*args):
    return subprocess.run([adb, '-s', serial, *args], check=True)

for fixture in ('qa-photo.png', 'qa-video.mp4'):
    command('push', str(ROOT / '.tools/qa-fixtures' / fixture), '/sdcard/Download/' + fixture)
    command('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/' + fixture)
command('install', '-r', str(apks[0]))
command('shell', 'pm', 'clear', 'com.sakura.romance.debug')
command('install', '-r', str(apks[1]))
result = subprocess.run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'invite', store.invite(), '-e', 'class', os.environ.get('SAKURA_QA_TEST_CLASS', 'com.sakura.romance.AppFlowTest'),
    'com.sakura.romance.debug.test/androidx.test.runner.AndroidJUnitRunner'], text=True, capture_output=True)
output = result.stdout + result.stderr
if not output.strip():
    output = f'FAILED: instrumentation returned no output (exit {result.returncode})\n'
if source_hash() != before:
    output += '\nINVALID: source changed during acceptance run\n'
report.write_text(output)
print(output)
for name in ('splash', 'connect', 'home', 'album', 'settings', 'photo', 'video', 'media', 'selection', 'queue-thumbnails', 'albums-loading', 'media-loading', 'photo-loading'):
    capture = subprocess.run([adb, '-s', serial, 'exec-out', 'run-as', 'com.sakura.romance.debug', 'cat', f'files/qa-{name}.png'], capture_output=True)
    if capture.returncode == 0 and capture.stdout.startswith(b'\x89PNG'):
        (ROOT / f'artifacts/qa-{name}.png').write_bytes(capture.stdout)
sys.exit(0 if re.search(r'OK \([1-9]\d* tests?\)', output) is not None and 'INVALID:' not in output else 1)
