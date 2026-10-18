"""Run on the project-owned sakura-test emulator and isolated qa_morning_server only."""
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
from sakura.storage import Store

artifacts = ROOT / 'artifacts'
adb = str(ROOT / '.tools/android-sdk/platform-tools/adb')
serial = 'emulator-5560'

def command(*args, **kwargs):
    return subprocess.run([adb, '-s', serial, *args], check=True, **kwargs)

if command('emu','avd','name',capture_output=True,text=True).stdout.splitlines()[0] != 'sakura-test':
    raise RuntimeError('Refusing to operate on a non-QA device')

def source_hash():
    paths = list((ROOT / 'backend/sakura').glob('*.py')) + list((ROOT / 'android/app/src').rglob('*.kt')) + list((ROOT / 'android/app/src').rglob('*.xml'))
    return hashlib.sha256(b''.join(p.read_bytes() for p in sorted(paths))).hexdigest()

before = source_hash()
backend_hash = hashlib.sha256(b''.join(p.read_bytes() for p in sorted((ROOT / 'backend/sakura').glob('*.py')))).hexdigest()
context = ssl.create_default_context(cafile=str(ROOT / '.tools/qa-tls/cert.pem'))
with urllib.request.urlopen('https://127.0.0.1:18445/qa/build',context=context,timeout=5) as response:
    server = json.load(response)
assert server['backend_sha256'] == backend_hash and server['synthetic_weather']
env = dict(os.environ, ANDROID_HOME=str(ROOT / '.tools/android-sdk'))
with (artifacts / 'morning-ui-build.log').open('w') as log:
    subprocess.run([str(ROOT / '.tools/gradle-8.11.1/bin/gradle'),'--offline','--no-daemon','assembleDebug','assembleDebugAndroidTest'],cwd=ROOT / 'android',env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
assert before == source_hash(), 'Sources changed during build'
store = Store(ROOT / '.tools/qa-morning-data',reserve=0)
with store.lock(),store.connect() as db:
    db.execute('UPDATE devices SET active=0')
    db.execute('UPDATE settings SET password=NULL,version=version+1')
    db.execute('DELETE FROM albums')
    db.execute('DELETE FROM morning_cache')
    db.execute('DELETE FROM morning_love')
apks = [ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk', ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
for apk in apks:
    command('install','-r',str(apk),stdout=subprocess.DEVNULL)
command('shell','pm','clear','com.sakura.romance.debug',stdout=subprocess.DEVNULL)
command('shell','input','keyevent','KEYCODE_WAKEUP',stdout=subprocess.DEVNULL)
command('shell','wm','dismiss-keyguard',stdout=subprocess.DEVNULL)
result = command('shell','am','instrument','-w','-r','-e','invite',store.invite(),'-e','class','com.sakura.romance.MorningAlbumsTest','com.sakura.romance.debug.test/androidx.test.runner.AndroidJUnitRunner',capture_output=True,text=True)
output = result.stdout + result.stderr
if source_hash() != before:
    output += '\nINVALID: source changed during run\n'
(artifacts / 'morning-ui-tests.log').write_text(output)
for name in ('morning-albums','morning-detail','morning-before-pin','morning-pin-failed','morning-organizing','morning-settings','weather-card','weather-card-empty','weather-sunny','weather-cloudy'):
    result = subprocess.run([adb,'-s',serial,'exec-out','run-as','com.sakura.romance.debug','cat',f'files/qa-{name}.png'],capture_output=True)
    if result.stdout.startswith(b'\x89PNG'):
        (artifacts / f'{name}.png').write_bytes(result.stdout)
(artifacts / 'morning-ui-provenance.json').write_text(json.dumps({'source_sha256':before,'backend_sha256':backend_hash,'synthetic_weather':True,'apks':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in apks}},indent=2))
print(output)
sys.exit(0 if re.search(r'OK \([1-9]\d* tests?\)',output) and 'INVALID' not in output else 1)
