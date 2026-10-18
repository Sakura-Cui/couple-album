"""Run only on sakura-test with the isolated qa_morning_server fixture."""
from pathlib import Path
import subprocess
import re
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'backend'))
from sakura.storage import Store
adb=str(ROOT/'.tools/android-sdk/platform-tools/adb');serial='emulator-5560'
def run(*args):return subprocess.run([adb,'-s',serial,*args],capture_output=True,text=True,check=True)
assert run('emu','avd','name').stdout.splitlines()[0]=='sakura-test'
store=Store(ROOT/'.tools/qa-morning-data',reserve=0)
with store.lock(),store.connect() as db:
    db.execute('UPDATE devices SET active=0')
    db.execute('UPDATE settings SET password=NULL,version=version+1')
    db.execute('DELETE FROM unlocks')
    db.execute("DELETE FROM albums WHERE name IN ('只属于我们的秘密','公开的小日常') AND id NOT IN (SELECT album_id FROM media) AND id NOT IN (SELECT album_id FROM uploads)")
for path in ('debug/app-debug.apk','androidTest/debug/app-debug-androidTest.apk'):
    run('install','-r',str(ROOT/'android/app/build/outputs/apk'/path))
run('shell','pm','clear','com.sakura.romance.debug')
for permission in ('android.permission.POST_NOTIFICATIONS','android.permission.ACCESS_COARSE_LOCATION'):
    run('shell','pm','grant','com.sakura.romance.debug',permission)
result=run('shell','am','instrument','-w','-r','-e','invite',store.invite(),'-e','class','com.sakura.romance.AlbumPrivacyTest','com.sakura.romance.debug.test/androidx.test.runner.AndroidJUnitRunner')
(ROOT/'artifacts/album-lock-ui-test.log').write_text(result.stdout+result.stderr)
print(result.stdout[-4500:])
for name in ('home','edit','unlock','settings'):
    img=subprocess.run([adb,'-s',serial,'exec-out','run-as','com.sakura.romance.debug','cat',f'files/qa-album-privacy-{name}.png'],capture_output=True)
    if img.returncode==0 and img.stdout.startswith(b'\x89PNG'):
        (ROOT/f'artifacts/qa-album-privacy-{name}.png').write_bytes(img.stdout)
raise SystemExit(0 if re.search(r'OK \([1-9]\d* tests?\)',result.stdout) else 1)
