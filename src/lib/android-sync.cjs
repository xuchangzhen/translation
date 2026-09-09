const crypto = require("node:crypto");
const http = require("node:http");
const https = require("node:https");

const PAIRING_PROTOCOL = "linguabridge-memory:";
const DESKTOP_JOIN_PROTOCOL = "linguabridge-space:";
const SYNC_PROTOCOL = "linguabridge-memory/1";
const MAX_RESPONSE_BYTES = 256 * 1024;

function decodeBase64Url(value) {
  return Buffer.from(String(value || ""), "base64url");
}

function normalizeServerUrl(value) {
  let server;
  try {
    server = new URL(String(value || "").trim());
  } catch {
    throw new Error("同步服务器地址无效");
  }
  const localDevelopment =
    server.protocol === "http:" && ["127.0.0.1", "localhost"].includes(server.hostname);
  if (server.protocol !== "https:" && !localDevelopment) {
    throw new Error("同步服务器必须使用 HTTPS");
  }
  if (server.username || server.password || server.search || server.hash) {
    throw new Error("同步服务器地址不能包含账号、查询参数或锚点");
  }
  server.pathname = server.pathname.replace(/\/+$/, "");
  return server.toString().replace(/\/$/, "");
}

function parsePairing(value) {
  let pairing;
  try {
    pairing = new URL(String(value || "").trim());
  } catch {
    throw new Error("配对信息无效，请从安卓端重新复制");
  }
  if (pairing.protocol !== PAIRING_PROTOCOL || pairing.hostname !== "pair") {
    throw new Error("这不是单词记忆应用生成的配对信息");
  }

  const serverUrl = normalizeServerUrl(pairing.searchParams.get("server") || "");

  const deviceId = pairing.searchParams.get("device") || "";
  const uploadToken = pairing.searchParams.get("token") || "";
  const readToken = pairing.searchParams.get("read") || "";
  const encryptionKey = pairing.searchParams.get("key") || "";
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(deviceId)) {
    throw new Error("配对信息中的设备编号无效");
  }
  if (!/^[A-Za-z0-9_-]+$/.test(uploadToken) || decodeBase64Url(uploadToken).length < 32) {
    throw new Error("配对信息中的上传凭据无效");
  }
  if (!/^[A-Za-z0-9_-]+$/.test(encryptionKey) || decodeBase64Url(encryptionKey).length !== 32) {
    throw new Error("配对信息中的加密密钥无效");
  }
  if (readToken && (!/^[A-Za-z0-9_-]+$/.test(readToken) || decodeBase64Url(readToken).length < 32)) {
    throw new Error("配对信息中的接收凭据无效");
  }
  return {
    serverUrl,
    deviceId,
    uploadToken,
    readToken,
    encryptionKey
  };
}

function createPairingUri(pairing) {
  const value = new URL(`${PAIRING_PROTOCOL}//pair`);
  value.searchParams.set("server", normalizeServerUrl(pairing.serverUrl));
  value.searchParams.set("device", pairing.deviceId);
  value.searchParams.set("token", pairing.uploadToken);
  value.searchParams.set("read", pairing.readToken);
  value.searchParams.set("key", pairing.encryptionKey);
  return value.toString();
}

function encryptPayload(value, encryptionKey) {
  const nonce = crypto.randomBytes(12);
  const key = decodeBase64Url(encryptionKey);
  if (key.length !== 32) throw new Error("内容密钥无效");
  const cipher = crypto.createCipheriv("aes-256-gcm", key, nonce);
  const plaintext = Buffer.from(JSON.stringify(value), "utf8");
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const ciphertext = Buffer.concat([encrypted, cipher.getAuthTag()]);
  return {
    algorithm: "A256GCM",
    nonce: nonce.toString("base64url"),
    ciphertext: ciphertext.toString("base64url")
  };
}

function decryptPayload(envelope, encryptionKey) {
  const nonce = decodeBase64Url(envelope?.nonce);
  const combined = decodeBase64Url(envelope?.ciphertext);
  if (nonce.length !== 12 || combined.length < 17) throw new Error("加密批次无效");
  const encrypted = combined.subarray(0, combined.length - 16);
  const tag = combined.subarray(combined.length - 16);
  const decipher = crypto.createDecipheriv(
    "aes-256-gcm",
    decodeBase64Url(encryptionKey),
    nonce
  );
  decipher.setAuthTag(tag);
  return JSON.parse(Buffer.concat([decipher.update(encrypted), decipher.final()]).toString("utf8"));
}

function encryptBatch(items, encryptionKey) {
  return encryptPayload({
    schemaVersion: 1,
    source: "linguabridge-desktop",
    sentAt: Date.now(),
    items
  }, encryptionKey);
}

function decryptBatch(envelope, encryptionKey) {
  return decryptPayload(envelope, encryptionKey);
}

