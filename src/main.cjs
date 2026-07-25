const path = require("node:path");
const fs = require("node:fs");
const { execFile } = require("node:child_process");
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
const { isReservedMacShortcut } = require("./lib/shortcuts.cjs");
const {
  codexLogin,
  codexLoginStatus,
  codexModels,
  enrichTranslation,
  testProvider,
  translateText,
  warmOllama
} = require("./lib/translator.cjs");
const {
  ensureMamboRunning,
  mamboHealth,
  synthesizeMambo
} = require("./lib/speech.cjs");

let mainWindow;
let popupWindow;
let pendingPopupPayload = null;
let tray;
let store;
let isQuitting = false;
let suppressMainWindowActivation = false;
let mamboHealthTimer = null;
let startupShortcutNotice = "";
let overlayWindows = [];
let overlayContexts = new Map();
let ocrWorker = null;
let ocrWorkerLanguages = "";
let modifierHook = null;
let modifierHookStarted = false;
let modifierShortcutHandlers = new Map();
let modifierCandidate = null;
let modifierCandidateTainted = false;
let popupDismissClickSerial = 0;
let popupManuallyHidden = false;
const translationCache = new Map();
const enrichmentCache = new Map();
const speechCache = new Map();
const speechInFlight = new Map();

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

function maintainMamboService() {
  if (
    process.platform !== "darwin" ||
    !store ||
    store.data.speechProvider !== "mambo"
  ) {
    return;
  }
  void ensureMamboRunning(store.data).catch((error) => {
    console.error("Unable to keep Mambo speech service running:", error);
  });
}

