# 加密同步服务

该服务只负责在桌面翻译器和安卓记忆应用之间中继加密批次。词条在桌面端使用 AES-256-GCM 加密，服务端仅保存 nonce、密文和不含正文的批次元数据。

## 部署

推荐由项目维护者在桌面端直接执行仓库中的 `scripts/deploy-sync-server.sh`。该脚本会生成强随机凭据、上传服务、启动容器、申请 HTTPS 并完成健康检查；普通用户不需要操作终端。

如果目标服务器的 80/443 已由 Nginx 承载其他业务，改用 `scripts/deploy-sync-server-nginx.sh`。它不会启动 Caddy 或升级 Docker，而是把同步 API 仅绑定到 `127.0.0.1:18787`，并为同步域名单独增加 Nginx 站点。现有站点文件不会被覆盖，数据库也不会暴露公网端口。

共享 Nginx 模式已经部署后，后续版本使用 `scripts/upgrade-sync-server-nginx.sh`。脚本读取本机部署回执和远程既有密钥，只替换 LinguaBridge 中继容器；新容器健康检查失败会自动回滚，不会改写或重载 Nginx，也不会触碰同机其他业务。

脚本需要 SSH 地址和已解析到服务器的独立子域名；共享 Nginx 模式还需要显式提供服务器公网 IPv4，以便部署前核对 DNS：

- 可通过 SSH 登录的服务器地址，例如 `ubuntu@203.0.113.10`。
- 已解析到该服务器的独立子域名，例如 `memory.example.com`。

手动部署方式如下：

1. 将 `.env.example` 复制为 `.env`，填写已解析到服务器的域名和两个随机长密码。
2. 确保服务器防火墙开放 80/443，域名 A/AAAA 记录已生效；共享 Nginx 服务器无需开放 18787。
3. 运行 `docker compose up -d --build`。
4. 打开 `https://你的域名/healthz`，应返回 `{"ok":true}`。

桌面端首次连接时填写 `https://你的域名` 和 `SYNC_REGISTRATION_KEY`，随后会生成供安卓手机扫描的本地二维码。注册密钥只用于创建设备；设备注册后使用各自的随机令牌。独占服务器模式由 Caddy 自动申请和续期证书；共享服务器模式沿用 Nginx 与 Certbot，并安装一个先验证配置再 reload 的续期钩子。

## 自定义词库密文同步

Android 用户在词库页面主动点击同步后，使用 `linguabridge-wordbooks/1` 独立协议。词库内容和清单均由 Android 使用配对内容密钥以 AES-256-GCM 加密，服务端只保存密文分片和版本号；自定义词库不会进入桌面翻译的批次队列。所有词库接口只接受配对 `readToken`，桌面上传令牌不能读取或写入词库。

每个词库通过最多 1024 个分片上传，单个请求不超过 512 KiB。提交使用 `baseVersion` compare-and-swap；同一上传编号重复提交幂等，其他设备已经发布新版本时返回冲突，避免静默覆盖。最新快照持续保留；旧快照从被替换时起保留 7 天，未提交上传保留 24 小时，启动及每小时清理；每个同步空间最多 200 个词库。服务器不解析词库名称、单词、译文或技术说明。

新版 `/healthz` 保留原来的 `protocol: "linguabridge-memory/1"`，并新增 `protocols` 数组，同时列出 memory/1 与 wordbooks/1。不要仅凭旧 `protocol` 字段判定版本。

本地可用 `python3 scripts/build-sync-handoff.py`（仓库根目录执行）生成不含环境文件和部署回执的源码交接包；部署步骤见 `docs/remote-openclaw-wordbook-deploy-prompt.md`。PostgreSQL 集成测试通过 `TEST_DATABASE_URL=postgresql://... pnpm --dir sync-server test` 启用，连接必须指向隔离测试数据库。测试会创建并删除随机命名的测试 schema；不设置变量时跳过数据库测试。
