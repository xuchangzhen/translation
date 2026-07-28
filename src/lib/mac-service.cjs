const http = require("node:http");
const { ensureLocalOllamaRunning } = require("./ollama-service.cjs");
const { synthesizeMambo } = require("./speech.cjs");

const SERVICE_PORT = 19876;
const MAX_REQUEST_BYTES = 2_000_000;

let server = null;

function isPrivateClient(address) {
  const value = String(address || "").replace(/^::ffff:/, "").toLowerCase();
  if (
    value === "::1" ||
    value === "127.0.0.1" ||
    value.startsWith("10.") ||
    value.startsWith("192.168.") ||
    value.startsWith("169.254.") ||
    value.startsWith("fc") ||
    value.startsWith("fd") ||
    value.startsWith("fe80:")
  ) {
    return true;
  }
  const match = value.match(/^172\.(\d+)\./);
  return Boolean(match && Number(match[1]) >= 16 && Number(match[1]) <= 31);
}

function sendJson(response, status, payload) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store"
  });
  response.end(JSON.stringify(payload));
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    request.on("data", (chunk) => {
      length += chunk.length;
      if (length > MAX_REQUEST_BYTES) {
        reject(new Error("请求内容过大"));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

async function proxyOllama(request, response, pathname) {
  await ensureLocalOllamaRunning();
  const body =
    request.method === "GET" || request.method === "HEAD"
      ? undefined
      : await readBody(request);
  const upstream = await fetch(`http://127.0.0.1:11434${pathname}`, {
    method: request.method,
    headers: {
      "Content-Type": request.headers["content-type"] || "application/json"
    },
    body
  });
  const payload = Buffer.from(await upstream.arrayBuffer());
  response.writeHead(upstream.status, {
    "Content-Type":
      upstream.headers.get("content-type") || "application/octet-stream",
    "Cache-Control": "no-store"
  });
  response.end(payload);
}

async function handleRequest(request, response, getSettings) {
  if (!isPrivateClient(request.socket.remoteAddress)) {
    sendJson(response, 403, { error: "只允许局域网设备访问" });
    return;
  }
  const url = new URL(request.url || "/", "http://127.0.0.1");
  if (request.method === "GET" && url.pathname === "/api/health") {
    sendJson(response, 200, {
      ok: true,
      service: "LinguaBridge Mac mini",
      version: 1
    });
    return;
  }
  if (url.pathname.startsWith("/ollama/")) {
    await proxyOllama(
      request,
      response,
      url.pathname.slice("/ollama".length) + url.search
    );
    return;
  }
  if (request.method === "POST" && url.pathname === "/api/speech") {
    const body = JSON.parse((await readBody(request)).toString("utf8") || "{}");
    const text = String(body.text || "").trim();
    if (!text) {
      sendJson(response, 400, { error: "朗读文本为空" });
      return;
    }
    const result = await synthesizeMambo(text, {
      ...getSettings(),
      mamboUrl: "http://127.0.0.1:9880",
      stopMamboAfterSpeech: true
    });
    response.writeHead(200, {
      "Content-Type": result.mimeType || "audio/wav",
      "Content-Length": result.audio.length,
      "Cache-Control": "no-store"
    });
    response.end(result.audio);
    return;
  }
  sendJson(response, 404, { error: "接口不存在" });
}

function startMacService(getSettings) {
  if (process.platform !== "darwin" || server) return server;
  server = http.createServer((request, response) => {
    void handleRequest(request, response, getSettings).catch((error) => {
      if (response.headersSent) {
        response.destroy();
        return;
      }
      sendJson(response, 500, {
        error: error instanceof Error ? error.message : String(error)
      });
    });
  });
  server.on("error", (error) => {
    console.error("Mac mini background service failed:", error);
  });
  server.listen(SERVICE_PORT, "0.0.0.0");
  return server;
}

function stopMacService() {
  if (!server) return;
  server.close();
  server = null;
}

module.exports = {
  SERVICE_PORT,
  isPrivateClient,
  startMacService,
  stopMacService
};
