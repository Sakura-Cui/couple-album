"""Run upload recovery on sakura-test against the isolated localhost fixture."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
from sakura.storage import Store
adb = str(ROOT / '.tools/android-sdk/platform-tools/adb')
serial = 'emulator-5560'
def run(*args):
    return subprocess.run([adb, '-s', serial, *args], capture_output=True, text=True, check=True)
assert run('emu', 'avd', 'name').stdout.splitlines()[0] == 'sakura-test'
store = Store(ROOT / '.tools/qa-upload-data', quota=1024**3, reserve=0)
with store.lock(), store.connect() as db:
    db.execute('UPDATE devices SET active=0')
    db.execute('UPDATE settings SET password=NULL,version=version+1')
    db.execute('DELETE FROM unlocks')
source_paths = list((ROOT / 'android/app/src/main/java/com/sakura/romance').glob('*.kt'))
before = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_paths}
for path in ('debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk'):
    run('install', '-r', str(ROOT / 'android/app/build/outputs/apk' / path))
run('shell', 'pm', 'clear', 'com.sakura.romance.debug')
run('shell', 'pm', 'grant', 'com.sakura.romance.debug', 'android.permission.POST_NOTIFICATIONS')
run('shell', 'pm', 'grant', 'com.sakura.romance.debug', 'android.permission.ACCESS_COARSE_LOCATION')
result = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'invite', store.invite(), '-e', 'class',
             'com.sakura.romance.UploadRecoveryFlowTest', 'com.sakura.romance.debug.test/androidx.test.runner.AndroidJUnitRunner')
label = os.environ.get('SAKURA_UPLOAD_QA_LABEL', 'upload-recovery-ui')
(ROOT / 'artifacts' / (label + '.log')).write_text(result.stdout + result.stderr)
print(result.stdout[-6000:])
image = subprocess.run([adb, '-s', serial, 'exec-out', 'run-as', 'com.sakura.romance.debug', 'cat', 'files/qa-upload-multiselect.png'], capture_output=True)
if image.returncode == 0 and image.stdout.startswith(b'\x89PNG'):
    (ROOT / 'artifacts/qa-upload-multiselect.png').write_bytes(image.stdout)
unchanged = all(hashlib.sha256((ROOT / p).read_bytes()).hexdigest() == digest for p, digest in before.items())
(ROOT / 'artifacts' / (label + '-provenance.json')).write_text(json.dumps({'sources_unchanged': unchanged, 'sources': before}, indent=2))
assert unchanged, 'Sources changed during QA'
raise SystemExit(0 if re.search(r'OK \([1-9]\d* tests?\)', result.stdout) else 1)
