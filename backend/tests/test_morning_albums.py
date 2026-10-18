import json
import sqlite3
import time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx
import pytest
from fastapi.testclient import TestClient
from sakura.main import create_app
from sakura.storage import Store


@pytest.fixture
def env(tmp_path):
    store = Store(tmp_path / 'data', reserve=0)
    app = create_app(store)
    client = TestClient(app)
    headers = []
    for name in ('a', 'b'):
        token = client.post('/api/bind', json={'invite': store.invite(), 'name': name}).json()['token']
        headers.append({'Authorization': 'Bearer ' + token})
    return store, app, client, headers


def create(client, headers, name):
    r = client.post('/api/albums', headers=headers, json={'name': name})
    assert r.status_code == 201
    return r.json()['id']


def move(client, headers, id, pinned, before=None, revision=None):
    if revision is None:
        revision = client.get('/api/albums/order', headers=headers).json()['revision']
    return client.put(f'/api/albums/{id}/position', headers=headers, json={'pinned': pinned, 'before_id': before, 'revision': revision})


def test_migration_preserves_order_and_is_repeatable(tmp_path):
    root = tmp_path / 'legacy'; root.mkdir()
    with sqlite3.connect(root / 'sakura.sqlite') as db:
        db.execute('CREATE TABLE albums (id TEXT PRIMARY KEY,name TEXT NOT NULL,cover_id TEXT,created REAL NOT NULL)')
        db.executemany('INSERT INTO albums VALUES(?,?,NULL,?)', [('a','a',1),('b','b',2),('c','c',2)])
    for _ in range(2):
        store = Store(root, reserve=0)
        with store.connect() as db:
            assert [r['id'] for r in db.execute('SELECT * FROM albums ORDER BY pinned DESC,sort_order,id')] == ['c','b','a']


def test_pin_unpin_drag_and_second_device(env):
    _, _, c, (h, other) = env
    a, b, d = [create(c,h,x) for x in ('a','b','d')]
    assert move(c,h,a,True).status_code == 200
    assert move(c,h,b,True).status_code == 200
    rows = c.get('/api/albums',headers=other).json()
    assert [r['id'] for r in rows] == [a,b,d]
    assert move(c,h,b,True,a).status_code == 200
    assert move(c,h,b,False,d).status_code == 200
    assert [r['id'] for r in c.get('/api/albums',headers=other).json()] == [a,b,d]
    new = create(c,h,'new')
    assert [r['id'] for r in c.get('/api/albums',headers=other).json()] == [a,new,b,d]


def test_revision_prevents_stale_drag_and_mixed_pagination(env):
    _, _, c, (h,other) = env
    ids = [create(c,h,str(i)) for i in range(37)]
    revision = c.get('/api/albums/order',headers=h).json()['revision']
    first = c.get(f'/api/albums?revision={revision}&limit=30',headers=h).json()
    assert len(first) == 30
    assert move(c,other,ids[0],True).status_code == 200
    assert c.get(f'/api/albums?revision={revision}&offset=30',headers=h).status_code == 409
    assert move(c,h,ids[1],True,revision=revision).status_code == 409
    revision = c.get('/api/albums/order',headers=h).json()['revision']
    rows = c.get(f'/api/albums?revision={revision}&limit=30',headers=h).json() + c.get(f'/api/albums?revision={revision}&offset=30',headers=h).json()
    assert len({r['id'] for r in rows}) == 37
    assert rows[0]['id'] == ids[0]
    assert move(c,h,ids[1],False,ids[0]).status_code == 409
    assert move(c,h,ids[1],False,ids[1]).status_code == 409


def wire_forecast(today):
    return {'daily': {'time': [(today + timedelta(days=i)).isoformat() for i in range(3)],
                     'weather_code': [61]*3, 'temperature_2m_min': [18.2]*3,
                     'temperature_2m_max': [25.1]*3, 'precipitation_probability_max': [60]*3}}


@pytest.fixture
def weather(env, monkeypatch):
    store, app, c, headers = env
    calls = []
    today = datetime.now(ZoneInfo('Asia/Shanghai')).date()
    def provider(url, **kwargs):
        assert url == 'https://api.open-meteo.com/v1/forecast'
        assert 'headers' not in kwargs and 'apikey' not in kwargs['params']
        assert kwargs['params']['timezone'] == 'Asia/Shanghai'
        calls.append(url)
        return httpx.Response(200, json=wire_forecast(today), request=httpx.Request('GET', url))
    monkeypatch.setattr(httpx, 'get', provider)
    monkeypatch.delenv('DEEPSEEK_API_KEY', raising=False)
    return app.state.morning, calls


