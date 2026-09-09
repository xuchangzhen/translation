# 自定义词库与中转 API 实现记录

## 1. 文件清单

以下路径均相对于仓库根目录。本次开始时已有未提交的设置、主题及配对修复；这些改动已保留，未覆盖或撤销。

| 文件 | 本次职责 |
| --- | --- |
| `android-memory/app/src/main/java/com/linguabridge/memory/Wordbook.java` | 词库 ID、名称及总数/到期/新词统计 |
| `android-memory/app/src/main/java/com/linguabridge/memory/ImportPreview.java` | 不可修改的预览条目列表及检测计数 |
| `android-memory/app/src/main/java/com/linguabridge/memory/CsvWordbookParser.java` | CSV/Tab 字段状态机 |
| `android-memory/app/src/main/java/com/linguabridge/memory/WordbookImporter.java` | 编码检测、文件读取、JSON/表格解析、字段映射、校验及去重 |
| `android-memory/app/src/main/java/com/linguabridge/memory/WordbookImportUi.java` | SAF、预览、后台导入与结果 UI |
| `android-memory/app/src/main/java/com/linguabridge/memory/MemoryDb.java` | v3 migration、词库统计、事务导入、云端版本游标、限定词库查询和分页 |
| `android-memory/app/src/main/java/com/linguabridge/memory/MemoryCard.java` | 添加 wordbookId/category |
| `android-memory/app/src/main/java/com/linguabridge/memory/MainActivity.java` | 词库入口、统计、分页、限定词库复习及分类/语境展示 |
| `android-memory/app/build.gradle.kts` | 仅测试使用的 Robolectric 依赖和资源配置 |
| `android-memory/app/src/test/java/com/linguabridge/memory/WordbookImportUiTest.java` | SAF、预览取消、名称校验、确认导入及进入目标词库 |
| `android-memory/app/src/test/java/com/linguabridge/memory/WordbookImporterTest.java` | 文件格式与校验测试 |
| `android-memory/app/src/test/java/com/linguabridge/memory/MemoryDbWordbookTest.java` | 真实 SQLite 迁移、复习保留、事务回滚及批量测试 |
| `android-memory/app/src/test/resources/memory-v1.sql` | 从原仓库 v1 onCreate 提取的旧 schema 测试夹具 |
| `src/lib/compatible.cjs` | Base URL、Chat Completions、模型列表、错误规范化和降级 |
| `src/lib/translator.cjs` | 接入共享 compatible 传输，保留翻译/技术解析结果解析器 |
| `src/lib/settings.cjs` | v7 设置迁移与各 provider 独立加密 Key |
| `src/main.cjs` | 模型列表 IPC、按实际 provider 读取 Key、按 provider 清除 Key |
| `src/preload.cjs` | 模型列表和清除 Key 的受控桥接 |
| `src/renderer.ts` | 中转配置标签、模型建议列表、独立 Key 状态及连接延迟 |
| `src/types.d.ts` | 更新公开设置及桥接接口类型 |
| `tests/compatible.test.cjs` | API 路径、认证、列表、降级、状态码、网络及超时测试 |
| `tests/settings-keys.test.cjs` | 密钥隔离、迁移、清除、持久化和公开设置无泄露测试 |
| `README.md`、`android-memory/README.md` | 使用示例、迁移、协议和本地同步边界 |
| `sync-server/src/app.mjs`、`sync-server/src/postgres.mjs` | 独立加密词库快照、分片上传、CAS 提交、只读凭据 ACL 和保留清理 |
| `android-memory/app/src/main/java/com/linguabridge/memory/CloudApi.java`、`CryptoBox.java`、`CloudSyncService.java` | 密文分片上传、云端下载、自动合并及 AES-GCM |
| `.github/workflows/validate.yml`、`scripts/verify-desktop-runtime.cjs` | Windows/macOS PR 构建和 Electron safeStorage/兼容 API smoke |
| `docs/wordbooks-relay-implementation.md` | 本实现与验证记录 |

`src/styles.css` 与 `tests/settings.test.cjs` 的工作区差异属于开始任务前已有改动，本次未改写它们。

## 2–4. Schema、旧数据迁移与词库结构

SQLite 数据库版本从 1 升至 2。新增 `wordbooks(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, source TEXT NOT NULL)`；固定创建 ID 1、名称“桌面翻译”、source=desktop。用户导入的词库 source=local。

`cards` 新增 `wordbook_id`（默认 1）和 `category`（默认空字符串）。`sync_key` 从全局唯一改为 `UNIQUE(wordbook_id, sync_key)`；增加按词库及到期时间查询的索引。v3 为 `wordbooks` 增加 `cloud_id`、`cloud_space`、`cloud_version`、`local_revision`、`synced_revision`，用于密文快照同步与本地冲突保护。

