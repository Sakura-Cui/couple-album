# 悄悄话与可选推送

App 提供图文收件箱、详情、已读和通知入口；管理端通过独立发送密钥调用接口。当前没有手机互发消息的聊天输入框。

备用设备可通过 `python -m sakura.admin invite --share-with DEVICE_ID` 共享收件箱和已读状态；Token、推送注册仍独立，设备名称不决定归属。推送仅发往指定设备。

## 发送接口

POST /api/notify，携带 Authorization: Bearer NOTIFY_KEY。SAKURA_NOTIFY_KEY 只保存在服务端，不是设备 Token。

```json
{
  "request_id": "unique-demo-message-001",
  "device_id": "收件设备的32位ID",
  "title": "把今天的小事讲给你听",
  "text": "用于本地演示的文字内容",
  "image_base64": ""
}
```

标题最多 40 字符，正文最多 4000 字符；文字和图片至少有一个。图片最多 4 MiB、2000 万像素，移除 EXIF 并转换为最长边 1920 的 JPEG。请求总量最多 6 MiB。相同 request_id 与内容返回原结果，换内容返回冲突。消息先保存再推送。

| 接口 | 用途 |
|---|---|
| GET /api/notify/devices | 管理端查看收件设备 |
| GET /api/notify/{message_id} | 保存与推送结果 |
| POST /api/notify/{message_id}/retry | 明确重试既有通知 |
| PUT /api/push/registration | App 注册或关闭推送 |
| GET /api/messages | 分页收件箱 |
| GET /api/messages/{message_id} | 消息详情 |
| GET /api/messages/{message_id}/image | 消息图片 |
| PUT /api/messages/{message_id}/read | 标记已读 |

vivo 需要 AppID/AppKey/AppSecret 和模式配置。accepted 只表示厂商受理；not_configured、not_registered、disabled、rejected、unknown 等状态需分别处理。未配置时不能承诺真机远程通知。

scripts/send-message.py 读取所有者独立配置。请明确设置自己的 HTTPS 地址和设备，不使用示例地址发送。源码：[messages.py](../backend/sakura/messages.py)。
