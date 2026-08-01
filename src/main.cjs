const path = require("node:path");
const fs = require("node:fs");
const { execFile } = require("node:child_process");
const { autoUpdater } = require("electron-updater");
const {
  app,
  BrowserWindow,
  Menu,
  Tray,
  clipboard,
  desktopCapturer,
  globalShortcut,
  ipcMain,
  nativeImage,
  screen,
  shell
} = require("electron");
const { DEFAULT_SETTINGS, SettingsStore } = require("./lib/settings.cjs");
const {
  hookShortcutMatches,
  isModifierOnlyShortcut,
  isReservedMacShortcut,
  normalizeModifierShortcut,
  parseHookShortcut
} = require("./lib/shortcuts.cjs");
const {
  codexLogin,
  codexLoginStatus,
  codexModels,
  enrichTranslation,
  listOllamaModels,
  testProvider,
  translateTechnicalText,
  translateText,
  warmOllama
} = require("./lib/translator.cjs");
const { updateErrorMessage } = require("./lib/updater.cjs");
const {
  ensureMamboRunning,
  mamboHealth,
  stopMambo,
  synthesizeMambo
} = require("./lib/speech.cjs");
const {
  startMacService,
  stopMacService
} = require("./lib/mac-service.cjs");
const {
  ensureLocalOllamaRunning
} = require("./lib/ollama-service.cjs");
const {
  popupBoundsNearPoint,
  popupWindowPresentation
} = require("./lib/windowing.cjs");
const {
  migrateLegacyUserData
} = require("./lib/user-data.cjs");

function configureStableUserDataPath() {
  const currentUserDataPath = app.getPath("userData");
  try {
    const result = migrateLegacyUserData({
      appDataPath: app.getPath("appData"),
      currentUserDataPath
    });
    app.setPath("userData", result.stablePath);
    if (result.migrated) {
      console.info(
        `Migrated settings from ${result.sourcePath} to ${result.stablePath}`
      );
    }
    return result.stablePath;
  } catch (error) {
    console.error("Unable to configure the stable settings directory:", error);
    return currentUserDataPath;
  }
}

configureStableUserDataPath();

let mainWindow;
let popupWindow;
let pendingPopupPayload = null;
let tray;
let store;
let isQuitting = false;
let suppressMainWindowActivation = false;
let startupShortcutNotice = "";
let overlayWindows = [];
let overlayContexts = new Map();
let ocrWorker = null;
let ocrWorkerLanguages = "";
let modifierHook = null;
let modifierHookStarted = false;
let modifierShortcutHandlers = new Map();
let hookShortcutHandlers = [];
let modifierCandidate = null;
let modifierCandidateTainted = false;
let shortcutRecording = false;
let suppressHookShortcutsUntil = 0;
const shortcutTriggerTimes = new Map();
let popupDismissClickSerial = 0;
let popupRevealSerial = 0;
let popupManuallyHidden = false;
const translationCache = new Map();
const enrichmentCache = new Map();
const speechCache = new Map();
const speechInFlight = new Map();
let updateState = {
  status: "idle",
  message: "尚未检查更新",
  progress: 0,
  version: ""
};

const isDev = Boolean(process.env.VITE_DEV_SERVER_URL);

function enterBackgroundWindowMode() {
  if (process.platform !== "darwin") return;
  if (mainWindow && !mainWindow.isDestroyed() && mainWindow.isVisible()) {
    mainWindow.hide();
  }
  app.setActivationPolicy("accessory");
}

function enterMainWindowMode() {
  if (process.platform !== "darwin") return;
  app.setActivationPolicy("regular");
}

function repairStoredShortcuts() {
  const repaired = [];
  for (const [key, label] of [
    ["selectionShortcut", "划词翻译"],
    ["screenshotShortcut", "截图翻译"],
    ["popupToggleShortcut", "显示/隐藏悬浮窗"]
  ]) {
    const previous = store.data[key];
    if (!isReservedMacShortcut(previous)) continue;
    store.data[key] = DEFAULT_SETTINGS[key];
    repaired.push(
      `${label}快捷键 ${previous} 与 macOS 系统快捷键冲突，已恢复为 ${store.data[key]}`
    );
  }
  if (!repaired.length) return;
  store.save();
  startupShortcutNotice = repaired.join("；");
}

function cacheRead(cache, key) {
  if (!cache.has(key)) return null;
  const value = cache.get(key);
  cache.delete(key);
  cache.set(key, value);
  return value;
}

function cacheWrite(cache, key, value, maximum) {
  if (cache.has(key)) cache.delete(key);
  cache.set(key, value);
  while (cache.size > maximum) {
    cache.delete(cache.keys().next().value);
  }
}

function activeModel(settings) {
  return {
    ollama: `${settings.ollamaUrl}|${settings.ollamaTranslationModel}|${settings.ollamaModel}|${settings.useTranslateGemma}`,
    openai: `${settings.openaiBaseUrl}|${settings.openaiModel}`,
    compatible: `${settings.compatibleBaseUrl}|${settings.compatibleModel}`,
    codex: `${settings.codexPath}|${settings.codexModel}`
  }[settings.provider] || settings.provider;
}

function translationCacheKey(text, settings) {
  return [
    settings.provider,
    activeModel(settings),
    settings.sourceLanguage,
    settings.targetLanguage,
    String(text || "").trim()
  ].join("\u241f");
}

function speechCacheKey(text, settings) {
  return [
    settings.speechProvider,
    settings.mamboUrl,
    settings.mamboRoot,
    settings.ollamaUrl,
    String(text || "").trim()
  ].join("\u241f");
}

async function cachedMamboSpeech(text, settings) {
  const key = speechCacheKey(text, settings);
  const cached = cacheRead(speechCache, key);
  if (cached) return cached;
  if (speechInFlight.has(key)) return speechInFlight.get(key);
  const request = synthesizeMambo(text, settings)
    .then((result) => {
      cacheWrite(speechCache, key, result, 10);
      return result;
    })
    .finally(() => {
      speechInFlight.delete(key);
    });
  speechInFlight.set(key, request);
  return request;
}

