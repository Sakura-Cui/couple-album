# 私密相册

新建或编辑相册时打开“加密相册”。所有私密相册共用密码，普通相册不需要这个密码。首次密码至少 6 字符；修改需验证旧密码。锁定时使用固定插画封面。

后端通过 Argon2id、设备身份、会话有效期和密码版本共同校验。进入后台后锁住界面；已授权后台上传按独立生命周期继续。

| 接口 | 用途 |
|---|---|
| PUT /api/password | 配置或修改共用密码 |
| POST /api/unlock | 验证密码并返回 unlock_token |
| POST /api/lock | 使当前解锁会话失效 |
| GET /api/session | 密码保护能力与设备状态 |
| POST /api/albums / PATCH /api/albums/{album_id} | 设置 protected |

受保护的媒体、缩略图、播放副本和上传请求需携带设备 Bearer Token 与 X-Unlock-Token。移动操作还要检查目标相册权限。

这是服务器执行的密码访问控制，不是媒体端到端加密。实现见 [features.md](features.md)。
