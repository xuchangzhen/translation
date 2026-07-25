# 翻译

“翻译”是一个面向开发者的 macOS / Windows 全局翻译桌面应用。它不依赖浏览器扩展，可在编辑器、终端、聊天工具、PDF 阅读器等任意可复制文字的软件中工作。

![翻译主界面](docs/images/main.png)

![快速翻译悬浮窗](docs/images/popup.png)

## 当前功能

- 全局快捷键读取鼠标选中的文字；macOS 使用 accessory 应用策略与原生非激活 Panel 显示悬浮窗，不会切走或收起当前应用
- 按下截图快捷键后，在当前屏幕上直接显示透明多屏选区遮罩；拖动鼠标框选后，可拖动选框内部整体移动，也可通过四边与四角的八个控制点精调范围，再点击“截图翻译”确认；右键或 Esc 可随时取消
- 悬浮窗支持拖动、复制、原文/译文朗读、关闭和展开到完整主窗口；长原文、语境说明与多条专业术语默认展示摘要，可按需展开
- 设置中可选择悬浮窗是否保持置顶；关闭后悬浮窗允许被其他软件覆盖，只在点击 macOS 桌面空白区域时自动收起，点击其他软件窗口不会误收起
- 界面采用中性浅灰、白色与蓝色强调的简洁主题，移除卡片和悬浮窗外围重阴影
- 主窗口输入停止后自动翻译，无需点击按钮；Codex 模式使用更长防抖以节省额度
- 独立顶部拖动带，拖动窗口时不会选中标题或设置文字
- 简洁桌面界面、可录制并修改划词、截图及显示/隐藏悬浮窗三组全局快捷键；支持单独按 Alt 等修饰键触发，悬浮窗快捷键可重新显示上一次翻译
- 英文单词显示原词 IPA，英文短语和句子不显示音标；原文与译文朗读按钮明确区分
- 中文译文可使用本机 MamboTTS / GPT-SoVITS 曼波音色；服务随应用自动启动、定时健康检查，并在关闭界面后继续后台运行
- 通过模型判断与本地技术词识别双重检测前端、后端、DevOps、数据库、云计算、嵌入式等 IT 内容，并在译文出现后继续补充实际用途
- Ollama、ChatGPT/Codex 额度（实验）、OpenAI Responses API、通用 OpenAI Chat Completions 兼容接口
- Codex 模型下拉列表从当前登录账号的本机 Codex 目录动态读取
- API Key 通过 macOS Keychain / Windows DPAPI 对应的 Electron `safeStorage` 加密
- macOS / Windows 自动构建工作流

## 为什么默认推荐 Mac mini + Ollama

ChatGPT/Codex 与 API Platform 的认证及计费是分开的。普通 OpenAI API 请求不能直接消耗 Plus 额度，但官方 Codex CLI 支持 ChatGPT 登录和非交互调用，所以本应用提供三条路径：

1. **局域网本地模型（默认）**：Mac mini 运行 Ollama，Mac 和 Windows 客户端都访问它。没有按量 API 成本，数据不离开局域网。
2. **ChatGPT/Codex 额度（实验）**：调用本机官方 `codex exec`，复用 Codex 已保存的 ChatGPT 登录。本应用不读取 OAuth token。优点是使用订阅内 Codex 用量，缺点是每次启动代理的延迟较高。
3. **云端 API**：自行填写 OpenAI API Key，获得低延迟且稳定的复杂语境翻译质量。API 用量单独计费。

推荐先在 Mac mini 使用 `qwen3:8b` 做低延迟日常翻译；如果内存允许，可换更大的 Qwen 系列模型提高长句和术语质量。重要、复杂或对措辞要求高的内容可切换到云端模型。

Ollama 模式会在应用启动和保存设置后预热模型，翻译时关闭 Qwen3 的思考输出，并让模型在内存中保留 30 分钟。第一次加载模型仍会比后续翻译慢；`Hello` 这类普通短文本只生成译文、英文 IPA 和朗读原文，不再等待技术说明。

