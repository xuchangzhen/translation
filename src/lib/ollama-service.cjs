const fs = require("node:fs");
const path = require("node:path");
const { spawn } = require("node:child_process");

let ollamaProcess = null;
let ollamaStartup = null;

function findOllamaExecutable() {
  const candidates = [
    "/usr/local/bin/ollama",
    "/opt/homebrew/bin/ollama",
    "/Applications/Ollama.app/Contents/Resources/ollama"
  ];
  return candidates.find((candidate) => fs.existsSync(candidate)) || "ollama";
}

async function localOllamaHealth(timeoutMs = 1500) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch("http://127.0.0.1:11434/api/tags", {
      signal: controller.signal
    });
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function waitForOllama(timeoutMs = 30000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    if (await localOllamaHealth()) return true;
    if (ollamaProcess && ollamaProcess.exitCode !== null) {
      throw new Error(`Ollama 后台服务启动失败（退出码 ${ollamaProcess.exitCode}）`);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("Ollama 后台服务启动超时");
}

async function ensureLocalOllamaRunning() {
  if (process.platform !== "darwin") return false;
  if (await localOllamaHealth()) return true;
  if (ollamaStartup) return ollamaStartup;
  ollamaStartup = (async () => {
    const executable = findOllamaExecutable();
    ollamaProcess = spawn(executable, ["serve"], {
      cwd: path.dirname(executable),
      detached: true,
      env: {
        ...process.env,
        OLLAMA_HOST: "127.0.0.1:11434"
      },
      stdio: "ignore",
      windowsHide: true
    });
    ollamaProcess.unref();
    ollamaProcess.once("exit", () => {
      ollamaProcess = null;
    });
    await waitForOllama();
    return true;
  })();
  try {
    return await ollamaStartup;
  } finally {
    ollamaStartup = null;
  }
}

module.exports = {
  ensureLocalOllamaRunning,
  findOllamaExecutable,
  localOllamaHealth
};
