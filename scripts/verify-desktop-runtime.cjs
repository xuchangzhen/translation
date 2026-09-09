const http = require("node:http");
const os = require("node:os");
const fs = require("node:fs");
const path = require("node:path");
const { app, safeStorage } = require("electron");

async function main() {
  await app.whenReady();
  if (!safeStorage.isEncryptionAvailable()) throw new Error("Electron safeStorage is unavailable");
  const { SettingsStore } = require("../src/lib/settings.cjs");
  const { compatibleModels, compatibleChat } = require("../src/lib/compatible.cjs");
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "translation-runtime-"));
  let server;
  try {
    const store = new SettingsStore(directory);
    store.update({ provider: "compatible", compatibleBaseUrl: "http://127.0.0.1:0/v1", compatibleModel: "smoke-model", apiKey: "runtime-secret" });
    if (store.apiKey("compatible") !== "runtime-secret") throw new Error("safeStorage round-trip failed");
    const publicValue = JSON.stringify(store.publicValue());
    if (publicValue.includes("runtime-secret") || publicValue.includes("apiKeysEncrypted")) throw new Error("public settings leaked a key");
    server = http.createServer((request, response) => {
      if (request.url === "/v1/models") return response.end(JSON.stringify({ data: [{ id: "smoke-model" }] }));
      if (request.url === "/v1/chat/completions") {
        let body = "";
        request.on("data", (chunk) => { body += chunk; });
        return request.on("end", () => {
          const parsed = JSON.parse(body);
          response.setHeader("Content-Type", "application/json");
          response.end(JSON.stringify({ choices: [{ message: { content: parsed.messages?.[0]?.content || "OK" } }] }));
        });
      }
      response.statusCode = 404; response.end(JSON.stringify({ error: "missing" }));
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const port = server.address().port;
    const settings = { ...store.data, compatibleBaseUrl: `http://127.0.0.1:${port}/v1` };
    const models = await compatibleModels(settings, store.apiKey("compatible"));
    if (!models.includes("smoke-model")) throw new Error("/models smoke failed");
    const response = await compatibleChat(settings, store.apiKey("compatible"), [{ role: "user", content: "OK" }], false);
    if (!response?.choices?.length) throw new Error("/chat/completions smoke failed");
    console.log(JSON.stringify({ verified: true, platform: process.platform, safeStorage: true, modelList: models.length, chatCompletions: true }));
  } finally {
    if (server) await new Promise((resolve) => server.close(resolve));
    fs.rmSync(directory, { recursive: true, force: true });
    app.quit();
  }
}
main().catch((error) => { console.error(error.message); app.exit(1); });
