const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function createSpeech(synthesizeSpeech = async () => ({ ok: false }), failSystem = false) {
  const spoken = [];
  const voices = [
    { name: "Samantha", lang: "en-US", localService: true },
    { name: "Samantha (Enhanced)", voiceURI: "Samantha (Enhanced)", lang: "en-US", localService: true },
    { name: "Meijia", lang: "zh-TW", localService: true },
    { name: "Tingting", lang: "zh-CN", localService: true }
  ];
  const window = {
    speechSynthesis: {
      getVoices: () => voices,
      cancel() {},
      speak: (utterance) => {
        spoken.push(utterance);
        queueMicrotask(() => failSystem ? utterance.onerror({ error: "synthesis-failed" }) : utterance.onend());
      }
    },
    lingua: { synthesizeSpeech },
    setTimeout,
    clearTimeout
  };
  class Utterance { constructor(text) { this.text = text; } }
  const source = fs.readFileSync(path.join(__dirname, "../src/speech.ts"), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
  const exports = {};
  vm.runInNewContext(compiled, { exports, window, SpeechSynthesisUtterance: Utterance, Uint8Array, Blob, URL, Audio: class {} });
  return { ...exports, spoken };
}

test("speech uses explicit language and detects Japanese before Chinese", () => {
  const { speechLanguage } = createSpeech();
  assert.equal(speechLanguage("ja", "日本語を話します"), "ja-JP");
  assert.equal(speechLanguage("auto", "日本語を話します"), "ja-JP");
  assert.equal(speechLanguage("zh-TW", "中文"), "zh-TW");
  assert.equal(speechLanguage("en", "Hello 中文"), "en-US");
});

test("prefers enhanced English voices over their basic versions", async () => {
  const { speakText, spoken } = createSpeech();
  await speakText("Successive Approximation Register", "en");
  assert.equal(spoken[0].voice.name, "Samantha (Enhanced)");
});

test("long Chinese text falls back intact and selects the matching locale", async () => {
  let requests = 0;
  const { speakText, spoken } = createSpeech(async () => { requests++; return { ok: false }; });
  const content = "中文".repeat(601);
  await speakText(content, "zh-CN");
  assert.equal(requests, 0);
  assert.equal(spoken[0].text, content);
  assert.equal(spoken[0].voice.name, "Tingting");
  await speakText("繁體中文", "zh-TW");
  assert.equal(spoken[1].voice.name, "Meijia");
});

test("an older pending synthesis cannot interrupt a newer request", async () => {
  let finish;
  const { speakText, spoken } = createSpeech(() => new Promise((resolve) => { finish = resolve; }));
  const first = speakText("你好", "zh-CN");
  await speakText("Hello", "en");
  finish({ ok: false });
  await first;
  assert.equal(spoken.length, 1);
  assert.equal(spoken[0].text, "Hello");
});

test("Mambo errors are reported rather than silently switching voices", async () => {
  const { speakText, spoken } = createSpeech(async () => ({ ok: false, error: "合成失败" }));
  await assert.rejects(speakText("你好", "zh-CN"), /曼波朗读失败.*合成失败/);
  assert.equal(spoken.length, 0);
});

test("system voice failures are reported to the caller", async () => {
  const { speakText } = createSpeech(undefined, true);
  await assert.rejects(speakText("Hello", "en"), /系统语音朗读失败.*synthesis-failed/);
});
