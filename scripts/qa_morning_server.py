"""Isolated emulator fixture with synthetic weather/model output. Never deploy."""
import hashlib
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
from sakura.storage import Store
from sakura.main import create_app

os.environ['SAKURA_STORAGE'] = 'local'
store = Store(Path(os.environ.get('SAKURA_QA_DATA', ROOT / '.tools/qa-morning-data')), reserve=0)
app = create_app(store)


def forecast(latitude, longitude, timezone, force=False):
    import time
    today = datetime.now(ZoneInfo(timezone)).date()
    # Synthetic observations precede the request; host and emulator clocks may differ slightly.
    return ([{'date': (today + timedelta(days=i)).isoformat(), 'low': 18, 'high': 25,
              'condition': '小雨', 'rain_probability': .6} for i in range(3)], time.time() - 60)


app.state.morning._forecast = forecast
app.state.morning._generate = lambda city, forecasts, recent: {
    row['date']: {'advice': '穿件薄外套，出门记得带把雨伞，舒服最重要呀。',
                  'love': '今天也很爱你哦♡', 'copy_source': 'qa-fixture'} for row in forecasts}


@app.get('/qa/build')
def build():
    return {'backend_sha256': hashlib.sha256(b''.join(p.read_bytes() for p in sorted((ROOT / 'backend/sakura').glob('*.py')))).hexdigest(), 'synthetic_weather': True}


if __name__ == '__main__':
    import uvicorn
    with store.lock(), store.connect() as db:
        db.execute('UPDATE devices SET active=0')
        db.execute('UPDATE settings SET password=NULL,version=version+1')
    invite = Path(os.environ['SAKURA_QA_DATA']) / 'qa-invite' if 'SAKURA_QA_DATA' in os.environ else ROOT / '.tools/qa-morning-invite'
    invite.write_text(store.invite())
    invite.chmod(0o600)
    uvicorn.run(app, host='127.0.0.1', port=int(os.environ.get('SAKURA_QA_PORT', 18445)), ssl_keyfile=str(ROOT / '.tools/qa-tls/key.pem'),
                ssl_certfile=str(ROOT / '.tools/qa-tls/cert.pem'), access_log=False)
