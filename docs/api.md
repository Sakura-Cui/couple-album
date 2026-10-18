# 当前 API 索引

从公开版当前 FastAPI 路由生成，不复用旧版本接口快照。字段、参数和响应结构见 [openapi.json](openapi.json)。

除 health、绑定及特殊管理路由外，App 请求使用设备 Bearer Token；受保护相册还需 X-Unlock-Token。notify 管理接口使用独立发送密钥。

| 方法 | 路径 | 处理函数 |
|---|---|---|
| GET | `/api/notify/devices` | Recipients |
| POST | `/api/notify` | Notify |
| GET | `/api/notify/{message_id}` | Notify Status |
| POST | `/api/notify/{message_id}/retry` | Retry |
| PUT | `/api/push/registration` | Register |
| GET | `/api/messages` | Inbox |
| GET | `/api/messages/{message_id}` | Detail |
| PUT | `/api/messages/{message_id}/read` | Read |
| GET | `/api/messages/{message_id}/image` | Image |
| GET | `/health` | Health |
| POST | `/api/bind` | Bind |
| GET | `/api/session` | Session |
| PUT | `/api/profile` | Profile |
| POST | `/api/unlock` | Unlock |
| POST | `/api/lock` | Lock |
| PUT | `/api/password` | Password |
| GET | `/api/storage` | Storage |
| POST | `/api/morning/briefs` | Morning Briefs |
| POST | `/api/morning/test` | Morning Test |
| GET | `/api/albums/order` | Album Order |
| PUT | `/api/albums/{album_id}/position` | Album Position |
| GET | `/api/albums` | Albums |
| POST | `/api/albums` | Create Album |
| PATCH | `/api/albums/{album_id}` | Rename Album |
| DELETE | `/api/albums/{album_id}` | Delete Album |
| PUT | `/api/albums/{album_id}/cover` | Cover |
| GET | `/api/albums/{album_id}/media` | Media List |
| PATCH | `/api/media/{media_id}` | Move |
| DELETE | `/api/media/{media_id}` | Delete Media |
| POST | `/api/uploads` | Upload |
| GET | `/api/uploads/{upload_id}` | Upload Status |
| DELETE | `/api/uploads/{upload_id}` | Cancel Upload |
| PUT | `/api/uploads/{upload_id}/chunks` | Chunk |
| POST | `/api/uploads/{upload_id}/complete` | Complete |
| PUT | `/api/media/{media_id}/thumbnail` | Set Thumbnail |
| GET | `/api/media/{media_id}/thumbnail` | Get Thumbnail |
| GET | `/api/media/{media_id}/live` | Live Photo |
| GET | `/api/media/{media_id}/playback` | Playback Status |
| POST | `/api/media/{media_id}/playback/retry` | Retry Playback |
| GET | `/api/media/{media_id}/playback/adaptive` | Adaptive Status |
| POST | `/api/media/{media_id}/playback/adaptive` | Prepare Adaptive |
| GET | `/api/media/{media_id}/playback/hls/{asset}` | Adaptive Content |
| GET | `/api/media/{media_id}/playback/content` | Playback Content |
| GET | `/api/media/{media_id}/content` | Content |

设备或密码鉴权失败可返回 401/403/423；并发版本冲突返回 409；请求体与媒体限制、磁盘配额等另有对应状态。具体以当前代码与测试为准。

源码：[main.py](../backend/sakura/main.py)、[messages.py](../backend/sakura/messages.py)。公开版版本字段已对齐 1.2.26；服务端默认不开放文档路由。
