"""Run a disposable production API smoke test from inside the API container."""

import hashlib
import io
import json
import os
import time

import requests
from PIL import Image

base_url = os.environ.get("SAKURA_SMOKE_URL", "http://127.0.0.1:8000")
invite = os.environ.get("SAKURA_SMOKE_INVITE")
store = None
if invite is None:
    from sakura.storage import Store

    store = Store()
    invite = store.invite()
device_id = album_id = media_id = upload_id = None
headers = {}


def checked(response, expected=None):
    if expected is not None and response.status_code != expected:
        raise RuntimeError(f"{response.request.method} {response.request.path_url}: {response.status_code} {response.text}")
    response.raise_for_status()
    return response


try:
    bound = checked(requests.post(f"{base_url}/api/bind", json={"invite": invite, "name": "上线验收"}, timeout=10)).json()
    device_id = bound["device_id"]
    headers = {"Authorization": f"Bearer {bound['token']}"}

    session = checked(requests.get(f"{base_url}/api/session", headers=headers, timeout=10)).json()
    storage = checked(requests.get(f"{base_url}/api/storage", headers=headers, timeout=10)).json()
    if session["password_enabled"] or storage["storage"] != "local":
        raise RuntimeError("production session or storage mode is unexpected")

    album = checked(
        requests.post(f"{base_url}/api/albums", headers=headers, json={"name": "上线验收"}, timeout=10),
        201,
    ).json()
    album_id = album["id"]

    image = io.BytesIO()
    Image.new("RGB", (32, 24), "#FFB7C5").save(image, "PNG")
    content = image.getvalue()
    digest = hashlib.sha256(content).hexdigest()
    started = checked(
        requests.post(
            f"{base_url}/api/uploads",
            headers=headers,
            json={
                "request_id": f"production-smoke-{time.time_ns()}",
                "album_id": album_id,
                "name": "smoke.png",
                "mime": "image/png",
                "size": len(content),
                "sha256": digest,
            },
            timeout=10,
        ),
        201,
    ).json()
    upload_id = started["id"]
    checked(requests.put(f"{base_url}/api/uploads/{upload_id}/chunks?offset=0", headers=headers, data=content, timeout=10))
    completed = checked(requests.post(f"{base_url}/api/uploads/{upload_id}/complete", headers=headers, timeout=30)).json()
    media_id = completed["media_id"]

    downloaded = checked(requests.get(f"{base_url}/api/media/{media_id}/content", headers=headers, timeout=10)).content
    if downloaded != content:
        raise RuntimeError("downloaded media differs from uploaded media")
    partial = checked(
        requests.get(f"{base_url}/api/media/{media_id}/content", headers={**headers, "Range": "bytes=0-15"}, timeout=10),
        206,
    )
    if partial.content != content[:16]:
        raise RuntimeError("range response differs from uploaded media")

    items = checked(requests.get(f"{base_url}/api/albums/{album_id}/media", headers=headers, timeout=10)).json()
    if [item["id"] for item in items] != [media_id]:
        raise RuntimeError("uploaded media is not visible in its album")
    checked(requests.put(f"{base_url}/api/albums/{album_id}/cover", headers=headers, json={"media_id": media_id}, timeout=10))
    print(json.dumps({"storage": storage["storage"], "complete": completed["status"], "range": partial.status_code, "bytes": len(content), "device_id": device_id}))
finally:
    if headers and media_id:
        requests.delete(f"{base_url}/api/media/{media_id}", headers=headers, timeout=10)
    if headers and upload_id and not media_id:
        requests.delete(f"{base_url}/api/uploads/{upload_id}", headers=headers, timeout=10)
    if headers and album_id:
        requests.delete(f"{base_url}/api/albums/{album_id}", headers=headers, timeout=10)
    if device_id and store is not None:
        with store.lock(), store.connect() as db:
            db.execute("DELETE FROM unlocks WHERE device_id=?", (device_id,))
            try:
                db.execute("DELETE FROM devices WHERE id=?", (device_id,))
            except Exception:
                db.execute("UPDATE devices SET active=0 WHERE id=?", (device_id,))