REQUEST = {'latitude': 32.06, 'longitude': 118.79, 'timezone': 'Asia/Shanghai', 'city': '南京'}


def test_morning_locked_access_and_revocation(env, weather):
    store, _, c, (h, _) = env
    assert c.put('/api/password',headers=h,json={'new_password':'secret123'}).status_code == 200
    assert c.get('/api/albums',headers=h).status_code == 200
    result = c.post('/api/morning/briefs',headers=h,json=REQUEST)
    assert result.status_code == 200
    assert len(result.json()['items']) == 3
    assert c.post('/api/morning/briefs',json=REQUEST).status_code == 401
    with store.connect() as db:
        db.execute("UPDATE devices SET active=0 WHERE name='a'")
    assert c.post('/api/morning/briefs',headers=h,json=REQUEST).status_code == 401


def test_fallback_facts_and_cache_do_not_forge_freshness(env, weather):
    _, _, c, (h, _) = env
    service, calls = weather
    first = c.post('/api/morning/briefs',headers=h,json=REQUEST).json()['items']
    second = c.post('/api/morning/briefs',headers=h,json=REQUEST).json()['items']
    assert first == second
    assert len(calls) == 1
    assert len({r['love'] for r in first}) == 3
    for row in first:
        assert row['text'].startswith('南京 18～25℃，小雨。老婆宝宝，')
        assert '伞' in row['advice']
        assert row['advice'] in row['text'] and row['love'] in row['text']
        assert row['copy_source'] == 'fallback'
        assert row['location_key'] == '32.06,118.79'


def test_deepseek_only_receives_city_weather_and_validates_output(env, weather, monkeypatch):
    _, _, c, (h, _) = env
    service, _ = weather
    monkeypatch.setenv('DEEPSEEK_API_KEY', 'test-only-key')
    captured = []
    def generate(url, **kwargs):
        captured.append(kwargs['json'])
        payload = json.loads(kwargs['json']['messages'][1]['content'])
        days = [{'date': r['date'], 'advice': '老婆宝宝，穿轻薄衣物，怕冷时加件薄外套；今天降雨概率60%，出门记得带伞。', 'love': ''} for i,r in enumerate(payload['forecasts'])]
        # Only the first item has a valid love note; missing notes must fall back.
        days[0]['love'] = '今天也要给你一个暖暖的抱抱♡'
        return httpx.Response(200, json={'choices':[{'message':{'content':json.dumps({'days':days},ensure_ascii=False)}}]},request=httpx.Request('POST',url))
    monkeypatch.setattr(httpx, 'post', generate)
    rows = c.post('/api/morning/briefs',headers=h,json=REQUEST).json()['items']
    assert rows[0]['copy_source'] == 'deepseek'
    assert '60%' in rows[0]['advice']
    assert rows[0]['advice'].startswith('老婆宝宝，')
    assert rows[1]['copy_source'] == 'fallback'
    assert captured[0]['thinking'] == {'type':'disabled'}
    content = captured[0]['messages'][1]['content']
    assert 'latitude' not in content and '118.79' not in content and h['Authorization'] not in content


@pytest.mark.parametrize('broken', ['', 'not json', '{}', '{"days":[{"date":"x","advice":null,"love":42}]}', 'null', '[]', '{"days":null}', '{"days":[null]}'])
def test_model_bad_output_falls_back(env, weather, monkeypatch, broken):
    _, _, c, (h, _) = env
    monkeypatch.setenv('DEEPSEEK_API_KEY','test-key')
    monkeypatch.setattr(httpx,'post',lambda url,**kw:httpx.Response(200,json={'choices':[{'message':{'content':broken}}]},request=httpx.Request('POST',url)))
    result = c.post('/api/morning/briefs',headers=h,json=REQUEST)
    assert result.status_code == 200
    assert all(r['copy_source'] == 'fallback' for r in result.json()['items'])


def test_invalid_coordinates_timezone_and_weather_outage(env, monkeypatch):
    _, _, c, (h, _) = env
    assert c.post('/api/morning/briefs',headers=h,json={**REQUEST,'latitude':95}).status_code == 422
    assert c.post('/api/morning/briefs',headers=h,json={**REQUEST,'timezone':'fake/zone'}).status_code == 422
    monkeypatch.setattr(httpx, 'get', lambda *a, **kw: httpx.Response(503, request=httpx.Request('GET', a[0])))
    assert c.post('/api/morning/briefs',headers=h,json=REQUEST).status_code == 503


