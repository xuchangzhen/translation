const LANGUAGE_NAMES = {
  auto: "自动检测",
  "zh-CN": "简体中文",
  "zh-TW": "繁体中文",
  en: "英语",
  ja: "日语",
  ko: "韩语",
  de: "德语",
  fr: "法语",
  es: "西班牙语",
  ru: "俄语"
};

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { toIPA } = require("phonemize");

const RESULT_SHAPE = {
  sourceLanguage: "",
  targetLanguage: "",
  translation: "",
  phonetic: "",
  pronunciationText: "",
  isTechnical: false,
  explanation: "",
  terms: [],
  abbreviations: [],
  alternatives: []
};

const TRANSLATION_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: {
    sourceLanguage: { type: "string" },
    targetLanguage: { type: "string" },
    translation: { type: "string" },
    phonetic: { type: "string" },
    pronunciationText: { type: "string" },
    isTechnical: { type: "boolean" }
  },
  required: [
    "sourceLanguage",
    "targetLanguage",
    "translation",
    "phonetic",
    "pronunciationText",
    "isTechnical"
  ]
};

const ENRICHMENT_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: {
    explanation: { type: "string" },
    terms: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        properties: {
          term: { type: "string" },
          translation: { type: "string" },
          definition: { type: "string" },
          category: { type: "string" }
        },
        required: ["term", "translation", "definition", "category"]
      }
    },
    abbreviations: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        properties: {
          abbreviation: { type: "string" },
          fullName: { type: "string" }
        },
        required: ["abbreviation", "fullName"]
      }
    },
    alternatives: {
      type: "array",
      items: { type: "string" }
    }
  },
  required: [
    "explanation",
    "terms",
    "abbreviations",
    "alternatives"
  ]
};

function normalizeBaseUrl(value) {
  return String(value || "").trim().replace(/\/+$/, "");
}

function isLocalHostname(hostname) {
  return ["127.0.0.1", "localhost", "::1"].includes(
    String(hostname || "").toLowerCase()
  );
}

function ollamaBaseUrls(settings) {
  const configured = normalizeBaseUrl(settings.ollamaUrl);
  const candidates = [configured];
  try {
    const url = new URL(configured);
    if (
      !isLocalHostname(url.hostname) &&
      !(url.port === "19876" && url.pathname.startsWith("/ollama"))
    ) {
      candidates.push(`${url.protocol}//${url.hostname}:19876/ollama`);
    }
  } catch {
    // The configured value will produce the normal actionable connection error.
  }
  return [...new Set(candidates.filter(Boolean))];
}

function stripCodeFence(value) {
  return String(value || "")
    .trim()
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "")
    .trim();
}

function extractResponseText(payload) {
  if (typeof payload?.output_text === "string") return payload.output_text;
  if (typeof payload?.message?.content === "string") return payload.message.content;
  if (typeof payload?.choices?.[0]?.message?.content === "string") {
    return payload.choices[0].message.content;
  }
  if (Array.isArray(payload?.output)) {
    return payload.output
      .flatMap((item) => item?.content || [])
      .map((item) => item?.text || item?.output_text || "")
      .join("");
  }
  return "";
}

