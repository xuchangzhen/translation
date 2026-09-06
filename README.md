# 翻译

“翻译”是一个面向开发者的 macOS / Windows 全局翻译桌面应用。它不依赖浏览器扩展，可在编辑器、终端、聊天工具、PDF 阅读器等任意可复制文字的软件中工作。

![翻译主界面](docs/images/main.png)

![快速翻译悬浮窗](docs/images/popup.png)

## 当前功能

- 全局快捷键读取鼠标选中的文字；macOS 使用 accessory 应用策略与原生非激活 Panel 显示悬浮窗，不会切走或收起当前应用
- 按下截图快捷键后，在当前屏幕上直接显示透明多屏选区遮罩；拖动鼠标框选后，可拖动选框内部整体移动，也可通过四边与四角的透明拖拽区域精调范围，不再用圆点遮挡待识别内容；再点击“截图翻译”确认，右键或 Esc 可随时取消
- 悬浮窗支持拖动、复制、原文/译文朗读、关闭和展开到完整主窗口；译文语言可从列表重新选择并立即重译；长原文、语境说明与多条专业术语默认展示摘要，可按需展开
- 设置中可选择悬浮窗是否保持置顶；macOS 关闭置顶时仍会先显示在当前软件上方，点击外部后允许被其他软件覆盖，只在点击桌面空白区域时自动收起
- 桌面端与 Android 端采用统一的深色 / 浅色双主题，支持跟随系统或手动切换；图标、窗口背景和快速翻译悬浮窗会同步换肤
- 以本地 AI 推理、双向翻译、加密同步和间隔记忆为语义重做桌面及 Android 技术型图标，不再使用无含义的通用装饰图案
- 使用分层玻璃质感、柔和环境光、状态动效、触觉反馈与减少动态效果兼容，保留清晰的信息层级和高对比可读性
- 主窗口输入停止后自动翻译，无需点击按钮；Codex 模式使用更长防抖以节省额度
- 独立顶部拖动带，拖动窗口时不会选中标题或设置文字
- 简洁桌面界面、可录制并修改划词、截图及显示/隐藏悬浮窗三组全局快捷键；支持单独按 Alt 等修饰键触发，悬浮窗快捷键可重新显示上一次翻译
- 英文单词显示原词 IPA，英文短语和句子不显示音标；原文与译文朗读按钮明确区分
- IPA 使用适合国际音标的 Charis SIL 字体，统一规范为 `/…/` 音位标注样式
- Markdown 原文会在翻译后保留标题、列表、引用、表格、链接、强调、行内代码与代码块样式
- 翻译过的单词和句子由桌面端自动拆分、去重并端到端加密上传；技术术语会连同定义、分类和原句语境一起发送
- 单词记忆是独立 Android 应用：手机端自动接收密文、离线保存词库，并按“先回忆、后看答案”的间隔复习机制推送待记内容
- 手机只需首次配对一台电脑；之后可从已连接的 Mac 或 Windows 生成 15 分钟有效、仅能使用一次的“添加电脑”链接，让另一台电脑加入同一加密同步空间
- 桌面端与 Android 端均显示同步空间中的电脑名称、系统、版本、最近上报时间和在线状态，方便确认哪台设备正在工作
- 中文译文可使用 Mac mini 的 MamboTTS / GPT-SoVITS 曼波音色；只在点击朗读时加载，音频生成后自动关闭模型
- 通过模型判断与本地技术词识别双重检测前端、后端、DevOps、数据库、云计算、嵌入式等 IT 内容，并在译文出现后继续补充实际用途
- TranslateGemma 主翻译 + Qwen 技术术语解析的本地混合链路，并在 TranslateGemma 不可用时自动回退 Qwen
- 单个英文词在 TranslateGemma 完成翻译后仍由 Qwen 判断专业含义并补充名词解析
- Ollama、Google Cloud Translation、ChatGPT/Codex 额度（实验）、OpenAI Responses API、通用 OpenAI Chat Completions 兼容接口
- 显示当前版本，并支持在应用内检查、下载和一键安装 GitHub Release 更新
- Ollama 技术解析模型与主翻译模型均从当前服务读取已安装模型，以列表方式选择
- Codex 模型下拉列表从当前登录账号的本机 Codex 目录动态读取
- API Key 通过 macOS Keychain / Windows DPAPI 对应的 Electron `safeStorage` 加密
- 设置固定保存在不随版本和安装包名称变化的 `translation` 用户数据目录；首次升级会自动迁移旧版配置，并保留上一份有效设置作为损坏回退
- macOS / Windows 自动构建工作流

