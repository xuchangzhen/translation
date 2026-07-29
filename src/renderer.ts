import "./styles.css";
import { speakText } from "./speech";

const LANGUAGES = [
  ["auto", "自动检测"],
  ["zh-CN", "简体中文"],
  ["zh-TW", "繁体中文"],
  ["en", "English"],
  ["ja", "日本語"],
  ["ko", "한국어"],
  ["de", "Deutsch"],
  ["fr", "Français"],
  ["es", "Español"],
  ["ru", "Русский"]
] as const;

let settings: AppSettings;
let lastResult: TranslationResult | null = null;
let isTranslating = false;
let realtimeTimer: number | undefined;
let queuedRealtime = false;
let lastTranslatedText = "";
let codexCatalog: CodexModel[] = [];
let codexCatalogLoading = false;
let ollamaCatalog: OllamaModel[] = [];
let ollamaCatalogLoading = false;
let currentPlatform = "";
let appVersion = "";

const app = document.querySelector<HTMLDivElement>("#app")!;

function languageOptions(selected: string, includeAuto = true) {
  return LANGUAGES.filter(([value]) => includeAuto || value !== "auto")
    .map(
      ([value, label]) =>
        `<option value="${value}" ${value === selected ? "selected" : ""}>${label}</option>`
    )
    .join("");
}

