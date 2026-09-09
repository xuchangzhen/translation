# OpenClaw：升级现有词库同步服务器

本地运行 `python3 scripts/build-sync-handoff.py`，把生成的 `.tar.gz`、同名 `.sha256` 和本文件交给 OpenClaw。包内容来自当前工作区；生成包不代表已经上传服务器。

```text
请使用我附上的 linguabridge-wordbooks-*.tar.gz，继续升级 /root/linguabridge-sync 的词库同步服务。现有容器是 linguabridge-memory-relay 和 linguabridge-memory-postgres。这与 AI 翻译中转站的 API Key/Model 配置无关。

1. 用同名 .sha256 校验附件。检查归档没有绝对路径、../ 或符号链接，解压到新的 staging 目录，在包根执行 sha256sum -c SHA256SUMS。记录 SOURCE.json 和源包校验值，不要从 GitHub main 猜代码版本。

2. 检查现有容器的 Compose 标签、镜像、网络、端口和挂载，确定真实启动方式；不要输出 docker inspect 中的环境变量。包内 sync-server/ 对应服务器工程根目录。workspace-reference/ 里的 package.json、pnpm-lock.yaml 和 pnpm-workspace.yaml 只是桌面工程参考，禁止用它们覆盖服务器 package.json。

3. 保留远端既有 .env、Compose、数据库卷、网络、Nginx、TLS 和端口。包内 docker-compose.yml/Caddyfile 只作参考，不要启动默认 Compose 另建数据库或占用 80/443。配置校验用 docker compose config --quiet，避免打印插值后的凭据。

4. 备份旧应用源码、镜像引用和启动配置。备份目录权限 700，含凭据的文件权限 600。对实际生产数据库执行 pg_dump -Fc，验证 pg_restore --list 能读取并记录备份校验值；备份失败则不切换。

5. 从 staging/sync-server 构建候选镜像。在独立临时 PostgreSQL 17 数据库上，用 Node 22 和 TEST_DATABASE_URL 执行 node --test test/*.test.mjs。测试会创建并删除随机 schema，禁止连接生产数据库；跳过 PostgreSQL 测试不算通过。候选镜像构建或测试失败时继续运行旧服务。

6. 沿用真实部署方式，只替换 linguabridge-memory-relay：Compose 管理时仅更新对应应用服务并使用 --no-deps；docker run 管理时保留既有网络、端口、环境和重启策略。保留旧镜像和回滚配置。新程序自动创建 sync_wordbook_uploads、sync_wordbook_snapshots 并补充 retired_at 字段，无需手工建表；旧四张表和设备凭据必须保留。

7. 验证本地及公网 /healthz 为 HTTP 200，protocols 数组包含 linguabridge-wordbooks/1。旧 protocol 字段仍为 linguabridge-memory/1 是兼容设计，不代表旧版。确认两张新表和 retired_at 列存在。

8. 执行真实接口验收：
docker exec linguabridge-memory-relay node src/verify-wordbooks.mjs
脚本读取容器已有环境，创建临时配对空间，验证加密上传/下载/解密、读写权限、幂等、版本冲突、请求大小及分片上限，最后只删除自己的临时空间。无需用户真实设备 token 或 AI API Key。必须同时满足退出码 0、verified=true、temporaryDeviceCleanup=complete。
最新快照持续保留，旧快照从被替换时起保留 7 天，未提交上传保留 24 小时，启动及每小时清理。保留时效和 200 个词库上限使用隔离数据库测试，不要改生产记录时间或向用户空间批量写测试数据。

9. 通过后将候选 src/、package.json、Dockerfile、README.md、test/ 和 SOURCE.json 同步到 /root/linguabridge-sync 对应位置，使源码和运行镜像一致，保留远端配置和数据卷。失败则恢复旧应用镜像与启动配置并检查健康；新增表可以保留，禁止直接用旧数据库备份覆盖上线后的新写入。

禁止 down -v、删除生产表或重置凭据。不要输出密码、token、Authorization、明文词条或密文。最终返回：源包 SHA-256、运行镜像 ID、迁移结果、协议检查、验收摘要、备份路径和回滚情况。
```