function isSingleEnglishWord(text) {
  return /^[A-Za-z][A-Za-z.'’_-]*$/.test(String(text || "").trim());
}

function localEnglishIpa(text) {
  if (!isSingleEnglishWord(text)) return "";
  try {
    const value = String(toIPA(String(text).trim(), "en-US") || "").trim();
    const unwrapped = value.replace(/^[/\[]/, "").replace(/[/\]]$/, "");
    return unwrapped ? `/${unwrapped}/` : "";
  } catch {
    return "";
  }
}

function parseTranslationResult(raw, targetLanguage, sourceText = "") {
  const cleaned = stripCodeFence(raw);
  let parsed;
  try {
    parsed = JSON.parse(cleaned);
  } catch {
    const start = cleaned.indexOf("{");
    const end = cleaned.lastIndexOf("}");
    if (start < 0 || end <= start) {
      throw new Error("翻译服务返回了无法解析的结果");
    }
    parsed = JSON.parse(cleaned.slice(start, end + 1));
  }

  return {
    ...RESULT_SHAPE,
    ...parsed,
    targetLanguage: parsed.targetLanguage || targetLanguage,
    translation: String(parsed.translation || "").trim(),
    phonetic: isSingleEnglishWord(sourceText)
      ? String(parsed.phonetic || "")
          .replace(/[\u3400-\u9fff]+/g, "")
          .trim() || localEnglishIpa(sourceText)
      : "",
    pronunciationText: String(
      parsed.pronunciationText || sourceText || ""
    ).trim(),
    isTechnical:
      parsed.isTechnical === true ||
      String(parsed.isTechnical || "").toLowerCase() === "true",
    explanation: String(parsed.explanation || "").trim(),
    terms: Array.isArray(parsed.terms)
      ? parsed.terms
          .filter((term) => term && term.term)
          .map((term) => ({
            term: String(term.term || ""),
            translation: String(term.translation || ""),
            definition: String(term.definition || ""),
            category: String(term.category || "IT")
          }))
      : [],
    abbreviations: Array.isArray(parsed.abbreviations)
      ? parsed.abbreviations
          .filter((item) => item && item.abbreviation && item.fullName)
          .slice(0, 4)
          .map((item) => ({
            abbreviation: String(item.abbreviation || "").trim(),
            fullName: String(item.fullName || "").trim()
          }))
      : [],
    alternatives: Array.isArray(parsed.alternatives)
      ? parsed.alternatives.map(String).filter(Boolean)
      : []
  };
}

function parseEnrichmentResult(raw) {
  const cleaned = stripCodeFence(raw);
  let parsed;
  try {
    parsed = JSON.parse(cleaned);
  } catch {
    const start = cleaned.indexOf("{");
    const end = cleaned.lastIndexOf("}");
    if (start < 0 || end <= start) {
      throw new Error("技术说明服务返回了无法解析的结果");
    }
    parsed = JSON.parse(cleaned.slice(start, end + 1));
  }
  return {
    explanation: String(parsed.explanation || "").trim(),
    terms: Array.isArray(parsed.terms)
      ? parsed.terms
          .filter((term) => term && term.term)
          .slice(0, 6)
          .map((term) => ({
            term: String(term.term || ""),
            translation: String(term.translation || ""),
            definition: String(term.definition || ""),
            category: String(term.category || "IT")
          }))
      : [],
    abbreviations: Array.isArray(parsed.abbreviations)
      ? parsed.abbreviations
          .filter((item) => item && item.abbreviation && item.fullName)
          .slice(0, 4)
          .map((item) => ({
            abbreviation: String(item.abbreviation || "").trim(),
            fullName: String(item.fullName || "").trim()
          }))
      : [],
    alternatives: Array.isArray(parsed.alternatives)
      ? parsed.alternatives.map(String).filter(Boolean).slice(0, 2)
      : []
  };
}

function hasLikelyAbbreviation(text) {
  return /\b(?:[A-Z]{2,}(?:[/-][A-Z0-9]+)*|[A-Z]+\d+[A-Z\d-]*)\b/.test(
    String(text || "")
  );
}

function isLikelyTechnicalText(text) {
  const value = String(text || "");
  return /(?:\b(?:API|SDK|HTTP|HTTPS|REST|RESTful|GraphQL|gRPC|JSON|XML|YAML|SQL|NoSQL|CSS|HTML|DOM|JavaScript|TypeScript|Node\.?js|Python|Java|Kotlin|Swift|Rust|Golang|C\+\+|C#|backend|frontend|fullstack|database|server|client|framework|library|function|method|class|object|interface|parameter|argument|variable|constant|property|field|reference|pointer|thread|process|kernel|compiler|runtime|event loop|callback|closure|recursion|iterator|async|await|promise|exception|error|debug|logging|module|package|component|plugin|schema|query|index|key|value|buffer|stream|socket|port|protocol|request|response|payload|header|proxy|session|cookie|config|environment|build|release|version|dependency|Docker|Kubernetes|Git|Linux|Unix|firmware|embedded|microcontroller|interrupt|vector|cache|queue|stack|heap|gateway|endpoint|deployment|DevOps|cloud|algorithm|binary tree|dependency injection|middleware|authentication|authorization|token|container|virtual machine|repository|branch|commit|CI\/CD|TCP|UDP|IP|DNS|SSH|WebSocket|MQTT|Redis|Kafka|Nginx|React|Vue|Angular|Spring|Django|Flask|microservice|distributed system|memory|CPU|GPU)\b|(?:前端|后端|全栈|嵌入式|固件|微控制器|单片机|操作系统|内核|编译器|运行时|事件循环|回调|异步|线程|进程|协程|中断|向量|指针|内存|算法|数据结构|二叉树|数据库|缓存|消息队列|网关|接口|中间件|依赖注入|容器|虚拟机|微服务|分布式|云计算|部署|鉴权|认证|授权|令牌|源码|代码|函数|方法|对象|参数|变量|常量|属性|字段|引用|异常|调试|日志|模块|组件|插件|开发|编程)|[{}\[\]();]|(?:--?[a-z][\w-]*))/i.test(
    value
  );
}

function detectSourceLanguage(text, configured = "auto") {
  if (configured && configured !== "auto") return configured;
  const value = String(text || "");
  if (/[\u3040-\u30ff]/.test(value)) return "ja";
  if (/[\uac00-\ud7af]/.test(value)) return "ko";
  if (/[\u3400-\u9fff]/.test(value)) return "zh-CN";
  if (/[\u0400-\u04ff]/.test(value)) return "ru";
  return "en";
}

function translateGemmaLanguage(language) {
  const code = language === "auto" ? "en" : language;
  return {
    "zh-CN": { name: "Chinese (Simplified)", code: "zh-Hans" },
    "zh-TW": { name: "Chinese (Traditional)", code: "zh-Hant" },
    en: { name: "English", code: "en" },
    ja: { name: "Japanese", code: "ja" },
    ko: { name: "Korean", code: "ko" },
    de: { name: "German", code: "de" },
    fr: { name: "French", code: "fr" },
    es: { name: "Spanish", code: "es" },
    ru: { name: "Russian", code: "ru" }
  }[code] || { name: LANGUAGE_NAMES[code] || code, code };
}

function buildTranslateGemmaPrompt(text, settings, technicalMode = false) {
  const source = translateGemmaLanguage(
    detectSourceLanguage(text, settings.sourceLanguage)
  );
  const target = translateGemmaLanguage(settings.targetLanguage);
  return [
    `You are a professional ${source.name} (${source.code}) to ${target.name} (${target.code}) translator. Your goal is to accurately convey the meaning and nuances of the original ${source.name} text while adhering to ${target.name} grammar, vocabulary, and cultural sensitivities.`,
    technicalMode
      ? "Treat the text as software, hardware, networking, operations, or another IT professional context. Use precise established technical terminology; preserve identifiers and code."
      : "",
    `Produce only the ${target.name} translation, without any additional explanations or commentary. Please translate the following ${source.name} text into ${target.name}:`,
    "",
    "",
    text
  ].join("\n");
}

function buildMessages(text, options, technicalMode = false) {
  const source = LANGUAGE_NAMES[options.sourceLanguage] || options.sourceLanguage;
  const target = LANGUAGE_NAMES[options.targetLanguage] || options.targetLanguage;
  const system = [
    "你是快速、严谨的翻译引擎，擅长普通语言与 IT 技术内容。",
    "只完成翻译，不生成解释、术语分析或替代表达。",
    "保持代码、命令、API 名、类名和变量名原样。",
    "必须只输出一个合法 JSON 对象，不要使用 Markdown。",
    "JSON 字段仅包含：sourceLanguage, targetLanguage, translation, phonetic, pronunciationText, isTechnical。",
    "仅当英文原文是一个单词时，phonetic 才返回该单词的标准 IPA；英文句子、短语、中文及其他语言一律返回空字符串。绝不能给中文译文标音。",
    "pronunciationText 必须是适合朗读的原文，不能填译文。",
    "isTechnical 表示文本是否涉及软件开发、前后端、嵌入式、运维、网络、数据库、AI 或其他 IT 专业语境。",
    technicalMode
      ? "用户已明确要求按 IT 专业语境翻译。必须采用准确、通行的技术术语，并将 isTechnical 设为 true。"
      : "",
    "优先尽快返回简洁结果。"
  ].filter(Boolean).join("\n");
  const user = `源语言：${source}\n目标语言：${target}\n待翻译文本：\n${text}`;
  return [
    { role: "system", content: system },
    { role: "user", content: user }
  ];
}

function buildEnrichmentMessages(text, translation, options) {
  const target = LANGUAGE_NAMES[options.targetLanguage] || options.targetLanguage;
  const singleWordLookup = isSingleEnglishWord(text);
  const system = [
    "你负责补充 IT 翻译的技术语境，不要重复生成译文或音标。",
    "必须只输出合法 JSON，不要使用 Markdown。",
    "字段仅包含 explanation, terms, abbreviations, alternatives。",
    "explanation 用目标语言说明原文中的技术内容在实际开发中做什么、典型场景和必要注意点，控制在 2 句话内。",
    "terms 只列真正的 IT 术语，最多 4 项；每项包含 term, translation, definition, category。",
    "abbreviations 列出原文或译文中实际出现的缩写，最多 4 项；每项包含 abbreviation 和英文 fullName。只给出有把握的全称；没有缩写时返回空数组。",
    singleWordLookup
      ? "原文是单个英文词：先判断它是否具有软件、硬件、网络或其他 IT 专业含义；如果有，terms 必须包含这个原词及准确的技术定义，即使它在日常英语中也有普通含义；如果完全没有 IT 含义，返回空 explanation、空 terms 和空 alternatives。"
      : "",
    "alternatives 最多 2 项；没有有价值的替代表达时返回空数组。"
  ].filter(Boolean).join("\n");
  const user = `目标语言：${target}\n原文：\n${text}\n\n已完成译文：\n${translation}`;
  return [
    { role: "system", content: system },
    { role: "user", content: user }
  ];
}

async function fetchJson(url, init, timeoutMs = 60000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      ...init,
      signal: controller.signal
    });
    const text = await response.text();
    let payload;
    try {
      payload = JSON.parse(text);
    } catch {
      payload = { raw: text };
    }
    if (!response.ok) {
      const detail =
        payload?.error?.message ||
        payload?.message ||
        payload?.raw ||
        `HTTP ${response.status}`;
      throw new Error(String(detail).slice(0, 500));
    }
    return payload;
  } catch (error) {
    if (error?.name === "AbortError") {
      throw new Error("翻译请求超时，请检查模型服务或网络");
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function fetchOllamaJson(settings, endpoint, init, timeoutMs) {
  let lastError;
  for (const baseUrl of ollamaBaseUrls(settings)) {
    try {
      return await fetchJson(`${baseUrl}${endpoint}`, init, timeoutMs);
    } catch (error) {
      lastError = error;
      const message = String(error?.message || error || "");
      if (
        !(
          error instanceof TypeError ||
          /fetch failed|ECONNREFUSED|Failed to connect|ENOTFOUND/i.test(message)
        )
      ) {
        throw error;
      }
    }
  }
  throw lastError;
}

async function translateWithQwenOllama(text, settings, technicalMode = false) {
  const messages = buildMessages(text, settings, technicalMode);
  let payload;
  try {
    payload = await fetchOllamaJson(
      settings,
      "/api/chat",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          model: settings.ollamaModel,
          messages,
          stream: false,
          format: TRANSLATION_SCHEMA,
          think: false,
          keep_alive: "30m",
          options: {
            temperature: 0,
            num_ctx: text.length <= 500 ? 2048 : 4096,
            num_predict: text.length <= 160 ? 240 : 700
          }
        })
      },
      90000
    );
  } catch (error) {
    throw enhanceOllamaError(error, settings);
  }
  return parseTranslationResult(
    extractResponseText(payload),
    settings.targetLanguage,
    text
  );
}

