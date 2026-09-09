const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { safeStorage } = require("electron");

const DEFAULT_SETTINGS = Object.freeze({
  settingsSchemaVersion: 7,
  themeMode: "system",
  syncClientId: "",
  syncClientName: os.hostname(),
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
  androidMemorySyncEnabled: true,
  androidMemoryPairingEncrypted: "",
  apiKeysEncrypted: Object.freeze({}),
  apiKeyEncrypted: ""
});

class SettingsStore {
  constructor(userDataPath) {
    this.filePath = path.join(userDataPath, "settings.json");
    this.backupFilePath = path.join(userDataPath, "settings.backup.json");
    this.data = this.read();
    let changed = Boolean(this.needsMigration);
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(this.data.syncClientId || "")) {
      this.data.syncClientId = crypto.randomUUID();
      changed = true;
    }
    const clientName = String(this.data.syncClientName || os.hostname() || "这台电脑")
      .trim()
      .slice(0, 80);
    if (clientName !== this.data.syncClientName) {
      this.data.syncClientName = clientName;
      changed = true;
    }
    if (!['system', 'light', 'dark'].includes(this.data.themeMode)) {
      this.data.themeMode = "system";
      changed = true;
    }
    if (changed) this.save();
  }

  normalize(parsed) {
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("设置文件内容无效");
    }
    const migrated = { ...parsed };
    this.needsMigration = Number(parsed.settingsSchemaVersion || 0) < DEFAULT_SETTINGS.settingsSchemaVersion;
    if (
      Number(migrated.settingsSchemaVersion || 0) < 2 &&
      process.platform === "win32" &&
      migrated.speechProvider === "system"
    ) {
      migrated.speechProvider = "mambo";
    }
    migrated.apiKeysEncrypted = { ...(migrated.apiKeysEncrypted || {}) };
    if (migrated.apiKeyEncrypted && ["google", "openai", "compatible"].includes(migrated.provider)) {
      migrated.apiKeysEncrypted[migrated.provider] ||= migrated.apiKeyEncrypted;
      migrated.apiKeyEncrypted = "";
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
    const { apiKeyEncrypted, apiKeysEncrypted, androidMemoryPairingEncrypted, ...visible } = this.data;
    const androidMemoryPairingAvailable = Boolean(this.androidMemoryPairing());
    return {
      ...visible,
      apiKeyConfigured: Boolean(apiKeysEncrypted?.[this.data.provider]),
      apiKeyConfiguredByProvider: Object.fromEntries(["google", "openai", "compatible"].map(provider => [provider, Boolean(apiKeysEncrypted?.[provider])])),
      apiKey: "",
      // Ciphertext may remain after an OS keychain/DPAPI reset even though it
      // can no longer be decrypted. The UI must distinguish that from a live
      // connection so it can offer recovery rather than reporting success.
      androidMemoryPaired: androidMemoryPairingAvailable,
      androidMemoryPairingUnavailable: Boolean(
        androidMemoryPairingEncrypted && !androidMemoryPairingAvailable
      ),
      androidMemoryPairing: ""
    };
  }

  update(patch) {
    if (Object.hasOwn(patch, "compatibleBaseUrl")) {
      patch = { ...patch, compatibleBaseUrl: require("./compatible.cjs").compatibleBaseUrl(patch.compatibleBaseUrl) };
    }
    const allowed = [
      "provider",
      "themeMode",
      "syncClientName",
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
      "popupAlwaysOnTop",
      "androidMemorySyncEnabled"
    ];

    const next = { ...this.data };
    for (const key of allowed) {
      if (Object.hasOwn(patch, key) && typeof patch[key] !== "undefined") {
        next[key] = patch[key];
      }
    }

    if (!["system", "light", "dark"].includes(next.themeMode)) {
      next.themeMode = "system";
    }
    next.syncClientName = String(
      next.syncClientName || os.hostname() || "这台电脑"
    ).trim().slice(0, 80) || "这台电脑";

    if (typeof patch.apiKey === "string" && patch.apiKey.trim()) {
      if (!safeStorage.isEncryptionAvailable()) {
        throw new Error("当前系统无法安全保存 API Key");
      }
      if (!["google", "openai", "compatible"].includes(next.provider)) throw new Error("当前服务不使用 API Key");
      next.apiKeysEncrypted = { ...next.apiKeysEncrypted };
      next.apiKeysEncrypted[next.provider] = safeStorage
        .encryptString(patch.apiKey.trim())
        .toString("base64");
    }

    if (
      typeof patch.androidMemoryPairing === "string" &&
      patch.androidMemoryPairing.trim()
    ) {
      if (!safeStorage.isEncryptionAvailable()) {
        throw new Error("当前系统无法安全保存安卓配对凭据");
      }
      next.androidMemoryPairingEncrypted = safeStorage
        .encryptString(patch.androidMemoryPairing.trim())
        .toString("base64");
    }

    if (patch.clearAndroidMemoryPairing === true) {
      next.androidMemoryPairingEncrypted = "";
    }

    if (patch.clearApiKey === true) {
      const provider = patch.apiKeyProvider || next.provider;
      next.apiKeysEncrypted = { ...next.apiKeysEncrypted };
      delete next.apiKeysEncrypted[provider];
    }

    const previous = this.data;
    this.data = next;
    try { this.save(); }
    catch (error) { this.data = previous; throw error; }
    return this.publicValue();
  }

  apiKey(provider = this.data.provider) {
    const encrypted = this.data.apiKeysEncrypted?.[provider];
    if (!encrypted) return "";
    try {
      return safeStorage.decryptString(
        Buffer.from(encrypted, "base64")
      );
    } catch {
      return "";
    }
  }

  androidMemoryPairing() {
    if (!this.data.androidMemoryPairingEncrypted) return "";
    try {
      return safeStorage.decryptString(
        Buffer.from(this.data.androidMemoryPairingEncrypted, "base64")
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
