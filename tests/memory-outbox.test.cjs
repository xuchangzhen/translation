const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {
  MemoryOutboxStore,
  createMemoryCandidates,
  stripMarkdown
} = require("../src/lib/memory.cjs");

test("turns a translated word into one deduplicated transfer item", () => {
  const candidates = createMemoryCandidates("parameter", {
    sourceLanguage: "en",
    targetLanguage: "zh-CN",
    translation: "参数",
    phonetic: "/pəˈræmɪtər/",
    explanation: "函数签名中接收调用方数据的命名变量。",
    terms: [
      {
        term: "parameter",
        translation: "参数",
        definition: "函数定义中的命名变量。",
        category: "编程基础"
      }
    ]
  });
  assert.equal(candidates.length, 1);
  assert.equal(candidates[0].type, "word");
  assert.equal(candidates[0].front, "parameter");
  assert.match(candidates[0].phonetic, /^\/.+\/$/);
  assert.match(candidates[0].technicalNotes.join(" "), /函数定义/);
});

test("keeps useful sentence and technical term items without duplicates", () => {
  const candidates = createMemoryCandidates(
    "The event loop schedules callbacks.",
    {
      sourceLanguage: "en",
      targetLanguage: "zh-CN",
      translation: "事件循环会调度回调函数。",
      explanation: "事件循环在调用栈清空后调度任务。",
      terms: [
        {
          term: "event loop",
          translation: "事件循环",
          definition: "协调任务队列与调用栈的运行时机制。",
          category: "运行时"
        }
      ]
    }
  );
  assert.deepEqual(
    candidates.map((item) => item.front),
    ["The event loop schedules callbacks.", "event loop"]
  );
  assert.equal(candidates[1].context, "The event loop schedules callbacks.");
});

test("strips Markdown structure and fenced code before making study units", () => {
  const text = "# Gateway\n\nUse **retry** here.\n\n```js\nretry()\n```";
  assert.equal(stripMarkdown(text), "Gateway\n\nUse retry here.");
});

test("outbox requeues changed items without owning review progress", () => {
  const userDataPath = fs.mkdtempSync(path.join(os.tmpdir(), "translation-outbox-"));
  const now = Date.UTC(2026, 8, 4);
  try {
    const outbox = new MemoryOutboxStore(userDataPath);
    const first = {
      sourceLanguage: "en",
      targetLanguage: "zh-CN",
      translation: "缓存",
      phonetic: "/kæʃ/",
      explanation: "缓存用于减少重复计算。",
      terms: []
    };
    assert.equal(outbox.capture("cache", first, now).added, 1);
    assert.equal(outbox.status().pending, 1);
    const sent = outbox.pending();
    assert.equal(outbox.markSynced(sent, now + 1000), 1);
    assert.deepEqual(outbox.status(), {
      total: 1,
      pending: 0,
      synced: 1,
      lastSyncedAt: now + 1000
    });

    outbox.capture(
      "Cache",
      { ...first, explanation: "缓存也可能导致数据陈旧。" },
      now + 2000
    );
    assert.equal(outbox.status().pending, 1);
    const reloaded = new MemoryOutboxStore(userDataPath);
    assert.equal(reloaded.status().pending, 1);
    assert.equal("dueAt" in reloaded.pending()[0], false);
  } finally {
    fs.rmSync(userDataPath, { recursive: true, force: true });
  }
});

test("migrates legacy desktop cards into the Android transfer queue", () => {
  const userDataPath = fs.mkdtempSync(path.join(os.tmpdir(), "translation-outbox-"));
  try {
    fs.writeFileSync(
      path.join(userDataPath, "memory.json"),
      JSON.stringify({
        items: [{ id: "legacy", key: "queue", front: "queue", back: "队列" }],
        reviewLog: []
      })
    );
    const outbox = new MemoryOutboxStore(userDataPath);
    assert.equal(outbox.status().pending, 1);
    assert.equal(outbox.pending()[0].id, "legacy");
  } finally {
    fs.rmSync(userDataPath, { recursive: true, force: true });
  }
});