async function translateWithTranslateGemma(text, settings, technicalMode = false) {
  const sourceLanguage = detectSourceLanguage(text, settings.sourceLanguage);
  const payload = await fetchOllamaJson(
    settings,
    "/api/chat",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model: settings.ollamaTranslationModel || "translategemma:4b",
        messages: [
          {
            role: "user",
            content: buildTranslateGemmaPrompt(text, settings, technicalMode)
          }
        ],
        stream: false,
        think: false,
        keep_alive: "30m",
        options: {
          temperature: 0,
          num_ctx: text.length <= 1000 ? 2048 : 4096,
          num_predict: text.length <= 500 ? 700 : 2400
        }
      })
    },
    120000
  );
  const translation = stripCodeFence(extractResponseText(payload))
    .replace(/^["“]|["”]$/g, "")
    .trim();
  if (!translation) throw new Error("TranslateGemma 没有返回译文");
  return {
    ...RESULT_SHAPE,
    sourceLanguage,
    targetLanguage: settings.targetLanguage,
    translation,
    phonetic:
      sourceLanguage === "en" && isSingleEnglishWord(text)
        ? localEnglishIpa(text)
        : "",
    pronunciationText: text,
    isTechnical:
      technicalMode ||
      isLikelyTechnicalText(text) ||
      isLikelyTechnicalText(translation)
  };
}

