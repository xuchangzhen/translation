const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("lingua", {
  getSettings: () => ipcRenderer.invoke("settings:get"),
  saveSettings: (settings) => ipcRenderer.invoke("settings:save", settings),
  setShortcutRecording: (active) =>
    ipcRenderer.invoke("shortcut:recording", active),
  clearApiKey: () => ipcRenderer.invoke("settings:clear-api-key"),
  copyText: (text) => ipcRenderer.invoke("clipboard:write", text),
  codexLogin: (settings = {}) => ipcRenderer.invoke("codex:login", settings),
  codexStatus: (settings = {}) => ipcRenderer.invoke("codex:status", settings),
  codexModels: (settings = {}) => ipcRenderer.invoke("codex:models", settings),
  ollamaModels: (settings = {}) => ipcRenderer.invoke("ollama:models", settings),
  synthesizeSpeech: (text, language) =>
    ipcRenderer.invoke("speech:synthesize", { text, language }),
  prepareSpeech: (text, language) =>
    ipcRenderer.invoke("speech:prepare", { text, language }),
  testSpeech: (settings = {}) => ipcRenderer.invoke("speech:test", settings),
  translate: (text, overrides = {}) =>
    ipcRenderer.invoke("translation:translate", { text, overrides }),
  translateTechnical: (text, overrides = {}) =>
    ipcRenderer.invoke("translation:translate-technical", { text, overrides }),
  enrichTranslation: (text, translation, overrides = {}) =>
    ipcRenderer.invoke("translation:enrich", {
      text,
      translation,
      overrides
    }),
  testProvider: (settings) =>
    ipcRenderer.invoke("translation:test-provider", settings),
  startScreenshot: () => ipcRenderer.invoke("capture:start"),
  openPermissionSettings: (kind) =>
    ipcRenderer.invoke("system:open-permission-settings", kind),
  openOllamaDownload: () => ipcRenderer.invoke("system:open-ollama-download"),
  getAppInfo: () => ipcRenderer.invoke("app:info"),
  getUpdateStatus: () => ipcRenderer.invoke("update:get-status"),
  checkForUpdates: () => ipcRenderer.invoke("update:check"),
  downloadUpdate: () => ipcRenderer.invoke("update:download"),
  installUpdate: () => ipcRenderer.invoke("update:install"),
  onUpdateStatus: (callback) => {
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on("update:status", listener);
    return () => ipcRenderer.removeListener("update:status", listener);
  },
  onTranslationStart: (callback) => {
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on("translation:start", listener);
    return () => ipcRenderer.removeListener("translation:start", listener);
  },
  onStatus: (callback) => {
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on("translation:status", listener);
    return () => ipcRenderer.removeListener("translation:status", listener);
  },
  onTranslationHydrate: (callback) => {
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on("translation:hydrate", listener);
    return () => ipcRenderer.removeListener("translation:hydrate", listener);
  },
  onPopupStart: (callback) => {
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on("popup:start", listener);
    return () => ipcRenderer.removeListener("popup:start", listener);
  },
  popupReady: () => ipcRenderer.invoke("popup:ready"),
  onPopupStatus: (callback) => {
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on("popup:status", listener);
    return () => ipcRenderer.removeListener("popup:status", listener);
  },
  resizePopup: (height) => ipcRenderer.invoke("popup:resize", height),
  closePopup: () => ipcRenderer.invoke("popup:close"),
  openPopupInMain: (payload) => ipcRenderer.invoke("popup:open-main", payload),
  overlayContext: () => ipcRenderer.invoke("overlay:context"),
  completeSelection: (rect) => ipcRenderer.invoke("overlay:complete", rect),
  cancelSelection: () => ipcRenderer.invoke("overlay:cancel")
});
