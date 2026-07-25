import "./styles.css";
import { speakText } from "./speech";

const root = document.querySelector<HTMLDivElement>("#popup-root")!;
let sourceText = "";
let currentResult: TranslationResult | null = null;
let providerName = "翻译服务";
let speechPreparationTimer: number | undefined;

function icon(name: "close" | "copy" | "speaker" | "expand") {
  const paths = {
    close: '<path d="m7 7 10 10M17 7 7 17"/>',
    copy: '<rect x="8" y="8" width="11" height="11" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
    speaker: '<path d="M5 10v4h3l4 3V7l-4 3H5Zm10-1.5a5 5 0 0 1 0 7M17.5 6a9 9 0 0 1 0 12"/>',
    expand: '<path d="M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5"/>'
  };
  return `<svg viewBox="0 0 24 24">${paths[name]}</svg>`;
}

function shell(content: string, source: string) {
  const label = source === "screenshot" ? "截图翻译" : "划词翻译";
  return `
    <section class="quick-popup">
      <header class="popup-header">
        <div class="popup-title"><span class="popup-logo">译</span><strong>${label}</strong><i>${providerName}</i></div>
        <button id="popup-close" class="popup-icon-button" title="关闭">${icon("close")}</button>
      </header>
      ${content}
    </section>
  `;
}

function bindCommon() {
  document.querySelector("#popup-close")?.addEventListener("click", () => {
    void window.lingua.closePopup();
  });
}

function resizeSoon() {
  requestAnimationFrame(() => {
    const card = document.querySelector<HTMLElement>(".quick-popup");
    if (!card) return;
    const header =
      document.querySelector<HTMLElement>(".popup-header")?.offsetHeight || 0;
    const content = document.querySelector<HTMLElement>(
      ".popup-content, .popup-loading, .popup-error"
    );
    const footer =
      document.querySelector<HTMLElement>(".popup-footer")?.offsetHeight || 0;
    const desiredHeight =
      header + (content?.scrollHeight || card.scrollHeight) + footer + 18;
    void window.lingua.resizePopup(
      Math.min(680, Math.max(210, desiredHeight))
    );
  });
}

function showLoading(message: string, source = "selection") {
  root.innerHTML = shell(
    `<div class="popup-loading">
      <div class="popup-spinner"></div>
      <strong>${escapeHtml(message)}</strong>
      <p>窗口可以拖动，完成后会自动显示结果</p>
    </div>`,
    source
  );
  bindCommon();
  resizeSoon();
}

function showError(message: string, source = "selection") {
  root.innerHTML = shell(
    `<div class="popup-error">
      <span>!</span>
      <div><strong>没有完成翻译</strong><p>${escapeHtml(message)}</p></div>
    </div>
    <footer class="popup-footer">
      <button id="open-settings-from-popup" class="popup-secondary">打开主窗口检查设置</button>
    </footer>`,
    source
  );
  bindCommon();
  document
    .querySelector("#open-settings-from-popup")
    ?.addEventListener("click", async () => {
      await window.lingua.openPopupInMain({
        text: sourceText,
        result: {
          sourceLanguage: "",
          targetLanguage: "",
          translation: "",
          phonetic: "",
          pronunciationText: "",
          explanation: message,
          terms: [],
          alternatives: []
        }
      });
    });
  resizeSoon();
}