async function translateWithOllama(text, settings, technicalMode = false) {
  if (settings.useTranslateGemma === true) {
    try {
      return await translateWithTranslateGemma(text, settings, technicalMode);
    } catch (error) {
      console.warn(
        `TranslateGemma unavailable, falling back to ${settings.ollamaModel}:`,
        error
      );
    }
  }
  return translateWithQwenOllama(text, settings, technicalMode);
}

async function enrichWithOllama(text, translation, settings) {
  let payload;
  try {
    payload = await fetchOllamaJson(
      settings,
      "/api/chat",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          model: settings.ollamaModel,
          messages: buildEnrichmentMessages(text, translation, settings),
          stream: false,
          format: ENRICHMENT_SCHEMA,
          think: false,
          keep_alive: "30m",
          options: {
            temperature: 0,
            num_ctx: 3072,
            num_predict: 500
          }
        })
      },
      90000
    );
  } catch (error) {
    throw enhanceOllamaError(error, settings);
  }
  return parseEnrichmentResult(extractResponseText(payload));
}

function enhanceOllamaError(error, settings) {
  const message = String(error?.message || error || "");
  const baseUrl = normalizeBaseUrl(settings.ollamaUrl);
  if (
    error instanceof TypeError ||
    /fetch failed|ECONNREFUSED|Failed to connect|ENOTFOUND/i.test(message)
  ) {
    const locationHint = /127\.0\.0\.1|localhost/i.test(baseUrl)
      ? "127.0.0.1 只代表当前这台电脑；如果模型运行在 Mac mini 上，请填写 Mac mini 的局域网 IP。"
      : "请确认 Mac mini 与当前设备位于同一局域网，并允许 11434 端口通过防火墙。";
    return new Error(
      `无法连接到 Ollama（${baseUrl}）。请先安装并启动 Ollama。${locationHint}`
    );
  }
  if (/model .* not found|not found, try pulling/i.test(message)) {
    return new Error(
      `Ollama 已连接，但没有模型“${settings.ollamaModel}”。请先运行：ollama pull ${settings.ollamaModel}`
    );
  }
  return error instanceof Error ? error : new Error(message);
}