function parseDesktopJoinLink(value) {
  let link;
  try {
    link = new URL(String(value || "").trim());
  } catch {
    throw new Error("电脑加入链接无效");
  }
  if (link.protocol !== DESKTOP_JOIN_PROTOCOL || link.hostname !== "join") {
    throw new Error("这不是 LinguaBridge 同步空间加入链接");
  }
  const serverUrl = normalizeServerUrl(link.searchParams.get("server") || "");
  const transferId = link.searchParams.get("transfer") || "";
  const accessToken = link.searchParams.get("token") || "";
  const transferKey = link.searchParams.get("key") || "";
  const expiresAt = Number(link.searchParams.get("expires") || 0);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(transferId)) {
    throw new Error("电脑接力编号无效");
  }
  if (!/^[A-Za-z0-9_-]+$/.test(accessToken) || decodeBase64Url(accessToken).length !== 32) {
    throw new Error("电脑接力凭据无效");
  }
  if (!/^[A-Za-z0-9_-]+$/.test(transferKey) || decodeBase64Url(transferKey).length !== 32) {
    throw new Error("电脑接力密钥无效");
  }
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    throw new Error("电脑加入链接已过期");
  }
  return { serverUrl, transferId, accessToken, transferKey, expiresAt };
}

function requestJsonUrl(serverUrl, method, pathname, headers, body) {
  const target = new URL(`${normalizeServerUrl(serverUrl)}${pathname}`);
  const payload = body ? Buffer.from(JSON.stringify(body), "utf8") : null;
  const transport = target.protocol === "https:" ? https : http;
  return new Promise((resolve, reject) => {
    const request = transport.request(
      target,
      {
        method,
        timeout: 10_000,
        headers: {
          Accept: "application/json",
          ...headers,
          ...(payload
            ? {
                "Content-Type": "application/json; charset=utf-8",
                "Content-Length": payload.length
              }
            : {})
        }
      },
      (response) => {
        const chunks = [];
        let size = 0;
        response.on("data", (chunk) => {
          size += chunk.length;
          if (size > MAX_RESPONSE_BYTES) {
            response.destroy(new Error("同步服务器响应过大"));
            return;
          }
          chunks.push(chunk);
        });
        response.on("end", () => {
          let parsed = {};
          try {
            const text = Buffer.concat(chunks).toString("utf8");
            parsed = text ? JSON.parse(text) : {};
          } catch {
            reject(new Error("同步服务器返回了无法识别的响应"));
            return;
          }
          if ((response.statusCode || 500) >= 400) {
            reject(new Error(parsed.error || `同步服务器连接失败（${response.statusCode}）`));
            return;
          }
          resolve(parsed);
        });
      }
    );
    request.on("timeout", () => request.destroy(new Error("连接同步服务器超时")));
    request.on("error", reject);
    if (payload) request.write(payload);
    request.end();
  });
}

function requestJson(pairingValue, method, pathname, body) {
  const pairing = typeof pairingValue === "string" ? parsePairing(pairingValue) : pairingValue;
  return requestJsonUrl(
    pairing.serverUrl,
    method,
    pathname,
    { Authorization: `Bearer ${pairing.uploadToken}` },
    body
  );
}

async function provisionAndroidMemoryConnection(serverValue) {
  const serverUrl = normalizeServerUrl(serverValue);
  const pairing = {
    serverUrl,
    deviceId: crypto.randomUUID(),
    uploadToken: crypto.randomBytes(32).toString("base64url"),
    readToken: crypto.randomBytes(32).toString("base64url"),
    encryptionKey: crypto.randomBytes(32).toString("base64url")
  };
  const response = await requestJsonUrl(
    serverUrl,
    "POST",
    "/v1/devices",
    {},
    {
      deviceId: pairing.deviceId,
      uploadToken: pairing.uploadToken,
      readToken: pairing.readToken
    }
  );
  if (!response.created || response.protocol !== SYNC_PROTOCOL) {
    throw new Error("同步服务器未能创建加密设备");
  }
  const pairingUri = createPairingUri(pairing);
  return { ...pairing, pairingUri };
}

async function testAndroidMemoryConnection(pairingValue) {
  const pairing = typeof pairingValue === "string"
    ? parsePairing(pairingValue)
    : pairingValue;
  const startedAt = Date.now();
  const response = await requestJson(
    pairing,
    "GET",
    `/v1/devices/${encodeURIComponent(pairing.deviceId)}/status`
  );
  if (response.protocol !== SYNC_PROTOCOL) {
    throw new Error("服务器不是兼容的单词记忆同步服务");
  }
  return {
    ok: true,
    latencyMs: Date.now() - startedAt,
    serverUrl: pairing.serverUrl,
    deviceId: pairing.deviceId,
    pendingBatches: Number(response.pendingBatches || 0)
  };
}

async function reportDesktopPresence(pairingValue, client) {
  const pairing = typeof pairingValue === "string"
    ? parsePairing(pairingValue)
    : pairingValue;
  const response = await requestJson(
    pairing,
    "POST",
    `/v1/devices/${encodeURIComponent(pairing.deviceId)}/clients`,
    {
      clientId: String(client?.clientId || ""),
      name: String(client?.name || "").trim().slice(0, 80),
      platform: String(client?.platform || ""),
      appVersion: String(client?.appVersion || "")
    }
  );
  if (!response.updated || response.protocol !== SYNC_PROTOCOL) {
    throw new Error("同步服务器未确认电脑在线状态");
  }
  return response;
}