function rendererUrl(page = "index.html") {
  if (isDev) {
    return `${process.env.VITE_DEV_SERVER_URL}/${page}`;
  }
  return `file://${path.join(__dirname, "..", "dist", page)}`;
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 980,
    height: 760,
    minWidth: 780,
    minHeight: 620,
    show: false,
    title: "翻译",
    backgroundColor: "#f5f5f7",
    titleBarStyle: process.platform === "darwin" ? "hiddenInset" : "default",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  mainWindow.loadURL(rendererUrl());
  mainWindow.once("ready-to-show", () => mainWindow.show());
  if (process.env.LINGUABRIDGE_SCREENSHOT_PATH) {
    mainWindow.webContents.once("did-finish-load", async () => {
      if (process.env.LINGUABRIDGE_SMOKE_AUTOTYPE) {
        const smokeText = JSON.stringify(
          process.env.LINGUABRIDGE_SMOKE_AUTOTYPE
        );
        await mainWindow.webContents.executeJavaScript(`
          (() => {
            const field = document.querySelector("#source-text");
            field.value = ${smokeText};
            field.dispatchEvent(new Event("input", { bubbles: true }));
          })()
        `);
      }
      if (process.env.LINGUABRIDGE_SMOKE_VIEW === "settings") {
        await mainWindow.webContents.executeJavaScript(`
          document.querySelector("#open-settings")?.click()
        `);
      }
      if (process.env.LINGUABRIDGE_SMOKE_PROVIDER) {
        const smokeProvider = JSON.stringify(
          process.env.LINGUABRIDGE_SMOKE_PROVIDER
        );
        await mainWindow.webContents.executeJavaScript(`
          (() => {
            const provider = document.querySelector("#setting-provider");
            provider.value = ${smokeProvider};
            provider.dispatchEvent(new Event("change", { bubbles: true }));
          })()
        `);
      }
      if (process.env.LINGUABRIDGE_SMOKE_SECTION) {
        const smokeSection = JSON.stringify(
          {
            shortcuts: "全局操作",
            speech: "语音朗读"
          }[process.env.LINGUABRIDGE_SMOKE_SECTION] ||
            process.env.LINGUABRIDGE_SMOKE_SECTION
        );
        await mainWindow.webContents.executeJavaScript(`
          (() => {
            const section = [...document.querySelectorAll(".settings-section")]
              .find((node) => node.querySelector("h2")?.textContent === ${smokeSection});
            section?.scrollIntoView({ block: "start" });
            ${
              process.env.LINGUABRIDGE_SMOKE_SECTION === "shortcuts"
                ? `
                  const recordButton = section?.querySelector(".shortcut-record-button");
                  recordButton?.click();
                  const shortcutInput = section?.querySelector(".shortcut-input");
                  shortcutInput?.dispatchEvent(new KeyboardEvent("keydown", {
                    key: "Alt", altKey: true, bubbles: true
                  }));
                  shortcutInput?.dispatchEvent(new KeyboardEvent("keyup", {
                    key: "Alt", bubbles: true
                  }));
                `
                : ""
            }
          })()
        `);
      }
      const smokeDelay = Math.max(
        500,
        Number(process.env.LINGUABRIDGE_SMOKE_DELAY_MS) ||
          (process.env.LINGUABRIDGE_SMOKE_AUTOTYPE ? 2200 : 1200)
      );
      setTimeout(async () => {
        const image = await mainWindow.capturePage();
        fs.writeFileSync(
          process.env.LINGUABRIDGE_SCREENSHOT_PATH,
          image.toPNG()
        );
        isQuitting = true;
        app.quit();
      }, smokeDelay);
    });
  }
  mainWindow.on("close", (event) => {
    if (!isQuitting) {
      event.preventDefault();
      mainWindow.hide();
      enterBackgroundWindowMode();
    }
  });
  mainWindow.on("hide", () => {
    shortcutRecording = false;
  });
}

function showMainWindow() {
  enterMainWindowMode();
  if (!mainWindow) createMainWindow();
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}

function popupBounds(width, height) {
  const cursor = screen.getCursorScreenPoint();
  const display = screen.getDisplayNearestPoint(cursor);
  return popupBoundsNearPoint(cursor, display.workArea, width, height);
}

function revealTranslationPopup() {
  if (!popupWindow || popupWindow.isDestroyed()) return;
  const revealSerial = ++popupRevealSerial;
  const presentation = popupWindowPresentation(
    process.platform,
    store.data.popupAlwaysOnTop
  );
  popupWindow.setAlwaysOnTop(
    presentation.initiallyAboveOtherApps ||
      presentation.brieflyAboveOtherApps,
    "floating"
  );
  popupWindow.showInactive();
  popupWindow.moveTop();
  if (presentation.brieflyAboveOtherApps) {
    setTimeout(() => {
      if (
        revealSerial !== popupRevealSerial ||
        !popupWindow ||
        popupWindow.isDestroyed() ||
        store?.data.popupAlwaysOnTop
      ) {
        return;
      }
      popupWindow.setAlwaysOnTop(false);
      popupWindow.moveTop();
    }, 700);
  }
}