async function listOllamaModels(settings) {
  let payload;
  try {
    payload = await fetchOllamaJson(
      settings,
      "/api/tags",
      { method: "GET" },
      5000
    );
  } catch (error) {
    throw enhanceOllamaError(error, settings);
  }
  return Array.isArray(payload?.models)
    ? payload.models
        .map((model) => ({
          name: String(model?.name || model?.model || ""),
          size: Number(model?.size || 0),
          parameterSize: String(model?.details?.parameter_size || ""),
          quantization: String(model?.details?.quantization_level || ""),
          family: String(model?.details?.family || "")
        }))
        .filter((model) => model.name)
    : [];
}

async function inspectOllama(settings) {
  const catalog = await listOllamaModels(settings);
  const models = catalog.map((model) => model.name);
  const requested = String(settings.ollamaModel || "");
  const requestedBase = requested.split(":")[0];
  const hasModel = models.some(
    (model) =>
      model === requested ||
      model.split(":")[0] === requestedBase
  );
  if (!hasModel) {
    const installed = models.length ? `当前已有：${models.join("、")}。` : "";
    throw new Error(
      `Ollama 服务已启动，但没有模型“${requested}”。${installed}请运行：ollama pull ${requested}`
    );
  }
  return models;
}

async function warmOllama(settings) {
  if (settings.provider !== "ollama") return false;
  const model =
    settings.useTranslateGemma === true
      ? settings.ollamaTranslationModel || "translategemma:4b"
      : settings.ollamaModel;
  await fetchOllamaJson(
    settings,
    "/api/generate",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model,
        prompt: "",
        stream: false,
        keep_alive: "30m",
        options: { num_predict: 1 }
      })
    },
    120000
  );
  return true;
}

async function translateWithOpenAI(text, settings, apiKey, technicalMode = false) {
  if (!apiKey) throw new Error("请先在设置中填写 OpenAI API Key");
  const messages = buildMessages(text, settings, technicalMode);
  const input = messages.map((message) => ({
    role: message.role,
    content: [{ type: "input_text", text: message.content }]
  }));
  const payload = await fetchJson(
    `${normalizeBaseUrl(settings.openaiBaseUrl)}/responses`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`
      },
      body: JSON.stringify({
        model: settings.openaiModel,
        input,
        reasoning: { effort: "none" },
        text: { verbosity: "low" },
        max_output_tokens: 700
      })
    }
  );
  return parseTranslationResult(
    extractResponseText(payload),
    settings.targetLanguage,
    text
  );
}

async function enrichWithOpenAI(text, translation, settings, apiKey) {
  if (!apiKey) throw new Error("请先在设置中填写 OpenAI API Key");
  const input = buildEnrichmentMessages(text, translation, settings).map(
    (message) => ({
      role: message.role,
      content: [{ type: "input_text", text: message.content }]
    })
  );
  const payload = await fetchJson(
    `${normalizeBaseUrl(settings.openaiBaseUrl)}/responses`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`
      },
      body: JSON.stringify({
        model: settings.openaiModel,
        input,
        reasoning: { effort: "none" },
        text: { verbosity: "low" },
        max_output_tokens: 900
      })
    }
  );
  return parseEnrichmentResult(extractResponseText(payload));
}