function startMamboBackgroundService() {
  maintainMamboService();
  if (mamboHealthTimer) return;
  mamboHealthTimer = setInterval(maintainMamboService, 45_000);
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
    ollama: `${settings.ollamaUrl}|${settings.ollamaModel}`,
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
      setTimeout(async () => {
        const image = await mainWindow.capturePage();
        fs.writeFileSync(
          process.env.LINGUABRIDGE_SCREENSHOT_PATH,
          image.toPNG()
        );
        isQuitting = true;
        app.quit();
      }, process.env.LINGUABRIDGE_SMOKE_AUTOTYPE ? 2200 : 1200);
    });
  }
  mainWindow.on("close", (event) => {
    if (!isQuitting) {
      event.preventDefault();
      mainWindow.hide();
      enterBackgroundWindowMode();
    }
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
  const area = display.workArea;
  const preferredX = cursor.x + 18;
  const preferredY = cursor.y + 18;
  return {
    x: Math.max(area.x + 10, Math.min(preferredX, area.x + area.width - width - 10)),
    y: Math.max(
      area.y + 10,
      Math.min(preferredY, area.y + area.height - height - 10)
    )
  };
}

function showTranslationPopup(payload) {
  enterBackgroundWindowMode();
  suppressMainWindowActivation = true;
  pendingPopupPayload = payload;
  popupManuallyHidden = false;
  const width = 480;
  const height = payload.result ? 520 : 270;
  const position = popupBounds(width, height);

  if (!popupWindow || popupWindow.isDestroyed()) {
    popupWindow = new BrowserWindow({
      ...position,
      width,
      height,
      minWidth: 420,
      minHeight: 210,
      maxWidth: 560,
      maxHeight: 680,
      type:
        process.platform === "darwin" && store.data.popupAlwaysOnTop
          ? "panel"
          : undefined,
      frame: false,
      transparent: true,
      resizable: false,
      show: false,
      skipTaskbar: true,
      acceptFirstMouse: true,
      alwaysOnTop: Boolean(store.data.popupAlwaysOnTop),
      hasShadow: false,
      webPreferences: {
        preload: path.join(__dirname, "preload.cjs"),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false
      }
    });
    popupWindow.loadURL(rendererUrl("popup.html"));
    if (store.data.popupAlwaysOnTop) {
      popupWindow.setAlwaysOnTop(true, "floating");
    }
    popupWindow.once("ready-to-show", () => {
      popupWindow.showInactive();
      popupWindow.moveTop();
      setTimeout(() => {
        suppressMainWindowActivation = false;
      }, 350);
    });
    popupWindow.webContents.once("did-finish-load", () => {
      if (process.env.LINGUABRIDGE_POPUP_SCREENSHOT_PATH) {
        setTimeout(async () => {
          const image = await popupWindow.capturePage();
          fs.writeFileSync(
            process.env.LINGUABRIDGE_POPUP_SCREENSHOT_PATH,
            image.toPNG()
          );
          isQuitting = true;
          app.quit();
        }, 1200);
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
  popupWindow.setAlwaysOnTop(Boolean(store.data.popupAlwaysOnTop), "floating");
  popupWindow.showInactive();
  popupWindow.moveTop();
  popupWindow.webContents.send("popup:start", payload);
  setTimeout(() => {
    suppressMainWindowActivation = false;
  }, 350);
}

function togglePopupVisibility() {
  if (popupWindow && !popupWindow.isDestroyed()) {
    if (!popupManuallyHidden) {
      popupManuallyHidden = true;
      popupWindow.hide();
      return;
    }
    enterBackgroundWindowMode();
    suppressMainWindowActivation = true;
    popupManuallyHidden = false;
    popupWindow.setAlwaysOnTop(
      Boolean(store.data.popupAlwaysOnTop),
      "floating"
    );
    popupWindow.showInactive();
    popupWindow.moveTop();
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

function runCopyShortcut() {
  return new Promise((resolve, reject) => {
    if (process.platform === "darwin") {
      execFile(
        "osascript",
        [
          "-e",
          'tell application "System Events" to keystroke "c" using {command down}'
        ],
        (error) => (error ? reject(error) : resolve())
      );
      return;
    }

    if (process.platform === "win32") {
      execFile(
        "powershell.exe",
        [
          "-NoProfile",
          "-Sta",
          "-Command",
          "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait('^c')"
        ],
        { windowsHide: true },
        (error) => (error ? reject(error) : resolve())
      );
      return;
    }

    reject(new Error("当前平台暂不支持自动复制选中文本"));
  });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function captureSelectedText() {
  const previousText = clipboard.readText();
  clipboard.clear();
  try {
    await runCopyShortcut();
    let selectedText = "";
    for (let index = 0; index < 12; index += 1) {
      await delay(45);
      selectedText = clipboard.readText().trim();
      if (selectedText) break;
    }
    if (previousText) clipboard.writeText(previousText);
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
    if (previousText) clipboard.writeText(previousText);
    showTranslationPopup({ error: error.message, source: "selection" });
  }
}

function registerShortcuts() {
  globalShortcut.unregisterAll();
  modifierShortcutHandlers = new Map();
  modifierCandidate = null;
  modifierCandidateTainted = false;
  const failures = [];
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
    if (isModifierOnlyShortcut(shortcut)) {
      const normalized = normalizeModifierShortcut(shortcut);
      if (modifierShortcutHandlers.has(normalized)) {
        failures.push(`${label}（${shortcut}）`);
      } else {
        modifierShortcutHandlers.set(normalized, handler);
      }
      continue;
    }
    if (!shortcut || !globalShortcut.register(shortcut, handler)) {
      failures.push(`${label}（${shortcut || "未设置"}）`);
    }
  }
  const needsGlobalMouseHook =
    modifierShortcutHandlers.size > 0 || process.platform === "darwin";
  if (needsGlobalMouseHook && !ensureModifierHook()) {
    for (const [shortcut, _handler, label] of shortcuts) {
      if (isModifierOnlyShortcut(shortcut)) {
        failures.push(`${label}（${shortcut}）`);
      }
    }
    modifierShortcutHandlers.clear();
  } else if (
    !needsGlobalMouseHook &&
    modifierHookStarted &&
    modifierHook
  ) {
    modifierHook.stop();
    modifierHookStarted = false;
  }
  return failures;
}

function normalizeModifierShortcut(value) {
  const shortcut = String(value || "").toLowerCase();
  if (["alt", "option"].includes(shortcut)) return "Alt";
  if (["control", "ctrl"].includes(shortcut)) return "Control";
  if (shortcut === "shift") return "Shift";
  if (["command", "cmd", "meta", "super"].includes(shortcut)) return "Meta";
  return "";
}

function isModifierOnlyShortcut(value) {
  return Boolean(normalizeModifierShortcut(value));
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
      });
      uIOhook.on("keyup", (event) => {
        const modifier = namesByCode.get(event.keycode);
        if (!modifierCandidate || modifier !== modifierCandidate) return;
        const candidate = modifierCandidate;
        const shouldTrigger = !modifierCandidateTainted;
        modifierCandidate = null;
        modifierCandidateTainted = false;
        if (shouldTrigger) {
          const handler = modifierShortcutHandlers.get(candidate);
          if (handler && !mainWindow?.isFocused()) {
            setTimeout(() => void handler(), 25);
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
      void warmOllama(store.data).catch(() => {});
    }
    if (store.data.speechProvider === "mambo") {
      startMamboBackgroundService();
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
  ipcMain.handle("speech:prepare", async (_event, payload) => {
    if (
      store.data.speechProvider !== "mambo" ||
      !/[\u3400-\u9fff]/.test(String(payload?.text || "")) ||
      String(payload?.text || "").length > 600
    ) {
      return { ok: false, fallback: true };
    }
    try {
      await cachedMamboSpeech(payload?.text, store.data);
      return { ok: true };
    } catch (error) {
      return {
        ok: false,
        fallback: true,
        error: error instanceof Error ? error.message : String(error)
      };
    }
  });
  ipcMain.handle("speech:test", async (_event, patch) => {
    const settings = { ...store.data, ...(patch || {}) };
    const startedAt = Date.now();
    await ensureMamboRunning(settings);
    if (!(await mamboHealth(settings))) {
      throw new Error("曼波语音引擎没有响应");
    }
    return {
      ok: true,
      latencyMs: Date.now() - startedAt,
      message: "曼波语音引擎已连接"
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
      y: Math.min(currentBounds.y, maximumY),
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
}

app.whenReady().then(() => {
  store = new SettingsStore(app.getPath("userData"));
  repairStoredShortcuts();
  if (process.env.LINGUABRIDGE_SMOKE_MODIFIER_SHORTCUT) {
    store.data.selectionShortcut =
      process.env.LINGUABRIDGE_SMOKE_MODIFIER_SHORTCUT;
  }
  if (store.data.provider === "ollama") {
    void warmOllama(store.data).catch(() => {});
  }
  if (store.data.speechProvider === "mambo") {
    startMamboBackgroundService();
  }
  registerIpc();
  createMainWindow();
  createTray();
  if (process.env.LINGUABRIDGE_POPUP_SCREENSHOT_PATH) {
    showTranslationPopup({
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
    });
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
  if (mamboHealthTimer) {
    clearInterval(mamboHealthTimer);
    mamboHealthTimer = null;
  }
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