function showTranslationPopup(payload) {
  enterBackgroundWindowMode();
  suppressMainWindowActivation = true;
  pendingPopupPayload = payload;
  popupManuallyHidden = false;
  const width = 480;
  const height = payload.result ? 520 : 270;
  const position = popupBounds(width, height);
  const presentation = popupWindowPresentation(
    process.platform,
    store.data.popupAlwaysOnTop
  );

  if (!popupWindow || popupWindow.isDestroyed()) {
    popupWindow = new BrowserWindow({
      ...position,
      width,
      height,
      minWidth: 420,
      minHeight: 210,
      maxWidth: 560,
      maxHeight: 680,
      type: presentation.type,
      frame: false,
      transparent: true,
      resizable: false,
      show: false,
      skipTaskbar: true,
      acceptFirstMouse: true,
      alwaysOnTop: presentation.initiallyAboveOtherApps,
      hasShadow: false,
      webPreferences: {
        preload: path.join(__dirname, "preload.cjs"),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false
      }
    });
    if (process.platform === "darwin") {
      popupWindow.setVisibleOnAllWorkspaces(true, {
        visibleOnFullScreen: true,
        skipTransformProcessType: true
      });
    }
    let popupRevealed = false;
    const revealCreatedPopup = () => {
      if (popupRevealed || !popupWindow || popupWindow.isDestroyed()) return;
      popupRevealed = true;
      revealTranslationPopup();
      setTimeout(() => {
        suppressMainWindowActivation = false;
      }, 350);
    };
    popupWindow.loadURL(rendererUrl("popup.html"));
    popupWindow.once("ready-to-show", revealCreatedPopup);
    popupWindow.webContents.once("did-finish-load", () => {
      revealCreatedPopup();
      if (process.env.LINGUABRIDGE_POPUP_SCREENSHOT_PATH) {
        if (process.env.LINGUABRIDGE_SMOKE_POPUP_TARGET) {
          const target = JSON.stringify(
            process.env.LINGUABRIDGE_SMOKE_POPUP_TARGET
          );
          setTimeout(() => {
            void popupWindow?.webContents.executeJavaScript(`
              (() => {
                const select = document.querySelector("#popup-target-language");
                if (!select) return false;
                select.value = ${target};
                select.dispatchEvent(new Event("change", { bubbles: true }));
                return true;
              })()
            `);
          }, 500);
        }
        const popupSmokeDelay = Math.max(
          1000,
          Number(process.env.LINGUABRIDGE_SMOKE_DELAY_MS) ||
            (process.env.LINGUABRIDGE_SMOKE_POPUP_TARGET ? 12000 : 1200)
        );
        setTimeout(async () => {
          const image = await popupWindow.capturePage();
          fs.writeFileSync(
            process.env.LINGUABRIDGE_POPUP_SCREENSHOT_PATH,
            image.toPNG()
          );
          isQuitting = true;
          app.quit();
        }, popupSmokeDelay);
      }
    });
    popupWindow.on("closed", () => {
      popupWindow = null;
      popupManuallyHidden = false;
    });
    return;
  }

  popupWindow.setSize(width, height);
  popupWindow.setPosition(position.x, position.y);
  revealTranslationPopup();
  popupWindow.webContents.send("popup:start", payload);
  setTimeout(() => {
    suppressMainWindowActivation = false;
  }, 350);
}

function togglePopupVisibility() {
  if (popupWindow && !popupWindow.isDestroyed()) {
    if (popupWindow.isVisible() && !popupManuallyHidden) {
      popupManuallyHidden = true;
      popupWindow.hide();
      return;
    }
    enterBackgroundWindowMode();
    suppressMainWindowActivation = true;
    popupManuallyHidden = false;
    revealTranslationPopup();
    setTimeout(() => {
      suppressMainWindowActivation = false;
    }, 350);
    return;
  }
  showTranslationPopup(
    pendingPopupPayload || {
      source: "selection",
      error: "还没有可显示的翻译，请先进行划词翻译或截图翻译。"
    }
  );
}

function sendPopupStatus(message, progress, error = false) {
  if (popupWindow && !popupWindow.isDestroyed()) {
    popupWindow.webContents.send("popup:status", { message, progress, error });
  }
}

function createTray() {
  const icon = nativeImage.createFromDataURL(
    "data:image/svg+xml;base64," +
      Buffer.from(
        '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" rx="8" fill="#0071e3"/><path d="M8 9h9v3h-3c-.5 3-1.6 5.2-3.3 7 1.2.9 2.6 1.7 4.3 2.3l-1.2 2.6c-2-.8-3.7-1.8-5.1-3-1.2 1-2.6 1.9-4.2 2.7l-1.3-2.5c1.4-.6 2.6-1.3 3.6-2.1-1-1.2-1.8-2.5-2.4-4l2.4-.9c.5 1.1 1.1 2.1 1.9 3 .9-1 1.6-2.2 2-3.6H4V12h4V9zm11 5h3l6 11h-3.3l-1.1-2.2h-6.2L16.3 25H13zm-.3 6.1h3.6l-1.8-3.7z" fill="#ffffff"/></svg>'
      ).toString("base64")
  ).resize({ width: 18, height: 18 });

  if (process.platform === "darwin") icon.setTemplateImage(true);
  tray = new Tray(icon);
  tray.setToolTip("翻译");
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: "打开翻译", click: showMainWindow },
      { label: "截图翻译", click: startScreenshotCapture },
      { label: "显示/隐藏悬浮窗", click: togglePopupVisibility },
      { type: "separator" },
      {
        label: "退出",
        click: () => {
          isQuitting = true;
          app.quit();
        }
      }
    ])
  );
  tray.on("double-click", showMainWindow);
}

function sendStatus(message, progress) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send("translation:status", { message, progress });
  }
  sendPopupStatus(message, progress);
}

function setUpdateState(patch) {
  updateState = { ...updateState, ...patch };
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send("update:status", updateState);
  }
}

function applyUpdateError(error) {
  const friendly = updateErrorMessage(error, app.getVersion());
  setUpdateState({
    ...friendly,
    progress: 0,
    version: app.getVersion()
  });
}

