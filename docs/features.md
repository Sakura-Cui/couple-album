# 功能详解与源码入口

本说明对照 1.2.26 当前代码整理。仓库内相对链接可以直接进入实现；界面截图的来源和验证范围见 [verification.md](verification.md)。

## 1. 设备邀请与共享空间

用户在服务器端生成邀请，Android 输入 HTTPS 空间地址、一次性邀请和手机名称完成绑定。邀请默认 24 小时有效、使用一次即失效，空间最多三台有效设备。设备 Token 服务端仅保存 SHA-256 摘要，手机通过 Android Keystore 保护持久化凭据。

普通邀请创建独立收件箱；`invite --share-with DEVICE_ID` 可让备用手机与现有设备共享悄悄话历史和已读状态。相册与媒体为整个空间共享，上传队列、未完成任务、解锁会话和设备凭证仍按设备隔离。

源码：[Vault.kt](../android/app/src/main/java/com/sakura/romance/Vault.kt)、[AppUi.kt](../android/app/src/main/java/com/sakura/romance/AppUi.kt)、[storage.py](../backend/sakura/storage.py)。测试：[test_api.py](../backend/tests/test_api.py)、[test_messages.py](../backend/tests/test_messages.py)。

## 2. 相册整理

支持创建、改名、删除、指定媒体作封面、置顶及长按拖动排序。置顶组和普通组分别排序，排序请求携带 revision，冲突时重新读取服务端顺序。进入相册后可选择多张照片/视频进行移动或删除，目标相册用带封面的底部面板选择。

媒体列表支持分页；正常列表使用游标辅助保持翻页稳定。Live Photo 模式先合并验证过的照片与动态伴侣，再分页，避免隐藏 MOV 影响可见数量。批量操作需要考虑照片和伴侣视频的配对关系。

源码：[AlbumsScreen.kt](../android/app/src/main/java/com/sakura/romance/AlbumsScreen.kt)、[AlbumScreen.kt](../android/app/src/main/java/com/sakura/romance/AlbumScreen.kt)、[MoveAlbumSheet.kt](../android/app/src/main/java/com/sakura/romance/MoveAlbumSheet.kt)、[main.py](../backend/sakura/main.py)。

## 3. 密码保护与后台重锁

普通相册直接进入；私密相册使用空间共用密码。首次设置至少 6 字符，服务端用 Argon2id 保存密码摘要。错误尝试达到阈值后暂时限制解锁；会话有有效期并绑定设备和密码版本，修改密码使旧解锁凭证失效。

锁定时封面显示专用插画，不展示私人缩略图。App 进入后台会锁住访问界面、清理内存图片缓存；已授权且正在上传的前台服务可以继续，随后按生命周期逻辑收回会话。鉴权由后端执行，不能只靠前端隐藏控件。

该功能没有把服务器原片变成端到端密文。手机的 Token、图片预览和视频缓存保护是独立机制，不应将两者混写为“所有照片均端到端加密”。

源码：[AlbumPrivacyDialogs.kt](../android/app/src/main/java/com/sakura/romance/AlbumPrivacyDialogs.kt)、[AppModel.kt](../android/app/src/main/java/com/sakura/romance/AppModel.kt)、[media_response.py](../backend/sakura/media_response.py)。

## 4. 上传、暂停与恢复

选择系统文档中的照片/视频后持久保存读取权限并添加队列；已准备好的任务自动开始。上传过程中可以暂停、移除、指定立即上传或重试失败任务。队列按完成状态和完成时间组织，并展示缩略图、字节数、百分比与错误原因。

默认最大单文件 2 GiB。新服务协商 1 MiB 分片、最多 3 路并发和 12 片接收窗口；旧协议仍可使用顺序分片，最大块 16 MiB。Android 边读取边计算 SHA-256；服务端记录已落盘分片及连续前缀，恢复位置以服务端确认值为准。

界面显示的已发送字节可能领先于已持久化的连续 offset，因此两者分开保存。字节上传完成后还有完整性校验、缩略图处理与落盘步骤，成功动画不能提前播放。相同 request_id 和完成请求的重试按幂等规则处理。

源码：[AppModelUploadTransfer.kt](../android/app/src/main/java/com/sakura/romance/AppModelUploadTransfer.kt)、[UploadTransfer.kt](../android/app/src/main/java/com/sakura/romance/UploadTransfer.kt)、[UploadTask.kt](../android/app/src/main/java/com/sakura/romance/UploadTask.kt)、[UploadService.kt](../android/app/src/main/java/com/sakura/romance/UploadService.kt)。测试：[test_parallel_upload.py](../backend/tests/test_parallel_upload.py)、[UploadRecoveryFlowTest.kt](../android/app/src/androidTest/java/com/sakura/romance/UploadRecoveryFlowTest.kt)。

