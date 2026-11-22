# NOTICE

本仓库包含第三方代码与商标以外的资源，列出如下：

## 上游代码

`web/` 目录是 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo) 的修改版本。

- 原项目许可：MIT（原文见 `web/LICENSE`）
- 上游说明保留在 `web/upstream-readme.md` 与 `web/upstream-readme.zh-cn.md`
- 本仓库对其所做的修改（模型切换、pro 请求裁剪、品牌名）见根目录 `README.md` 的「与上游 chat-demo 的差异」

## 商标

"DeepSeek" 等名称归深度求索（DeepSeek）所有。本仓库为第三方封装，与官方无关联；`com.deepseekdemo.app` 这个包名仅用于本地自用安装。若要在应用商店分发，请自行更换名称与图标以避免商标问题。

## 不含内容

本仓库**不含**任何 API Key、账号凭据、设备序列号、本机绝对路径或用户数据。构建产物（`web/dist`、`assets/www`、`app/build`）已通过 `.gitignore` 排除。
