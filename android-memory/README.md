# 单词记忆 Android

独立运行的 Android 记忆应用。它可以在没有桌面翻译器、同步服务器或网络的情况下浏览词库、复习和安排提醒；与桌面端的关系仅是自动接收其整理后的学习条目。

## 工作方式

- 推荐在桌面翻译器填写同步域名并创建空间；无需服务器注册码。安卓手机相机扫描二维码并打开本应用后，会自动验证和保存设备级读写令牌与 256 位内容密钥。
- 也可以在本应用“连接桌面”页面填写同步地址创建空间，再将配对信息粘贴到桌面端一次。日常使用不需要导出、复制或手动同步。
- 同一单词或句子以标准化键去重。桌面更新释义和技术语境时不会重置手机上的复习进度。
- 复习采用主动回忆和间隔重复；“忘记”会在 10 分钟后再次出现，“模糊 / 记得 / 轻松”会安排逐渐增长的间隔。
- SQLite 词库、复习日志和提醒均保存在手机本地。同步凭据使用 Android Keystore 加密。
- 应用每天自动检查四次更新。发现新版本后会自动下载，并验证 SHA-256、包名、版本号及 APK 发布签名；点击系统通知即可进入安装确认。

## 自定义词库

从“词库 → 导入词库”选择 UTF-8 CSV、TSV、TXT 或 JSON，先预览再确认。词库名称默认取 JSON name 或文件名；填写已有本地词库名称可更新释义并保留复习进度。不同词库中的相同单词独立保存。词库显示总词数、待复习数和新词数，支持每页 60 条浏览及限定词库复习。选择本地词库后可点击“同步到已连接的其他设备”，通过独立的 AES-256-GCM 密文分片协议同步；服务器只保存密文，其他 Android 设备自动下载并解密。

自定义词库可以省略 `phonetic` / `ipa` 列。导入时会在后台用内置的 ECDICT 离线音标词典进行标准化后的精确查询并补全空音标；词库自身已有的音标永远优先，不会被覆盖。查不到或本地词典不可用时仍会正常导入，音标保持为空，整个过程不需要联网。

### 重建离线音标词典

生产数据库位于 `app/src/main/assets/phonetics-v1.db`，仅包含 ECDICT 的 `word` 和 `phonetic` 字段。其 APK 内的授权声明位于 `app/src/main/assets/phonetics-v1.notice.txt`，ECDICT 使用 MIT License。需要更新词典时，先从 [ECDICT](https://github.com/skywind3000/ECDICT) 获取 `ecdict.csv`，然后在 `android-memory` 目录执行：

```bash
python3 tools/build_phonetic_db.py \
  --source ~/Downloads/ecdict.csv \
  --output app/src/main/assets/phonetics-v1.db
```

脚本只使用 Python 标准库，输出写入数量、忽略数量和数据库大小；Gradle 构建不会下载 ECDICT。

本地导入默认不上传服务器，只有点击同步按钮的词库才上传；桌面自动同步固定进入“桌面翻译”。文件选择使用 SAF，不请求存储权限。格式示例与字段别名见根目录 README 的“自定义词库”。

SQLite v1 → v3 迁移保留原卡片 ID、全部复习字段和 review_log；新增 wordbooks 表、`(wordbook_id, sync_key)` 唯一约束及云端版本/本地修改游标。新卡片复用 ReviewScheduler 和系统提醒。本地未上传的修改不会被静默覆盖：下载新云端版本时会保留一个“本地副本”词库。

## 构建

需要 JDK 17 和 Android SDK 36：

```bash
./gradlew testDebugUnitTest assembleDebug
```

开发安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。为保证最早安装的客户端可以保留数据并持续覆盖升级，正式版本沿用首版证书作为长期发布证书；证书私钥已独立备份，密码只保存在 macOS 钥匙串且不会进入仓库。通过根目录的 `scripts/build-android-release.sh` 构建到 `release/linguabridge-memory-<版本>.apk`。

正式 APK 和 `latest-android.json` 由 `scripts/publish-android-update.sh` 原子上传到私有同步域名。GitHub 正式发布后，服务器 systemd timer 每六小时自动镜像新版本；手机不需要手工接收或复制 APK。根据 Android 安全规则，首次需要允许本应用“安装未知应用”，每次真正安装仍由系统要求用户确认。

手机通过 USB 调试连接 Mac 时，可在项目根目录运行 `pnpm pair:android`。该命令会从 macOS 安全存储读取已有配对信息并直接送入手机应用，终端不会显示令牌或内容密钥。

当前同步服务使用 `remoteMessaging` 前台服务保持即时加密接收，因此系统会显示一条低优先级常驻通知。安装包适合自用或企业侧载；如果发布到 Google Play，需要按商店要求申报对应的前台服务用途。

单元测试使用 JUnit 与 Robolectric（仅测试依赖，不进入 APK），覆盖真实 SQLite 升级、导入去重、复习状态保留及 20,000 条批量导入。首次运行需要下载测试运行时。