SQLiteOpenHelper 在事务中将原 cards 暂时改名，创建带新唯一约束的 cards，逐列复制所有旧列及原始 ID，再删除已完整复制的临时旧表。未删除数据库文件；review_log 不重建、不清空，原 card_id 引用保持有效。新列默认值使全部历史词条自动归属“桌面翻译”。

## 5. 文件读取与解析

系统 ACTION_OPEN_DOCUMENT 选择文件，不添加存储权限。后台读取 UTF-8、UTF-16LE/BE 或显式 GB18030，处理 BOM；CSV 使用状态机正确处理引号内逗号、换行和双引号转义，TSV/TXT 用真实 Tab。空行及行首 # 注释忽略。

表头支持 front/word/term/english、back/translation/meaning/chinese，以及 phonetic/ipa、definition、category/tag、context/example。无表头时按单词、释义、音标、定义、分类、语境顺序解释。JSON 支持数组或 `{name, items}`，字段使用同样别名。

解析不写库。预览显示文件、可编辑名称、有效/重复/无效统计和前 8 条。名称优先使用 JSON name，否则取无扩展名文件名。只有确认后才执行一个数据库事务。

## 6. 同词与内容更新

同名本地词库作为再次导入的目标；不同词库之间单词独立。词库内 front 使用 Unicode NFKC、大小写和空白标准化产生 sync_key。文件中同词保留最后一个有效条目并统计重复数。

更新只写内容字段，不覆盖 state、due_at、interval_days、ease_factor、repetitions、lapses、last_reviewed_at、created_at、归档状态和卡片 ID。可选字段未提供时会清空旧值。桌面同步查询固定限制 wordbook_id=1，继续采用现有 payload key 和更新时间去重。

## 7. 复习和同步边界

导入新条目 state=new，due_at=当前时间；按词库查询复用原 nextDue 和 ReviewScheduler，四种反馈及 review_log 写入保持原机制。Today 和提醒汇总所有词库；Library 可查看统计、每页 60 条浏览并启动指定词库复习。

本地导入解析本身不调用 CloudApi，也没有复用受 100 条限制的 importPayload；用户主动同步时才通过独立 CloudApi 词库协议上传。AES、pairing、desktop presence、自动更新未改动，CloudSyncService 额外负责已发布词库的后台下载。

## 8. 中转配置与请求

沿用 compatible provider。设置显示 API Base URL、API Key、Model、获取模型和测试连接。URL 例如 `https://api.example.com/v1`；移除尾斜杠和误填的完整 /chat/completions 或 /models 后缀，并拒绝地址内凭据、查询参数及片段。

模型列表用 GET /models 和 Bearer Header，提取去重后的 data[].id；Model 使用可手输的建议列表，列表接口不可用不会阻止手工使用。测试连接向 /chat/completions 发送简短请求并返回模型与毫秒延迟。翻译和技术解析共用兼容请求模块；其他 provider 保留原协议。

## 9. 密钥安全

SettingsStore schema 从 6 升到 7，Google/OpenAI/Compatible 各自使用 safeStorage 加密的密文。迁移仅将旧单密钥归入当时选中的 API provider，不复制给其他服务；无法判断归属时保留旧密文但不自动使用。

publicValue 不返回任何密钥密文或已保存明文，只返回 apiKeyConfigured、各 provider 配置布尔值和空 apiKey。测试连接和模型读取可使用当前尚未保存的输入；切换 provider 时清空未保存的密码输入，避免误归属。网络错误不展示完整服务端响应或 HTML。

## 10. 参数 fallback

首次结构化请求包含 model、messages、temperature、reasoning_effort、response_format。只有 HTTP 400 的结构化错误正文明确提及 reasoning_effort/response_format 且说明 unsupported/unknown 等不支持语义时，才以 model/messages/temperature 重试一次。其他错误不降级。降级后的翻译仍使用原 JSON 提示词及 parseTranslationResult，技术解析使用原 enrichment parser。

## 11. 新增测试

Android 新增 15 项：CSV/BOM、TSV/TXT/注释、引号与换行、去重和无效计数、两种 JSON 格式与别名、友好错误；同词跨库及桌面同步隔离、重复导入保留复习字段、v1 迁移保留 cards/review_log、20,000 条导入及四种复习反馈、事务失败全量回滚、保留桌面词库名称（部分覆盖组合在同一测试方法）。

桌面 API 新增 17 项：URL、Header、请求路径、模型列表及缺失回退、两种不支持参数的降级、技术解析降级、各状态码、网络/超时、简短连接测试；另新增 5 项密钥隔离/迁移/公开值及保存失败回滚测试。