## 使用 ChatGPT / Codex 额度

应用会自动检测 ChatGPT macOS 应用内置的 Codex CLI，也可以在设置中手动填写 `codex` 可执行文件路径。选择“ChatGPT / Codex 额度（实验）”后：

1. 点击“登录 ChatGPT”，在浏览器中完成官方登录。
2. 点击“检查登录状态”，确认显示 `Logged in using ChatGPT`。
3. 从模型下拉列表中选择当前账号可用的模型，或保留“自动选择”。
4. 保存设置并翻译。

这个实现使用官方稳定的 `codex exec` 非交互接口，并为每次翻译启用只读沙箱、临时会话和 JSON Schema 输出。它没有复刻 OpenClaw 的底层 OAuth token 存储或直接请求 `chatgpt.com/backend-api`，因此账号边界更清晰，也更不容易因私有路由变化而失效。

## 使用曼波中文语音

设置中的“语音朗读”默认指向 `~/manbo/MamboTTS-macOS-port` 和 `http://127.0.0.1:9880`。中文译文出现后，应用会在后台预生成并缓存音频；点击“朗读译文”时可直接复用。若语音服务尚未启动，macOS 版会通过已有的 `GPTSoVits` Conda 环境自动启动模型；模型不可用时会回退到系统中文语音。悬浮窗原文区域的“朗读原文”支持英文单词、短语和完整句子，并使用系统英文音色。

曼波服务会在应用启动后自动进入后台，并每 45 秒检查一次健康状态。关闭主界面或退出“翻译”不会结束已经启动的曼波进程；下次打开应用会直接复用现有服务。

## 开发运行

需要 Node.js 22 或更高版本。项目锁定使用 pnpm 11。

```bash
corepack enable
pnpm install
pnpm dev
```

首次使用 macOS 时：

- 划词翻译需要在“系统设置 → 隐私与安全性 → 辅助功能”中允许“翻译”。
- 截图翻译需要在“系统设置 → 隐私与安全性 → 屏幕录制”中允许“翻译”。
- OCR 语言数据首次使用时会下载并缓存，首次识别会比之后慢。
- Ollama 不是应用内置组件，需要先安装并启动；设置页会检查服务和模型状态。

## 在 Mac mini 开启局域网翻译

先安装 [Ollama](https://ollama.com/)，然后在 Mac mini 上执行：

```bash
ollama pull qwen3:8b
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

查看 Mac mini 的局域网 IP：

```bash
ipconfig getifaddr en0
```

在 Windows 版“翻译”的设置中填写：

```text
http://<Mac-mini-局域网-IP>:11434
```

然后点击“测试连接”。请只在可信局域网内开放 Ollama，并在 macOS 防火墙中限制访问；Ollama 默认 HTTP 接口本身不提供账号认证。

## 验证与打包

```bash
pnpm typecheck
pnpm test
pnpm build:mac
```

Windows 安装包应在 Windows 上构建：

```powershell
corepack enable
pnpm install --frozen-lockfile
pnpm build:win
```

推送 `v*` 标签或手动运行 GitHub Actions 的 `build-desktop` 工作流，会分别在 macOS 和 Windows runner 中生成安装包。

## 已知边界

- 某些受保护应用或密码输入框不允许自动复制文字，这是操作系统/应用的安全限制。
- macOS 划词快捷键依赖“辅助功能”权限；截图依赖“屏幕录制”权限。
- OCR 首次运行需要下载所选语言包。之后从用户目录缓存加载。
- 当前 Windows 构建目标为 x64；如需 arm64，可在 `package.json` 的 builder 配置中增加目标架构。
- 本项目参考 Immersive Translate 的“随处触发、上下文翻译、专业术语解释”产品思路；其当前公开仓库不是源代码仓库，本项目未复制其实现。

## License

MIT