## 为什么默认推荐 Mac mini + Ollama

ChatGPT/Codex 与 API Platform 的认证及计费是分开的。普通 OpenAI API 请求不能直接消耗 Plus 额度，但官方 Codex CLI 支持 ChatGPT 登录和非交互调用，所以本应用提供三条路径：

1. **局域网本地模型（默认）**：Mac mini 运行 Ollama，Mac 和 Windows 客户端都访问它。没有按量 API 成本，数据不离开局域网。
2. **ChatGPT/Codex 额度（实验）**：调用本机官方 `codex exec`，复用 Codex 已保存的 ChatGPT 登录。本应用不读取 OAuth token。优点是使用订阅内 Codex 用量，缺点是每次启动代理的延迟较高。
3. **云端 API**：自行填写 OpenAI API Key，获得低延迟且稳定的复杂语境翻译质量。API 用量单独计费。

推荐在 16GB Mac mini 使用 `translategemma:4b` 生成主译文、`qwen3:8b` 判断技术内容并补充术语用途。TranslateGemma 是专用翻译模型，Qwen 更擅长结构化解释；应用会自动组合两者。未安装 TranslateGemma 时仍可直接使用 Qwen，不会中断翻译。

Ollama 模式会在应用启动和保存设置后预热主翻译模型，关闭思考输出，并让模型在内存中保留 30 分钟。第一次加载模型仍会比后续翻译慢；`Hello` 这类普通短文本只生成译文、英文 IPA 和朗读原文，不再等待技术说明。

## 使用 ChatGPT / Codex 额度

应用会自动检测 ChatGPT macOS 应用内置的 Codex CLI，也可以在设置中手动填写 `codex` 可执行文件路径。选择“ChatGPT / Codex 额度（实验）”后：

1. 点击“登录 ChatGPT”，在浏览器中完成官方登录。
2. 点击“检查登录状态”，确认显示 `Logged in using ChatGPT`。
3. 从模型下拉列表中选择当前账号可用的模型，或保留“自动选择”。
4. 保存设置并翻译。

这个实现使用官方稳定的 `codex exec` 非交互接口，并为每次翻译启用只读沙箱、临时会话和 JSON Schema 输出。它没有复刻 OpenClaw 的底层 OAuth token 存储或直接请求 `chatgpt.com/backend-api`，因此账号边界更清晰，也更不容易因私有路由变化而失效。

## 使用曼波中文语音

设置中的“语音朗读”默认指向 `~/manbo/MamboTTS-macOS-port` 和 `http://127.0.0.1:9880`。点击中文朗读后，macOS 版会通过已有的 `GPTSoVits` Conda 环境自动启动模型，生成并缓存 WAV 音频，然后立即关闭模型；模型不可用时回退到系统中文语音。悬浮窗原文区域的“朗读原文”支持英文单词、短语和完整句子，并使用系统英文音色。

Mac mini 版会运行一个很轻量的局域网后台桥（端口 `19876`），它本身不加载语音模型。Windows 点击中文朗读时，会根据已设置的远程 Ollama 地址自动找到这个桥，在 Mac mini 上按需启动曼波、取得音频并关闭模型。

## 单词记忆

单词记忆已拆分为三个边界清晰的组件：

| 组件 | 职责 | 能否独立运行 |
| --- | --- | --- |
| 桌面翻译器 | 翻译后自动拆句、提取术语、去重、加密上传 | 可以，手机离线时保留待发送队列并自动重试 |
| 加密同步服务 | 通过 HTTPS 暂存和转交密文，手机确认后删除 | 不接触明文，不参与翻译或复习 |
| Android 单词记忆 | 自动接收、SQLite 离线词库、主动回忆、间隔复习和系统提醒 | 可以，断网后仍可完整学习 |