function setupAutoUpdater() {
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on("checking-for-update", () => {
    setUpdateState({
      status: "checking",
      message: "正在检查新版本…",
      progress: 0
    });
  });
  autoUpdater.on("update-available", (info) => {
    setUpdateState({
      status: "available",
      message: `发现新版本 v${info.version}`,
      version: info.version,
      progress: 0
    });
  });
  autoUpdater.on("update-not-available", () => {
    setUpdateState({
      status: "current",
      message: "当前已是最新版本",
      version: app.getVersion(),
      progress: 0
    });
  });
  autoUpdater.on("download-progress", (progress) => {
    const percent = Math.max(0, Math.min(100, Math.round(progress.percent || 0)));
    setUpdateState({
      status: "downloading",
      message: `正在下载更新 ${percent}%`,
      progress: percent
    });
  });
  autoUpdater.on("update-downloaded", (info) => {
    setUpdateState({
      status: "downloaded",
      message: `v${info.version} 已下载，可立即安装`,
      version: info.version,
      progress: 100
    });
  });
  autoUpdater.on("error", (error) => {
    applyUpdateError(error);
  });
  if (!app.isPackaged) {
    setUpdateState({
      status: "development",
      message: "开发版本不检查在线更新",
      version: app.getVersion()
    });
    return;
  }
  setTimeout(() => {
    void autoUpdater.checkForUpdates().catch(() => {});
  }, 8000);
}

function runPowerShellCopyShortcut() {
  return new Promise((resolve, reject) => {
    execFile(
      "powershell.exe",
      [
        "-NoProfile",
        "-Sta",
        "-Command",
        "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait('^c')"
      ],
      { windowsHide: true, timeout: 5000 },
      (error) => (error ? reject(error) : resolve())
    );
  });
}

function runNativeWindowsCopyShortcut() {
  const { uIOhook, UiohookKey } = require("uiohook-napi");
  suppressHookShortcutsUntil = Date.now() + 500;
  uIOhook.keyTap(UiohookKey.C, [UiohookKey.Ctrl]);
}