function escapeHtml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function isSingleEnglishWord(value: string) {
  return /^[A-Za-z][A-Za-z.'’_-]*$/.test(value.trim());
}

function renderResult(result: TranslationResult, source: string) {
  currentResult = result;
  const terms = result.terms.slice(0, 6);
  const sourceNeedsToggle =
    sourceText.length > 110 || sourceText.split(/\r?\n/).length > 3;
  const sourceMarkup = `
    <section class="popup-source-section">
      <div class="popup-source-heading">
        <span>${/[A-Za-z]/.test(sourceText) ? "英文原文" : "原文"}</span>
        <button id="speak-source" class="popup-icon-button popup-speech-button" title="朗读原文">${icon("speaker")}<span>朗读原文</span></button>
      </div>
      <p id="popup-source-text" class="popup-source-text${sourceNeedsToggle ? " collapsible" : ""}">${escapeHtml(sourceText)}</p>
      ${
        sourceNeedsToggle
          ? '<button class="popup-expand-button" data-toggle-target="popup-source-text" data-collapsed-label="展开原文" data-expanded-label="收起原文">展开原文</button>'
          : ""
      }
    </section>
  `;
  const termMarkup = terms.length
    ? `<section class="popup-section">
        <div class="popup-section-heading">
          <h3>专业术语 <span>${terms.length}</span></h3>
          ${
            terms.length > 2
              ? '<button class="popup-expand-button" data-toggle-target="popup-terms" data-collapsed-label="展开全部" data-expanded-label="收起">展开全部</button>'
              : ""
          }
        </div>
        <div id="popup-terms" class="popup-terms${terms.length > 2 ? " collapsible" : ""}">
          ${terms
            .map(
              (term) => `<article>
                <div><strong>${escapeHtml(term.term)}</strong><em>${escapeHtml(term.category)}</em></div>
                <p><b>${escapeHtml(term.translation)}</b> · ${escapeHtml(term.definition)}</p>
              </article>`
            )
            .join("")}
        </div>
      </section>`
    : "";
  const explanation = result.explanation
    ? `<section class="popup-section">
        <div class="popup-section-heading">
          <h3>语境说明</h3>
          ${
            result.explanation.length > 95
              ? '<button class="popup-expand-button" data-toggle-target="popup-explanation" data-collapsed-label="展开说明" data-expanded-label="收起">展开说明</button>'
              : ""
          }
        </div>
        <p id="popup-explanation" class="popup-section-copy${result.explanation.length > 95 ? " collapsible" : ""}">${escapeHtml(result.explanation)}</p>
      </section>`
    : result.needsEnrichment
      ? `<section class="popup-section popup-enrichment-pending"><div class="popup-section-heading"><h3>IT 行业解释</h3></div><p class="popup-section-copy">正在后台生成用途、典型场景与注意事项…</p></section>`
      : result.enrichmentFailed
        ? `<section class="popup-section popup-enrichment-failed"><div class="popup-section-heading"><h3>IT 行业解释</h3></div><p class="popup-section-copy">本次技术解释生成失败；译文不受影响，可重新触发翻译后重试。</p></section>`
        : "";
  const phonetic = result.phonetic && isSingleEnglishWord(sourceText)
    ? `<div class="popup-phonetic"><div class="popup-phonetic-text"><small>英文音标</small><span>${escapeHtml(result.phonetic)}</span></div></div>`
    : "";

  root.innerHTML = shell(
    `<div class="popup-content">
      ${sourceMarkup}
      <div class="popup-result-heading"><span>译文</span><button id="speak-result" class="popup-icon-button popup-speech-button" title="朗读译文">${icon("speaker")}<span>朗读译文</span></button></div>
      <p class="popup-translation">${escapeHtml(result.translation)}</p>
      ${phonetic}
      ${explanation}
      ${termMarkup}
    </div>
    <footer class="popup-footer">
      <button id="copy-result" class="popup-secondary">${icon("copy")}复制</button>
      <button id="expand-result" class="popup-primary">${icon("expand")}在主窗口展开</button>
    </footer>`,
    source
  );
  bindCommon();
  document.querySelector("#copy-result")?.addEventListener("click", async () => {
    await window.lingua.copyText(result.translation);
    const button = document.querySelector<HTMLButtonElement>("#copy-result")!;
    button.textContent = "已复制";
  });
  document.querySelector("#expand-result")?.addEventListener("click", () => {
    void window.lingua.openPopupInMain({ text: sourceText, result });
  });
  document
    .querySelectorAll<HTMLButtonElement>("[data-toggle-target]")
    .forEach((button) => {
      button.addEventListener("click", () => {
        const target = document.querySelector<HTMLElement>(
          `#${button.dataset.toggleTarget}`
        );
        if (!target) return;
        const expanded = target.classList.toggle("expanded");
        button.textContent = expanded
          ? button.dataset.expandedLabel || "收起"
          : button.dataset.collapsedLabel || "展开";
        resizeSoon();
      });
    });
  document.querySelector("#speak-source")?.addEventListener("click", async (event) => {
    const button = event.currentTarget as HTMLButtonElement;
    button.disabled = true;
    await speakText(
      result.pronunciationText || sourceText,
      result.sourceLanguage
    );
    button.disabled = false;
  });
  document.querySelector("#speak-result")?.addEventListener("click", async (event) => {
    const button = event.currentTarget as HTMLButtonElement;
    button.disabled = true;
    await speakText(result.translation, result.targetLanguage);
    button.disabled = false;
  });
  document.querySelector("#speak-result")?.addEventListener("pointerenter", () => {
    void window.lingua.prepareSpeech(
      result.translation,
      result.targetLanguage
    );
  });
  window.clearTimeout(speechPreparationTimer);
  if (/[\u3400-\u9fff]/.test(result.translation)) {
    speechPreparationTimer = window.setTimeout(() => {
      void window.lingua.prepareSpeech(
        result.translation,
        result.targetLanguage
      );
    }, 900);
  }
  resizeSoon();
}

async function enrichPopupResult(
  text: string,
  coreResult: TranslationResult,
  source: string
) {
  try {
    const enrichment = await window.lingua.enrichTranslation(
      text,
      coreResult.translation
    );
    if (sourceText !== text || currentResult !== coreResult) return;
    renderResult(
      { ...coreResult, ...enrichment, needsEnrichment: false },
      source
    );
  } catch {
    if (sourceText === text && currentResult === coreResult) {
      renderResult(
        {
          ...coreResult,
          needsEnrichment: false,
          enrichmentFailed: true
        },
        source
      );
    }
  }
}

async function handlePayload(payload: PopupPayload) {
  if (payload.error) {
    showError(payload.error, payload.source);
    return;
  }
  if (payload.stage === "ocr" && !payload.text) {
    showLoading(payload.message || "正在识别截图文字…", payload.source);
    return;
  }
  if (payload.result) {
    sourceText = payload.text || "";
    renderResult(payload.result, payload.source || "selection");
    return;
  }
  if (!payload.text) return;
  sourceText = payload.text;
  showLoading(
    payload.source === "screenshot" ? "文字已识别，正在翻译…" : "正在理解选中内容…",
    payload.source
  );
  try {
    const result = await window.lingua.translate(payload.text);
    renderResult(result, payload.source || "selection");
    if (result.needsEnrichment) {
      void enrichPopupResult(
        payload.text,
        result,
        payload.source || "selection"
      );
    }
  } catch (error) {
    showError(
      error instanceof Error ? error.message : String(error),
      payload.source
    );
  }
}

window.addEventListener("keydown", (event) => {
  if (event.key === "Escape") void window.lingua.closePopup();
});

async function initialize() {
  const settings = await window.lingua.getSettings();
  providerName =
    {
      ollama: "Ollama",
      codex: "Codex",
      openai: "OpenAI",
      compatible: "兼容接口"
    }[settings.provider] || "翻译服务";
  window.lingua.onPopupStart((payload) => void handlePayload(payload));
  window.lingua.onPopupStatus(({ message, progress, error }) => {
    if (error) {
      showError(message, "screenshot");
      return;
    }
    showLoading(
      typeof progress === "number" && progress > 0
        ? `${message} ${progress}%`
        : message,
      "screenshot"
    );
  });
  const pending = await window.lingua.popupReady();
  if (pending) await handlePayload(pending);
}

void initialize();
