const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { safeStorage } = require("electron");

const DEFAULT_SETTINGS = Object.freeze({
  provider: "ollama",
  sourceLanguage: "auto",
  targetLanguage: "zh-CN",
  selectionShortcut: "CommandOrControl+Shift+D",
  screenshotShortcut: "CommandOrControl+Shift+S",
  popupToggleShortcut: "CommandOrControl+Shift+H",
  ollamaUrl: "http://127.0.0.1:11434",
  ollamaModel: "qwen3:8b",
  openaiBaseUrl: "https://api.openai.com/v1",
  openaiModel: "gpt-5.6-luna",
  compatibleBaseUrl: "http://127.0.0.1:1234/v1",
  compatibleModel: "local-model",
  codexPath: "",
  codexModel: "",
  speechProvider: process.platform === "darwin" ? "mambo" : "system",
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
    this.data = this.read();
  }

  read() {
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, "utf8"));
      return { ...DEFAULT_SETTINGS, ...parsed };
    } catch {
      return { ...DEFAULT_SETTINGS };
    }
  }

  save() {
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    fs.writeFileSync(this.filePath, JSON.stringify(this.data, null, 2), {
      mode: 0o600
    });
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
      "openaiBaseUrl",
      "openaiModel",
      "compatibleBaseUrl",
      "compatibleModel",
      "codexPath",
      "codexModel",
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
