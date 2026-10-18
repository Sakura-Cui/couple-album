#!/usr/bin/env python3
"""Send a private letter. Sender credentials stay in a mode-600 local file."""
import argparse
import base64
import json
from pathlib import Path
import uuid
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description='SakuYao 悄悄话发送工具')
parser.add_argument('--config', type=Path, default=Path.home()/'.config/sakura-romance/sender.env')
parser.add_argument('--devices', action='store_true', help='列出已绑定设备')
parser.add_argument('--device', help='收件设备 ID')
parser.add_argument('--text', default='')
parser.add_argument('--image', type=Path)
parser.add_argument('--title', default='老婆宝宝，有你的悄悄话')
parser.add_argument('--request-id', default=None, help='同一消息重试时使用相同值，避免重复存储')
parser.add_argument('--status', help='查询消息 ID 的发送状态')
parser.add_argument('--retry', help='明确重试已有消息；unknown 状态可能导致重复通知，请先核实')
args = parser.parse_args()
values = dict(line.split('=',1) for line in args.config.read_text().splitlines() if '=' in line and not line.startswith('#'))
base = values.get('SAKURA_API_URL', 'https://album.example.com').rstrip('/')
if not base.startswith('https://'):
    parser.error('空间地址必须使用 HTTPS')
key = values.get('SAKURA_NOTIFY_KEY', '')
if not key:
    parser.error('配置文件缺少 SAKURA_NOTIFY_KEY')
body = None; method = 'GET'
if args.devices:
    path = '/api/notify/devices'
elif args.status or args.retry:
    mid = args.status or args.retry
    if len(mid) != 32 or any(c not in '0123456789abcdef' for c in mid): parser.error('消息 ID 格式无效')
    path = '/api/notify/' + mid + ('/retry' if args.retry else '')
    if args.retry: body = b'{}'; method = 'POST'
else:
    device = args.device or values.get('SAKURA_NOTIFY_DEVICE_ID')
    if not device: parser.error('先使用 --devices 查看设备，然后用 --device 指定收件人')
    if not args.text.strip() and not args.image: parser.error('请提供 --text 或 --image')
    data = {'request_id': args.request_id or uuid.uuid4().hex, 'device_id': device, 'title': args.title, 'text': args.text}
    if args.image:
        if args.image.stat().st_size > 4*1024**2: parser.error('图片最多 4 MiB')
        data['image_base64'] = base64.b64encode(args.image.read_bytes()).decode()
    body = json.dumps(data, ensure_ascii=False).encode()
    print('request_id=' + data['request_id'])
    method = 'POST'; path = '/api/notify'
request = urllib.request.Request(base + path, data=body, method=method,
    headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(request, timeout=45) as response:
        print(json.dumps(json.load(response), ensure_ascii=False, indent=2))
except urllib.error.HTTPError as error:
    print('HTTP', error.code, error.read().decode()[:1000])
    raise SystemExit(1)
except urllib.error.URLError:
    print('连接未完成：请保留 request_id 并用同一个值重试，避免重复消息。')
    raise SystemExit(1)
