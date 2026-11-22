# DeepSeek Web → Android

把 [DeepSeek 官方开源的网页版前端 `chat-demo`](https://github.com/deepseek-ai/chat-demo)（MIT）直接打进一个安卓 WebView 壳里，得到一个**无自建后端、自带 API Key** 的安卓客户端。

前端一行没重写：页面通过 `https://appassets.androidplatform.net/` 由壳从 APK 的 assets 里读出，运行时直接 `fetch` 官方 API。

> 本项目是第三方封装，与 DeepSeek 官方无关。请自行准备 API Key，仅建议自用/侧载。

## 特性

| 能力 | 说明 |
|---|---|
| 完整聊天 | 流式回复、深度思考、智能搜索、链接阅读、引用、多会话/分支、Markdown/KaTeX/Mermaid |
| 语音 | 录音（`getUserMedia`）+ ASR 转写；朗读用 TTS 裸 PCM 流播放 |
| 附件 | 图片/文档选择上传、拖拽、粘贴 |
| 数据 | 会话与附件存 IndexedDB，设置存 localStorage，历史 ZIP 导入/导出 |
| 模型切换 | **新增**：`deepseek-flash` / `deepseek-v4-pro`（专家模式），两处入口 |
| 安卓适配 | 状态栏跟随页面主题、返回键先关弹窗再退出、横屏不重建 WebView、键盘跟随 |

## 与上游 `chat-demo` 的差异

1. **模型可选**：上游写死 `deepseek-flash`。这里新增 `localStorage['chat-demo:model']`，输入栏「专家模式」开关 + 设置里的「模型」下拉。
2. **pro 请求裁剪**：`deepseek-v4-pro` 不接受 `tools`（`deepseek_read_url` / `deepseek_web_search`）、`title`、`latest_reminder`，带了会 400。`store.ts` 按模型裁剪请求，专家模式下禁用「智能搜索」。
3. **品牌名**：`API 使用 demo` → `DeepSeek`（`i18n/{zh,en}.ts`、`index.html`）。

## 壳做了什么（`android/`）

- **资源服务**：`shouldInterceptRequest` 把 `https://appassets.androidplatform.net/*` 映射到 `assets/www/*`。用 https 自定义域是为了拿到安全上下文——否则 `getUserMedia`/`AudioContext` 直接不可用；也不用 `file://`，避免 null origin 下 IndexedDB/fetch 异常。
- **麦克风**：`onPermissionRequest` → 运行时申请 `RECORD_AUDIO` → 授权后 `grant(RESOURCE_AUDIO_CAPTURE)`。
- **文件选择**：`onShowFileChooser` → `ACTION_GET_CONTENT`（多选）。**不依赖** `FileChooserParams.parseResult()`，自己遍历 `getClipData()/getData()` 取 URI（部分机型该 API 返回 null，会把整批选择丢掉）。
- **导出 ZIP**：blob 链接在 WebView 里下不了，注入脚本拦截 `<a download>` + blob，分块 base64 过 JS 桥写入 MediaStore 的 `Download/`，完成后弹原生提示框给出完整路径和「打开」。
- **主题同步**：注入脚本监听 `meta[name=theme-color]` 与 `body.dark`，把颜色推给原生，状态栏/导航栏与图标明暗跟着页面走。
- **返回键**：先向页面派发 `Escape`（关弹窗/菜单），2 秒内再按退出。

## 目录

```
web/                 patched chat-demo 源码（React + TypeScript + rspack）
android/             安卓壳（纯 Java，无第三方依赖）
  app/src/main/java/com/deepseekdemo/app/MainActivity.java
  app/src/main/res/raw/inject.js     注入脚本（blob 保存 / 主题上报 / window.open）
  sync_web.ps1                       web/dist -> assets/www
  build_apk.ps1 / .bat               一键构建
  cdp.mjs                            WebView 远程调试客户端
```

## 构建

前置：Node.js ≥ 22.12、pnpm、JDK 17、Android SDK（`compileSdk 36`，build-tools 可用即可）。若系统默认 `java` 不是 17，在 `android/gradle.properties` 里设置 `org.gradle.java.home`；SDK 路径可用 `ANDROID_HOME` 或 `android/local.properties` 的 `sdk.dir` 指定。

```powershell
cd android
powershell -File build_apk.ps1            # 构建 web + 打包 debug
# 或
build_apk.bat
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。

只改网页时：

```powershell
cd web
$env:NODE_ENV='production'; pnpm exec rspack build -c rspack.config.ts
cd ..\android
powershell -File sync_web.ps1
```

> 注意：上游 `package.json` 的 `pnpm build` 里用了 `rm -rf`，Windows 上跑不了，`build_apk.ps1` 已改为直接调 `rspack`。

## 调试（WebView DevTools）

```powershell
$p = (adb shell pidof com.deepseekdemo.app).Trim()
adb forward tcp:9222 localabstract:webview_devtools_remote_$p
node android/cdp.mjs list
node android/cdp.mjs eval "location.origin"
node android/cdp.mjs tap 260 739        # CSS 像素坐标
node android/cdp.mjs shot out.png
```

`cdp.mjs` 的结果除了打到 stdout，还会写一份到同目录的 `cdp_last.txt`（某些终端下 node 的 stdout 会被吞）。

## 已知限制

- **专家模式（pro）**：不支持联网搜索、不自动生成会话标题、不支持图片输入；`thinking` 可用。
- **API Key 明文**存在页面 `localStorage`（上游行为），适合自用；要分发建议改成原生 Keystore 注入。
- 无推送、无后台保活、无系统分享、无多设备同步（纯前端无后端）。
- 需要系统 WebView 内核 ≥ Chrome 105（低于此版本启动时会提示）。

## 许可

代码 MIT。`web/` 目录来自 [`deepseek-ai/chat-demo`](https://github.com/deepseek-ai/chat-demo)（MIT，见 `web/LICENSE` 与 `web/upstream-readme.md`），保留其版权声明。安卓壳部分同样以 MIT 发布，见 `LICENSE`。
