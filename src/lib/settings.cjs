const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { safeStorage } = require("electron");

const DEFAULT_SETTINGS = Object.freeze({
  settingsSchemaVersion: 4,
  provider: "ollama",
  sourceLanguage: "auto",
  targetLanguage: "zh-CN",
  selectionShortcut: "CommandOrControl+Shift+D",
  screenshotShortcut: "CommandOrControl+Shift+S",
  popupToggleShortcut: "CommandOrControl+Shift+H",
  ollamaUrl: "http://127.0.0.1:11434",
  ollamaModel: "qwen3:8b",
  ollamaTranslationModel: "translategemma:4b",
  useTranslateGemma: true,
  openaiBaseUrl: "https://api.openai.com/v1",
  openaiModel: "gpt-5.6-luna",
  compatibleBaseUrl: "http://127.0.0.1:1234/v1",
  compatibleModel: "local-model",
  codexPath: "",
  codexModel: "",
  useThinking: false,
  speechProvider: "mambo",
  mamboUrl: "http://127.0.0.1:9880",
  mamboRoot: path.join(
    os.homedir(),
    "manbo",
    "MamboTTS-macOS-port"
  ),
  ocrLanguages: "eng+chi_sim",
  launchAtLogin: false,
  popupAlwaysOnTop: false,
  apiKeyEncrypted: ""
});

class SettingsStore {
  constructor(userDataPath) {
    this.filePath = path.join(userDataPath, "settings.json");
    this.backupFilePath = path.join(userDataPath, "settings.backup.json");
    this.data = this.read();
  }

  normalize(parsed) {
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("设置文件内容无效");
    }
    const migrated = { ...parsed };
    if (
      Number(migrated.settingsSchemaVersion || 0) < 2 &&
      process.platform === "win32" &&
      migrated.speechProvider === "system"
    ) {
      migrated.speechProvider = "mambo";
    }
    migrated.settingsSchemaVersion = DEFAULT_SETTINGS.settingsSchemaVersion;
    return { ...DEFAULT_SETTINGS, ...migrated };
  }

  readFile(filePath) {
    return this.normalize(JSON.parse(fs.readFileSync(filePath, "utf8")));
  }

  read() {
    for (const candidate of [this.filePath, this.backupFilePath]) {
      try {
        return this.readFile(candidate);
      } catch {
        // Try the last known-good backup before falling back to defaults.
      }
    }
    return { ...DEFAULT_SETTINGS };
  }

  backupCurrentFile() {
    if (!fs.existsSync(this.filePath)) return;
    try {
      this.readFile(this.filePath);
      fs.copyFileSync(this.filePath, this.backupFilePath);
    } catch {
      // Never replace a valid backup with a corrupt current file.
    }
  }

  save() {
    const directory = path.dirname(this.filePath);
    const temporaryPath = path.join(
      directory,
      `settings.${process.pid}.tmp`
    );
    fs.mkdirSync(directory, { recursive: true });
    this.backupCurrentFile();
    fs.writeFileSync(temporaryPath, JSON.stringify(this.data, null, 2), {
      mode: 0o600
    });
    try {
      fs.renameSync(temporaryPath, this.filePath);
    } catch (error) {
      try {
        fs.copyFileSync(temporaryPath, this.filePath);
        fs.unlinkSync(temporaryPath);
      } catch {
        if (fs.existsSync(temporaryPath)) fs.unlinkSync(temporaryPath);
        throw error;
      }
    }
  }

  publicValue() {
    const { apiKeyEncrypted, ...visible } = this.data;
    return {
      ...visible,
      apiKeyConfigured: Boolean(apiKeyEncrypted),
      apiKey: ""
    };
  }

  update(patch) {
    const allowed = [
      "provider",
      "sourceLanguage",
      "targetLanguage",
      "selectionShortcut",
      "screenshotShortcut",
      "popupToggleShortcut",
      "ollamaUrl",
      "ollamaModel",
      "ollamaTranslationModel",
      "useTranslateGemma",
      "openaiBaseUrl",
      "openaiModel",
      "compatibleBaseUrl",
      "compatibleModel",
      "codexPath",
      "codexModel",
      "useThinking",
      "speechProvider",
      "mamboUrl",
      "mamboRoot",
      "ocrLanguages",
      "launchAtLogin",
      "popupAlwaysOnTop"
    ];

    for (const key of allowed) {
      if (Object.hasOwn(patch, key) && typeof patch[key] !== "undefined") {
        this.data[key] = patch[key];
      }
    }

    if (typeof patch.apiKey === "string" && patch.apiKey.trim()) {
      if (!safeStorage.isEncryptionAvailable()) {
        throw new Error("当前系统无法安全保存 API Key");
      }
      this.data.apiKeyEncrypted = safeStorage
        .encryptString(patch.apiKey.trim())
        .toString("base64");
    }

    if (patch.clearApiKey === true) {
      this.data.apiKeyEncrypted = "";
    }

    this.save();
    return this.publicValue();
  }

  apiKey() {
    if (!this.data.apiKeyEncrypted) return "";
    try {
      return safeStorage.decryptString(
        Buffer.from(this.data.apiKeyEncrypted, "base64")
      );
    } catch {
      return "";
    }
  }
}

module.exports = {
  DEFAULT_SETTINGS,
  SettingsStore
};