正常使用链路为：`翻译完成 → 自动整理 → AES-256-GCM 加密 → 服务器中继 → Android 自动接收并入库`。除首次设备配对外，不需要导出文件、点击同步或手动导入。

同一部手机只需要扫码一次。首台电脑配对完成后，在桌面设置的“同步空间”中点击“添加另一台电脑”，把生成的一次性链接粘贴到另一台 Mac 或 Windows 的“加入已有空间”输入框即可。链接 15 分钟后失效且领取后立即销毁；同步服务只保存加密后的配对包，无法读取内容密钥。后续两台电脑翻译的内容都会自动进入同一部 Android 手机。

- 主窗口实时翻译会等待输入稳定 4 秒再收录，避免残缺输入；划词、截图和手动翻译完成后立即进入自动上传流程。
- 单个英文词生成单词卡；句子按自然句或 Markdown 段落配对拆分；技术术语和缩写会携带定义、类别及原句语境。
- 同一内容再次出现时只更新释义和语境。Android 会保留已有复习进度，不会因为桌面端更新而变回新卡。
- Android 端先展示英文供主动回忆，再根据“忘记 / 模糊 / 记得 / 轻松”安排 10 分钟到逐步增长的复习间隔。
- 桌面配对凭据使用系统安全存储；Android 凭据使用 Android Keystore；内容密钥只存在于两端，服务器数据库仅保存密文。

### 部署加密同步服务

服务端位于 [`sync-server`](sync-server)，提供 PostgreSQL 持久化、设备读写令牌、注册密钥保护、限流、长轮询即时下发和 Caddy 自动 HTTPS。日常部署推荐让维护者直接运行自动化脚本，用户只需提供 SSH 地址和同步子域名：

```bash
LINGUABRIDGE_SSH_TARGET=user@server \
LINGUABRIDGE_SYNC_DOMAIN=memory.example.com \
./scripts/deploy-sync-server.sh
```

脚本自动生成强随机数据库密码和注册码、上传服务、启动容器、申请 HTTPS、检查公网健康状态，并把注册码放入 macOS 钥匙串。

如果服务器的 80/443 已由现有 Nginx 承载其他业务，不需要迁移或停机。使用共享服务器脚本后，同步服务只监听 `127.0.0.1:18787`，新增独立子域名站点并沿用 Nginx/Certbot；脚本不会升级 Docker，也不会覆盖已有站点：

```bash
LINGUABRIDGE_SSH_TARGET=root@15.204.209.199 \
LINGUABRIDGE_SSH_IDENTITY="$HOME/.ssh/linguabridge_deploy_ed25519" \
LINGUABRIDGE_SYNC_DOMAIN=memory.xuchangzhen968.top \
LINGUABRIDGE_SERVER_IPV4=15.204.209.199 \
./scripts/deploy-sync-server-nginx.sh
```

该模式在写入自己的站点文件前后都会执行 `nginx -t`，使用独立容器名、Docker 网络和数据卷；数据库不映射到宿主机端口。证书续期钩子也会先验证 Nginx 配置，再平滑 reload。

也可以手动部署：

```bash
cd sync-server
cp .env.example .env
# 修改域名、数据库密码和注册码
docker compose up -d --build
```

将域名解析到服务器并开放 80/443 后，访问 `https://你的域名/healthz` 检查服务。在桌面翻译器中填写同步地址和一次性服务器注册码，点击“一键连接手机”；再用安卓手机相机扫描本机生成的二维码，应用会自动验证、保存密钥并开启后台接收。无需复制长密钥或手动导入。

### 构建 Android 应用

Android 工程位于 [`android-memory`](android-memory)，需要 JDK 17 与 Android SDK 36：

```bash
pnpm build:android
```

Debug APK 输出到 `android-memory/app/build/outputs/apk/debug/app-debug.apk`；正式签名 APK 由 `pnpm build:android:release` 生成。应用安装并完成首次连接后，会通过低优先级前台通知保持自动接收；词库和复习功能本身不依赖网络。

