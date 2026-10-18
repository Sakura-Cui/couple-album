# Loading 动画设计源文件

本目录保留第三版加载动画的可编辑生成代码、角色原图与离线预览。App 已接入四套 Loading；**运行时实际资源以 [Android assets/loading](../../android/app/src/main/assets/loading) 和当前 Kotlin 组件为准**，本目录属于设计来源，不是完整旧交付包。

| 主题 | 内容 |
|---|---|
| video | 双人趴姿、胶片轨道、成功动作 |
| image | 拍立得与照片逐张显色 |
| queue | 推爱心小车、七颗爱心进度 |
| app | 毛玻璃胶囊、樱花进度节点 |

`source/` 保存布局、生成脚本与原图，`preview/` 保存离线演示和播放器许可。为控制仓库体积，原交接包中的逐帧导出、重复素材及历史 QA 文件没有全部复制。脚本重新导出的文件属于本地生成物；修改后需对照实际 App 资源与状态控制验证。

## 预览与导出

在此目录安装 package.json 声明的依赖后执行 `npm run build`。离线页面可打开 `preview/index.html`；当前 App 资源的浏览器预览为 [docs/showcase/loading.html](../../docs/showcase/loading.html)。

正常加载进度映射到 0–100 帧，业务成功后播放 100–124 帧，约 800 ms；100% 等待与成功动作分别控制。未知进度不应伪造百分比。

实际原生上传队列已拍摄，见 [功能截图](../../docs/screenshots.md)；四套浏览器 GIF 与原生截图的来源、范围见 [验证记录](../../docs/verification.md)。原生完整动画时序和不同设备帧率尚未做专项性能验收。