def test_expired_weather_does_not_get_returned_as_current(env, weather, monkeypatch):
    store, _, c, (h, _) = env
    service, _ = weather
    assert c.post('/api/morning/briefs',headers=h,json=REQUEST).status_code == 200
    with store.connect() as db:
        db.execute("UPDATE morning_cache SET fetched=? WHERE key LIKE 'open-meteo:%'",(time.time()-86401,))
    def down(*args, **kwargs):
        raise httpx.ReadTimeout('天气暂时无法更新，请稍后重试')
    monkeypatch.setattr(httpx,'get',down)
    assert c.post('/api/morning/briefs',headers=h,json=REQUEST).status_code == 503


def test_manual_trigger_refreshes_weather_without_consuming_history(env, weather):
    store, _, c, (h, _) = env
    service, calls = weather
    assert c.post('/api/morning/briefs',headers=h,json=REQUEST).status_code == 200
    with store.connect() as db:
        history = [tuple(r) for r in db.execute('SELECT * FROM morning_love')]
    before = len(calls)
    preview = c.post('/api/morning/test',headers=h,json=REQUEST)
    assert preview.status_code == 200 and preview.json()['test'] is True
    assert len(calls) == before + 1
    assert c.post('/api/morning/test',headers=h,json=REQUEST).json() == preview.json()
    assert len(calls) == before + 1  # One-minute debounce prevents accidental repeated provider charges.
    with store.connect() as db:
        assert [tuple(r) for r in db.execute('SELECT * FROM morning_love')] == history
    assert c.post('/api/morning/test',json=REQUEST).status_code == 401
    with store.connect() as db:
        db.execute("UPDATE devices SET active=0 WHERE name='a'")
    assert c.post('/api/morning/test',headers=h,json=REQUEST).status_code == 401


def test_failed_order_migration_rolls_back_columns_and_keeps_legacy_order(tmp_path):
    root = tmp_path / 'interrupted'; root.mkdir()
    with sqlite3.connect(root / 'sakura.sqlite') as db:
        db.execute('CREATE TABLE albums (id TEXT PRIMARY KEY,name TEXT NOT NULL,cover_id TEXT,created REAL NOT NULL)')
        db.executemany('INSERT INTO albums VALUES(?,?,NULL,?)', [('a','a',1),('b','b',2)])
        db.execute("CREATE TRIGGER fail_migration BEFORE UPDATE ON albums BEGIN SELECT RAISE(ABORT,'simulated migration failure'); END")
    with pytest.raises(sqlite3.IntegrityError):
        Store(root,reserve=0)
    with sqlite3.connect(root / 'sakura.sqlite') as db:
        assert 'sort_order' not in {r[1] for r in db.execute('PRAGMA table_info(albums)')}
        assert db.execute('SELECT id FROM albums ORDER BY created DESC').fetchall() == [('b',),('a',)]
        db.execute('DROP TRIGGER fail_migration')
    store = Store(root,reserve=0)
    with store.connect() as db:
        assert [r['id'] for r in db.execute('SELECT id FROM albums ORDER BY sort_order')] == ['b','a']


@pytest.mark.parametrize('broken', ['missing_date', 'unknown_code', 'null_temperature', 'bad_probability', 'inverted_range'])
def test_open_meteo_invalid_forecast_never_becomes_weather(env, monkeypatch, broken):
    _, _, c, (h, _) = env
    today = datetime.now(ZoneInfo('Asia/Shanghai')).date()
    payload = wire_forecast(today)
    daily = payload['daily']
    if broken == 'missing_date': daily['time'][0] = (today - timedelta(days=1)).isoformat()
    if broken == 'unknown_code': daily['weather_code'][0] = 999
    if broken == 'null_temperature': daily['temperature_2m_min'][0] = None
    if broken == 'bad_probability': daily['precipitation_probability_max'][0] = 101
    if broken == 'inverted_range': daily['temperature_2m_min'][0] = 40
    monkeypatch.setattr(httpx, 'get', lambda url, **kw: httpx.Response(200, json=payload, request=httpx.Request('GET', url)))
    assert c.post('/api/morning/briefs', headers=h, json=REQUEST).status_code == 503


