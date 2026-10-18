# Sakura Romance · App Loading 素材包 v3

本版按已确认的第三版效果制作。四套沿用同一对 Q 版人物，以身体姿势和互动区分用途；通用款人物复用已完成素材。建议显示 **320 × 100 dp**，导出画布 **960 × 300 px**，透明 RGBA。

## 开发接入入口

优先使用 `app-ready/`：
- `assets/loading/video.json`
- `assets/loading/image.json`
- `assets/loading/queue.json`
- `assets/loading/app.json`
- `SakuraLoading.kt`：Kotlin / Jetpack Compose 组件示例。

四个 JSON 都自包含 PNG 贴图，可单独使用。将 assets/loading 复制到 Android 模块的 src/main/assets/loading，将组件放进源码目录；依赖与调用示例见 `app-ready/README.md`。

## 四套内容

| key | 加载动作 | 加载条 | 业务成功后 |
|---|---|---|---|
| video | 双人趴姿托腮、翘脚，相向靠近 | 樱花胶片、播放标记，两端向中间填充 | 中央亲吻，播放爱心弹出 |
| image | 男生直立举拍立得，女生背手害羞等待 | 8 张照片依次显色，樱花装饰 | 相机放至腰部，亲脸颊，女生闭眼 |
| queue | 男生踮脚伸臂推爱心礼物小车，女生胸前期待 | 固定 7 颗毛玻璃爱心依次点亮 | 小车退至旁边，男生靠近亲脸颊 |
| app | 男生背手轻轻前倾，女生等待 | 粉桃渐变毛玻璃胶囊、单朵樱花进度节点 | 亲脸颊，弹出小爱心 |

手持相机属于男生姿势图层；队列小车为独立图层。人物以完整姿势位图配合位移及淡入淡出动画，轨道与特效单独控制。轨道以渐变、透明度和高光表现毛玻璃，适合 App 小尺寸显示。

## 真实进度与完成控制

- 输入实际进度 `p ∈ [0,1]`，寻帧到 **p × 100**。加载阶段保持在指定帧；不要自动播放整条动画模拟进度。
- 只有确认业务成功时，播放 **100 → 124** 帧一次，**30 fps / 0.8 秒**，然后停留。
- 100% 等待态仍没有成功爱心。上传字节结束但服务端仍在处理时，保持 100 帧等待业务完成。
- 队列的七颗心为七档显示，点亮数 `floor(7 × p)`；每到 1/7、2/7…全部点亮。实际任务数可不同于 7。未知总量时保留已知位置，不计算虚构百分比。
- 图片点亮数 `floor(8 × p)`。
- 失败或取消时不要发送成功信号；取消末段播放并由宿主显示业务状态。
- 新任务更换 `operationKey`。组件隐藏或 App 不在前台时传 `isActive=false`，完成动作会暂停，恢复后接着播放。
- 宿主已有无动画/减少动态效果设置时，可直接展示对应静态 PNG 或完成末帧。

## 完整源素材

每套主题目录均含：
- `characters/loading/`、`characters/complete/`：独立男、女透明 PNG；`placement.json` 给出尺寸和关键布局。
- `props/cart@3x.png`：队列的独立小车。
- `layers/`：轨道、全量填充、装饰的 SVG 与透明 PNG。填充需按进度遮罩显示。
- `effects/`：爱心、播放爱心、樱花、花瓣、闪光 SVG/PNG。
- `states/`：0%、25%、50%、75%、100% 等待、完成最终态，共 6 张。
- `frames/progress/`：0–100 每 5% 一帧，共 21 张。
- `frames/complete/`：000–024 共 25 张，30 fps。
- `source/loading.svg`、`source/complete.svg`：轨道为可编辑矢量，人物为嵌入位图的分层 SVG。
- `lottie/loading-embedded.json`：单文件版本。
- `lottie/loading.json` + `lottie/images/`：独立贴图版本，需完整保留同目录图片。
- `loading@3x.png`、`complete@3x.png`、`preview@3x.png`：合成预览。

包根目录：
- `source/scene.mjs`：布局、进度遮罩与人物状态的共同来源。
- `source/build.mjs`：PNG、SVG、Lottie 导出。
- `source/package-modules.mjs`：组织独立模组和精简接入目录。
- `source/extract-atlases.mjs`：独立角色图集的单元拆分。保留图像生成的 Alpha。
- `source/atlases/`、`source/originals/`：高分辨率角色、道具原图。
- `source/delivery-prompts.json`：本版内置 image_gen 提示词；`source/reused-app-prompts.json` 为复用的通用人物记录。
- `references/`：确认的四张概念效果图。
- `qa/`：检查报告和截图；`manifest.json` 为机器可读规格。
- `preview/index.html`：带本地 Lottie 播放器的离线预览。

人物 PNG 按原始比例导出；建议保持整体 3.2:1 的画布比例缩放，避免独立拉伸人物。该尺寸支持约定的 App 使用范围，放大展示时可使用高分辨率原图重新导出。

## 复现

Node.js 环境安装依赖后运行：
```sh
npm install
npm run build
```

已固定 sharp 0.34.5。已有独立原图可直接 build；如需从本版透明图集重新拆分，先运行 `node source/extract-atlases.mjs`。验证 PNG 需 Python 与 Pillow，运行 `python source/verify.py`。

离线预览可直接打开 `preview/index.html`；也可在包目录执行 `python3 -m http.server 8770 --bind 127.0.0.1`，访问 /preview/index.html。实际效果图中的文字、背景和按钮仅属预览页面。

## 验证范围

- 四套 184 张动画 PNG、24 张关键状态图已检查尺寸、RGBA、透明边角和画布边界。
- 自包含与外置 Lottie 的资源引用、图层和帧范围已检查。
- 浏览器 Lottie 渲染已检查实际 320×100 尺寸、深浅背景、任意进度寻帧、100% 等待、完成播放及重置。
- Kotlin / Compose 示例已在独立 Android 工程中通过 `:app:compileDebugKotlin`：AGP 8.9.2、Kotlin 2.1.20、compileSdk 35、Compose BOM 2025.04.01、lottie-compose 6.7.1。
- 尚未接入现有 App 业务或进行 Android 实机渲染及帧率验收。接入后需确认目标设备上的遮罩、贴图和页面生命周期表现。

## 参考

- [Lottie Android 官方库](https://github.com/airbnb/lottie-android)
- [Lottie Compose 进度控制 API](https://github.com/airbnb/lottie-android/blob/master/lottie-compose/src/main/java/com/airbnb/lottie/compose/LottieAnimation.kt)
- 离线预览播放器为 lottie-web 5.12.2，MIT 许可见 `preview/vendor/LOTTIE-LICENSE.md`。
