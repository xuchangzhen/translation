# 给服务器端 OpenClaw 的引导提示词

请在服务器 `15.204.209.199` 上以 root 身份执行以下引导任务。目标只是为 LinguaBridge 加密同步服务准备安全部署入口；不要创建业务数据，不要索要或输出任何密码、令牌、私钥，也不要改动无关网站或服务。

1. 先只读检查系统版本、剩余磁盘、CPU 架构、Docker/Compose 状态，以及 TCP 22、80、443 的监听占用。如果 80 或 443 已被其他服务占用，停止并报告服务名，不要擅自停止或覆盖它。
2. 如果 Docker Engine 或 Docker Compose v2 插件缺失，按当前 Linux 发行版使用 Docker 官方软件源安装稳定版 `docker-ce`、`docker-ce-cli`、`containerd.io`、`docker-buildx-plugin`、`docker-compose-plugin`。不要使用来源不明的一键脚本。启用并启动 Docker，然后确认 `docker compose version` 成功。
3. 创建 `/root/.ssh`（权限 700）和 `/root/.ssh/authorized_keys`（权限 600）。若下列公钥尚不存在，则原样追加一行；若已存在，不要重复追加：

   ```text
   ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINbCDukiJoLoWBWasSQLBf85dKkcEuPGKSmQ2mc+9hXS linguabridge-sync-deploy
   ```

4. 不要删除已有 SSH 公钥，不要修改 root 密码，不要关闭密码登录，不要更改 SSH 端口。确认该公钥的指纹为 `SHA256:5UlTwMRx4R21nJ9UO6xrAsMsQwPPVwcoPWJo/BXIyuM`。
5. 检查主机防火墙：如果 UFW 已启用，确保 TCP 22、80、443 被允许；如果 firewalld 已启用，确保 `ssh`、`http`、`https` 服务被允许。不要重置现有防火墙规则，也不要关闭其他已开放端口。若服务器没有启用主机防火墙，只报告状态，不要自行改变总体策略。
6. 最后再次验证：`docker info`、`docker compose version` 成功；公钥只出现一次；22 可继续连接；80/443 没有未知占用。输出简短报告，包含系统版本、Docker/Compose 版本、防火墙状态、端口占用，以及最后一行：

   ```text
   LINGUABRIDGE_BOOTSTRAP_READY=yes
   ```

不要部署 LinguaBridge 本体。引导完成后，桌面端的部署程序会通过这把专用公钥上传服务、生成随机凭据、启动 PostgreSQL/Caddy，并验证 HTTPS。
