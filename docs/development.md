# 本地开发与部署

## 环境

| 用途 | 要求 |
|---|---|
| 后端 | Linux/macOS，Python 3.12+；本地磁盘默认启用 |
| Android | JDK 17 或 21；Android platform 35 / build-tools 35.0.0；Gradle Wrapper 8.11.1 |
| 视频副本/演示 | FFmpeg 与 ffprobe 在 PATH 中 |
| Loading 设计导出 | Node.js，design/loading 中固定的 sharp 版本 |
| 正式部署 | Docker Engine / Compose、可信 HTTPS 证书、独立数据目录 |

原后端直接导入 fcntl，不能在原生 Windows 上原样启动。Windows 用户可使用 Linux 开发环境或 Linux 容器。Android 有 gradlew.bat，但原有 Bash/Python QA 脚本仍包含 Unix 工具与历史模拟器路径假设。

## 后端 API 调试

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r backend/requirements-dev.txt -c backend/constraints.txt
mkdir -p .tools/local-data
cd backend
export SAKURA_DATA="$PWD/../.tools/local-data"
export SAKURA_RESERVE_BYTES=0
python -m uvicorn sakura.main:create_app --factory --host 127.0.0.1 --port 8000
```

另开终端并设置同一 SAKURA_DATA 后，可运行管理命令：

```bash
source .venv/bin/activate
cd backend
export SAKURA_DATA="$PWD/../.tools/local-data"
export SAKURA_RESERVE_BYTES=0
python -m sakura.admin invite
python -m sakura.admin devices
```

邀请和 Token 是访问凭证，不要提交到仓库。App 接受 HTTPS；上面的 HTTP 服务用于本机 API 调试。接口默认不开放 Swagger 路由，阅读 [api.md](api.md) 和 [openapi.json](openapi.json)。

## Android 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
bash scripts/qa-certificate.sh
cd android
bash gradlew testDebugUnitTest assembleDebug
```

证书脚本创建只用于本地 Debug 的测试 CA，并复制到被 Git 忽略的 Debug 资源目录。Release 使用 Android 系统信任。直接运行 Gradle Debug 任务前也需要执行脚本，否则缺少 @raw/qa_certificate。

Debug 包名为 com.sakura.romance.debug；正式包为 com.sakura.romance。发布构建需要 SAKURA_SIGNING_PROPERTIES 指向仓库外配置；已有用户升级必须使用原签名。公开仓库不提供正式签名材料。

vivo SDK 以 android/app/libs 的本地 AAR 引入；SAKURA_VIVO_PROPERTIES 可配置 VIVO_APP_ID / VIVO_APP_KEY，公开版默认 AppID 为占位值 0。没有厂商配置不能承诺收到 vivo 远程通知。第三方库和 SDK 遵循各自授权。

## Android 演示截图

新增演示只使用独立 .tools/showcase-data、合成视频、通用插画和演示消息，不连接原生产服务器。若目录已存在，脚本会拒绝覆盖；换一个独立工作副本进行演示。

```bash
# 仓库根目录，激活 Python 环境
bash scripts/qa-certificate.sh
python scripts/showcase_server.py
```

服务绑定 127.0.0.1:18444，仅供测试。标准 Android 模拟器通过 https://10.0.2.2:18444 连接。不要把演示服务对公网开放，/showcase/* 路由仅用于隔离演示。

专用模拟器安装 Debug 和 androidTest APK 后运行 ShowcaseFlowTest，通过参数传入 .tools/showcase-invite。测试使用真正 App UI 和本地 API，截图写入 Debug 应用外部专属目录 `/sdcard/Android/data/com.sakura.romance.debug/files/showcase/`，可用 `adb pull` 导出。上传队列的暂停/完成条目是测试注入的固定状态，不代表真实网络上传性能。

完整自动化命令见 [.github/workflows/verify.yml](../.github/workflows/verify.yml)。工作流分开执行 Linux 后端测试、Android 单测和模拟器截图，只保存截图与测试报告，不上传邀请或数据目录。

已拍摄的 14 张实际界面见 [截图图集](screenshots.md)，本地执行结果见 [验证记录](verification.md)。

## Loading 预览与测试

```bash
python3 -m http.server 8768 --bind 127.0.0.1
# 打开 http://127.0.0.1:8768/docs/showcase/loading.html

cd backend
python -m pytest -q
# 可选，另需约 2 GiB 空间
SAKURA_LARGE_TEST=1 python -m pytest -q tests/test_large_upload.py
```

大文件测试使用合成容器字节，验证上传与 Range，不证明真实视频编码可播放。COS 测试使用本地替身，不消耗真实云资源。design/loading/source 保存动画生成代码与原图。

## Docker 部署

复制 deploy/.env.example 为 deploy/.env，设置独立的数据、备份、证书目录，并按机器调整配额。三个服务为 api、playback、tls，默认镜像标签已对齐 1.2.26。Nginx 读取证书目录中的 ip/fullchain.pem 和 ip/privkey.pem；证书须与手机连接地址匹配。192.0.2.10 和 *.example.com 都是占位地址。

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml config
docker compose --env-file deploy/.env -f deploy/compose.yaml build
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
```

容器以 UID/GID 10001 运行，提前设置数据和备份目录权限。默认监听回环地址，按自己的网络设置入口。播放 worker 无网络，共用数据目录并使用独立资源限额。

默认逻辑配额 8 GiB、待上传 4 GiB、单文件 2 GiB、系统盘余量 12 GiB。小磁盘需复核参数；测试用 SAKURA_RESERVE_BYTES=0 不应直接用于正式部署。renew-ip-cert.sh 和 systemd 文件是原工程模板，需要修改证书目标、路径和容器名。公开项目不代表提供了供陌生用户登录的在线相册。

## 备份

```bash
# 使用与服务相同的 SAKURA_DATA
python -m sakura.admin backup /independent/path/backup-YYYYMMDD
python -m sakura.admin restore /independent/path/backup-YYYYMMDD /new/empty/restore-dir
```

恢复目标必须是不存在的新目录，不覆盖在线数据；恢复后旧邀请和设备授权失效。同盘备份可用于误删恢复，但不能抵御整盘损坏。
