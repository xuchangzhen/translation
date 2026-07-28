const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const MAMBO_PROMPT_TEXT =
  "最近看大家都在讲自己的经历，球波也是忍不住了。";

let mamboProcess = null;
let mamboStartup = null;
let activeSynthesisCount = 0;

function normalizeUrl(value) {
  return String(value || "").trim().replace(/\/+$/, "");
}

function isLocalMamboUrl(value) {
  try {
    const url = new URL(normalizeUrl(value));
    return ["127.0.0.1", "localhost", "::1"].includes(url.hostname);
  } catch {
    return false;
  }
}

function macCompanionUrl(settings) {
  const explicit = normalizeUrl(settings.macMiniServiceUrl);
  if (explicit) return explicit;
  if (process.platform !== "win32") return "";
  try {
    const ollama = new URL(normalizeUrl(settings.ollamaUrl));
    if (["127.0.0.1", "localhost", "::1"].includes(ollama.hostname)) return "";
    return `${ollama.protocol}//${ollama.hostname}:19876`;
  } catch {
    return "";
  }
}

async function mamboHealth(settings, timeoutMs = 1500) {
  const companion = macCompanionUrl(settings);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(
      companion
        ? `${companion}/api/health`
        : `${normalizeUrl(settings.mamboUrl)}/control`,
      { signal: controller.signal }
    );
    return response.status < 500;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

function findConda() {
  const home = os.homedir();
  const candidates = [
    path.join(home, "miniforge3", "bin", "conda"),
    path.join(home, "mambaforge", "bin", "conda"),
    "/opt/homebrew/bin/conda"
  ];
  return candidates.find((candidate) => fs.existsSync(candidate)) || "";
}

function validateMamboRoot(rootValue) {
  const root = path.resolve(String(rootValue || ""));
  const required = [
    path.join(root, "run_engine_mac.py"),
    path.join(root, "models", "mambo-e15.ckpt"),
    path.join(root, "models", "mambo_e8_s352.pth"),
    path.join(root, "models", "refer.wav"),
    path.join(root, "GPT-SoVITS", "api_v2.py")
  ];
  const missing = required.filter(
    (file) => !fs.existsSync(file) || fs.statSync(file).size === 0
  );
  if (missing.length) {
    throw new Error(`曼波语音模型文件不完整：${path.basename(missing[0])}`);
  }
  return root;
}

async function waitForMambo(settings, timeoutMs = 120000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    if (await mamboHealth(settings, 1500)) return true;
    await new Promise((resolve) => setTimeout(resolve, 900));
    if (mamboProcess && mamboProcess.exitCode !== null) {
      throw new Error(`曼波语音引擎启动失败（退出码 ${mamboProcess.exitCode}）`);
    }
  }
  throw new Error("曼波语音引擎启动超时");
}

async function ensureMamboRunning(settings) {
  const companion = macCompanionUrl(settings);
  if (companion) {
    if (await mamboHealth(settings)) return true;
    throw new Error(`无法连接 Mac mini 后台服务：${companion}`);
  }
  if (await mamboHealth(settings)) return true;
  if (!isLocalMamboUrl(settings.mamboUrl)) {
    throw new Error(`无法连接曼波语音服务：${normalizeUrl(settings.mamboUrl)}`);
  }
  if (process.platform !== "darwin") {
    throw new Error("当前设备无法自动启动 Mac 上的曼波语音引擎");
  }
  if (mamboStartup) return mamboStartup;

  mamboStartup = (async () => {
    const root = validateMamboRoot(settings.mamboRoot);
    const conda = findConda();
    if (!conda) throw new Error("没有找到 Miniforge/Conda，无法启动曼波语音");
    mamboProcess = spawn(
      conda,
      [
        "run",
        "-n",
        "GPTSoVits",
        "--no-capture-output",
        "python",
        path.join(root, "run_engine_mac.py")
      ],
      {
        cwd: root,
        detached: true,
        stdio: "ignore",
        windowsHide: true
      }
    );
    mamboProcess.unref();
    mamboProcess.once("exit", () => {
      mamboProcess = null;
    });
    await waitForMambo(settings);
    return true;
  })();

  try {
    return await mamboStartup;
  } finally {
    mamboStartup = null;
  }
}

async function synthesizeMambo(text, settings) {
  const content = String(text || "").trim();
  if (!content) throw new Error("朗读文本为空");
  const companion = macCompanionUrl(settings);
  if (companion) {
    const response = await fetch(`${companion}/api/speech`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text: content.slice(0, 1200) })
    });
    if (!response.ok) {
      const detail = (await response.text()).trim();
      throw new Error(
        `Mac mini 曼波语音合成失败：${detail.slice(0, 400) || response.status}`
      );
    }
    const audio = Buffer.from(await response.arrayBuffer());
    if (audio.length < 44 || audio.subarray(0, 4).toString("ascii") !== "RIFF") {
      throw new Error("Mac mini 返回的不是有效 WAV 音频");
    }
    return {
      audio,
      mimeType: response.headers.get("content-type") || "audio/wav"
    };
  }

  activeSynthesisCount += 1;
  try {
    await ensureMamboRunning(settings);
    const root = validateMamboRoot(settings.mamboRoot);
    const response = await fetch(`${normalizeUrl(settings.mamboUrl)}/tts`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        text: content.slice(0, 1200),
        text_lang: "zh",
        ref_audio_path: path.join(root, "models", "refer.wav"),
        prompt_lang: "zh",
        prompt_text: MAMBO_PROMPT_TEXT,
        text_split_method: "cut5",
        batch_size: 1,
        speed_factor: 1,
        media_type: "wav",
        streaming_mode: false
      })
    });
    if (!response.ok) {
      const detail = (await response.text()).trim();
      throw new Error(`曼波语音合成失败：${detail.slice(0, 400) || response.status}`);
    }
    const audio = Buffer.from(await response.arrayBuffer());
    if (audio.length < 44 || audio.subarray(0, 4).toString("ascii") !== "RIFF") {
      throw new Error("曼波语音返回的不是有效 WAV 音频");
    }
    return {
      audio,
      mimeType: response.headers.get("content-type") || "audio/wav"
    };
  } finally {
    activeSynthesisCount = Math.max(0, activeSynthesisCount - 1);
    if (activeSynthesisCount === 0 && settings.stopMamboAfterSpeech !== false) {
      await stopMambo(settings);
    }
  }
}

async function waitForMamboStop(settings, timeoutMs = 10000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    if (!(await mamboHealth(settings, 600))) return true;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  return false;
}

async function stopMambo(settings = {}) {
  if (isLocalMamboUrl(settings.mamboUrl)) {
    try {
      await fetch(
        `${normalizeUrl(settings.mamboUrl)}/control?command=exit`,
        { method: "GET" }
      );
    } catch {
      // The service may close the connection as part of a successful shutdown.
    }
  }
  if (mamboProcess?.pid) {
    try {
      process.kill(-mamboProcess.pid, "SIGTERM");
    } catch {
      try {
        mamboProcess.kill("SIGTERM");
      } catch {
        // The engine has already exited.
      }
    }
    mamboProcess = null;
  }
  if (isLocalMamboUrl(settings.mamboUrl)) {
    await waitForMamboStop(settings);
  }
}

module.exports = {
  ensureMamboRunning,
  isLocalMamboUrl,
  macCompanionUrl,
  mamboHealth,
  normalizeUrl,
  stopMambo,
  synthesizeMambo,
  validateMamboRoot
};
