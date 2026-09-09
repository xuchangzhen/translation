type Provider = "ollama" | "google" | "openai" | "compatible" | "codex";

interface AppSettings {
  themeMode: "system" | "light" | "dark";
  syncClientId: string;
  syncClientName: string;
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
  useThinking: boolean;
  speechProvider: "mambo" | "system";
  mamboUrl: string;
  mamboRoot: string;
  ocrLanguages: string;
  launchAtLogin: boolean;
  popupAlwaysOnTop: boolean;
  androidMemorySyncEnabled: boolean;
  androidMemoryPaired: boolean;
  androidMemoryPairingUnavailable: boolean;
  androidMemoryPairing: string;
  apiKeyConfigured: boolean;
  apiKeyConfiguredByProvider: Partial<Record<Provider, boolean>>;
  apiKey: string;
}

interface TermItem {
  term: string;
  translation: string;
  definition: string;
  category: string;
}

interface AbbreviationItem {
  abbreviation: string;
  fullName: string;
}

interface TranslationResult {
  sourceLanguage: string;
  targetLanguage: string;
  sourceFormat?: "plain" | "markdown";
  translation: string;
  phonetic: string;
  pronunciationText: string;
  isTechnical?: boolean;
  explanation: string;
  terms: TermItem[];
  abbreviations: AbbreviationItem[];
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
  setThemeMode(mode: AppSettings["themeMode"]): Promise<AppSettings>;
  setShortcutRecording(active: boolean): Promise<boolean>;
  clearApiKey(provider?: Provider): Promise<AppSettings>;
  copyText(text: string): Promise<boolean>;
  getMemorySyncStatus(): Promise<MemorySyncStatus>;
  getDesktopClients(): Promise<DesktopClientsResult>;
  captureMemory(
    text: string,
    result: TranslationResult
  ): Promise<MemoryMutationResult>;
  testAndroidMemory(pairingValue?: string): Promise<AndroidMemoryTestResult>;
  provisionAndroidMemory(
    serverUrl: string,
    registrationKey: string
  ): Promise<{
    settings: AppSettings;
    sync: MemorySyncStatus;
    pairingUri: string;
  }>;
  getAndroidMemoryPairing(): Promise<string>;
  createDesktopJoinLink(): Promise<{
    joinLink: string;
    expiresAt: number;
    deviceId: string;
  }>;
  claimDesktopJoinLink(joinLink: string): Promise<{
    settings: AppSettings;
    sync: MemorySyncStatus;
    connection: AndroidMemoryTestResult;
  }>;
  clearAndroidMemoryPairing(): Promise<{
    settings: AppSettings;
    sync: MemorySyncStatus;
  }>;
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
  translateTechnical(
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
    abbreviations: AbbreviationItem[];
    alternatives: string[];
  }>;
  compatibleModels(settings: Partial<AppSettings>): Promise<string[]>;
  testProvider(
    settings: Partial<AppSettings>
  ): Promise<{ ok: boolean; latencyMs: number; model: string; note?: string }>;
  startScreenshot(): Promise<boolean>;
  openPermissionSettings(kind: "screen" | "accessibility"): Promise<boolean>;
  openOllamaDownload(): Promise<boolean>;
  openExternal(url: string): Promise<boolean>;
  getAppInfo(): Promise<{
    version: string;
    platform: string;
    isPackaged: boolean;
  }>;
  getUpdateStatus(): Promise<UpdateStatus>;
  checkForUpdates(): Promise<UpdateStatus>;
  downloadUpdate(): Promise<UpdateStatus>;
  installUpdate(): Promise<boolean>;
  openUpdateRepair(): Promise<boolean>;
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
  onMemorySyncChanged(callback: (payload: MemorySyncStatus) => void): () => void;
  onDesktopClientsChanged(callback: (payload: DesktopClientsResult) => void): () => void;
  onThemeChanged(callback: (payload: { mode: AppSettings["themeMode"]; dark: boolean }) => void): () => void;
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

interface MemoryMutationResult {
  added: number;
  updated: number;
  candidateCount: number;
  sync: MemorySyncStatus;
}

interface MemorySyncStatus {
  total: number;
  pending: number;
  synced: number;
  lastSyncedAt: number;
  configured: boolean;
  state: "idle" | "syncing" | "success" | "waiting";
  message: string;
}

interface DesktopClient {
  clientId: string;
  name: string;
  platform: "darwin" | "win32" | "linux" | string;
  appVersion: string;
  firstSeenAt: number;
  lastSeenAt: number;
}

interface DesktopClientsResult {
  serverTime: number;
  clients: DesktopClient[];
}

interface AndroidMemoryTestResult {
  ok: boolean;
  latencyMs: number;
  serverUrl: string;
  deviceId: string;
  pendingBatches: number;
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
    | "repair"
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