def test_default_nanjing_location_and_different_city_cache(env, weather):
    _, _, c, (h, _) = env
    nanjing = {**REQUEST, 'latitude': 32.06167, 'longitude': 118.77778, 'city': '南京市'}
    first = c.post('/api/morning/briefs', headers=h, json=nanjing).json()['items']
    assert first[0]['location_key'] == '32.06,118.78'
    assert first[0]['source'] == 'Open-Meteo' and first[0]['weather_available'] is True
    assert '南京市' in first[0]['text']
    shanghai = {**REQUEST, 'latitude': 31.23, 'longitude': 121.47, 'city': '上海市'}
    second = c.post('/api/morning/test', headers=h, json=shanghai).json()['items']
    assert second[0]['location_key'] == '31.23,121.47'
    assert '上海市' in second[0]['text'] and '南京' not in second[0]['text']


@pytest.mark.parametrize('low,high,condition,rain,required,excluded', [
    (22, 27, '阴', .15, ('老婆宝宝，', '轻薄', '降雨概率15%', '可能会有雨', '折叠伞'), ('微凉', '针织')),
    (18, 25, '小雨', .15, ('老婆宝宝，', '薄外套', '出门带伞'), ('降雨概率低',)),
    (22, 27, '阴', .8, ('老婆宝宝，', '出门带伞'), ('降雨概率低',)),
    (22, 27, '多云', .4, ('老婆宝宝，', '折叠伞'), ('一定下雨',)),
    (10, 26, '晴', .1, ('老婆宝宝，', '温差', '添减'), ('全天寒冷',)),
    (18, 25, '小雨', 0, ('老婆宝宝，', '出门带伞'), ('暂无降雨预报',)),
    (2, 8, '小雪', 0, ('老婆宝宝，', '厚外套', '防滑', '伞'), ('暂无降雨预报',)),
    (2, 8, '小雪', .8, ('老婆宝宝，', '厚外套', '防滑', '伞'), ()),
])
def test_weather_specific_clothing(low, high, condition, rain, required, excluded):
    from sakura.morning import clothing
    advice = clothing({'low': low, 'high': high, 'condition': condition, 'rain_probability': rain})
    assert f'降雨概率{rain:.0%}' in advice
    for phrase in required:
        assert phrase in advice
    for phrase in excluded:
        assert phrase not in advice


def test_new_copy_ignores_legacy_notes_and_keeps_weather_timestamp(env, weather):
    store, _, c, (h, _) = env
    service, calls = weather
    original = c.post('/api/morning/briefs', headers=h, json=REQUEST).json()['items']
    with store.connect() as db:
        # Seed the pre-change cache namespace with visibly stale copy.
        for key, payload, fetched in db.execute("SELECT key,payload,fetched FROM morning_cache WHERE key LIKE 'open-meteo-note-v3:%'").fetchall():
            note = json.loads(payload)
            note['advice'] = '旧版模板'
            db.execute('INSERT OR REPLACE INTO morning_cache VALUES(?,?,?)',
                       (key.replace('open-meteo-note-v3:', 'open-meteo-note-v2:'), json.dumps(note), fetched))
        db.execute("DELETE FROM morning_cache WHERE key LIKE 'open-meteo-note-v3:%'")
    updated = c.post('/api/morning/briefs', headers=h, json=REQUEST).json()['items']
    assert all(row['advice'].startswith('老婆宝宝，') for row in updated)
    assert all(row['love'] for row in updated)
    assert [row['fetched_at'] for row in original] == [row['fetched_at'] for row in updated]
    assert len(calls) == 1


@pytest.mark.parametrize('advice', [
    '可以穿轻薄衣物，怕冷时加件薄外套，今天降雨概率百分之十五，带把伞备用。',
    '老婆宝宝，穿轻薄透气的衣服，怕冷时加件薄外套，今天也要开开心心的。',
    '老婆宝宝，穿轻薄透气的衣服，今天降雨概率85%，可能会有雨，带把折叠伞备用。',
    '老婆宝宝，穿轻薄透气的衣服，今天降雨概率60%，基本不会下雨，带把折叠伞备用。',
])
def test_model_missing_requirements_or_wrong_probability_uses_complete_fallback(env, weather, monkeypatch, advice):
    _, _, c, (h, _) = env
    monkeypatch.setenv('DEEPSEEK_API_KEY', 'test-only-key')
    def generate(url, **kwargs):
        forecasts = json.loads(kwargs['json']['messages'][1]['content'])['forecasts']
        days = [{'date': f['date'], 'advice': advice, 'love': '想你了，好想抱抱你♡'} for f in forecasts]
        return httpx.Response(200, json={'choices': [{'message': {'content': json.dumps({'days': days})}}]}, request=httpx.Request('POST', url))
    monkeypatch.setattr(httpx, 'post', generate)
    rows = c.post('/api/morning/briefs', headers=h, json=REQUEST).json()['items']
    assert all(row['copy_source'] == 'fallback' for row in rows)
    assert all(row['advice'].startswith('老婆宝宝，') and '60%' in row['advice'] and '伞' in row['advice'] and row['love'] for row in rows)


