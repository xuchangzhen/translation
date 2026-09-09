# OpenClaw：刷新 Android OTA 镜像

GitHub Release `v0.9.1` 发布后，Android 版本为 `0.4.1`、`versionCode=6`。现有服务器镜像还可能停留在旧版本，请在服务器上执行以下提示词：

```text
请在现有 LinguaBridge 服务器上刷新 Android OTA 镜像，不要修改 PostgreSQL、词库同步容器、Nginx/TLS 或任何设备凭据。

目标：
- 服务器目录：/srv/linguabridge-memory/android
- 镜像脚本：/usr/local/sbin/linguabridge-android-mirror
- systemd service：linguabridge-android-mirror.service
- 公网清单：https://memory.xuchangzhen968.top/android/latest.json

先读取公网清单并记录当前 versionCode/versionName。然后执行：
systemctl start linguabridge-android-mirror.service
systemctl status --no-pager linguabridge-android-mirror.service

刷新脚本必须从 GitHub latest Release 下载并验证 sourceUrl、APK SHA-256、文件大小，然后原子替换 APK 和 latest.json。不要手工改 JSON，也不要下载 debug APK。执行失败时保留旧文件。

刷新后验证：
1. `curl --fail --silent https://memory.xuchangzhen968.top/android/latest.json` 的 packageName 是 com.linguabridge.memory，versionCode 是 6，versionName 是 0.4.1，sourceUrl 指向 GitHub v0.9.1，url 指向 `linguabridge-memory-0.4.1.apk`。
2. 下载公网 APK 到临时目录，按清单验证 SHA-256 和文件大小；不要把 APK 内容、token、密码或环境变量输出到聊天。
3. 检查 `systemctl is-enabled linguabridge-android-mirror.timer` 和 `systemctl is-active linguabridge-android-mirror.timer`，不要新建重复 timer。
4. 返回旧清单版本、新清单版本、APK 校验是否通过、systemd service/timer 状态和失败日志摘要。不要输出密钥。
```

刷新完成后，手机打开“设置 → 立即检查更新”。后台检查有六小时节流；手动检查会立即请求新清单。安装覆盖升级，不要卸载应用。