## 12–13. 实际验证结果

| 命令 | 结果 |
| --- | --- |
| pnpm typecheck | 通过；也由完整 build 再次执行 |
| pnpm test:desktop | 最终 87/87 通过，无跳过 |
| pnpm build:renderer | 通过，在 pnpm build 内实际执行 |
| pnpm build | 通过，包含类型检查、桌面与服务端测试、renderer 和 electron-builder；本机生成 macOS ARM64 DMG/ZIP |
| pnpm build:android | 最终 20/20 单元测试通过，assembleDebug 成功 |
| git diff --check | 通过 |
| `pnpm exec electron scripts/verify-desktop-runtime.cjs` | macOS 本机通过 safeStorage、/models、/chat/completions smoke |
| Android Pixel_10_Pro_API_36 emulator | APK 安装、启动和 Library/导入词库 UI 文本验收通过 |

服务端 5/5 测试通过，新增密文词库分片 ACL、CAS 和幂等提交。最后一次 20,000 条解析、事务导入和复习测试耗时 515 ms，为本机 Robolectric/SQLite 测试环境结果，不等同于手机实测速度。

自定义词库跨设备同步使用独立的 `linguabridge-wordbooks/1` 协议：用户在 Android 词库页主动点击同步；服务端只保存 AES-GCM 密文清单和分片，另一台已配对 Android 在后台拉取并本机解密。上传使用 `readToken`，桌面 `uploadToken` 没有词库权限；版本提交使用 CAS，发现本地未上传修改时会保留“本地副本”而不静默覆盖。

产物：`release/translation-0.8.1-arm64-mac.dmg`、`release/translation-0.8.1-arm64-mac.zip`、`android-memory/app/build/outputs/apk/debug/app-debug.apk`。未发布、未推送，也未替换用户已安装应用。

## 14. 已知限制

- 本机是 macOS，已增加 Windows 原生 GitHub Actions 验证（PR/手动触发），包括 `build:win` 和 Electron safeStorage/API smoke；当前未在本机执行 Windows 真机 UI。
- 未使用真实付费中转凭据测试；API 自动测试使用模拟 HTTP 响应，Android 使用 Robolectric 原生 SQLite，未做手机 UI 手工验收。
- 本地文件支持 UTF-8、UTF-16 和 GB18030，单次 64 MB / 100,000 条；不支持 XLSX 或 Anki 包。
- 词库重导通过名称匹配；未新增词库重命名、删除、合并或导出功能。
- 大文件导入没有持久化后台任务：应用进程被系统结束时由 SQLite 保证事务原子性，重新打开后可再次导入；文件预览不跨 Activity 重建保存。
- 旧密钥在 Ollama/Codex 处于选中状态时无法确认原所属 API provider，需要重新填写；保留旧密文避免丢失。
- macOS 产物沿用仓库现有本地签名方式，没有新增 Apple 公证；未提升发布版本号。Android 产物是 Debug APK。

## 后续验收修正

2026-09-09 服务器交接检查：本地隔离 PostgreSQL 17 环境运行服务端测试，12/12 通过（含 6 个数据库子测试）；独立真实 HTTP 验收脚本完成 17 项检查及临时设备清理。修复了当前词库快照误过期、首次版本并发竞争、分片不可变性与生产数据库 200 个词库上限。最新快照持续保留，被替换的旧快照在退休后保留 7 天。健康接口增加 `protocols` 字段，同时保留 memory/1 和 wordbooks/1。

交接包由 `scripts/build-sync-handoff.py` 生成，采用明确源码白名单并包含归档和逐文件 SHA-256，不包含 `.env` 或部署回执。远端部署仍由 OpenClaw 执行，步骤见 `docs/remote-openclaw-wordbook-deploy-prompt.md`；生成交接包不代表远端已经上传或上线。

- 设置先在候选对象中完成校验和加密；加密或保存失败时保留原内存状态，避免 provider 和 Key 部分变更。
- 参数 fallback 支持 error.param + unsupported_parameter 等结构化错误，同时要求正文中的拒绝语义与扩展参数处于同一分句，避免把无关的 unsupported model 当作参数兼容问题。
- 模型列表在地址/密钥编辑后清空，过期请求不会写回新的配置界面。
- Library 每次读取 61 条、显示 60 条，以额外一条判断下一页，避免满 60 条时显示空页入口。
- 导入预览按钮在 Dialog 显示后立即绑定监听，新增四项 Robolectric 界面测试验证 SAF、取消不写库、保留名称校验与确认导入回调。