function isSingleEnglishWord(value: string) {
  return /^[A-Za-z][A-Za-z.'’_-]*$/.test(value.trim());
}

function codexModelOptions(selected: string) {
  const options = [
    '<option value="">自动选择 Codex 推荐模型</option>',
    ...codexCatalog.map(
      (model) =>
        `<option value="${escapeAttribute(model.id)}" ${model.id === selected ? "selected" : ""}>${escapeAttribute(model.name)} · ${escapeAttribute(model.id)}</option>`
    )
  ];
  if (selected && !codexCatalog.some((model) => model.id === selected)) {
    options.splice(
      1,
      0,
      `<option value="${escapeAttribute(selected)}" selected>${escapeAttribute(selected)}（当前保存）</option>`
    );
  }
  return options.join("");
}

function ollamaModelLabel(model: OllamaModel) {
  const details = [model.parameterSize, model.quantization]
    .filter(Boolean)
    .join(" · ");
  return details ? `${model.name} · ${details}` : model.name;
}

function ollamaModelOptions(selected: string, purpose: "translation" | "technical") {
  const sorted = [...ollamaCatalog].sort((left, right) => {
    const preferred = (name: string) =>
      purpose === "translation"
        ? /translate|gemma/i.test(name)
        : /qwen|coder|code/i.test(name);
    return Number(preferred(right.name)) - Number(preferred(left.name)) ||
      left.name.localeCompare(right.name);
  });
  const options = sorted.map(
    (model) =>
      `<option value="${escapeAttribute(model.name)}" ${model.name === selected ? "selected" : ""}>${escapeAttribute(ollamaModelLabel(model))}</option>`
  );
  if (selected && !sorted.some((model) => model.name === selected)) {
    options.unshift(
      `<option value="${escapeAttribute(selected)}" selected>${escapeAttribute(selected)}（当前保存）</option>`
    );
  }
  return options.join("");
}

function renderShell() {
  app.innerHTML = `
    <div class="app-shell">
      <aside class="rail">
        <div class="brand-mark" aria-label="翻译">译</div>
        <nav>
          <button class="rail-button active" data-view="translate" title="翻译">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h10M9 3v2m3 0c-.7 4.1-3.3 7.2-7 9m2.5-5c1.2 2.1 3 3.8 5.5 5M14 20l4-9 4 9m-6.7-3h5.4"/></svg>
          </button>
          <button class="rail-button" data-view="settings" title="设置">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1a1.7 1.7 0 0 0 1.9.3A1.7 1.7 0 0 0 10 3v-.2h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z"/></svg>
          </button>
        </nav>
        <div class="provider-dot" title="当前翻译服务"><span></span></div>
      </aside>

      <main class="workspace">
        <div class="window-drag-region" aria-hidden="true"></div>
        <section id="translate-view" class="view active">
          <header class="topbar">
            <div>
              <p class="eyebrow">翻译</p>
              <h1>选中，即译。</h1>
            </div>
            <div class="quick-actions">
              <button id="capture-button" class="button subtle">
                <svg viewBox="0 0 24 24"><path d="M4 7V4h3M17 4h3v3M20 17v3h-3M7 20H4v-3M8 9h8v6H8z"/></svg>
                截图翻译
              </button>
              <button id="open-settings" class="icon-button" title="设置">
                <svg viewBox="0 0 24 24"><path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z"/><path d="M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.4-2.4 1A7 7 0 0 0 15 6.2L14.7 4h-4L10.3 6.2a7 7 0 0 0-1.6.9l-2.3-1-2 3.4 1.9 1.5a7 7 0 0 0 0 2l-2 1.5 2 3.4 2.4-1a7 7 0 0 0 1.6.9l.4 2.2h4l.4-2.2a7 7 0 0 0 1.6-.9l2.3 1 2-3.4-1.9-1.5a7 7 0 0 0 .1-1Z"/></svg>
              </button>
            </div>
          </header>

          <div class="shortcut-strip">
            <span><kbd>${settings.selectionShortcut}</kbd> 翻译选中文字</span>
            <span class="divider"></span>
            <span><kbd>${settings.screenshotShortcut}</kbd> 识别画面文字</span>
            <span class="divider"></span>
            <span><kbd>${settings.popupToggleShortcut}</kbd> 显示/隐藏悬浮窗</span>
            <span id="provider-pill" class="provider-pill">${providerLabel(settings.provider)}</span>
          </div>

          <section class="translator-card">
            <div class="language-bar">
              <select id="source-language" aria-label="源语言">${languageOptions(settings.sourceLanguage)}</select>
              <button id="swap-languages" class="swap-button" title="交换语言">
                <svg viewBox="0 0 24 24"><path d="m7 7-3 3 3 3M4 10h13M17 17l3-3-3-3M20 14H7"/></svg>
              </button>
              <select id="target-language" aria-label="目标语言">${languageOptions(settings.targetLanguage, false)}</select>
            </div>

            <div class="translation-grid">
              <div class="source-pane">
                <label for="source-text">原文</label>
                <textarea id="source-text" maxlength="12000" placeholder="输入文字，或在任意软件中选中文字后按全局快捷键…"></textarea>
                <div class="pane-footer">
                  <span id="character-count">0 / 12,000</span>
                  <button id="clear-button" class="text-button">清空</button>
                </div>
              </div>
              <div class="result-pane" id="result-pane">
                <div id="empty-result" class="empty-result">
                  <div class="empty-glyph">A<span>译</span></div>
                  <p>译文、音标与技术语境会显示在这里</p>
                </div>
                <div id="loading-result" class="loading-result hidden">
                  <div class="loading-line wide"></div>
                  <div class="loading-line"></div>
                  <div class="loading-line short"></div>
                  <p id="loading-message">正在理解上下文…</p>
                </div>
                <div id="result-content" class="result-content hidden"></div>
              </div>
            </div>

            <div class="translate-footer">
              <div id="status-line" class="status-line" role="status"></div>
              <button id="translate-button" class="button primary">
                立即翻译
                <svg viewBox="0 0 24 24"><path d="m9 18 6-6-6-6"/></svg>
              </button>
            </div>
          </section>
        </section>

        <section id="settings-view" class="view">
          ${settingsMarkup()}
        </section>
      </main>
    </div>
  `;
  bindEvents();
  refreshProviderFields();
  refreshSpeechFields();
  void window.lingua.getUpdateStatus().then(renderUpdateStatus);
  if (settings.provider === "codex") void loadCodexModels();
  if (settings.provider === "ollama") void loadOllamaModels();
}

function providerLabel(provider: Provider) {
  return {
    ollama: "Mac mini · Ollama",
    google: "Google 翻译",
    openai: "OpenAI API",
    compatible: "OpenAI 兼容服务",
    codex: "ChatGPT · Codex"
  }[provider];
}

function settingsMarkup() {
  return `
    <header class="settings-header">
      <div>
        <p class="eyebrow">PREFERENCES</p>
        <h1>设置</h1>
        <p>所有设置保存在本机；API Key 使用系统安全存储加密。</p>
      </div>
      <button id="back-to-translate" class="button subtle">返回翻译</button>
    </header>

    <div class="settings-layout">
      <section class="settings-section">
        <div class="section-heading">
          <span class="section-number">01</span>
          <div><h2>翻译引擎</h2><p>Windows 可通过局域网使用 Mac mini 的模型。</p></div>
        </div>
        <div class="setting-card">
          <label class="field span-2">
            <span>服务类型</span>
            <select id="setting-provider">
              <option value="ollama" ${settings.provider === "ollama" ? "selected" : ""}>Ollama（推荐本地/局域网）</option>
              <option value="google" ${settings.provider === "google" ? "selected" : ""}>Google Cloud Translation</option>
              <option value="codex" ${settings.provider === "codex" ? "selected" : ""}>ChatGPT / Codex 额度（实验）</option>
              <option value="openai" ${settings.provider === "openai" ? "selected" : ""}>OpenAI Responses API</option>
              <option value="compatible" ${settings.provider === "compatible" ? "selected" : ""}>OpenAI 兼容接口（LM Studio 等）</option>
            </select>
          </label>
          <div class="provider-fields span-2" data-provider="ollama">
            <label class="field"><span>Ollama 地址</span><input id="setting-ollama-url" value="${escapeAttribute(settings.ollamaUrl)}" placeholder="http://192.168.1.10:11434"></label>
            <label class="field">
              <span>技术解析模型</span>
              <select id="setting-ollama-model">${ollamaModelOptions(settings.ollamaModel, "technical")}</select>
            </label>
            <label class="field">
              <span>主翻译模型</span>
              <div class="input-action">
                <select id="setting-ollama-translation-model">${ollamaModelOptions(settings.ollamaTranslationModel, "translation")}</select>
                <button id="refresh-ollama-models" class="text-button" type="button">刷新列表</button>
              </div>
              <small id="ollama-model-status" class="field-help">${ollamaCatalog.length ? `已读取 ${ollamaCatalog.length} 个已安装模型` : "将从当前 Ollama 服务读取已安装模型"}</small>
            </label>
            <label class="toggle-row">
              <span><strong>TranslateGemma 主翻译</strong><small>专用模型负责译文；缺失或失败时自动回退 Qwen</small></span>
              <input id="setting-use-translategemma" type="checkbox" ${settings.useTranslateGemma ? "checked" : ""}>
            </label>
            <div class="ollama-setup span-2">
              <div>
                <strong>Mac mini 后台服务无需终端窗口</strong>
                <p>Mac 版会静默启动 Ollama，并在 19876 端口提供局域网代理。Windows 可继续填写 Mac mini 的 11434 地址；连接失败时会自动尝试后台代理。</p>
              </div>
              <div class="ollama-setup-actions">
                <button id="open-ollama-download" class="button subtle" type="button">下载 Ollama</button>
                <button id="copy-ollama-command" class="text-button" type="button">复制模型安装命令</button>
              </div>
            </div>
          </div>
          <div class="provider-fields span-2" data-provider="google">
            <div class="ollama-setup span-2">
              <div>
                <strong>Google Cloud Translation API</strong>
                <p>适合追求低延迟和广泛语言覆盖的场景，需要 Google Cloud API Key，并会产生云端用量费用。技术术语解释仍由 Mac mini 的 Qwen 完成。</p>
              </div>
            </div>
          </div>
          <div class="provider-fields span-2" data-provider="openai">
            <label class="field"><span>API 地址</span><input id="setting-openai-url" value="${escapeAttribute(settings.openaiBaseUrl)}"></label>
            <label class="field"><span>模型</span><input id="setting-openai-model" value="${escapeAttribute(settings.openaiModel)}"></label>
          </div>
          <div class="provider-fields span-2" data-provider="compatible">
            <label class="field"><span>兼容接口地址</span><input id="setting-compatible-url" value="${escapeAttribute(settings.compatibleBaseUrl)}"></label>
            <label class="field"><span>模型</span><input id="setting-compatible-model" value="${escapeAttribute(settings.compatibleModel)}"></label>
          </div>
          <div class="provider-fields span-2" data-provider="codex">
            <label class="field"><span>Codex 可执行文件</span><input id="setting-codex-path" value="${escapeAttribute(settings.codexPath)}" placeholder="留空自动检测"></label>
            <label class="field">
              <span>Codex 模型</span>
              <div class="input-action">
                <select id="setting-codex-model">${codexModelOptions(settings.codexModel)}</select>
                <button id="refresh-codex-models" class="text-button" type="button">刷新列表</button>
              </div>
              <small id="codex-model-status" class="field-help">${codexCatalog.length ? `已读取当前账号的 ${codexCatalog.length} 个模型` : "打开此服务后会读取当前登录账号可用的模型"}</small>
            </label>
            <div class="codex-note span-2">
              <strong>账号由官方 Codex 客户端管理</strong>
              <p>本应用不会读取或保存 OAuth token。单次翻译会启动一次只读、临时的 <code>codex exec</code>，因此延迟通常高于 API 或 Ollama。</p>
              <button id="codex-login" class="button subtle" type="button">登录 ChatGPT</button>
              <button id="codex-status" class="text-button" type="button">检查登录状态</button>
              <span id="codex-login-result"></span>
            </div>
          </div>
          <label id="api-key-field" class="field span-2">
            <span>API Key ${settings.apiKeyConfigured ? '<em class="saved-badge">已安全保存</em>' : ""}</span>
            <div class="input-action">
              <input id="setting-api-key" type="password" autocomplete="off" placeholder="${settings.apiKeyConfigured ? "留空则保留现有 Key" : "sk-…"}">
              ${settings.apiKeyConfigured ? '<button id="clear-api-key" class="text-button danger" type="button">清除</button>' : ""}
            </div>
          </label>
          <div class="settings-actions span-2">
            <button id="test-provider" class="button subtle">测试连接</button>
            <span id="provider-test-result"></span>
          </div>
        </div>
      </section>

      <section class="settings-section">
        <div class="section-heading">
          <span class="section-number">02</span>
          <div><h2>全局操作</h2><p>三个全局快捷键都可以重新设定，保存后立即生效。</p></div>
        </div>
        <div class="setting-card">
          <label class="field">
            <span>划词翻译快捷键</span>
            <div class="shortcut-recorder">
              <input id="setting-selection-shortcut" class="shortcut-input" readonly aria-label="划词翻译快捷键" value="${escapeAttribute(settings.selectionShortcut)}">
              <button class="shortcut-record-button" type="button" data-shortcut-target="setting-selection-shortcut">重新设定</button>
            </div>
            <small class="shortcut-help">可单独按 Alt / Control / Shift / Command，也可录制组合键</small>
          </label>
          <label class="field">
            <span>截图翻译快捷键</span>
            <div class="shortcut-recorder">
              <input id="setting-screenshot-shortcut" class="shortcut-input" readonly aria-label="截图翻译快捷键" value="${escapeAttribute(settings.screenshotShortcut)}">
              <button class="shortcut-record-button" type="button" data-shortcut-target="setting-screenshot-shortcut">重新设定</button>
            </div>
            <small class="shortcut-help">按 Esc 可取消；macOS 的 ⌘Q、⌘H、⌘M 等系统组合不可使用</small>
          </label>
          <label class="field">
            <span>显示/隐藏悬浮窗快捷键</span>
            <div class="shortcut-recorder">
              <input id="setting-popup-toggle-shortcut" class="shortcut-input" readonly aria-label="显示或隐藏悬浮窗快捷键" value="${escapeAttribute(settings.popupToggleShortcut)}">
              <button class="shortcut-record-button" type="button" data-shortcut-target="setting-popup-toggle-shortcut">重新设定</button>
            </div>
            <small class="shortcut-help">再次按下同一快捷键即可隐藏或重新显示上次的翻译</small>
          </label>
          <label class="field"><span>OCR 语言包</span>
            <select id="setting-ocr-languages">
              <option value="eng+chi_sim" ${settings.ocrLanguages === "eng+chi_sim" ? "selected" : ""}>英文 + 简体中文</option>
              <option value="eng" ${settings.ocrLanguages === "eng" ? "selected" : ""}>仅英文（更快）</option>
              <option value="eng+chi_sim+jpn" ${settings.ocrLanguages === "eng+chi_sim+jpn" ? "selected" : ""}>英文 + 中文 + 日文</option>
            </select>
          </label>
          <label class="toggle-row">
            <span><strong>登录时启动</strong><small>在后台注册全局快捷键</small></span>
            <input id="setting-launch-at-login" type="checkbox" ${settings.launchAtLogin ? "checked" : ""}>
          </label>
          <label class="toggle-row">
            <span><strong>悬浮窗保持置顶</strong><small>开启后始终显示在其他窗口上方；关闭后可被其他窗口覆盖</small></span>
            <input id="setting-popup-always-on-top" type="checkbox" ${settings.popupAlwaysOnTop ? "checked" : ""}>
          </label>
        </div>
      </section>

      <section class="settings-section">
        <div class="section-heading">
          <span class="section-number">03</span>
          <div><h2>语音朗读</h2><p>点击中文朗读时才启动曼波，音频生成后立即关闭模型。</p></div>
        </div>
        <div class="setting-card">
          <label class="field span-2"><span>朗读引擎</span>
            <select id="setting-speech-provider">
              <option value="mambo" ${settings.speechProvider === "mambo" ? "selected" : ""}>MamboTTS 曼波（推荐中文）</option>
              <option value="system" ${settings.speechProvider === "system" ? "selected" : ""}>系统语音</option>
            </select>
          </label>
          <div id="mambo-settings" class="span-2 setting-subgrid">
            <label class="field"><span>MamboTTS 地址</span><input id="setting-mambo-url" value="${escapeAttribute(settings.mamboUrl)}"></label>
            <label class="field"><span>Mac mini 模型目录</span><input id="setting-mambo-root" value="${escapeAttribute(settings.mamboRoot)}"></label>
            <div class="settings-actions span-2">
              <button id="test-speech" class="button subtle" type="button">检查语音服务</button>
              <span id="speech-test-result">Windows 会根据 Ollama 地址自动使用 Mac mini 后台语音服务</span>
            </div>
          </div>
        </div>
      </section>

      <section class="settings-section">
        <div class="section-heading">
          <span class="section-number">04</span>
          <div><h2>软件更新</h2><p>当前版本 v${escapeAttribute(appVersion || "0.0.0")}，可在应用内检查、下载并安装更新。</p></div>
        </div>
        <div class="setting-card">
          <div class="update-row span-2">
            <div>
              <strong id="update-version">翻译 v${escapeAttribute(appVersion || "0.0.0")}</strong>
              <span id="update-status">尚未检查更新</span>
              <div class="update-progress"><i id="update-progress-bar"></i></div>
            </div>
            <button id="update-action" class="button subtle" type="button">检查更新</button>
          </div>
        </div>
      </section>

      <section class="settings-section">
        <div class="section-heading">
          <span class="section-number">05</span>
          <div><h2>系统权限</h2><p>macOS 首次使用需要手动授权。</p></div>
        </div>
        <div class="permission-grid">
          <article class="permission-card">
            <div class="permission-icon">⌘</div>
            <div><h3>辅助功能</h3><p>用于在其他软件中复制当前选中的文字。</p></div>
            <button class="text-button permission-button" data-permission="accessibility">打开设置</button>
          </article>
          <article class="permission-card">
            <div class="permission-icon">▣</div>
            <div><h3>屏幕录制</h3><p>用于读取截图选区，图像只在本机 OCR。</p></div>
            <button class="text-button permission-button" data-permission="screen">打开设置</button>
          </article>
        </div>
      </section>
    </div>

    <footer class="settings-footer">
      <span id="settings-save-status"></span>
      <button id="save-settings" class="button primary">保存设置</button>
    </footer>
  `;
}

function escapeAttribute(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function bindEvents() {
  document.querySelectorAll<HTMLButtonElement>("[data-view]").forEach((button) => {
    button.addEventListener("click", () => switchView(button.dataset.view!));
  });
  document
    .querySelector("#open-settings")
    ?.addEventListener("click", () => switchView("settings"));
  document
    .querySelector("#back-to-translate")
    ?.addEventListener("click", () => switchView("translate"));
  document
    .querySelector("#capture-button")
    ?.addEventListener("click", () => void window.lingua.startScreenshot());
  document
    .querySelector("#translate-button")
    ?.addEventListener("click", () => void translateCurrent(false));
  document.querySelector("#source-text")?.addEventListener("input", () => {
    updateCount();
    scheduleRealtimeTranslation();
  });
  document.querySelector("#source-text")?.addEventListener("keydown", (event) => {
    const keyboardEvent = event as KeyboardEvent;
    if ((keyboardEvent.metaKey || keyboardEvent.ctrlKey) && keyboardEvent.key === "Enter") {
      void translateCurrent(false);
    }
  });
  document.querySelector("#clear-button")?.addEventListener("click", clearTranslation);
  document.querySelector("#swap-languages")?.addEventListener("click", swapLanguages);
  document
    .querySelector("#setting-provider")
    ?.addEventListener("change", (event) => {
      refreshProviderFields();
      if ((event.target as HTMLSelectElement).value === "codex") {
        void loadCodexModels();
      }
      if ((event.target as HTMLSelectElement).value === "ollama") {
        void loadOllamaModels();
      }
    });
  document
    .querySelector("#refresh-codex-models")
    ?.addEventListener("click", () => void loadCodexModels(true));
  document
    .querySelector("#refresh-ollama-models")
    ?.addEventListener("click", () => void loadOllamaModels(true));
  document
    .querySelector("#setting-speech-provider")
    ?.addEventListener("change", refreshSpeechFields);
  document
    .querySelector("#test-speech")
    ?.addEventListener("click", () => void testSpeech());
  document
    .querySelector("#save-settings")
    ?.addEventListener("click", () => void saveSettings());
  document
    .querySelector("#test-provider")
    ?.addEventListener("click", () => void testConnection());
  document
    .querySelector("#open-ollama-download")
    ?.addEventListener("click", () => void window.lingua.openOllamaDownload());
  document
    .querySelector("#copy-ollama-command")
    ?.addEventListener("click", async () => {
      const model =
        document
          .querySelector<HTMLSelectElement>("#setting-ollama-model")
          ?.value.trim() || "qwen3:8b";
      const translationModel =
        document
          .querySelector<HTMLSelectElement>("#setting-ollama-translation-model")
          ?.value.trim() || "translategemma:4b";
      await window.lingua.copyText(
        `ollama pull ${model}\nollama pull ${translationModel}`
      );
      const button =
        document.querySelector<HTMLButtonElement>("#copy-ollama-command");
      if (button) button.textContent = "命令已复制";
    });
  document
    .querySelector("#update-action")
    ?.addEventListener("click", () => void runUpdateAction());
  document
    .querySelector("#codex-login")
    ?.addEventListener("click", () => void loginCodex());
  document
    .querySelector("#codex-status")
    ?.addEventListener("click", () => void checkCodexStatus());
  document.querySelector("#clear-api-key")?.addEventListener("click", async () => {
    settings = await window.lingua.clearApiKey();
    renderShell();
    switchView("settings");
  });
  document
    .querySelectorAll<HTMLButtonElement>(".permission-button")
    .forEach((button) =>
      button.addEventListener("click", () =>
        window.lingua.openPermissionSettings(
          button.dataset.permission as "screen" | "accessibility"
        )
      )
    );
  document.querySelectorAll<HTMLInputElement>(".shortcut-input").forEach((input) => {
    input.addEventListener("keydown", (event) => captureShortcut(event, input));
    input.addEventListener("keyup", (event) =>
      captureModifierShortcut(event, input)
    );
    input.addEventListener("click", () => beginShortcutCapture(input));
    input.addEventListener("blur", () => cancelShortcutCapture(input));
  });
  document
    .querySelectorAll<HTMLButtonElement>(".shortcut-record-button")
    .forEach((button) => {
      button.addEventListener("click", () => {
        const input = document.querySelector<HTMLInputElement>(
          `#${button.dataset.shortcutTarget}`
        );
        if (input) beginShortcutCapture(input);
      });
  });
  document
    .querySelector("#source-language")
    ?.addEventListener("change", (event) => {
      settings.sourceLanguage = (event.target as HTMLSelectElement).value;
      lastTranslatedText = "";
      scheduleRealtimeTranslation(100);
    });
  document
    .querySelector("#target-language")
    ?.addEventListener("change", (event) => {
      settings.targetLanguage = (event.target as HTMLSelectElement).value;
      lastTranslatedText = "";
      scheduleRealtimeTranslation(100);
    });
}

function switchView(view: string) {
  document.querySelectorAll(".view").forEach((node) => node.classList.remove("active"));
  document.querySelector(`#${view}-view`)?.classList.add("active");
  document.querySelectorAll(".rail-button").forEach((button) => {
    button.classList.toggle(
      "active",
      (button as HTMLElement).dataset.view === view
    );
  });
  if (view === "settings") {
    const provider =
      document.querySelector<HTMLSelectElement>("#setting-provider")?.value;
    if (provider === "ollama") void loadOllamaModels();
    if (provider === "codex") void loadCodexModels();
  }
}

function refreshProviderFields() {
  const provider =
    document.querySelector<HTMLSelectElement>("#setting-provider")?.value ||
    settings.provider;
  document.querySelectorAll<HTMLElement>(".provider-fields").forEach((fields) => {
    fields.hidden = fields.dataset.provider !== provider;
  });
  const apiKeyField = document.querySelector<HTMLElement>("#api-key-field");
  if (apiKeyField) apiKeyField.hidden = provider === "ollama" || provider === "codex";
}

function refreshSpeechFields() {
  const provider =
    document.querySelector<HTMLSelectElement>("#setting-speech-provider")
      ?.value || settings.speechProvider;
  const fields = document.querySelector<HTMLElement>("#mambo-settings");
  if (fields) fields.hidden = provider !== "mambo";
}

async function loadCodexModels(force = false) {
  if (codexCatalogLoading || (codexCatalog.length && !force)) return;
  const status = document.querySelector<HTMLElement>("#codex-model-status");
  const button =
    document.querySelector<HTMLButtonElement>("#refresh-codex-models");
  const select =
    document.querySelector<HTMLSelectElement>("#setting-codex-model");
  if (!select) return;
  const selected = select.value;
  codexCatalogLoading = true;
  if (status) status.textContent = "正在读取当前账号可用的模型…";
  if (button) button.disabled = true;
  try {
    codexCatalog = await window.lingua.codexModels(collectSettings());
    select.replaceChildren();
    const automatic = document.createElement("option");
    automatic.value = "";
    automatic.textContent = "自动选择 Codex 推荐模型";
    select.append(automatic);
    codexCatalog.forEach((model) => {
      const option = document.createElement("option");
      option.value = model.id;
      option.textContent = `${model.name} · ${model.id}`;
      option.title = model.description;
      select.append(option);
    });
    if (
      selected &&
      !codexCatalog.some((model) => model.id === selected)
    ) {
      const saved = document.createElement("option");
      saved.value = selected;
      saved.textContent = `${selected}（当前保存）`;
      select.append(saved);
    }
    select.value = selected;
    if (status) {
      status.textContent = codexCatalog.length
        ? `已读取当前账号的 ${codexCatalog.length} 个模型`
        : "当前账号没有返回可选择的模型";
    }
  } catch (error) {
    if (status) status.textContent = humanizeError(error);
    status?.classList.add("error");
  } finally {
    codexCatalogLoading = false;
    if (button) button.disabled = false;
  }
}

function replaceOllamaModelOptions(
  select: HTMLSelectElement | null,
  catalog: OllamaModel[],
  purpose: "translation" | "technical"
) {
  if (!select) return;
  const selected = select.value;
  const sorted = [...catalog].sort((left, right) => {
    const preferred = (name: string) =>
      purpose === "translation"
        ? /translate|gemma/i.test(name)
        : /qwen|coder|code/i.test(name);
    return Number(preferred(right.name)) - Number(preferred(left.name)) ||
      left.name.localeCompare(right.name);
  });
  select.replaceChildren();
  sorted.forEach((model) => {
    const option = document.createElement("option");
    option.value = model.name;
    option.textContent = ollamaModelLabel(model);
    select.append(option);
  });
  if (selected && !catalog.some((model) => model.name === selected)) {
    const saved = document.createElement("option");
    saved.value = selected;
    saved.textContent = `${selected}（当前保存）`;
    select.prepend(saved);
  }
  select.value = selected || sorted[0]?.name || "";
}

async function loadOllamaModels(force = false) {
  if (ollamaCatalogLoading || (ollamaCatalog.length && !force)) return;
  const status = document.querySelector<HTMLElement>("#ollama-model-status");
  const button =
    document.querySelector<HTMLButtonElement>("#refresh-ollama-models");
  const technical =
    document.querySelector<HTMLSelectElement>("#setting-ollama-model");
  const translation =
    document.querySelector<HTMLSelectElement>("#setting-ollama-translation-model");
  if (!technical || !translation) return;
  ollamaCatalogLoading = true;
  status?.classList.remove("error");
  if (status) status.textContent = "正在读取 Ollama 已安装模型…";
  if (button) button.disabled = true;
  try {
    ollamaCatalog = await window.lingua.ollamaModels(collectSettings());
    replaceOllamaModelOptions(technical, ollamaCatalog, "technical");
    replaceOllamaModelOptions(translation, ollamaCatalog, "translation");
    if (status) {
      status.textContent = ollamaCatalog.length
        ? `已读取 ${ollamaCatalog.length} 个已安装模型`
        : "Ollama 当前没有已安装模型";
    }
  } catch (error) {
    if (status) status.textContent = humanizeError(error);
    status?.classList.add("error");
  } finally {
    ollamaCatalogLoading = false;
    if (button) button.disabled = false;
  }
}

async function testSpeech() {
  const resultNode =
    document.querySelector<HTMLElement>("#speech-test-result");
  const button = document.querySelector<HTMLButtonElement>("#test-speech");
  if (!resultNode || !button) return;
  resultNode.textContent = "正在启动曼波语音引擎…";
  resultNode.className = "";
  button.disabled = true;
  try {
    const result = await window.lingua.testSpeech(collectSettings());
    resultNode.textContent = `${result.message} · ${(result.latencyMs / 1000).toFixed(1)} 秒`;
    resultNode.className = "success";
  } catch (error) {
    resultNode.textContent = humanizeError(error);
    resultNode.className = "error";
  } finally {
    button.disabled = false;
  }
}

function updateCount() {
  const source = document.querySelector<HTMLTextAreaElement>("#source-text")!;
  document.querySelector("#character-count")!.textContent =
    `${source.value.length.toLocaleString()} / 12,000`;
}

function setStatus(message: string, error = false) {
  const line = document.querySelector<HTMLElement>("#status-line");
  if (!line) return;
  line.textContent = message;
  line.classList.toggle("error", error);
}

function setLoading(loading: boolean, message = "正在理解上下文…") {
  isTranslating = loading;
  const resultContent =
    document.querySelector<HTMLElement>("#result-content");
  const hasRenderedContent = Boolean(resultContent?.childElementCount);
  document
    .querySelector("#empty-result")
    ?.classList.toggle(
      "hidden",
      loading || Boolean(lastResult) || hasRenderedContent
    );
  document.querySelector("#loading-result")?.classList.toggle("hidden", !loading);
  resultContent?.classList.toggle(
    "hidden",
    loading || (!lastResult && !hasRenderedContent)
  );
  const loadingMessage = document.querySelector("#loading-message");
  if (loadingMessage) loadingMessage.textContent = message;
  const button = document.querySelector<HTMLButtonElement>("#translate-button");
  if (button) {
    button.disabled = loading;
    button.firstChild!.textContent = loading ? "翻译中 " : "立即翻译 ";
  }
}

function scheduleRealtimeTranslation(delay?: number) {
  window.clearTimeout(realtimeTimer);
  const source = document.querySelector<HTMLTextAreaElement>("#source-text");
  if (!source) return;
  const text = source.value.trim();
  if (!text) {
    lastTranslatedText = "";
    return;
  }
  if (text === lastTranslatedText && lastResult) return;
  const wait = delay ?? (settings.provider === "codex" ? 1200 : 650);
  setStatus("输入完成后将自动翻译…");
  realtimeTimer = window.setTimeout(() => {
    void translateCurrent(true);
  }, wait);
}

function humanizeError(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  return message
    .replace(/^Error invoking remote method '[^']+':\s*/i, "")
    .replace(/^Error:\s*/i, "")
    .trim();
}

async function translateCurrent(automatic = false) {
  if (isTranslating) {
    if (automatic) queuedRealtime = true;
    return;
  }
  const source = document.querySelector<HTMLTextAreaElement>("#source-text")!;
  const text = source.value.trim();
  if (!text) {
    source.focus();
    setStatus("请先输入或选取要翻译的文字", true);
    return;
  }
  const sourceLanguage =
    document.querySelector<HTMLSelectElement>("#source-language")!.value;
  const targetLanguage =
    document.querySelector<HTMLSelectElement>("#target-language")!.value;
  queuedRealtime = false;
  lastResult = null;
  document
    .querySelector<HTMLElement>("#result-content")
    ?.replaceChildren();
  setStatus("");
  setLoading(true);
  try {
    lastResult = await window.lingua.translate(text, {
      sourceLanguage,
      targetLanguage
    });
    if (source.value.trim() !== text) {
      queuedRealtime = true;
      return;
    }
    lastTranslatedText = text;
    renderResult(lastResult);
    setStatus(
      lastResult.cacheHit
        ? "已从本地缓存显示"
        : `已通过 ${providerLabel(settings.provider)} 完成`
    );
    if (lastResult.needsEnrichment) {
      void enrichCurrentResult(text, lastResult);
    }
  } catch (error) {
    lastResult = null;
    const message = humanizeError(error);
    setStatus(message, true);
    showResultError(message);
  } finally {
    setLoading(false);
    if (queuedRealtime || source.value.trim() !== text) {
      queuedRealtime = false;
      scheduleRealtimeTranslation(250);
    }
  }
}

async function enrichCurrentResult(
  sourceText: string,
  coreResult: TranslationResult
) {
  setStatus("译文已显示，正在后台补充技术说明…");
  try {
    const enrichment = await window.lingua.enrichTranslation(
      sourceText,
      coreResult.translation,
      {
        sourceLanguage:
          document.querySelector<HTMLSelectElement>("#source-language")?.value,
        targetLanguage:
          document.querySelector<HTMLSelectElement>("#target-language")?.value
      }
    );
    const currentText =
      document.querySelector<HTMLTextAreaElement>("#source-text")?.value.trim();
    if (currentText !== sourceText || lastResult !== coreResult) return;
    lastResult = { ...coreResult, ...enrichment, needsEnrichment: false };
    renderResult(lastResult);
    setStatus("译文与技术说明已完成");
  } catch {
    if (lastResult === coreResult) {
      lastResult = {
        ...coreResult,
        needsEnrichment: false,
        enrichmentFailed: true
      };
      renderResult(lastResult);
      setStatus("译文已完成；技术说明暂未补充");
    }
  }
}

function showResultError(message: string) {
  const container = document.querySelector<HTMLDivElement>("#result-content")!;
  container.replaceChildren();
  const panel = document.createElement("div");
  panel.className = "result-error";
  const title = document.createElement("strong");
  title.textContent = "翻译没有完成";
  const text = document.createElement("p");
  text.textContent = message;
  const button = document.createElement("button");
  button.className = "button subtle";
  button.textContent = "检查翻译引擎";
  button.addEventListener("click", () => switchView("settings"));
  panel.append(title, text, button);
  container.append(panel);
  container.classList.remove("hidden");
}

function renderResult(result: TranslationResult) {
  const container = document.querySelector<HTMLDivElement>("#result-content")!;
  container.replaceChildren();

  const heading = document.createElement("div");
  heading.className = "result-heading";
  const label = document.createElement("span");
  label.textContent = "译文";
  const actions = document.createElement("div");
  actions.className = "result-actions";
  const speakTranslation = actionButton(
    "朗读译文",
    "speaker",
    () => speakText(result.translation, result.targetLanguage),
    true
  );
  actions.append(
    speakTranslation,
    actionButton("复制", "copy", async () => {
      await window.lingua.copyText(result.translation);
      setStatus("译文已复制");
    })
  );
  heading.append(label, actions);

  const translation = document.createElement("p");
  translation.className = "translation-text";
  translation.textContent = result.translation;
  container.append(heading, translation);

  if (result.phonetic) {
    const phonetic = document.createElement("div");
    phonetic.className = "phonetic-line";
    const phoneticText = document.createElement("div");
    phoneticText.className = "phonetic-text";
    const phoneticLabel = document.createElement("small");
    phoneticLabel.textContent = "英文音标";
    const ipa = document.createElement("span");
    ipa.textContent = result.phonetic;
    const play = actionButton(
      "朗读原文",
      "speaker",
      () =>
        speakText(
          result.pronunciationText ||
            document.querySelector<HTMLTextAreaElement>("#source-text")!.value,
          result.sourceLanguage
        ),
      true
    );
    phoneticText.append(phoneticLabel, ipa);
    phonetic.append(phoneticText, play);
    container.append(phonetic);
  }

  if (result.explanation) {
    const sourceText =
      document.querySelector<HTMLTextAreaElement>("#source-text")?.value || "";
    container.append(
      resultSection(
        isSingleEnglishWord(sourceText) ? "名词解析" : "语境说明",
        result.explanation
      )
    );
  } else if (result.needsEnrichment) {
    const sourceText =
      document.querySelector<HTMLTextAreaElement>("#source-text")?.value || "";
    const pending = resultSection(
      isSingleEnglishWord(sourceText) ? "名词解析" : "IT 行业解释",
      "正在后台生成用途、典型场景与注意事项…"
    );
    pending.classList.add("enrichment-pending");
    container.append(pending);
  } else if (result.enrichmentFailed) {
    const sourceText =
      document.querySelector<HTMLTextAreaElement>("#source-text")?.value || "";
    const failed = resultSection(
      isSingleEnglishWord(sourceText) ? "名词解析" : "IT 行业解释",
      "本次技术解释生成失败；译文不受影响，可重新翻译后重试。"
    );
    failed.classList.add("enrichment-failed");
    container.append(failed);
  }

  if (result.terms.length) {
    const section = document.createElement("section");
    section.className = "result-section";
    const title = document.createElement("h3");
    title.textContent = "技术术语";
    const terms = document.createElement("div");
    terms.className = "term-list";
    result.terms.forEach((term) => {
      const item = document.createElement("article");
      const top = document.createElement("div");
      const name = document.createElement("strong");
      name.textContent = term.term;
      const category = document.createElement("span");
      category.textContent = term.category;
      top.append(name, category);
      const translated = document.createElement("p");
      translated.className = "term-translation";
      translated.textContent = term.translation;
      const definition = document.createElement("p");
      definition.textContent = term.definition;
      item.append(top, translated, definition);
      terms.append(item);
    });
    section.append(title, terms);
    container.append(section);
  }

  if (result.alternatives.length) {
    container.append(resultSection("其他表达", result.alternatives.join(" · ")));
  }
  container.classList.remove("hidden");
}

function actionButton(
  label: string,
  icon: "speaker" | "copy",
  callback: () => void | Promise<void>,
  showLabel = false
) {
  const button = document.createElement("button");
  button.className = `mini-action${showLabel ? " with-label" : ""}`;
  button.title = label;
  button.innerHTML =
    icon === "speaker"
      ? '<svg viewBox="0 0 24 24"><path d="M5 10v4h3l4 3V7l-4 3H5Zm10-1.5a5 5 0 0 1 0 7M17.5 6a9 9 0 0 1 0 12"/></svg>'
      : '<svg viewBox="0 0 24 24"><rect x="8" y="8" width="11" height="11" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg>';
  if (showLabel) {
    const text = document.createElement("span");
    text.textContent = label;
    button.append(text);
  }
  button.addEventListener("click", async () => {
    button.disabled = true;
    button.classList.add("busy");
    const text = button.querySelector("span");
    const originalText = text?.textContent || "";
    const waitingLabel = window.setTimeout(() => {
      if (text && icon === "speaker") text.textContent = "准备音频…";
    }, 120);
    try {
      await callback();
    } finally {
      window.clearTimeout(waitingLabel);
      if (text) text.textContent = originalText;
      button.disabled = false;
      button.classList.remove("busy");
    }
  });
  return button;
}

function resultSection(titleText: string, bodyText: string) {
  const section = document.createElement("section");
  section.className = "result-section";
  const title = document.createElement("h3");
  title.textContent = titleText;
  const body = document.createElement("p");
  body.textContent = bodyText;
  section.append(title, body);
  return section;
}

function clearTranslation() {
  const source = document.querySelector<HTMLTextAreaElement>("#source-text")!;
  window.clearTimeout(realtimeTimer);
  source.value = "";
  lastResult = null;
  lastTranslatedText = "";
  const resultContent =
    document.querySelector<HTMLElement>("#result-content");
  resultContent?.replaceChildren();
  resultContent?.classList.add("hidden");
  document.querySelector("#empty-result")?.classList.remove("hidden");
  setStatus("");
  updateCount();
  source.focus();
}

function swapLanguages() {
  const source = document.querySelector<HTMLSelectElement>("#source-language")!;
  const target = document.querySelector<HTMLSelectElement>("#target-language")!;
  if (source.value === "auto") source.value = "en";
  const oldSource = source.value;
  source.value = target.value;
  target.value = oldSource === "auto" ? "en" : oldSource;
  settings.sourceLanguage = source.value;
  settings.targetLanguage = target.value;
  lastTranslatedText = "";
  scheduleRealtimeTranslation(100);
}

function syncShortcutRecordingState() {
  const active = Boolean(document.querySelector(".shortcut-input.recording"));
  void window.lingua.setShortcutRecording(active);
}

function captureShortcut(event: KeyboardEvent, input: HTMLInputElement) {
  event.preventDefault();
  event.stopPropagation();
  if (!input.classList.contains("recording")) return;
  if (["Meta", "Control", "Alt", "Shift"].includes(event.key)) {
    input.dataset.pendingModifier = event.key;
    input.value = `松开 ${modifierDisplayName(event.key)} 可设为单键，或继续按组合键…`;
    return;
  }
  if (event.key === "Tab") {
    cancelShortcutCapture(input);
    input.blur();
    return;
  }
  if (event.key === "Escape") {
    cancelShortcutCapture(input);
    input.blur();
    return;
  }
  const modifiers = [];
  if (event.metaKey || event.ctrlKey) modifiers.push("CommandOrControl");
  if (event.altKey) modifiers.push("Alt");
  if (event.shiftKey) modifiers.push("Shift");
  const isFunctionKey = /^F(?:[1-9]|1\d|2[0-4])$/i.test(event.key);
  if (!modifiers.length && !isFunctionKey) return;
  const keyMap: Record<string, string> = {
    " ": "Space",
    ArrowUp: "Up",
    ArrowDown: "Down",
    ArrowLeft: "Left",
    ArrowRight: "Right"
  };
  const key =
    keyMap[event.key] ||
    (event.key.length === 1 ? event.key.toUpperCase() : event.key);
  input.value = modifiers.length ? [...modifiers, key].join("+") : key;
  delete input.dataset.previousValue;
  delete input.dataset.pendingModifier;
  input.classList.remove("recording");
  syncShortcutRecordingState();
  input.blur();
}

function modifierDisplayName(value: string) {
  return {
    Meta: "Command",
    Control: "Control",
    Alt: "Alt",
    Shift: "Shift"
  }[value] || value;
}

function captureModifierShortcut(
  event: KeyboardEvent,
  input: HTMLInputElement
) {
  if (!input.classList.contains("recording")) return;
  const pending = input.dataset.pendingModifier;
  if (!pending || event.key !== pending) return;
  event.preventDefault();
  event.stopPropagation();
  input.value = pending === "Meta" ? "Command" : pending;
  delete input.dataset.previousValue;
  delete input.dataset.pendingModifier;
  input.classList.remove("recording");
  syncShortcutRecordingState();
  input.blur();
}

function beginShortcutCapture(input: HTMLInputElement) {
  document.querySelectorAll<HTMLInputElement>(".shortcut-input.recording").forEach(
    (other) => {
      if (other !== input) cancelShortcutCapture(other);
    }
  );
  if (!input.classList.contains("recording")) {
    input.dataset.previousValue = input.value;
    input.value = "请按下新组合键…";
    input.classList.add("recording");
  }
  syncShortcutRecordingState();
  input.focus();
}

function cancelShortcutCapture(input: HTMLInputElement) {
  if (!input.classList.contains("recording")) return;
  input.value = input.dataset.previousValue || input.value;
  delete input.dataset.previousValue;
  delete input.dataset.pendingModifier;
  input.classList.remove("recording");
  syncShortcutRecordingState();
}

function validateShortcuts() {
  const selection = document.querySelector<HTMLInputElement>(
    "#setting-selection-shortcut"
  )!;
  const screenshot = document.querySelector<HTMLInputElement>(
    "#setting-screenshot-shortcut"
  )!;
  const popupToggle = document.querySelector<HTMLInputElement>(
    "#setting-popup-toggle-shortcut"
  )!;
  cancelShortcutCapture(selection);
  cancelShortcutCapture(screenshot);
  cancelShortcutCapture(popupToggle);
  const values = [
    selection.value.trim(),
    screenshot.value.trim(),
    popupToggle.value.trim()
  ];
  if (values.some((value) => !value)) {
    return "三个快捷键都不能为空";
  }
  if (new Set(values.map((value) => value.toLowerCase())).size !== values.length) {
    return "三个全局操作不能使用同一个快捷键";
  }
  if (currentPlatform === "darwin") {
    for (const value of values) {
      const normalized = value
        .toLowerCase()
        .replace(/\s+/g, "")
        .replace(/commandorcontrol|cmd|meta|super/g, "command");
      if (
        [
          "command+q",
          "command+h",
          "command+m",
          "command+w",
          "command+tab",
          "command+space"
        ].includes(normalized)
      ) {
        return `${value} 是 macOS 系统快捷键，请选择其他按键`;
      }
    }
  }
  if (
    values.some(
      (value) =>
        !/^(Alt|Option|Control|Ctrl|Shift|Command|Cmd|Meta|Super)$/i.test(value) &&
        !/(CommandOrControl|Command|Control|Ctrl|Alt|Option|Shift)\+/i.test(value) &&
        !/^F(?:[1-9]|1\d|2[0-4])$/i.test(value)
    )
  ) {
    return "可使用单独修饰键、包含修饰键的组合，或 F1–F24";
  }
  return "";
}

function collectSettings(): Partial<AppSettings> {
  return {
    provider: document.querySelector<HTMLSelectElement>("#setting-provider")!
      .value as Provider,
    ollamaUrl:
      document.querySelector<HTMLInputElement>("#setting-ollama-url")!.value.trim(),
    ollamaModel:
      document.querySelector<HTMLSelectElement>("#setting-ollama-model")!.value.trim(),
    ollamaTranslationModel:
      document
        .querySelector<HTMLSelectElement>("#setting-ollama-translation-model")!
        .value.trim(),
    useTranslateGemma:
      document.querySelector<HTMLInputElement>("#setting-use-translategemma")!
        .checked,
    openaiBaseUrl:
      document.querySelector<HTMLInputElement>("#setting-openai-url")!.value.trim(),
    openaiModel:
      document.querySelector<HTMLInputElement>("#setting-openai-model")!.value.trim(),
    compatibleBaseUrl:
      document.querySelector<HTMLInputElement>("#setting-compatible-url")!.value.trim(),
    compatibleModel:
      document.querySelector<HTMLInputElement>("#setting-compatible-model")!.value.trim(),
    codexPath:
      document.querySelector<HTMLInputElement>("#setting-codex-path")!.value.trim(),
    codexModel:
      document.querySelector<HTMLSelectElement>("#setting-codex-model")!.value.trim(),
    speechProvider:
      document.querySelector<HTMLSelectElement>("#setting-speech-provider")!
        .value as AppSettings["speechProvider"],
    mamboUrl:
      document.querySelector<HTMLInputElement>("#setting-mambo-url")!.value.trim(),
    mamboRoot:
      document.querySelector<HTMLInputElement>("#setting-mambo-root")!.value.trim(),
    apiKey:
      document.querySelector<HTMLInputElement>("#setting-api-key")!.value.trim(),
    selectionShortcut: document.querySelector<HTMLInputElement>(
      "#setting-selection-shortcut"
    )!.value,
    screenshotShortcut: document.querySelector<HTMLInputElement>(
      "#setting-screenshot-shortcut"
    )!.value,
    popupToggleShortcut: document.querySelector<HTMLInputElement>(
      "#setting-popup-toggle-shortcut"
    )!.value,
    ocrLanguages: document.querySelector<HTMLSelectElement>(
      "#setting-ocr-languages"
    )!.value,
    launchAtLogin: document.querySelector<HTMLInputElement>(
      "#setting-launch-at-login"
    )!.checked,
    popupAlwaysOnTop: document.querySelector<HTMLInputElement>(
      "#setting-popup-always-on-top"
    )!.checked,
    sourceLanguage: settings.sourceLanguage,
    targetLanguage: settings.targetLanguage
  };
}

async function loginCodex() {
  const resultNode = document.querySelector<HTMLElement>("#codex-login-result")!;
  const button = document.querySelector<HTMLButtonElement>("#codex-login")!;
  resultNode.textContent = "请在浏览器中完成登录…";
  resultNode.className = "";
  button.disabled = true;
  try {
    const result = await window.lingua.codexLogin(collectSettings());
    resultNode.textContent = result.message;
    resultNode.className = result.ok ? "success" : "error";
  } catch (error) {
    resultNode.textContent = humanizeError(error);
    resultNode.className = "error";
  } finally {
    button.disabled = false;
  }
}

async function checkCodexStatus() {
  const resultNode = document.querySelector<HTMLElement>("#codex-login-result")!;
  resultNode.textContent = "检查中…";
  try {
    const result = await window.lingua.codexStatus(collectSettings());
    resultNode.textContent = result.message;
    resultNode.className = result.ok ? "success" : "error";
  } catch (error) {
    resultNode.textContent = humanizeError(error);
    resultNode.className = "error";
  }
}

async function saveSettings() {
  const status = document.querySelector<HTMLElement>("#settings-save-status")!;
  const shortcutError = validateShortcuts();
  if (shortcutError) {
    status.textContent = shortcutError;
    status.classList.add("error");
    return;
  }
  try {
    const response = await window.lingua.saveSettings(collectSettings());
    settings = response.settings;
    status.textContent = response.shortcutFailures.length
      ? `已保存，但快捷键被占用：${response.shortcutFailures.join("、")}`
      : "设置已保存";
    status.classList.toggle("error", response.shortcutFailures.length > 0);
    setTimeout(() => {
      renderShell();
      switchView("settings");
    }, 800);
  } catch (error) {
    status.textContent = humanizeError(error);
    status.classList.add("error");
  }
}

async function testConnection() {
  const resultNode = document.querySelector<HTMLElement>("#provider-test-result")!;
  const button = document.querySelector<HTMLButtonElement>("#test-provider")!;
  resultNode.textContent = "正在请求模型…";
  resultNode.className = "";
  button.disabled = true;
  try {
    const result = await window.lingua.testProvider(collectSettings());
    resultNode.textContent = `连接成功 · ${result.model} · ${(result.latencyMs / 1000).toFixed(1)} 秒${result.note ? ` · ${result.note}` : ""}`;
    resultNode.className = "success";
  } catch (error) {
    resultNode.textContent = humanizeError(error);
    resultNode.className = "error";
  } finally {
    button.disabled = false;
  }
}

function renderUpdateStatus(update: UpdateStatus) {
  const status = document.querySelector<HTMLElement>("#update-status");
  const bar = document.querySelector<HTMLElement>("#update-progress-bar");
  const button = document.querySelector<HTMLButtonElement>("#update-action");
  if (!status || !bar || !button) return;
  status.textContent = update.message;
  status.classList.toggle("error", update.status === "error");
  bar.style.width = `${Math.max(0, Math.min(100, update.progress || 0))}%`;
  button.disabled =
    update.status === "checking" || update.status === "downloading";
  const buttonLabels: Partial<Record<UpdateStatus["status"], string>> = {
      available: "下载更新",
      downloaded: "立即安装",
      checking: "检查中…",
      downloading: `下载中 ${update.progress || 0}%`,
      development: "开发版本",
      current: "再次检查",
      error: "重新检查"
  };
  button.textContent = buttonLabels[update.status] || "检查更新";
}

async function runUpdateAction() {
  const current = await window.lingua.getUpdateStatus();
  try {
    if (current.status === "available") {
      await window.lingua.downloadUpdate();
      return;
    }
    if (current.status === "downloaded") {
      await window.lingua.installUpdate();
      return;
    }
    await window.lingua.checkForUpdates();
  } catch (error) {
    renderUpdateStatus({
      status: "error",
      message: humanizeError(error),
      progress: 0,
      version: ""
    });
  }
}

async function initialize() {
  const [storedSettings, appInfo] = await Promise.all([
    window.lingua.getSettings(),
    window.lingua.getAppInfo()
  ]);
  settings = storedSettings;
  currentPlatform = appInfo.platform;
  appVersion = appInfo.version;
  renderShell();
  window.lingua.onUpdateStatus(renderUpdateStatus);
  void window.lingua.getUpdateStatus().then(renderUpdateStatus);
  window.lingua.onTranslationStart(({ text, source }) => {
    switchView("translate");
    const sourceField =
      document.querySelector<HTMLTextAreaElement>("#source-text")!;
    sourceField.value = text;
    updateCount();
    setStatus(source === "screenshot" ? "截图文字已识别" : "已读取选中文字");
    void translateCurrent(false);
  });
  window.lingua.onTranslationHydrate(({ text, result }) => {
    switchView("translate");
    const sourceField =
      document.querySelector<HTMLTextAreaElement>("#source-text")!;
    sourceField.value = text;
    updateCount();
    lastResult = result;
    renderResult(result);
    setLoading(false);
    setStatus("已从快速翻译悬浮窗展开");
  });
  window.lingua.onStatus(({ message, error, progress }) => {
    switchView("translate");
    setStatus(
      typeof progress === "number" && progress > 0
        ? `${message} ${progress}%`
        : message,
      Boolean(error)
    );
    if (message.startsWith("OCR：")) setLoading(true, message);
  });
}

void initialize();
