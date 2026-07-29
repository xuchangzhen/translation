const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {
  isLocalMamboUrl,
  normalizeUrl,
  synthesizeMambo
} = require("../src/lib/speech.cjs");

test("normalizes and recognizes local Mambo URLs", () => {
  assert.equal(normalizeUrl(" http://127.0.0.1:9880/// "), "http://127.0.0.1:9880");
  assert.equal(isLocalMamboUrl("http://localhost:9880"), true);
  assert.equal(isLocalMamboUrl("http://192.168.1.8:9880"), false);
});

test("Mambo synthesis sends Chinese API v2 payload and accepts WAV", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "linguabridge-mambo-"));
  const files = [
    "run_engine_mac.py",
    "models/mambo-e15.ckpt",
    "models/mambo_e8_s352.pth",
    "models/refer.wav",
    "GPT-SoVITS/api_v2.py"
  ];
  for (const relative of files) {
    const file = path.join(root, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, "test");
  }
  const originalFetch = global.fetch;
  let payload;
  let stopped = false;
  global.fetch = async (url, init = {}) => {
    if (String(url).includes("/control?command=exit")) {
      stopped = true;
      return { status: 200 };
    }
    if (String(url).endsWith("/control")) {
      return { status: stopped ? 503 : 400 };
    }
    payload = JSON.parse(init.body);
    const wav = Buffer.alloc(64);
    wav.write("RIFF");
    return {
      ok: true,
      headers: { get: () => "audio/wav" },
      arrayBuffer: async () =>
        wav.buffer.slice(wav.byteOffset, wav.byteOffset + wav.byteLength)
    };
  };

  try {
    const result = await synthesizeMambo("你好", {
      mamboUrl: "http://127.0.0.1:9880",
      mamboRoot: root
    });
    assert.equal(result.audio.subarray(0, 4).toString("ascii"), "RIFF");
    assert.equal(payload.text_lang, "zh");
    assert.equal(payload.text, "你好");
    assert.equal(path.basename(payload.ref_audio_path), "refer.wav");
  } finally {
    global.fetch = originalFetch;
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("remote Mac mini speech uses the lightweight companion service", async () => {
  const originalFetch = global.fetch;
  let requestedUrl;
  global.fetch = async (url) => {
    requestedUrl = String(url);
    const wav = Buffer.alloc(64);
    wav.write("RIFF");
    return {
      ok: true,
      headers: { get: () => "audio/wav" },
      arrayBuffer: async () =>
        wav.buffer.slice(wav.byteOffset, wav.byteOffset + wav.byteLength)
    };
  };
  try {
    const result = await synthesizeMambo("你好", {
      macMiniServiceUrl: "http://192.168.1.9:19876"
    });
    assert.equal(requestedUrl, "http://192.168.1.9:19876/api/speech");
    assert.equal(result.audio.subarray(0, 4).toString("ascii"), "RIFF");
  } finally {
    global.fetch = originalFetch;
  }
});