@pytest.mark.parametrize('reminder,accepted', [('基本不会下雨', False), ('带把折叠伞备用', False), ('可能会有雨', True)])
def test_low_probability_model_copy_keeps_rain_possibility(env, weather, monkeypatch, reminder, accepted):
    service, _ = weather
    monkeypatch.setenv('DEEPSEEK_API_KEY', 'test-only-key')
    day = datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat()
    forecast = {'date': day, 'low': 22, 'high': 27, 'condition': '阴', 'rain_probability': .15}
    advice = f'老婆宝宝，穿轻薄衣物，怕冷时再加薄外套。降雨概率15%，{reminder}，带把伞备用。'
    def generate(url, **kwargs):
        payload = {'days': [{'date': day, 'advice': advice, 'love': '想你了，好想抱抱你♡'}]}
        return httpx.Response(200, json={'choices': [{'message': {'content': json.dumps(payload)}}]}, request=httpx.Request('POST', url))
    monkeypatch.setattr(httpx, 'post', generate)
    assert (day in service._generate('南京市', [forecast], [])) is accepted


@pytest.mark.parametrize('probability', [0, .004])
def test_zero_displayed_rain_without_rain_or_snow_never_reminds_umbrella(probability):
    from sakura.morning import clothing
    advice = clothing({'low': 12, 'high': 26, 'condition': '晴', 'rain_probability': probability})
    assert '降雨概率0%' in advice
    assert '伞' not in advice
    assert '可能会有雨' not in advice


@pytest.mark.parametrize('umbrella,accepted', [(False, True), (True, False)])
def test_zero_rain_model_copy_matches_dry_forecast(env, weather, monkeypatch, umbrella, accepted):
    service, _ = weather
    monkeypatch.setenv('DEEPSEEK_API_KEY', 'test-only-key')
    day = datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat()
    forecast = {'date': day, 'low': 12, 'high': 26, 'condition': '晴', 'rain_probability': 0}
    ending = '可能会有雨，带把折叠伞备用。' if umbrella else '暂无降雨预报。'
    advice = '老婆宝宝，今天温差较大，穿方便添减的衣服，低温时加件保暖外套。降雨概率0%，' + ending
    payload = {'days': [{'date': day, 'advice': advice, 'love': '今天也想牵着你的手慢慢走♡'}]}
    monkeypatch.setattr(httpx, 'post', lambda url, **kw: httpx.Response(200,
        json={'choices': [{'message': {'content': json.dumps(payload)}}]}, request=httpx.Request('POST', url)))
    assert (day in service._generate('南京', [forecast], [])) is accepted


def test_fallback_changes_with_day_even_when_preview_has_no_history(env, weather, monkeypatch):
    import sakura.morning as morning
    store, _, _, _ = env
    service, _ = weather
    today = datetime.now(ZoneInfo('Asia/Shanghai')).date()
    class Clock(datetime):
        day = today
        @classmethod
        def now(cls, tz=None):
            return datetime.combine(cls.day, datetime.min.time(), tzinfo=tz)
    monkeypatch.setattr(morning, 'datetime', Clock)
    monkeypatch.setattr(service, '_forecast', lambda *args, **kwargs: ([{
        'date': Clock.day.isoformat(), 'low': 12, 'high': 26, 'condition': '晴', 'rain_probability': 0}], time.time()))
    with store.connect() as db:
        device = db.execute("SELECT id FROM devices WHERE name='a'").fetchone()[0]
    loves = []
    for i in range(3):
        Clock.day = today + timedelta(days=i)
        loves.append(service.briefs(device, morning.MorningInput(**REQUEST), force=True)['items'][0]['love'])
    assert len(set(loves)) == 3
    with store.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM morning_love').fetchone()[0] == 0