function decodeHtmlEntities(value) {
  return String(value || "")
    .replaceAll("&quot;", '"')
    .replaceAll("&#39;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&");
}

async function translateWithGoogle(text, settings, apiKey, technicalMode = false) {
  if (!apiKey) throw new Error("请先在设置中填写 Google Cloud API Key");
  const body = {
    q: text,
    target: settings.targetLanguage,
    format: "text",
    model: "nmt"
  };
  if (settings.sourceLanguage && settings.sourceLanguage !== "auto") {
    body.source = settings.sourceLanguage;
  }
  const payload = await fetchJson(
    `https://translation.googleapis.com/language/translate/v2?key=${encodeURIComponent(apiKey)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    },
    30000
  );
  const item = payload?.data?.translations?.[0];
  const translation = decodeHtmlEntities(item?.translatedText).trim();
  if (!translation) throw new Error("Google Cloud Translation 没有返回译文");
  const sourceLanguage = detectSourceLanguage(
    text,
    settings.sourceLanguage === "auto"
      ? item?.detectedSourceLanguage || "auto"
      : settings.sourceLanguage
  );
  return {
    ...RESULT_SHAPE,
    sourceLanguage,
    targetLanguage: settings.targetLanguage,
    translation,
    phonetic:
      sourceLanguage === "en" && isSingleEnglishWord(text)
        ? localEnglishIpa(text)
        : "",
    pronunciationText: text,
    isTechnical:
      technicalMode ||
      isLikelyTechnicalText(text) ||
      isLikelyTechnicalText(translation)
  };
}

async function translateWithCompatible(text, settings, apiKey, technicalMode = false) {
  const headers = { "Content-Type": "application/json" };
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;
  const payload = await fetchJson(
    `${normalizeBaseUrl(settings.compatibleBaseUrl)}/chat/completions`,
    {
      method: "POST",
      headers,
      body: JSON.stringify({
        model: settings.compatibleModel,
        messages: buildMessages(text, settings, technicalMode),
        temperature: 0.1,
        response_format: { type: "json_object" }
      })
    }
  );
  return parseTranslationResult(
    extractResponseText(payload),
    settings.targetLanguage,
    text
  );
}

async function enrichWithCompatible(text, translation, settings, apiKey) {
  const headers = { "Content-Type": "application/json" };
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;
  const payload = await fetchJson(
    `${normalizeBaseUrl(settings.compatibleBaseUrl)}/chat/completions`,
    {
      method: "POST",
      headers,
      body: JSON.stringify({
        model: settings.compatibleModel,
        messages: buildEnrichmentMessages(text, translation, settings),
        temperature: 0.1,
        response_format: { type: "json_object" }
      })
    }
  );
  return parseEnrichmentResult(extractResponseText(payload));
}

function resolveCodexPath(configuredPath = "") {
  const configured = String(configuredPath || "").trim();
  if (configured) return configured;
  if (
    process.platform === "darwin" &&
    fs.existsSync("/Applications/ChatGPT.app/Contents/Resources/codex")
  ) {
    return "/Applications/ChatGPT.app/Contents/Resources/codex";
  }
  for (const candidate of ["/opt/homebrew/bin/codex", "/usr/local/bin/codex"]) {
    if (fs.existsSync(candidate)) return candidate;
  }
  return "codex";
}

function runCodexProcess(settings, args, input = "", timeoutMs = 180000) {
  return new Promise((resolve, reject) => {
    const executable = resolveCodexPath(settings.codexPath);
    const child = spawn(executable, args, {
      cwd: os.tmpdir(),
      env: process.env,
      windowsHide: true,
      stdio: ["pipe", "pipe", "pipe"]
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const finish = (callback) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      callback();
    };
    const timer = setTimeout(() => {
      child.kill();
      finish(() => reject(new Error("Codex 请求超时，请稍后重试")));
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
      if (stdout.length > 2_000_000) stdout = stdout.slice(-2_000_000);
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
      if (stderr.length > 2_000_000) stderr = stderr.slice(-2_000_000);
    });
    child.on("error", (error) =>
      finish(() => {
        if (error.code === "ENOENT") {
          reject(
            new Error(
              "没有找到 Codex CLI。请安装 Codex，或在设置中填写 codex 可执行文件路径。"
            )
          );
          return;
        }
        reject(error);
      })
    );
    child.on("close", (code) =>
      finish(() => {
        if (code !== 0) {
          const detail = stderr.trim() || stdout.trim() || `退出码 ${code}`;
          const authHint = /login|auth|credential|unauthorized/i.test(detail)
            ? "请先点击“登录 ChatGPT”。"
            : "";
          reject(new Error(`Codex 执行失败：${detail.slice(-700)} ${authHint}`.trim()));
          return;
        }
        resolve({ stdout, stderr });
      })
    );
    if (input) child.stdin.write(input);
    child.stdin.end();
  });
}

async function codexLoginStatus(settings) {
  const startedAt = Date.now();
  const { stdout, stderr } = await runCodexProcess(
    settings,
    ["login", "status"],
    "",
    15000
  );
  const message = (stdout || stderr).trim() || "Codex 已登录";
  return {
    ok: /logged in/i.test(message) || message.includes("已登录"),
    message,
    latencyMs: Date.now() - startedAt,
    model: settings.codexModel || "Codex 默认模型"
  };
}

async function codexLogin(settings) {
  const { stdout, stderr } = await runCodexProcess(
    settings,
    ["login"],
    "",
    300000
  );
  return {
    ok: true,
    message: (stdout || stderr).trim() || "ChatGPT 登录完成"
  };
}

async function codexModels(settings) {
  const { stdout } = await runCodexProcess(
    settings,
    ["debug", "models"],
    "",
    30000
  );
  let payload;
  try {
    payload = JSON.parse(stdout.trim());
  } catch {
    throw new Error("Codex 返回的模型列表无法解析，请更新 Codex 后重试");
  }
  return (Array.isArray(payload?.models) ? payload.models : [])
    .filter((model) => model?.slug && model?.visibility === "list")
    .sort((left, right) => Number(left.priority || 999) - Number(right.priority || 999))
    .map((model) => ({
      id: String(model.slug),
      name: String(model.display_name || model.slug),
      description: String(model.description || ""),
      defaultReasoning: String(model.default_reasoning_level || ""),
      reasoningLevels: Array.isArray(model.supported_reasoning_levels)
        ? model.supported_reasoning_levels
            .map((level) => String(level?.effort || ""))
            .filter(Boolean)
        : []
    }));
}

async function runCodexStructured(messages, schema, settings, prefix) {
  const temporaryDirectory = fs.mkdtempSync(
    path.join(os.tmpdir(), "translation-codex-")
  );
  const schemaPath = path.join(temporaryDirectory, `${prefix}.schema.json`);
  const outputPath = path.join(temporaryDirectory, `${prefix}.result.json`);
  fs.writeFileSync(schemaPath, JSON.stringify(schema));
  const prompt = `${messages[0].content}\n\n${messages[1].content}`;
  const args = [
    "exec",
    "--ephemeral",
    "--skip-git-repo-check",
    "--sandbox",
    "read-only",
    "--ignore-user-config",
    "--ignore-rules",
    "--config",
    'model_reasoning_effort="low"',
    "--output-schema",
    schemaPath,
    "--output-last-message",
    outputPath,
    "--color",
    "never"
  ];
  if (settings.codexModel) args.push("--model", settings.codexModel);
  args.push("-");

  try {
    const result = await runCodexProcess(settings, args, prompt, 180000);
    return fs.existsSync(outputPath)
      ? fs.readFileSync(outputPath, "utf8")
      : result.stdout;
  } finally {
    fs.rmSync(temporaryDirectory, { recursive: true, force: true });
  }
}

async function translateWithCodex(text, settings, technicalMode = false) {
  const output = await runCodexStructured(
    buildMessages(text, settings, technicalMode),
    TRANSLATION_SCHEMA,
    settings,
    "translation"
  );
  return parseTranslationResult(output, settings.targetLanguage, text);
}

async function enrichWithCodex(text, translation, settings) {
  const output = await runCodexStructured(
    buildEnrichmentMessages(text, translation, settings),
    ENRICHMENT_SCHEMA,
    settings,
    "enrichment"
  );
  return parseEnrichmentResult(output);
}

async function translateTextWithMode(
  text,
  settings,
  apiKey = "",
  technicalMode = false
) {
  const normalizedText = String(text || "").trim();
  if (!normalizedText) throw new Error("请输入或选择要翻译的内容");
  if (normalizedText.length > 12000) {
    throw new Error("单次翻译请控制在 12,000 个字符以内");
  }

  let result;
  switch (settings.provider) {
    case "codex":
      result = await translateWithCodex(normalizedText, settings, technicalMode);
      break;
    case "openai":
      result = await translateWithOpenAI(
        normalizedText,
        settings,
        apiKey,
        technicalMode
      );
      break;
    case "google":
      result = await translateWithGoogle(
        normalizedText,
        settings,
        apiKey,
        technicalMode
      );
      break;
    case "compatible":
      result = await translateWithCompatible(
        normalizedText,
        settings,
        apiKey,
        technicalMode
      );
      break;
    case "ollama":
      result = await translateWithOllama(normalizedText, settings, technicalMode);
      break;
    default:
      throw new Error(`不支持的翻译服务：${settings.provider}`);
  }
  return {
    ...result,
    isTechnical: technicalMode || result.isTechnical,
    needsEnrichment:
      technicalMode ||
      result.isTechnical === true ||
      isSingleEnglishWord(normalizedText) ||
      isLikelyTechnicalText(normalizedText) ||
      isLikelyTechnicalText(result.translation) ||
      hasLikelyAbbreviation(normalizedText) ||
      hasLikelyAbbreviation(result.translation)
  };
}

async function translateText(text, settings, apiKey = "") {
  return translateTextWithMode(text, settings, apiKey, false);
}

async function translateTechnicalText(text, settings, apiKey = "") {
  return translateTextWithMode(text, settings, apiKey, true);
}

async function enrichTranslation(text, translation, settings, apiKey = "") {
  const normalizedText = String(text || "").trim();
  const normalizedTranslation = String(translation || "").trim();
  if (!normalizedText || !normalizedTranslation) {
    return { explanation: "", terms: [], abbreviations: [], alternatives: [] };
  }
  switch (settings.provider) {
    case "codex":
      return enrichWithCodex(normalizedText, normalizedTranslation, settings);
    case "openai":
      return enrichWithOpenAI(
        normalizedText,
        normalizedTranslation,
        settings,
        apiKey
      );
    case "compatible":
      return enrichWithCompatible(
        normalizedText,
        normalizedTranslation,
        settings,
        apiKey
      );
    case "ollama":
      return enrichWithOllama(normalizedText, normalizedTranslation, settings);
    case "google":
      return enrichWithOllama(normalizedText, normalizedTranslation, settings);
    default:
      throw new Error(`不支持的翻译服务：${settings.provider}`);
  }
}

async function testProvider(settings, apiKey = "") {
  const startedAt = Date.now();
  if (settings.provider === "codex") {
    return codexLoginStatus(settings);
  }
  if (settings.provider === "ollama") {
    const models = await inspectOllama(settings);
    const translationModel =
      settings.ollamaTranslationModel || "translategemma:4b";
    const translationModelBase = translationModel.split(":")[0];
    const hasTranslateGemma = models.some(
      (model) =>
        model === translationModel ||
        model.split(":")[0] === translationModelBase
    );
    return {
      ok: true,
      latencyMs: Date.now() - startedAt,
      model:
        settings.useTranslateGemma === true && hasTranslateGemma
          ? `${translationModel} + ${settings.ollamaModel}`
          : settings.ollamaModel,
      note:
        settings.useTranslateGemma === true && !hasTranslateGemma
          ? `Qwen 可用；未安装 ${translationModel}，主翻译会自动回退 Qwen`
          : "主译文与技术解析模型均可用"
    };
  }
  const result = await translateText("Hello, API gateway.", settings, apiKey);
  return {
    ok: Boolean(result.translation),
    latencyMs: Date.now() - startedAt,
    model:
      settings.provider === "ollama"
        ? settings.ollamaModel
        : settings.provider === "google"
          ? "Google Cloud Translation"
          : settings.provider === "openai"
          ? settings.openaiModel
          : settings.compatibleModel
  };
}

module.exports = {
  LANGUAGE_NAMES,
  buildMessages,
  buildEnrichmentMessages,
  codexLogin,
  codexLoginStatus,
  codexModels,
  extractResponseText,
  enrichTranslation,
  hasLikelyAbbreviation,
  isLikelyTechnicalText,
  isSingleEnglishWord,
  listOllamaModels,
  localEnglishIpa,
  normalizeBaseUrl,
  parseTranslationResult,
  testProvider,
  translateTechnicalText,
  translateText,
  warmOllama
};
