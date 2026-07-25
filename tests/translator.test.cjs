const test = require("node:test");
const assert = require("node:assert/strict");
const {
  buildMessages,
  enrichTranslation,
  extractResponseText,
  isLikelyTechnicalText,
  isSingleEnglishWord,
  localEnglishIpa,
  normalizeBaseUrl,
  parseTranslationResult,
  translateText
} = require("../src/lib/translator.cjs");

test("normalizes provider URLs", () => {
  assert.equal(normalizeBaseUrl(" http://localhost:11434/// "), "http://localhost:11434");
});

test("extracts Responses API text", () => {
  const payload = {
    output: [{ content: [{ type: "output_text", text: '{"translation":"你好"}' }] }]
  };
  assert.equal(extractResponseText(payload), '{"translation":"你好"}');
});

test("parses fenced JSON and normalizes arrays", () => {
  const result = parseTranslationResult(
    '```json\n{"translation":"网关","terms":[{"term":"gateway","definition":"入口"}]}\n```',
    "zh-CN",
    "gateway"
  );
  assert.equal(result.translation, "网关");
  assert.equal(result.targetLanguage, "zh-CN");
  assert.equal(result.terms[0].category, "IT");
  assert.deepEqual(result.alternatives, []);
});

test("translation prompt requests only the fast core result", () => {
  const messages = buildMessages("event loop", {
    sourceLanguage: "en",
    targetLanguage: "zh-CN"
  });
  assert.match(messages[0].content, /IT/);
  assert.match(messages[0].content, /JSON/);
  assert.match(messages[0].content, /不生成解释/);
  assert.match(messages[0].content, /英文原文/);
  assert.match(messages[1].content, /event loop/);
});

test("English IPA and pronunciation always refer to the English source", () => {
  const result = parseTranslationResult(
    JSON.stringify({
      translation: "你好",
      phonetic: "/həˈloʊ/ 你好",
      pronunciationText: "",
      isTechnical: false
    }),
    "zh-CN",
    "Hello"
  );
  assert.equal(result.phonetic, "/həˈloʊ/");
  assert.equal(result.pronunciationText, "Hello");
});

test("non-English source does not display an invented English IPA", () => {
  const result = parseTranslationResult(
    JSON.stringify({
      translation: "Hello",
      phonetic: "/həˈloʊ/",
      pronunciationText: "",
      isTechnical: false
    }),
    "en",
    "你好"
  );
  assert.equal(result.phonetic, "");
  assert.equal(result.pronunciationText, "你好");
});

test("IPA is shown for one English word but hidden for phrases and sentences", () => {
  assert.equal(isSingleEnglishWord("backend"), true);
  assert.equal(isSingleEnglishWord("don't"), true);
  assert.equal(isSingleEnglishWord("event loop"), false);
  assert.match(localEnglishIpa("Hello"), /^\/.+\/$/);
  const word = parseTranslationResult(
    JSON.stringify({
      translation: "你好",
      phonetic: "",
      pronunciationText: "Hello",
      isTechnical: false
    }),
    "zh-CN",
    "Hello"
  );
  assert.match(word.phonetic, /^\/.+\/$/);
  const result = parseTranslationResult(
    JSON.stringify({
      translation: "事件循环会分派回调。",
      phonetic: "/ɪˈvent luːp/",
      pronunciationText: "The event loop dispatches callbacks.",
      isTechnical: true
    }),
    "zh-CN",
    "The event loop dispatches callbacks."
  );
  assert.equal(result.phonetic, "");
});

test("technical enrichment is only requested for likely IT text", () => {
  assert.equal(isLikelyTechnicalText("Hello, nice to meet you."), false);
  assert.equal(isLikelyTechnicalText("The event loop schedules callbacks."), true);
  assert.equal(isLikelyTechnicalText("后端开发使用依赖注入管理组件"), true);
  assert.equal(isLikelyTechnicalText("An interrupt vector maps hardware events."), true);
});

test("model technical classification triggers enrichment for unknown jargon", async () => {
  const originalFetch = global.fetch;
  global.fetch = async () => ({
    ok: true,
    text: async () =>
      JSON.stringify({
        message: {
          content: JSON.stringify({
            sourceLanguage: "英语",
            targetLanguage: "简体中文",
            translation: "幂等性",
            phonetic: "/ˌaɪdəmˈpoʊtənsi/",
            pronunciationText: "idempotency",
            isTechnical: true
          })
        }
      })
  });
  try {
    const result = await translateText("idempotency", {
      provider: "ollama",
      sourceLanguage: "en",
      targetLanguage: "zh-CN",
      ollamaUrl: "http://127.0.0.1:11434",
      ollamaModel: "qwen3:8b"
    });
    assert.equal(result.needsEnrichment, true);
  } finally {
    global.fetch = originalFetch;
  }
});

test("background enrichment runs after a model-only technical classification", async () => {
  const originalFetch = global.fetch;
  global.fetch = async () => ({
    ok: true,
    text: async () =>
      JSON.stringify({
        message: {
          content: JSON.stringify({
            explanation: "用于保证重复执行同一操作不会产生额外副作用。",
            terms: [
              {
                term: "idempotency",
                translation: "幂等性",
                definition: "重复调用的效果与调用一次相同。",
                category: "后端开发"
              }
            ],
            alternatives: []
          })
        }
      })
  });
  try {
    const result = await enrichTranslation(
      "idempotency",
      "幂等性",
      {
        provider: "ollama",
        sourceLanguage: "en",
        targetLanguage: "zh-CN",
        ollamaUrl: "http://127.0.0.1:11434",
        ollamaModel: "qwen3:8b"
      }
    );
    assert.match(result.explanation, /重复执行/);
    assert.equal(result.terms[0].category, "后端开发");
  } finally {
    global.fetch = originalFetch;
  }
});

test("Ollama short translation disables thinking and keeps the model warm", async () => {
  const originalFetch = global.fetch;
  let requestBody;
  global.fetch = async (_url, init) => {
    requestBody = JSON.parse(init.body);
    return {
      ok: true,
      text: async () =>
        JSON.stringify({
          message: {
            content: JSON.stringify({
              sourceLanguage: "英语",
              targetLanguage: "简体中文",
              translation: "你好",
              phonetic: "/həˈloʊ/",
              pronunciationText: "Hello",
              isTechnical: false,
              explanation: "",
              terms: [],
              alternatives: []
            })
          }
        })
    };
  };

  try {
    const result = await translateText("Hello", {
      provider: "ollama",
      sourceLanguage: "en",
      targetLanguage: "zh-CN",
      ollamaUrl: "http://127.0.0.1:11434",
      ollamaModel: "qwen3:8b"
    });
    assert.equal(result.translation, "你好");
    assert.equal(requestBody.think, false);
    assert.equal(requestBody.keep_alive, "30m");
    assert.equal(requestBody.options.num_predict, 240);
    assert.equal(requestBody.options.num_ctx, 2048);
  } finally {
    global.fetch = originalFetch;
  }
});

test("Ollama connection failures include an actionable message", async () => {
  await assert.rejects(
    translateText("Hello", {
      provider: "ollama",
      sourceLanguage: "en",
      targetLanguage: "zh-CN",
      ollamaUrl: "http://127.0.0.1:1",
      ollamaModel: "qwen3:8b"
    }),
    /无法连接到 Ollama.*请先安装并启动 Ollama/
  );
});