async function listDesktopClients(pairingValue) {
  const pairing = typeof pairingValue === "string"
    ? parsePairing(pairingValue)
    : pairingValue;
  const response = await requestJson(
    pairing,
    "GET",
    `/v1/devices/${encodeURIComponent(pairing.deviceId)}/clients`
  );
  if (response.protocol !== SYNC_PROTOCOL || !Array.isArray(response.clients)) {
    throw new Error("同步服务器返回的电脑状态无效");
  }
  return {
    serverTime: Number(response.serverTime || Date.now()),
    clients: response.clients.map((client) => ({
      clientId: String(client.clientId || ""),
      name: String(client.name || "这台电脑"),
      platform: String(client.platform || ""),
      appVersion: String(client.appVersion || ""),
      firstSeenAt: Number(client.firstSeenAt || 0),
      lastSeenAt: Number(client.lastSeenAt || 0)
    }))
  };
}

async function createDesktopJoinLink(pairingValue, computerName = "") {
  const pairing = parsePairing(pairingValue);
  const transferId = crypto.randomUUID();
  const accessToken = crypto.randomBytes(32).toString("base64url");
  const transferKey = crypto.randomBytes(32).toString("base64url");
  const envelope = encryptPayload({
    schemaVersion: 1,
    pairingUri: createPairingUri(pairing),
    computerName: String(computerName || "").trim().slice(0, 80),
    createdAt: Date.now()
  }, transferKey);
  const response = await requestJson(
    pairing,
    "POST",
    `/v1/devices/${encodeURIComponent(pairing.deviceId)}/transfers`,
    {
      protocol: SYNC_PROTOCOL,
      transferId,
      accessTokenHash: crypto.createHash("sha256").update(accessToken).digest("hex"),
      envelope
    }
  );
  if (!response.created || response.protocol !== SYNC_PROTOCOL) {
    throw new Error("服务器未能创建电脑加入链接");
  }
  const expiresAt = Number(response.expiresAt || 0);
  const link = new URL(`${DESKTOP_JOIN_PROTOCOL}//join`);
  link.searchParams.set("server", pairing.serverUrl);
  link.searchParams.set("transfer", transferId);
  link.searchParams.set("token", accessToken);
  link.searchParams.set("key", transferKey);
  link.searchParams.set("expires", String(expiresAt));
  return {
    joinLink: link.toString(),
    expiresAt,
    deviceId: pairing.deviceId
  };
}

async function claimDesktopJoinLink(value) {
  const link = parseDesktopJoinLink(value);
  const response = await requestJsonUrl(
    link.serverUrl,
    "POST",
    `/v1/transfers/${encodeURIComponent(link.transferId)}/claim`,
    { Authorization: `Bearer ${link.accessToken}` },
    {}
  );
  if (response.protocol !== SYNC_PROTOCOL || !response.envelope) {
    throw new Error("服务器返回的电脑接力信息无效");
  }
  let payload;
  try {
    payload = decryptPayload(response.envelope, link.transferKey);
  } catch {
    throw new Error("无法解密电脑加入链接");
  }
  if (payload?.schemaVersion !== 1 || !payload.pairingUri) {
    throw new Error("电脑加入链接内容无效");
  }
  const pairing = parsePairing(payload.pairingUri);
  if (pairing.serverUrl !== link.serverUrl) {
    throw new Error("电脑加入链接的服务器不匹配");
  }
  const connection = await testAndroidMemoryConnection(pairing);
  return {
    pairingUri: createPairingUri(pairing),
    computerName: String(payload.computerName || ""),
    connection
  };
}

async function syncAndroidMemory(outbox, pairingValue) {
  const pairing = parsePairing(pairingValue);
  const items = outbox.pending(100);
  if (!items.length) {
    return { sent: 0, status: outbox.status() };
  }
  const envelope = encryptBatch(items, pairing.encryptionKey);
  const response = await requestJson(
    pairing,
    "POST",
    `/v1/devices/${encodeURIComponent(pairing.deviceId)}/batches`,
    {
      protocol: SYNC_PROTOCOL,
      itemCount: items.length,
      envelope
    }
  );
  if (!response.queued || !response.batchId) {
    throw new Error("同步服务器未确认加密批次，已保留待发送队列");
  }
  outbox.markSynced(items);
  return { sent: items.length, batchId: response.batchId, status: outbox.status() };
}

module.exports = {
  DESKTOP_JOIN_PROTOCOL,
  PAIRING_PROTOCOL,
  SYNC_PROTOCOL,
  claimDesktopJoinLink,
  createDesktopJoinLink,
  decryptBatch,
  encryptBatch,
  createPairingUri,
  normalizeServerUrl,
  listDesktopClients,
  parseDesktopJoinLink,
  parsePairing,
  provisionAndroidMemoryConnection,
  requestJson,
  reportDesktopPresence,
  syncAndroidMemory,
  testAndroidMemoryConnection
};
