# 验证记录与图片来源

整理日期：2026-10-18（Asia/Shanghai）。原交接快照为 2026-10-15，App 源码为 1.2.26/code 51。

## 本轮检查

| 检查 | 结果 | 说明 |
|---|---|---|
| 文件完整性 | 已核对 | 3071 个清单文件；19 个差异均为声明脱敏的历史文本，无额外源码差异 |
| 功能与源码 | 已整理 | Android、后端、部署、测试、动画资源对应，见 architecture.md 与 features.md |
| 当前 Loading | 通过 | 4 套当前 App JSON，Chromium + lottie-web，未出现页面渲染错误 |
| Loading 关键状态 | 已截图 | 0%、50%、100% 等待、124 帧完成；四个 GIF 共使用 128 张截图帧 |
| Windows 后端诊断 | 已执行 | 使用测试启动器适配 fcntl，不改产品文件锁实现；见下文 |
| Linux 原样测试 | 本轮未完成 | 本机无 Linux 环境；GitHub 托管任务因账户执行环境限制未启动任何步骤 |
| Android 单测/构建 | 通过 | 本地 JDK 17 / SDK 35 / Gradle 8.11.1；Debug 单元测试 55 项全部通过，App 与测试 APK 构建成功 |
| Android 功能截图 | 已执行 | Android 9 模拟器；ShowcaseFlowTest 1 项通过（29.785 秒），13 张功能图 + 1 张启动页，共 14 张原生截图 |
| 当前生产/真机 | 未执行 | 不连接原服务器或私人设备 |

## Windows 诊断边界

原后端依赖 Unix fcntl。诊断启动器将其适配到 Windows 文件锁，使用约束文件安装依赖。首次 179 passed / 5 failed / 1 skipped，其中两项由适配器遗漏 LOCK_NB 导致，另外三项涉及 Windows 路径与备份 manifest 的 POSIX 假设。补齐适配器后对公开版执行的最终诊断为 **181 passed / 3 failed / 1 skipped**（34.75 秒）。三项失败均落在备份/恢复的 Windows 路径语义上；没有将其标记为全部通过。

该适配不能证明原程序在 Windows 原样可用，也不替代 Linux 文件锁/备份恢复验收。公开版明确使用 Linux/macOS/容器运行后端。

## Android 本次执行

本地执行 `testDebugUnitTest assembleDebug assembleDebugAndroidTest`，Debug 单元测试 XML 汇总为 55 项、0 失败、0 错误。设备测试日志为 `OK (1 test)`，用时 29.785 秒。测试覆盖相册、创建/解锁弹窗、媒体网格与多选、图片等待/查看、视频控制与清晰度菜单、上传队列、空间设置及悄悄话列表/详情的导航与截图。

本轮使用独立 Debug 包和一次性本地演示数据，不连接原生产服务。Windows 演示服务沿用下述文件锁适配；视频预先准备 HLS 副本。测试中的部分检查用于确认页面就绪，不等同于所有功能的端到端正确性验证。GitHub Actions 已配置，但托管执行环境未启动步骤，因此本次通过结果来自本地执行。

## 历史证据

交接报告记录 2026-10-09 时后端 184 passed / 1 skipped、Android Debug/Release 单测各 55、合成 UI 23/23，另有 Lint 和发布日志。它们是既有证据，不是本次新执行的结果。

## 图片来源

- loading-overview.png：本次使用当前 App 的四套 JSON 实际渲染；外框是展示容器。
- loading-*.gif：同一资源逐帧截图合成，0–100 帧进度和 100–124 帧完成由演示输入驱动。
- loading-frame-0/100/124.png：完整页面关键状态。
- images/native/*.png：本轮本地 Android 9 模拟器原生截图，1080 × 1920；13 张由 ShowcaseFlowTest 调用 UiAutomation.takeScreenshot() 生成，启动页使用 adb screencap。见 [完整界面图集](screenshots.md) 和 [文件哈希](showcase/native-capture.json)。

原生演示照片使用通用插画，视频为 FFmpeg 合成测试画面，消息和设备均为演示数据。上传队列暂停/完成数值是固定 UI 测试状态，不是上传吞吐或性能测量。

## 复验

Linux 执行 python -m pytest -q。Android 先生成 Debug 测试证书，再用 Gradle Wrapper 构建与测试。工作流：[verify.yml](../.github/workflows/verify.yml)，本地步骤：[development.md](development.md)。

真机还需覆盖弱网首屏、实况方向/重复按住、快速翻页、提前松手、后台恢复、HDR/高帧率/音画同步、密码变化、三设备/共享收件箱及真实 vivo 通知。