function runCopyShortcut() {
  if (process.platform === "darwin") {
    return new Promise((resolve, reject) => {
      execFile(
        "osascript",
        [
          "-e",
          'tell application "System Events" to keystroke "c" using {command down}'
        ],
        (error) => (error ? reject(error) : resolve())
      );
    });
  }
  if (process.platform === "win32") {
    try {
      runNativeWindowsCopyShortcut();
      return Promise.resolve();
    } catch (error) {
      return runPowerShellCopyShortcut().catch(() => {
        throw error;
      });
    }
  }
  return Promise.reject(new Error("当前平台暂不支持自动复制选中文本"));
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForClipboardText(attempts, interval) {
  for (let index = 0; index < attempts; index += 1) {
    await delay(interval);
    const text = clipboard.readText().trim();
    if (text) return text;
  }
  return "";
}

function restoreClipboardText(previousText) {
  if (previousText) clipboard.writeText(previousText);
  else clipboard.clear();
}

async function captureSelectedText() {
  const previousText = clipboard.readText();
  clipboard.clear();
  try {
    if (process.platform === "win32") {
      // RegisterHotKey fires on keydown. Let the user's shortcut keys return
      // to the up state before injecting Ctrl+C into the foreground app.
      await delay(140);
    }
    await runCopyShortcut();
    let selectedText = await waitForClipboardText(
      process.platform === "win32" ? 8 : 12,
      45
    );
    if (!selectedText && process.platform === "win32") {
      // A shortcut can still be physically held for a little longer. Retry
      // the native event once, then retain PowerShell as a compatibility path.
      await delay(100);
      try {
        runNativeWindowsCopyShortcut();
      } catch {
        // The explicit PowerShell fallback below handles missing native hooks.
      }
      selectedText = await waitForClipboardText(8, 45);
    }
    if (!selectedText && process.platform === "win32") {
      await runPowerShellCopyShortcut();
      selectedText = await waitForClipboardText(12, 45);
    }
    restoreClipboardText(previousText);
    if (!selectedText) {
      throw new Error(
        process.platform === "darwin"
          ? "未读取到选中文本。请授予“辅助功能”权限后重试。"
          : "未读取到选中文本，请先选中文字后再按快捷键。"
      );
    }
    showTranslationPopup({
      text: selectedText,
      source: "selection"
    });
  } catch (error) {
    restoreClipboardText(previousText);
    showTranslationPopup({
      error: error instanceof Error ? error.message : String(error),
      source: "selection"
    });
  }
}

function invokeShortcut(id, handler) {
  if (shortcutRecording) return;
  const now = Date.now();
  if (now - (shortcutTriggerTimes.get(id) || 0) < 220) return;
  shortcutTriggerTimes.set(id, now);
  setTimeout(() => {
    void Promise.resolve(handler()).catch((error) => {
      console.error(`Unable to run shortcut ${id}:`, error);
    });
  }, 0);
}

function registerShortcuts() {
  globalShortcut.unregisterAll();
  modifierShortcutHandlers = new Map();
  hookShortcutHandlers = [];
  modifierCandidate = null;
  modifierCandidateTainted = false;
  shortcutTriggerTimes.clear();
  const failures = [];
  const hookOnlyFallbacks = [];
  const shortcuts = [
    [store.data.selectionShortcut, captureSelectedText, "划词翻译"],
    [store.data.screenshotShortcut, startScreenshotCapture, "截图翻译"],
    [
      store.data.popupToggleShortcut,
      togglePopupVisibility,
      "显示/隐藏悬浮窗"
    ]
  ];

  for (const [shortcut, handler, label] of shortcuts) {
    const invoke = () => invokeShortcut(label, handler);
    if (isModifierOnlyShortcut(shortcut)) {
      const normalized = normalizeModifierShortcut(shortcut);
      if (modifierShortcutHandlers.has(normalized)) {
        failures.push(`${label}（${shortcut}）`);
      } else {
        modifierShortcutHandlers.set(normalized, { id: label, handler });
      }
      continue;
    }
    const registered = Boolean(
      shortcut && globalShortcut.register(shortcut, invoke)
    );
    const hookShortcut =
      process.platform === "win32"
        ? parseHookShortcut(shortcut, process.platform)
        : null;
    if (hookShortcut) {
      hookShortcutHandlers.push({
        ...hookShortcut,
        id: label,
        handler
      });
    }
    if (!registered && hookShortcut) {
      hookOnlyFallbacks.push({ label, shortcut });
    } else if (!registered) {
      failures.push(`${label}（${shortcut || "未设置"}）`);
    }
  }
  const needsGlobalMouseHook =
    modifierShortcutHandlers.size > 0 ||
    hookShortcutHandlers.length > 0 ||
    process.platform === "darwin";
  const hookAvailable = !needsGlobalMouseHook || ensureModifierHook();
  if (!hookAvailable) {
    for (const [shortcut, _handler, label] of shortcuts) {
      if (isModifierOnlyShortcut(shortcut)) {
        failures.push(`${label}（${shortcut}）`);
      }
    }
    for (const { label, shortcut } of hookOnlyFallbacks) {
      failures.push(`${label}（${shortcut}）`);
    }
    modifierShortcutHandlers.clear();
    hookShortcutHandlers = [];
  } else if (
    !needsGlobalMouseHook &&
    modifierHookStarted &&
    modifierHook
  ) {
    modifierHook.stop();
    modifierHookStarted = false;
  }
  return [...new Set(failures)];
}

function isMacDesktopPoint(point) {
  const script = `
ObjC.import("CoreGraphics");
function run(argv) {
  const pointerX = Number(argv[0]);
  const pointerY = Number(argv[1]);
  const windowList = ObjC.castRefToObject(
    $.CGWindowListCopyWindowInfo(1, 0)
  );
  for (let index = 0; index < windowList.count; index += 1) {
    const windowInfo = windowList.objectAtIndex(index);
    const layer = Number(
      ObjC.unwrap(windowInfo.objectForKey("kCGWindowLayer"))
    );
    if (layer !== 0) continue;
    const bounds = ObjC.deepUnwrap(
      windowInfo.objectForKey("kCGWindowBounds")
    );
    if (
      bounds.Width > 1 &&
      bounds.Height > 1 &&
      pointerX >= bounds.X &&
      pointerX < bounds.X + bounds.Width &&
      pointerY >= bounds.Y &&
      pointerY < bounds.Y + bounds.Height
    ) {
      return "false";
    }
  }
  return "true";
}`;
  return new Promise((resolve) => {
    execFile(
      "osascript",
      [
        "-l",
        "JavaScript",
        "-e",
        script,
        "--",
        String(point.x),
        String(point.y)
      ],
      { timeout: 1500 },
      (error, stdout) => {
        resolve(!error && String(stdout).trim() === "true");
      }
    );
  });
}

function dismissUnpinnedPopupFromGlobalClick() {
  const clickSerial = ++popupDismissClickSerial;
  if (
    process.platform !== "darwin" ||
    !popupWindow ||
    popupWindow.isDestroyed() ||
    store?.data.popupAlwaysOnTop ||
    overlayWindows.length
  ) {
    return;
  }
  const point = screen.getCursorScreenPoint();
  const bounds = popupWindow.getBounds();
  const clickedInside =
    point.x >= bounds.x &&
    point.x < bounds.x + bounds.width &&
    point.y >= bounds.y &&
    point.y < bounds.y + bounds.height;
  if (clickedInside) return;
  const presentation = popupWindowPresentation(
    process.platform,
    store.data.popupAlwaysOnTop
  );
  if (presentation.releaseOnOutsideClick) {
    popupWindow.setAlwaysOnTop(false);
  }
  setTimeout(async () => {
    if (
      clickSerial !== popupDismissClickSerial ||
      !popupWindow ||
      popupWindow.isDestroyed() ||
      store?.data.popupAlwaysOnTop
    ) {
      return;
    }
    if (await isMacDesktopPoint(point)) {
      if (
        clickSerial === popupDismissClickSerial &&
        popupWindow &&
        !popupWindow.isDestroyed()
      ) {
        popupWindow.destroy();
      }
    }
  }, 90);
}

function ensureModifierHook() {
  try {
    if (!modifierHook) {
      const { uIOhook, UiohookKey } = require("uiohook-napi");
      const namesByCode = new Map([
        [UiohookKey.Alt, "Alt"],
        [UiohookKey.AltRight, "Alt"],
        [UiohookKey.Ctrl, "Control"],
        [UiohookKey.CtrlRight, "Control"],
        [UiohookKey.Shift, "Shift"],
        [UiohookKey.ShiftRight, "Shift"],
        [UiohookKey.Meta, "Meta"],
        [UiohookKey.MetaRight, "Meta"]
      ]);
      uIOhook.on("keydown", (event) => {
        const modifier = namesByCode.get(event.keycode);
        const anotherModifierIsDown =
          (event.altKey && modifier !== "Alt") ||
          (event.ctrlKey && modifier !== "Control") ||
          (event.shiftKey && modifier !== "Shift") ||
          (event.metaKey && modifier !== "Meta");
        if (
          !modifierCandidate &&
          modifierShortcutHandlers.has(modifier) &&
          !anotherModifierIsDown
        ) {
          modifierCandidate = modifier;
          modifierCandidateTainted = false;
          return;
        }
        if (modifierCandidate && modifier !== modifierCandidate) {
          modifierCandidateTainted = true;
        }
        if (
          Date.now() >= suppressHookShortcutsUntil &&
          !shortcutRecording
        ) {
          for (const shortcut of hookShortcutHandlers) {
            const keycode = UiohookKey[shortcut.key];
            if (hookShortcutMatches(shortcut, event, keycode)) {
              invokeShortcut(shortcut.id, shortcut.handler);
            }
          }
        }
      });
      uIOhook.on("keyup", (event) => {
        const modifier = namesByCode.get(event.keycode);
        if (!modifierCandidate || modifier !== modifierCandidate) return;
        const candidate = modifierCandidate;
        const shouldTrigger = !modifierCandidateTainted;
        modifierCandidate = null;
        modifierCandidateTainted = false;
        if (shouldTrigger) {
          const shortcut = modifierShortcutHandlers.get(candidate);
          if (shortcut && !shortcutRecording) {
            setTimeout(
              () => invokeShortcut(shortcut.id, shortcut.handler),
              25
            );
          }
        }
      });
      uIOhook.on("mousedown", () => {
        if (modifierCandidate) modifierCandidateTainted = true;
        setTimeout(dismissUnpinnedPopupFromGlobalClick, 20);
      });
      modifierHook = uIOhook;
    }
    if (!modifierHookStarted) {
      modifierHook.start();
      modifierHookStarted = true;
    }
    return true;
  } catch (error) {
    console.error("Unable to start modifier shortcut hook:", error);
    return false;
  }
}

async function captureAllDisplays() {
  const displays = screen.getAllDisplays();
  const maximum = displays.reduce(
    (size, display) => ({
      width: Math.max(size.width, Math.ceil(display.size.width * display.scaleFactor)),
      height: Math.max(
        size.height,
        Math.ceil(display.size.height * display.scaleFactor)
      )
    }),
    { width: 1, height: 1 }
  );
  const sources = await desktopCapturer.getSources({
    types: ["screen"],
    thumbnailSize: maximum,
    fetchWindowIcons: false
  });

  return displays.map((display, index) => {
    const source =
      sources.find((candidate) => candidate.display_id === String(display.id)) ||
      sources[index];
    if (!source || source.thumbnail.isEmpty()) {
      throw new Error("无法读取屏幕画面，请检查屏幕录制权限");
    }
    return {
      display,
      image: source.thumbnail
    };
  });
}

async function startScreenshotCapture() {
  if (overlayWindows.length) return;
  enterBackgroundWindowMode();
  suppressMainWindowActivation = true;
  if (popupWindow && !popupWindow.isDestroyed()) popupWindow.destroy();
  try {
    const captures = await captureAllDisplays();
    overlayContexts = new Map();
    overlayWindows = captures.map(({ display, image }) => {
      const win = new BrowserWindow({
        x: display.bounds.x,
        y: display.bounds.y,
        width: display.bounds.width,
        height: display.bounds.height,
        frame: false,
        transparent: true,
        resizable: false,
        movable: false,
        show: false,
        alwaysOnTop: true,
        skipTaskbar: true,
        type: process.platform === "darwin" ? "panel" : undefined,
        acceptFirstMouse: true,
        enableLargerThanScreen: true,
        backgroundColor: "#00000000",
        webPreferences: {
          preload: path.join(__dirname, "preload.cjs"),
          contextIsolation: true,
          nodeIntegration: false,
          sandbox: false
        }
      });
      win.setAlwaysOnTop(true, "screen-saver");
      win.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
      const webContentsId = win.webContents.id;
      overlayContexts.set(webContentsId, { display, image });
      win.loadURL(rendererUrl("overlay.html"));
      win.once("ready-to-show", () => {
        if (win.isDestroyed()) return;
        win.setBounds(display.bounds, false);
        win.showInactive();
      });
      win.on("closed", () => {
        overlayContexts.delete(webContentsId);
        overlayWindows = overlayWindows.filter((item) => item !== win);
      });
      return win;
    });
  } catch (error) {
    suppressMainWindowActivation = false;
    showTranslationPopup({
      source: "screenshot",
      error: `无法开始截图选区：${error.message}`
    });
  }
}

function closeOverlays() {
  const windows = [...overlayWindows];
  overlayWindows = [];
  overlayContexts.clear();
  suppressMainWindowActivation = false;
  for (const win of windows) {
    if (!win.isDestroyed()) win.destroy();
  }
}

async function getOcrWorker(languages) {
  if (ocrWorker && ocrWorkerLanguages === languages) return ocrWorker;
  if (ocrWorker) {
    await ocrWorker.terminate();
    ocrWorker = null;
  }
  const { createWorker } = await import("tesseract.js");
  const cachePath = path.join(app.getPath("userData"), "ocr-cache");
  fs.mkdirSync(cachePath, { recursive: true });
  ocrWorker = await createWorker(languages, 1, {
    cachePath,
    logger: (event) => {
      if (event.status) {
        sendStatus(`OCR：${event.status}`, Math.round((event.progress || 0) * 100));
      }
    }
  });
  ocrWorkerLanguages = languages;
  return ocrWorker;
}

async function recognizeAndTranslate(imageBuffer) {
  showTranslationPopup({
    source: "screenshot",
    stage: "ocr",
    message: "正在识别截图文字…"
  });
  sendPopupStatus("正在识别截图文字…", 5);
  try {
    const worker = await getOcrWorker(store.data.ocrLanguages);
    const result = await worker.recognize(imageBuffer);
    const text = String(result?.data?.text || "").trim();
    if (!text) throw new Error("截图中没有识别到可翻译文字");
    showTranslationPopup({
      text,
      source: "screenshot"
    });
  } catch (error) {
    showTranslationPopup({
      source: "screenshot",
      error: `OCR 失败：${error.message}`
    });
  }
}

function registerIpc() {
  ipcMain.handle("shortcut:recording", (_event, active) => {
    shortcutRecording = Boolean(active);
    return true;
  });
  ipcMain.handle("settings:get", () => store.publicValue());
  ipcMain.handle("settings:save", (_event, patch) => {
    for (const [key, label] of [
      ["selectionShortcut", "划词翻译"],
      ["screenshotShortcut", "截图翻译"],
      ["popupToggleShortcut", "显示/隐藏悬浮窗"]
    ]) {
      if (isReservedMacShortcut(patch?.[key])) {
        throw new Error(
          `${patch[key]} 是 macOS 系统快捷键，请为${label}选择其他按键`
        );
      }
    }
    const previousPopupAlwaysOnTop = store.data.popupAlwaysOnTop;
    const settings = store.update(patch || {});
    translationCache.clear();
    enrichmentCache.clear();
    speechCache.clear();
    app.setLoginItemSettings({ openAtLogin: Boolean(settings.launchAtLogin) });
    if (
      previousPopupAlwaysOnTop !== settings.popupAlwaysOnTop &&
      popupWindow &&
      !popupWindow.isDestroyed()
    ) {
      popupWindow.destroy();
    }
    const shortcutFailures = registerShortcuts();
    if (store.data.provider === "ollama") {
      void ensureLocalOllamaRunning()
        .then(() => warmOllama(store.data))
        .catch(() => {});
    }
    return { settings, shortcutFailures };
  });
  ipcMain.handle("settings:clear-api-key", () =>
    store.update({ clearApiKey: true })
  );
  ipcMain.handle("clipboard:write", (_event, text) => {
    clipboard.writeText(String(text || ""));
    return true;
  });
  ipcMain.handle("codex:login", (_event, patch) =>
    codexLogin({ ...store.data, ...(patch || {}) })
  );
  ipcMain.handle("codex:status", (_event, patch) =>
    codexLoginStatus({ ...store.data, ...(patch || {}) })
  );
  ipcMain.handle("codex:models", (_event, patch) =>
    codexModels({ ...store.data, ...(patch || {}) })
  );
  ipcMain.handle("ollama:models", (_event, patch) =>
    listOllamaModels({ ...store.data, ...(patch || {}) })
  );
  ipcMain.handle("speech:synthesize", async (_event, payload) => {
    if (
      store.data.speechProvider !== "mambo" ||
      !/[\u3400-\u9fff]/.test(String(payload?.text || ""))
    ) {
      return { ok: false, fallback: true };
    }
    try {
      const result = await cachedMamboSpeech(payload?.text, store.data);
      return { ok: true, ...result };
    } catch (error) {
      return {
        ok: false,
        fallback: true,
        error: error instanceof Error ? error.message : String(error)
      };
    }
  });
  ipcMain.handle("speech:prepare", () => ({
    ok: false,
    deferred: true
  }));
  ipcMain.handle("speech:test", async (_event, patch) => {
    const settings = { ...store.data, ...(patch || {}) };
    const startedAt = Date.now();
    await ensureMamboRunning(settings);
    if (!(await mamboHealth(settings))) {
      throw new Error("曼波语音引擎没有响应");
    }
    await stopMambo(settings);
    return {
      ok: true,
      latencyMs: Date.now() - startedAt,
      message: "曼波语音引擎可用，检查后已关闭"
    };
  });
  ipcMain.handle("translation:translate", async (_event, payload) => {
    const settings = { ...store.data, ...(payload?.overrides || {}) };
    const key = translationCacheKey(payload?.text, settings);
    const cached = cacheRead(translationCache, key);
    if (cached) return { ...cached, cacheHit: true };
    const result = await translateText(
      payload?.text,
      settings,
      store.apiKey()
    );
    cacheWrite(translationCache, key, result, 120);
    return result;
  });
  ipcMain.handle("translation:enrich", async (_event, payload) => {
    const settings = { ...store.data, ...(payload?.overrides || {}) };
    const key = `${translationCacheKey(payload?.text, settings)}\u241f${payload?.translation || ""}`;
    const cached = cacheRead(enrichmentCache, key);
    if (cached) return cached;
    const result = await enrichTranslation(
      payload?.text,
      payload?.translation,
      settings,
      store.apiKey()
    );
    cacheWrite(enrichmentCache, key, result, 80);
    return result;
  });
  ipcMain.handle("translation:translate-technical", async (_event, payload) => {
    const settings = { ...store.data, ...(payload?.overrides || {}) };
    return translateTechnicalText(payload?.text, settings, store.apiKey());
  });
  ipcMain.handle("translation:test-provider", async (_event, patch) => {
    const settings = { ...store.data, ...(patch || {}) };
    const candidateKey =
      typeof patch?.apiKey === "string" && patch.apiKey.trim()
        ? patch.apiKey.trim()
        : store.apiKey();
    return testProvider(settings, candidateKey);
  });
  ipcMain.handle("capture:start", async () => {
    await startScreenshotCapture();
    return true;
  });
  ipcMain.handle("popup:resize", (event, requestedHeight) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (!win || win !== popupWindow) return false;
    const height = Math.max(210, Math.min(680, Math.round(requestedHeight)));
    const [width] = win.getSize();
    const currentBounds = win.getBounds();
    const display = screen.getDisplayMatching(currentBounds);
    const maximumY = display.workArea.y + display.workArea.height - height - 10;
    win.setBounds({
      x: currentBounds.x,
      y: Math.max(
        display.workArea.y + 10,
        Math.min(currentBounds.y, maximumY)
      ),
      width,
      height
    });
    return true;
  });
  ipcMain.handle("popup:ready", (event) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (!win || win !== popupWindow) return null;
    return pendingPopupPayload;
  });
  ipcMain.handle("popup:close", (event) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (win && win === popupWindow) win.destroy();
    return true;
  });
  ipcMain.handle("popup:open-main", (_event, payload) => {
    showMainWindow();
    mainWindow.webContents.send("translation:hydrate", payload);
    if (popupWindow && !popupWindow.isDestroyed()) popupWindow.destroy();
    return true;
  });
  ipcMain.handle("overlay:context", (event) => {
    const context = overlayContexts.get(event.sender.id);
    if (!context) throw new Error("截图上下文已失效");
    return {
      displayId: context.display.id,
      imageDataUrl: context.image.toDataURL(),
      width: context.display.bounds.width,
      height: context.display.bounds.height
    };
  });
  ipcMain.handle("overlay:complete", async (event, rect) => {
    const context = overlayContexts.get(event.sender.id);
    if (!context) return false;
    const imageSize = context.image.getSize();
    const xScale = imageSize.width / context.display.bounds.width;
    const yScale = imageSize.height / context.display.bounds.height;
    const crop = {
      x: Math.max(0, Math.round(rect.x * xScale)),
      y: Math.max(0, Math.round(rect.y * yScale)),
      width: Math.max(1, Math.round(rect.width * xScale)),
      height: Math.max(1, Math.round(rect.height * yScale))
    };
    crop.x = Math.min(crop.x, imageSize.width - 1);
    crop.y = Math.min(crop.y, imageSize.height - 1);
    crop.width = Math.min(crop.width, imageSize.width - crop.x);
    crop.height = Math.min(crop.height, imageSize.height - crop.y);
    const buffer = context.image.crop(crop).toPNG();
    closeOverlays();
    void recognizeAndTranslate(buffer);
    return true;
  });
  ipcMain.handle("overlay:cancel", () => {
    closeOverlays();
    return true;
  });
  ipcMain.handle("system:open-permission-settings", async (_event, kind) => {
    if (process.platform === "darwin") {
      const pane =
        kind === "screen"
          ? "Privacy_ScreenCapture"
          : "Privacy_Accessibility";
      await shell.openExternal(
        `x-apple.systempreferences:com.apple.preference.security?${pane}`
      );
      return true;
    }
    if (process.platform === "win32") {
      await shell.openExternal("ms-settings:privacy");
      return true;
    }
    return false;
  });
  ipcMain.handle("system:open-ollama-download", async () => {
    await shell.openExternal("https://ollama.com/download");
    return true;
  });
  ipcMain.handle("app:info", () => ({
    version: app.getVersion(),
    platform: process.platform,
    isPackaged: app.isPackaged
  }));
  ipcMain.handle("update:get-status", () => updateState);
  ipcMain.handle("update:check", async () => {
    if (!app.isPackaged) return updateState;
    try {
      await autoUpdater.checkForUpdates();
    } catch (error) {
      applyUpdateError(error);
    }
    return updateState;
  });
  ipcMain.handle("update:download", async () => {
    if (!app.isPackaged) return updateState;
    try {
      await autoUpdater.downloadUpdate();
    } catch (error) {
      applyUpdateError(error);
    }
    return updateState;
  });
  ipcMain.handle("update:install", () => {
    if (updateState.status !== "downloaded") return false;
    setImmediate(() => autoUpdater.quitAndInstall(false, true));
    return true;
  });
}