## 5. Live Photo

将照片与配套 MOV 上传到同一相册，服务端读取 Apple ContentIdentifier 验证配对，避免仅凭文件名把无关文件配在一起。支持相关 QuickTime metadata 布局及方向处理；列表使用“实况”标识。

按住照片开始准备并播放，松开、切换页面或进入后台即停止。当前保护阈值包括动态片段最长 10 秒、最大 32 MiB。Android 必须取得完整动态数据、写入加密缓存、Media3 进入 READY 且首帧完成后，才退出加载状态；完整缓存后使用缓存源。

源码：[live_photos.py](../backend/sakura/live_photos.py)、[LivePhoto.kt](../android/app/src/main/java/com/sakura/romance/LivePhoto.kt)、[LivePhotoCache.kt](../android/app/src/main/java/com/sakura/romance/LivePhotoCache.kt)。测试覆盖配对、分组、方向、时长、完整缓存和反复按住操作。

## 6. 视频、原片与兼容播放

Android 使用 Media3，服务端提供鉴权单段 HTTP Range。独立 worker 检测编码、像素格式、分辨率、HDR 和音频兼容性，必要时生成兼容播放副本；自适应 HLS 使用独立清晰度资产。原片和副本路径分离，保留原始内容供原片播放/缓存。

普通兼容副本有分辨率与帧率策略；实况副本另有保留方向、画质和时间轴的策略。不得将“有兼容副本”写成“任何手机均可原片硬解所有格式”。真实手机的解码能力、音画同步、弱网与长时间播放仍需要单独验收。

源码：[VideoPlayer.kt](../android/app/src/main/java/com/sakura/romance/VideoPlayer.kt)、[StreamCache.kt](../android/app/src/main/java/com/sakura/romance/StreamCache.kt)、[OriginalVideoDownload.kt](../android/app/src/main/java/com/sakura/romance/OriginalVideoDownload.kt)、[playback.py](../backend/sakura/playback.py)。

## 7. 图文悄悄话

App 提供收件箱、详情、已读状态与通知入口。管理端通过独立发送密钥调用接口发送文字或图片；消息先保存再尝试推送，推送服务受理不等于手机已经显示。请求编号相同且内容一致时返回既有结果，避免普通重试重复发送。

目前不是手机之间互发消息的聊天软件。vivo 推送需要自行配置 AppID/AppKey/AppSecret；没有厂商凭据时仍可理解和测试本地收件箱逻辑。

源码：[messages.py](../backend/sakura/messages.py)、[InboxModel.kt](../android/app/src/main/java/com/sakura/romance/InboxModel.kt)、[InboxScreen.kt](../android/app/src/main/java/com/sakura/romance/InboxScreen.kt)。接口见 [private-messages.md](private-messages.md)。

## 8. 纪念日、签名与天气

首页纪念日按日期计算；公开版默认日期已替换为演示值。个性签名在服务端共享，以最后成功保存版本为准，首次导入支持指定设备约束。天气卡片结合定位与时区获得事实信息，可选接入 AI 生成文案；密钥留在服务端。

当前正式流程已去除早安通知，天气卡片不等于定时通知。部分旧方法/类名仍含 Morning，不能仅凭命名推断已存在通知功能。

源码：[Anniversary.kt](../android/app/src/main/java/com/sakura/romance/Anniversary.kt)、[SignatureHeader.kt](../android/app/src/main/java/com/sakura/romance/SignatureHeader.kt)、[TodayWeatherCard.kt](../android/app/src/main/java/com/sakura/romance/TodayWeatherCard.kt)、[morning.py](../backend/sakura/morning.py)。

## 9. 容量、备份与部署

默认本地磁盘存储，SQLite 保存元数据，目录分别保存原片、缩略图和未完成上传。逻辑配额同时统计媒体和待上传预留；额外检查实际磁盘空余，防止大量任务预占或写满磁盘。COS 适配器保留，但默认部署固定 local。

管理工具支持导出 SQLite、媒体、缩略图和哈希清单，恢复到新的空目录，恢复后失效旧设备/邀请。Docker Compose 包含 API、播放 worker 和 TLS 三个服务，提供非 root、资源限额及健康检查。

源码：[storage.py](../backend/sakura/storage.py)、[admin.py](../backend/sakura/admin.py)、[compose.yaml](../deploy/compose.yaml)。
