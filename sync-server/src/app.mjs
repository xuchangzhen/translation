import crypto from "node:crypto";
import { EventEmitter } from "node:events";

const PROTOCOL = "linguabridge-memory/1";
const MAX_BODY_BYTES = 1024 * 1024;
const DEVICE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;

function json(response, status, body) {
  const payload = Buffer.from(JSON.stringify(body), "utf8");
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": payload.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'"
  });
  response.end(payload);
}

async function readJson(request) {
  const declaredLength = Number(request.headers["content-length"] || 0);
  if (declaredLength > MAX_BODY_BYTES) throw Object.assign(new Error("请求过大"), { status: 413 });
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw Object.assign(new Error("请求过大"), { status: 413 });
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch {
    throw Object.assign(new Error("JSON 格式无效"), { status: 400 });
  }
}

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function safeEqual(left, right) {
  const leftBuffer = Buffer.from(String(left || ""));
  const rightBuffer = Buffer.from(String(right || ""));
  return leftBuffer.length === rightBuffer.length && crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

function bearerToken(request) {
  const match = String(request.headers.authorization || "").match(/^Bearer\s+(.+)$/i);
  return match?.[1] || "";
}

function validToken(value) {
  return BASE64URL_PATTERN.test(String(value || "")) && Buffer.from(value, "base64url").length >= 32;
}

function devicePath(pathname) {
  const match = pathname.match(/^\/v1\/devices\/([^/]+)(?:\/(status|batches)(?:\/([^/]+)\/ack)?)?$/);
  if (!match) return null;
  return {
    deviceId: decodeURIComponent(match[1]),
    resource: match[2] || "device",
    batchId: match[3] ? decodeURIComponent(match[3]) : ""
  };
}

export class MemoryRepository {
  constructor() {
    this.devices = new Map();
    this.batches = new Map();
  }

  async createDevice(device) {
    if (this.devices.has(device.id)) return false;
    this.devices.set(device.id, { ...device, createdAt: Date.now(), lastSeenAt: Date.now() });
    this.batches.set(device.id, []);
    return true;
  }

  async getDevice(id) {
    return this.devices.get(id) || null;
  }

  async touchDevice(id) {
    const device = this.devices.get(id);
    if (device) device.lastSeenAt = Date.now();
  }

  async enqueueBatch(deviceId, batch) {
    this.batches.get(deviceId).push(batch);
  }

  async listBatches(deviceId) {
    return (this.batches.get(deviceId) || []).slice(0, 20);
  }

  async countPending(deviceId) {
    return (this.batches.get(deviceId) || []).length;
  }

  async ackBatch(deviceId, batchId) {
    const batches = this.batches.get(deviceId) || [];
    const index = batches.findIndex((batch) => batch.id === batchId);
    if (index < 0) return false;
    batches.splice(index, 1);
    return true;
  }
}

export function createHandler({ repository, registrationKey, longPollMs = 25_000 }) {
  if (!registrationKey || registrationKey.length < 16) {
    throw new Error("SYNC_REGISTRATION_KEY 至少需要 16 个字符");
  }
  const events = new EventEmitter();
  events.setMaxListeners(1000);
  const rateBuckets = new Map();

  function rateLimited(request) {
    const address = request.socket.remoteAddress || "unknown";
    const now = Date.now();
    const bucket = rateBuckets.get(address);
    if (!bucket || now - bucket.startedAt >= 60_000) {
      rateBuckets.set(address, { startedAt: now, count: 1 });
      return false;
    }
    bucket.count += 1;
    return bucket.count > 180;
  }

  async function authorize(request, deviceId, mode) {
    const device = await repository.getDevice(deviceId);
    if (!device) throw Object.assign(new Error("设备不存在"), { status: 404 });
    const token = bearerToken(request);
    const expected = mode === "read" ? device.readTokenHash : device.uploadTokenHash;
    if (!validToken(token) || !safeEqual(sha256(token), expected)) {
      throw Object.assign(new Error("设备凭据无效"), { status: 401 });
    }
    await repository.touchDevice(deviceId);
    return device;
  }

  return async function handler(request, response) {
    try {
      if (rateLimited(request)) {
        json(response, 429, { error: "请求过于频繁" });
        return;
      }
      const url = new URL(request.url, "http://relay.local");
      if (request.method === "GET" && url.pathname === "/healthz") {
        json(response, 200, { ok: true, protocol: PROTOCOL });
        return;
      }

      if (request.method === "POST" && url.pathname === "/v1/devices") {
        if (!safeEqual(request.headers["x-registration-key"], registrationKey)) {
          throw Object.assign(new Error("服务器注册码无效"), { status: 401 });
        }
        const body = await readJson(request);
        if (!DEVICE_ID_PATTERN.test(body.deviceId || "")) {
          throw Object.assign(new Error("设备编号无效"), { status: 400 });
        }
        if (!validToken(body.uploadToken) || !validToken(body.readToken)) {
          throw Object.assign(new Error("设备令牌无效"), { status: 400 });
        }
        const created = await repository.createDevice({
          id: body.deviceId,
          uploadTokenHash: sha256(body.uploadToken),
          readTokenHash: sha256(body.readToken)
        });
        json(response, created ? 201 : 409, created
          ? { created: true, protocol: PROTOCOL }
          : { error: "设备已经注册" });
        return;
      }

      const route = devicePath(url.pathname);
      if (!route || !DEVICE_ID_PATTERN.test(route.deviceId)) {
        json(response, 404, { error: "接口不存在" });
        return;
      }

      if (request.method === "GET" && route.resource === "status") {
        await authorize(request, route.deviceId, "upload");
        json(response, 200, {
          protocol: PROTOCOL,
          pendingBatches: await repository.countPending(route.deviceId)
        });
        return;
      }

      if (request.method === "POST" && route.resource === "batches" && !route.batchId) {
        await authorize(request, route.deviceId, "upload");
        const body = await readJson(request);
        const envelope = body.envelope || {};
        if (
          body.protocol !== PROTOCOL ||
          envelope.algorithm !== "A256GCM" ||
          !BASE64URL_PATTERN.test(envelope.nonce || "") ||
          Buffer.from(envelope.nonce || "", "base64url").length !== 12 ||
          !BASE64URL_PATTERN.test(envelope.ciphertext || "") ||
          Buffer.from(envelope.ciphertext || "", "base64url").length < 17
        ) {
          throw Object.assign(new Error("加密批次格式无效"), { status: 400 });
        }
        const batch = {
          id: crypto.randomUUID(),
          protocol: PROTOCOL,
          itemCount: Math.max(0, Math.min(100, Number(body.itemCount || 0))),
          envelope: {
            algorithm: "A256GCM",
            nonce: envelope.nonce,
            ciphertext: envelope.ciphertext
          },
          createdAt: Date.now()
        };
        await repository.enqueueBatch(route.deviceId, batch);
        events.emit(`batch:${route.deviceId}`);
        json(response, 201, { queued: true, batchId: batch.id });
        return;
      }

      if (request.method === "GET" && route.resource === "batches") {
        await authorize(request, route.deviceId, "read");
        let batches = await repository.listBatches(route.deviceId);
        const waitSeconds = Math.max(0, Math.min(30, Number(url.searchParams.get("wait") || 0)));
        if (!batches.length && waitSeconds > 0) {
          await new Promise((resolve) => {
            const event = `batch:${route.deviceId}`;
            const timer = setTimeout(done, Math.min(longPollMs, waitSeconds * 1000));
            function done() {
              clearTimeout(timer);
              events.removeListener(event, done);
              resolve();
            }
            events.once(event, done);
          });
          batches = await repository.listBatches(route.deviceId);
        }
        json(response, 200, { protocol: PROTOCOL, batches });
        return;
      }

      if (request.method === "POST" && route.resource === "batches" && route.batchId) {
        await authorize(request, route.deviceId, "read");
        const acknowledged = await repository.ackBatch(route.deviceId, route.batchId);
        json(response, acknowledged ? 200 : 404, acknowledged
          ? { acknowledged: true }
          : { error: "批次不存在" });
        return;
      }

      json(response, 404, { error: "接口不存在" });
    } catch (error) {
      json(response, Number(error.status || 500), {
        error: error.status ? error.message : "服务器内部错误"
      });
    }
  };
}

export { PROTOCOL, sha256 };