Android 正式版每天自动检查四次更新，下载后验证哈希、包名、版本和 APK 签名，再显示安装通知。服务器会定时镜像 GitHub Release 中的最新 APK，因此日常升级不需要电脑传文件。Android 不允许普通应用静默安装：首次需允许本应用安装未知来源更新，每次安装仍需点击系统确认。

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

先安装 [Ollama](https://ollama.com/)，并安装两个模型：

```bash
ollama pull qwen3:8b
ollama pull translategemma:4b
```

查看 Mac mini 的局域网 IP：

```bash
ipconfig getifaddr en0
```

保持 Mac mini 版“翻译”在后台运行。在 Windows 版设置中可继续填写：

```text
http://<Mac-mini-局域网-IP>:11434
```

然后点击“测试连接”。若 11434 没有直接对局域网开放，Windows 会自动尝试 `http://<Mac-mini-IP>:19876/ollama`，因此不再需要在终端运行 `OLLAMA_HOST=0.0.0.0:11434 ollama serve`。后台桥只接受回环、私有局域网和网线直连的链路本地地址；仍建议只在可信网络使用。

## Google Cloud Translation

设置中可选择“Google Cloud Translation”并填写 Cloud Translation Basic API Key。它的优势是延迟稳定、语言覆盖广，适合作为云端备用；代价是文本会发送到 Google Cloud 且按用量计费。技术文本完成主翻译后，应用仍尝试调用 Mac mini 的 Qwen 补充术语解释。默认继续推荐本地 TranslateGemma + Qwen，以保持隐私和避免按量费用。

## 应用内更新

设置页会显示当前版本。点击“检查更新”后可直接下载，完成后点击“立即安装”。Windows 继续使用 `electron-updater`；macOS 使用项目自己的安全更新通道，因此没有 Apple Developer Program 会员也能让已安装的应用可靠更新。

macOS 发布清单使用独立 Ed25519 私钥签名，应用内只嵌入公钥。客户端先验证清单签名，再验证 ZIP 的 SHA-512、文件大小、应用 Bundle ID、版本号和完整性签名；安装时保留上一版本，失败会自动回滚。发布私钥只保存在维护者的 macOS 钥匙串和 GitHub Actions Secret `MAC_UPDATE_PRIVATE_KEY_BASE64` 中，不进入仓库或安装包。

这套机制保证更新确实来自本项目且下载内容未被篡改，但它不等同于 Apple Developer ID 公证。首次从互联网下载的新安装包仍可能需要在 Finder 中右键选择“打开”一次；完成首次安装后，后续版本可在应用内一键更新。旧版 `electron-updater` 遇到 Apple 签名校验失败时，会提示完成一次修复安装并切换到新通道。

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

推送 `v*` 标签或手动运行 GitHub Actions 的“构建桌面安装包”工作流，会分别在 macOS 和 Windows 构建环境中生成安装包。
每次发布前必须先提升 `package.json` 的 `version`，然后推送相同版本号的标签（例如版本 `0.5.6` 对应 `v0.5.6`）；应用内更新仅会下载版本号高于当前安装版本的 Release。

## 已知边界

- 某些受保护应用或密码输入框不允许自动复制文字，这是操作系统/应用的安全限制。
- macOS 划词快捷键依赖“辅助功能”权限；截图依赖“屏幕录制”权限。
- OCR 首次运行需要下载所选语言包。之后从用户目录缓存加载。
- 当前 Windows 构建目标为 x64；如需 arm64，可在 `package.json` 的 builder 配置中增加目标架构。
- 本项目参考 Immersive Translate 的“随处触发、上下文翻译、专业术语解释”产品思路；其当前公开仓库不是源代码仓库，本项目未复制其实现。
- Android 单词记忆应用参考 [WordDrill](https://github.com/ChHsiching/word-drill) 的离线闪卡与极简浏览思路，并独立实现加密自动接收、去重入库、主动回忆和间隔复习调度。

## 许可证

MIT
