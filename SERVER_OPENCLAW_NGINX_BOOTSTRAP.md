# 给服务器端 OpenClaw 的第二阶段提示词

已确认服务器 `15.204.209.199` 上的 Nginx 正在承载其他业务。请不要停止、重启、升级或改写 Nginx，不要升级现有 Docker，也不要创建容器、站点或证书。本阶段只完成专用 SSH 公钥引导和只读环境报告。

1. 创建 `/root/.ssh`（权限 700）和 `/root/.ssh/authorized_keys`（权限 600）。若下列公钥尚不存在，则原样追加一行；若已存在，不要重复追加，不要删除任何已有公钥：

   ```text
   ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINbCDukiJoLoWBWasSQLBf85dKkcEuPGKSmQ2mc+9hXS linguabridge-sync-deploy
   ```

2. 验证这行公钥只出现一次，并确认其指纹为：

   ```text
   SHA256:5UlTwMRx4R21nJ9UO6xrAsMsQwPPVwcoPWJo/BXIyuM
   ```

3. 仅执行只读检查并报告：
   - `nginx -V` 与 `nginx -t` 是否成功；
   - `/etc/nginx/nginx.conf` 实际 include 的站点目录；
   - 已有站点文件通常放在哪个目录，只报告路径和文件名，不输出证书私钥或敏感配置内容；
   - `certbot --version`、`acme.sh --version` 是否可用，以及现有证书通常由哪一种工具续期；
   - `docker-compose --version` 是否可用；
   - `127.0.0.1:18787` 是否空闲；
   - 名为 `linguabridge-memory-relay`、`linguabridge-memory-postgres` 的容器是否已存在。

4. 不要修改 UFW：已确认 TCP 22/80/443 允许，后端将只绑定回环地址 `127.0.0.1:18787`，不需要开放新公网端口。

5. 最后输出简短报告，并以这一行结束：

   ```text
   LINGUABRIDGE_NGINX_BOOTSTRAP_READY=yes
   ```

此后桌面端部署程序会先备份自己的新增站点文件、运行 `nginx -t`，只在验证成功后平滑 reload；不会触碰现有站点文件。
