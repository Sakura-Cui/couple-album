"""Run only against the dedicated sakura-test emulator and qa_messages_server.py."""
import os
import re
import subprocess
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'backend'))
from sakura.storage import Store
adb=str(ROOT/'.tools/android-sdk/platform-tools/adb');serial='emulator-5560'
def run(*args):return subprocess.run([adb,'-s',serial,*args],capture_output=True,text=True,check=True)
assert run('emu','avd','name').stdout.splitlines()[0]=='sakura-test'
store=Store(ROOT/'.tools/qa-messages-data',reserve=0)
with store.lock(),store.connect() as db:
    db.execute('UPDATE devices SET active=0')
    db.execute('UPDATE settings SET password=NULL,version=version+1')
    db.execute('DELETE FROM unlocks')
for path in ('debug/app-debug.apk','androidTest/debug/app-debug-androidTest.apk'):
    run('install','-r',str(ROOT/'android/app/build/outputs/apk'/path))
run('shell','pm','clear','com.sakura.romance.debug')
for permission in ('android.permission.POST_NOTIFICATIONS','android.permission.ACCESS_COARSE_LOCATION'):
    run('shell','pm','grant','com.sakura.romance.debug',permission)
result=run('shell','am','instrument','-w','-r','-e','invite',store.invite(),'-e','class','com.sakura.romance.InboxTest','com.sakura.romance.debug.test/androidx.test.runner.AndroidJUnitRunner')
(ROOT/'artifacts/push-inbox-test.log').write_text(result.stdout+result.stderr)
print(result.stdout[-4500:])
image=subprocess.run([adb,'-s',serial,'exec-out','run-as','com.sakura.romance.debug','cat','files/qa-inbox-detail.png'],capture_output=True)
if image.returncode==0 and image.stdout.startswith(b'\x89PNG'):
    (ROOT/'artifacts/qa-inbox-detail.png').write_bytes(image.stdout)
raise SystemExit(0 if re.search(r'OK \([1-9]\d* tests?\)',result.stdout) else 1)
