type Provider = "ollama" | "google" | "openai" | "compatible" | "codex";

interface AppSettings {
  provider: Provider;
  sourceLanguage: string;
  targetLanguage: string;
  selectionShortcut: string;
  screenshotShortcut: string;
  popupToggleShortcut: string;
  ollamaUrl: string;
  ollamaModel: string;
  ollamaTranslationModel: string;
  useTranslateGemma: boolean;
  openaiBaseUrl: string;
  openaiModel: string;
  compatibleBaseUrl: string;
  compatibleModel: string;
  codexPath: string;
  codexModel: string;
  speechProvider: "mambo" | "system";
  mamboUrl: string;
  mamboRoot: string;
  ocrLanguages: string;
  launchAtLogin: boolean;
  popupAlwaysOnTop: boolean;
  apiKeyConfigured: boolean;
  apiKey: string;
}

interface TermItem {
  term: string;
  translation: string;
  definition: string;
  category: string;
}

interface TranslationResult {
  sourceLanguage: string;
  targetLanguage: string;
  translation: string;
  phonetic: string;
  pronunciationText: string;
  isTechnical?: boolean;
  explanation: string;
  terms: TermItem[];
  alternatives: string[];
  needsEnrichment?: boolean;
  cacheHit?: boolean;
  enrichmentFailed?: boolean;
}

interface OverlayContext {
  displayId: number;
  imageDataUrl: string;
  width: number;
  height: number;
}

interface PopupPayload {
  text?: string;
  source?: "selection" | "screenshot";
  stage?: "ocr" | "translate";
  message?: string;
  error?: string;
  result?: TranslationResult;
}

interface LinguaApi {
  getSettings(): Promise<AppSettings>;
  saveSettings(
    settings: Partial<AppSettings>
  ): Promise<{ settings: AppSettings; shortcutFailures: string[] }>;
  clearApiKey(): Promise<AppSettings>;
  copyText(text: string): Promise<boolean>;
  codexLogin(settings?: Partial<AppSettings>): Promise<{
    ok: boolean;
    message: string;
  }>;
  codexStatus(settings?: Partial<AppSettings>): Promise<{
    ok: boolean;
    message: string;
  }>;
  codexModels(settings?: Partial<AppSettings>): Promise<CodexModel[]>;
  ollamaModels(settings?: Partial<AppSettings>): Promise<OllamaModel[]>;
  synthesizeSpeech(
    text: string,
    language: string
  ): Promise<{
    ok: boolean;
    fallback?: boolean;
    error?: string;
    audio?: Uint8Array;
    mimeType?: string;
  }>;
  prepareSpeech(
    text: string,
    language: string
  ): Promise<{ ok: boolean; fallback?: boolean; error?: string }>;
  testSpeech(settings?: Partial<AppSettings>): Promise<{
    ok: boolean;
    latencyMs: number;
    message: string;
  }>;
  translate(
    text: string,
    overrides?: Partial<AppSettings>
  ): Promise<TranslationResult>;
  enrichTranslation(
    text: string,
    translation: string,
    overrides?: Partial<AppSettings>
  ): Promise<{
    explanation: string;
    terms: TermItem[];
    alternatives: string[];
  }>;
  testProvider(
    settings: Partial<AppSettings>
  ): Promise<{ ok: boolean; latencyMs: number; model: string; note?: string }>;
  startScreenshot(): Promise<boolean>;
  openPermissionSettings(kind: "screen" | "accessibility"): Promise<boolean>;
  openOllamaDownload(): Promise<boolean>;
  getAppInfo(): Promise<{
    version: string;
    platform: string;
    isPackaged: boolean;
  }>;
  getUpdateStatus(): Promise<UpdateStatus>;
  checkForUpdates(): Promise<UpdateStatus>;
  downloadUpdate(): Promise<UpdateStatus>;
  installUpdate(): Promise<boolean>;
  onUpdateStatus(callback: (payload: UpdateStatus) => void): () => void;
  onTranslationStart(
    callback: (payload: { text: string; source: string }) => void
  ): () => void;
  onStatus(
    callback: (payload: {
      message: string;
      progress?: number;
      error?: boolean;
    }) => void
  ): () => void;
  onTranslationHydrate(
    callback: (payload: { text: string; result: TranslationResult }) => void
  ): () => void;
  onPopupStart(callback: (payload: PopupPayload) => void): () => void;
  popupReady(): Promise<PopupPayload | null>;
  onPopupStatus(
    callback: (payload: {
      message: string;
      progress?: number;
      error?: boolean;
    }) => void
  ): () => void;
  resizePopup(height: number): Promise<boolean>;
  closePopup(): Promise<boolean>;
  openPopupInMain(payload: {
    text: string;
    result: TranslationResult;
  }): Promise<boolean>;
  overlayContext(): Promise<OverlayContext>;
  completeSelection(rect: {
    x: number;
    y: number;
    width: number;
    height: number;
  }): Promise<boolean>;
  cancelSelection(): Promise<boolean>;
}

interface UpdateStatus {
  status:
    | "idle"
    | "checking"
    | "available"
    | "downloading"
    | "downloaded"
    | "current"
    | "error"
    | "development";
  message: string;
  progress: number;
  version: string;
}

interface CodexModel {
  id: string;
  name: string;
  description: string;
  defaultReasoning: string;
  reasoningLevels: string[];
}

interface OllamaModel {
  name: string;
  size: number;
  parameterSize: string;
  quantization: string;
  family: string;
}

interface Window {
  lingua: LinguaApi;
}
