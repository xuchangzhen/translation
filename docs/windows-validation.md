# Windows 与跨平台验收

仓库新增的 `跨平台验证` workflow 在每个 Pull Request 和手动触发时运行 Windows、macOS 和 Android 验证。Windows runner 使用原生 Electron 构建 NSIS 与 portable 安装包，并执行 `scripts/verify-desktop-runtime.cjs`。

该 smoke 会在隔离临时目录中验证 Electron `safeStorage` 的加密/解密、公开设置不包含密钥，以及真实 HTTP `/models` 和 `/chat/completions` 请求。它不会读取开发机设置、真实 API Key 或同步凭据，也不会发布 Release。

现有 `build.yml` 继续只负责 tag / 手动的发布构建；Windows 真机 UI 需要在 `windows-latest` CI 安装包完成后由人工验收系统托盘、全局快捷键、截图权限和 API Key 设置。中转站仍是可选配置，smoke 使用本地模拟服务，不需要账号。