app.whenReady().then(() => {
  store = new SettingsStore(app.getPath("userData"));
  repairStoredShortcuts();
  if (process.env.LINGUABRIDGE_SMOKE_MODIFIER_SHORTCUT) {
    store.data.selectionShortcut =
      process.env.LINGUABRIDGE_SMOKE_MODIFIER_SHORTCUT;
  }
  if (store.data.provider === "ollama") {
    void ensureLocalOllamaRunning()
      .then(() => warmOllama(store.data))
      .catch(() => {});
  }
  startMacService(() => store.data);
  registerIpc();
  createMainWindow();
  createTray();
  setupAutoUpdater();
  if (process.env.LINGUABRIDGE_POPUP_SCREENSHOT_PATH) {
    const smokePopupText = String(
      process.env.LINGUABRIDGE_SMOKE_POPUP_TEXT || ""
    ).trim();
    showTranslationPopup(
      smokePopupText
        ? {
            text: smokePopupText,
            source: "selection"
          }
        : {
          text: "The event loop dispatches callbacks after the call stack is empty, allowing asynchronous work from the task queue to continue without blocking the current function.",
          source: "selection",
          result: {
        sourceLanguage: "en",
        targetLanguage: "zh-CN",
        translation: "调用栈清空后，事件循环会分派回调函数执行。",
        phonetic: "",
        pronunciationText:
          "The event loop dispatches callbacks after the call stack is empty, allowing asynchronous work from the task queue to continue without blocking the current function.",
        explanation:
          "在 JavaScript 等运行时中，同步代码先在调用栈执行；调用栈清空后，事件循环从任务队列调度回调。长时间同步任务会阻塞后续回调，因此开发者通常会把网络请求、定时器和文件读写等工作交给异步 API，并避免在主线程执行耗时计算。",
        terms: [
          {
            term: "event loop",
            translation: "事件循环",
            definition: "检查调用栈和任务队列，并调度待处理任务的运行机制。",
            category: "运行时机制"
          },
          {
            term: "call stack",
            translation: "调用栈",
            definition: "记录当前执行函数及调用关系的栈式数据结构。",
            category: "数据结构"
          },
          {
            term: "callback",
            translation: "回调函数",
            definition: "在某项操作完成或指定事件发生后，由运行时调用的函数。",
            category: "编程模式"
          },
          {
            term: "task queue",
            translation: "任务队列",
            definition: "保存等待事件循环调度执行的异步任务。",
            category: "运行时机制"
          }
        ],
        alternatives: []
          }
        }
    );
  }
  const failures = registerShortcuts();
  if (failures.length || startupShortcutNotice) {
    mainWindow.webContents.once("did-finish-load", () => {
      const messages = [];
      if (startupShortcutNotice) messages.push(startupShortcutNotice);
      if (failures.length) {
        messages.push(`快捷键注册失败：${failures.join("、")}`);
      }
      sendStatus(messages.join("；"), 0);
    });
  }
  app.on("activate", () => {
    if (suppressMainWindowActivation) return;
    const popupIsVisible =
      popupWindow && !popupWindow.isDestroyed() && popupWindow.isVisible();
    const overlayIsVisible = overlayWindows.some(
      (window) => !window.isDestroyed() && window.isVisible()
    );
    if (!popupIsVisible && !overlayIsVisible) showMainWindow();
  });
});

app.on("before-quit", async () => {
  isQuitting = true;
  globalShortcut.unregisterAll();
  stopMacService();
  await stopMambo(store?.data || {});
  if (modifierHookStarted && modifierHook) {
    modifierHook.stop();
    modifierHookStarted = false;
  }
  closeOverlays();
  if (popupWindow && !popupWindow.isDestroyed()) popupWindow.destroy();
  if (ocrWorker) {
    await ocrWorker.terminate();
    ocrWorker = null;
  }
});

app.on("window-all-closed", () => {});
