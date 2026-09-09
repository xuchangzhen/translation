import crypto from "node:crypto";
import { EventEmitter } from "node:events";

const PROTOCOL = "linguabridge-memory/1";
const WORDBOOK_PROTOCOL = "linguabridge-wordbooks/1";
const MAX_BODY_BYTES = 1024 * 1024;
const MAX_WORDBOOK_BODY_BYTES = 512 * 1024;
const MAX_WORDBOOKS = 200;
const MAX_WORDBOOK_CHUNKS = 1024;
const UPLOAD_RETENTION_MS = 24 * 60 * 60 * 1000;
const SNAPSHOT_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
const DEVICE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const TRANSFER_TTL_MS = 15 * 60 * 1000;
const CLIENT_RETENTION_MS = 90 * 24 * 60 * 60 * 1000;

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

async function readJson(request, maxBytes = MAX_BODY_BYTES) {
  const declaredLength = Number(request.headers["content-length"] || 0);
  if (declaredLength > maxBytes) throw Object.assign(new Error("请求过大"), { status: 413 });
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > maxBytes) throw Object.assign(new Error("请求过大"), { status: 413 });
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
  const match = pathname.match(/^\/v1\/devices\/([^/]+)(?:\/(status|batches|transfers|clients)(?:\/([^/]+)\/ack)?)?$/);
  if (!match) return null;
  return {
    deviceId: decodeURIComponent(match[1]),
    resource: match[2] || "device",
    batchId: match[3] ? decodeURIComponent(match[3]) : ""
  };
}

function transferPath(pathname) {
  const match = pathname.match(/^\/v1\/transfers\/([^/]+)\/claim$/);
  return match ? decodeURIComponent(match[1]) : "";
}

function wordbookPath(pathname) {
  const match = pathname.match(/^\/v1\/devices\/([^/]+)\/wordbooks(?:\/([^/]+)\/uploads\/([^/]+)\/(?:chunks\/(\d+)|(commit)))?$/);
  if (!match || !DEVICE_ID_PATTERN.test(match[1])) return null;
  if (match[2] && (!DEVICE_ID_PATTERN.test(match[2]) || !DEVICE_ID_PATTERN.test(match[3]))) return null;
  return {
    deviceId: match[1],
    bookId: match[2]?.toLowerCase(),
    uploadId: match[3]?.toLowerCase(),
    index: match[4] === undefined ? null : Number(match[4]),
    commit: Boolean(match[5])
  };
}

function wordbookError(message, status = 409) {
  return Object.assign(new Error(message), { status });
}

function encryptedValue(envelope) {
  if (typeof envelope?.nonce !== "string" || typeof envelope?.ciphertext !== "string" || !validEnvelope(envelope)) {
    throw wordbookError("词库密文格式无效", 400);
  }
  return { algorithm: "A256GCM", nonce: envelope.nonce, ciphertext: envelope.ciphertext };
}

function sameEnvelope(left, right) {
  return left?.algorithm === right?.algorithm && left?.nonce === right?.nonce && left?.ciphertext === right?.ciphertext;
}

function wordbookSummary(upload) {
  return {
    id: upload.bookId,
    version: upload.version,
    uploadId: upload.uploadId,
    chunkCount: upload.chunkCount,
    manifest: structuredClone(upload.manifest)
  };
}

function validEnvelope(envelope) {
  return envelope?.algorithm === "A256GCM" &&
    BASE64URL_PATTERN.test(envelope.nonce || "") &&
    Buffer.from(envelope.nonce || "", "base64url").length === 12 &&
    BASE64URL_PATTERN.test(envelope.ciphertext || "") &&
    Buffer.from(envelope.ciphertext || "", "base64url").length >= 17;
}

export class MemoryRepository {
  constructor() {
    this.devices = new Map();
    this.batches = new Map();
    this.transfers = new Map();
    this.clients = new Map();
    this.wordbookUploads = new Map();
    this.wordbooks = new Map();
  }

  async createDevice(device) {
    if (this.devices.has(device.id)) return false;
    this.devices.set(device.id, { ...device, createdAt: Date.now(), lastSeenAt: Date.now() });
    this.batches.set(device.id, []);
    this.clients.set(device.id, new Map());
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

  async createTransfer(transfer) {
    const now = Date.now();
    for (const [id, candidate] of this.transfers) {
      if (candidate.expiresAt <= now) this.transfers.delete(id);
    }
    if (this.transfers.has(transfer.id)) return false;
    this.transfers.set(transfer.id, transfer);
    return true;
  }

  async claimTransfer(id, accessTokenHash) {
    const transfer = this.transfers.get(id);
    if (
      !transfer ||
      transfer.expiresAt <= Date.now() ||
      !safeEqual(transfer.accessTokenHash, accessTokenHash)
    ) {
      if (transfer?.expiresAt <= Date.now()) this.transfers.delete(id);
      return null;
    }
    this.transfers.delete(id);
    return transfer;
  }

  async upsertClient(deviceId, client) {
    const now = Date.now();
    const clients = this.clients.get(deviceId) || new Map();
    for (const [id, candidate] of clients) {
      if (candidate.lastSeenAt < now - CLIENT_RETENTION_MS) clients.delete(id);
    }
    const previous = clients.get(client.clientId);
    clients.set(client.clientId, {
      ...client,
      firstSeenAt: previous?.firstSeenAt || now,
      lastSeenAt: now
    });
    this.clients.set(deviceId, clients);
  }

  async listClients(deviceId) {
    const cutoff = Date.now() - CLIENT_RETENTION_MS;
    return [...(this.clients.get(deviceId) || new Map()).values()]
      .filter((client) => client.lastSeenAt >= cutoff)
      .sort((left, right) => right.lastSeenAt - left.lastSeenAt);
  }

  cleanupWordbooks(deviceId) {
    const uploads = this.wordbookUploads.get(deviceId);
    if (!uploads) return;
    const now = Date.now();
    for (const [key, upload] of uploads) {
      if ((!upload.version && upload.createdAt < now - UPLOAD_RETENTION_MS) ||
          (upload.retiredAt && upload.retiredAt < now - SNAPSHOT_RETENTION_MS)) uploads.delete(key);
    }
  }

  async listWordbooks(deviceId) {
    return [...(this.wordbooks.get(deviceId)?.values() || [])]
      .map(wordbookSummary).sort((left, right) => left.id.localeCompare(right.id));
  }

  getOrCreateWordbookUpload(deviceId, bookId, uploadId) {
    this.cleanupWordbooks(deviceId);
    const uploads = this.wordbookUploads.get(deviceId) || new Map();
    const key = `${bookId}/${uploadId}`;
    let upload = uploads.get(key);
    if (!upload) {
      const bookIds = new Set([...uploads.values()].map((item) => item.bookId));
      if (!bookIds.has(bookId) && bookIds.size >= MAX_WORDBOOKS) throw wordbookError("同步空间最多保存 200 个词库", 413);
      upload = { bookId, uploadId, version: 0, chunks: new Map(), createdAt: Date.now(), retiredAt: null };
      uploads.set(key, upload);
      this.wordbookUploads.set(deviceId, uploads);
    }
    return upload;
  }

  async putWordbookChunk(deviceId, bookId, uploadId, index, envelope) {
    const upload = this.getOrCreateWordbookUpload(deviceId, bookId, uploadId);
    const previous = upload.chunks.get(index);
    if (previous && sameEnvelope(previous, envelope)) return;
    if (previous || upload.version) throw wordbookError("此上传分片已经固定，请创建新的上传编号");
    upload.chunks.set(index, structuredClone(envelope));
  }

  async commitWordbook(deviceId, bookId, uploadId, { baseVersion, chunkCount, manifest }) {
    // No awaits between checking the base version and publishing: the in-memory
    // implementation has the same atomic visibility as the PostgreSQL transaction.
    const upload = this.getOrCreateWordbookUpload(deviceId, bookId, uploadId);
    if (upload.version) {
      if (upload.chunkCount !== chunkCount || !sameEnvelope(upload.manifest, manifest)) throw wordbookError("此上传编号已用于其他提交");
      return wordbookSummary(upload);
    }
    const books = this.wordbooks.get(deviceId) || new Map();
    const current = books.get(bookId);
    if ((current?.version || 0) !== baseVersion) throw wordbookError("词库已由其他设备更新，请重新下载后同步");
    if (upload.chunks.size !== chunkCount || Array.from({ length: chunkCount }, (_, index) => index).some((index) => !upload.chunks.has(index))) {
      throw wordbookError("词库分片不完整，请完成上传后重试");
    }
    if (current) current.retiredAt = Date.now();
    Object.assign(upload, { version: baseVersion + 1, chunkCount, manifest: structuredClone(manifest) });
    books.set(bookId, upload);
    this.wordbooks.set(deviceId, books);
    return wordbookSummary(upload);
  }

  async getWordbookChunk(deviceId, bookId, uploadId, index) {
    const upload = this.wordbookUploads.get(deviceId)?.get(`${bookId}/${uploadId}`);
    return upload?.version && upload.chunks.has(index) ? structuredClone(upload.chunks.get(index)) : null;
  }
}

export function createHandler({ repository, registrationKey, longPollMs = 25_000 }) {
  if (!registrationKey || registrationKey.length < 16) {
    throw new Error("SYNC_REGISTRATION_KEY 至少需要 16 个字符");
  }
  const events = new EventEmitter();
  events.setMaxListeners(1000);
  const rateBuckets = new Map();

  function rateLimited(request, wordbooks = false) {
    const address = `${request.socket.remoteAddress || "unknown"}:${wordbooks ? "wordbooks" : "memory"}`;
    const now = Date.now();
    const bucket = rateBuckets.get(address);
    if (!bucket || now - bucket.startedAt >= 60_000) {
      rateBuckets.set(address, { startedAt: now, count: 1 });
      return false;
    }
    bucket.count += 1;
    // A complete 1024-part upload followed by a download must fit one window.
    return bucket.count > (wordbooks ? 2400 : 180);
  }

  async function authorize(request, deviceId, mode) {
    const device = await repository.getDevice(deviceId);
    if (!device) throw Object.assign(new Error("设备不存在"), { status: 404 });
    const token = bearerToken(request);
    const expected = mode === "read"
      ? [device.readTokenHash]
      : mode === "either"
        ? [device.uploadTokenHash, device.readTokenHash]
        : [device.uploadTokenHash];
    if (!validToken(token) || !expected.some((hash) => safeEqual(sha256(token), hash))) {
      throw Object.assign(new Error("设备凭据无效"), { status: 401 });
    }
    await repository.touchDevice(deviceId);
    return device;
  }

  return async function handler(request, response) {
    try {
      const url = new URL(request.url, "http://relay.local");
      const wordbookRoute = wordbookPath(url.pathname);
      if (rateLimited(request, Boolean(wordbookRoute))) {
        json(response, 429, { error: "请求过于频繁" });
        return;
      }
      if (request.method === "GET" && url.pathname === "/healthz") {
        json(response, 200, { ok: true, protocol: PROTOCOL, protocols: [PROTOCOL, WORDBOOK_PROTOCOL] });
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

      const transferId = transferPath(url.pathname);
      if (request.method === "POST" && transferId) {
        if (!DEVICE_ID_PATTERN.test(transferId)) {
          throw Object.assign(new Error("接力编号无效"), { status: 400 });
        }
        const token = bearerToken(request);
        if (!validToken(token)) {
          throw Object.assign(new Error("接力凭据无效"), { status: 401 });
        }
        const transfer = await repository.claimTransfer(transferId, sha256(token));
        if (!transfer) {
          throw Object.assign(new Error("接力链接已使用或已过期"), { status: 404 });
        }
        json(response, 200, {
          protocol: PROTOCOL,
          envelope: transfer.envelope,
          expiresAt: transfer.expiresAt
        });
        return;
      }

      if (wordbookRoute) {
        const { deviceId, bookId, uploadId, index, commit } = wordbookRoute;
        // The desktop upload credential intentionally has no access to phone
        // wordbooks. Every operation requires the paired phones' read token.
        await authorize(request, deviceId, "read");
        if (request.method === "GET" && !bookId) {
          json(response, 200, { protocol: WORDBOOK_PROTOCOL, wordbooks: await repository.listWordbooks(deviceId) });
          return;
        }
        if (index !== null && (!Number.isInteger(index) || index < 0 || index >= MAX_WORDBOOK_CHUNKS)) {
          throw wordbookError("词库分片编号无效", 400);
        }
        if (request.method === "GET" && index !== null) {
          const envelope = await repository.getWordbookChunk(deviceId, bookId, uploadId, index);
          if (!envelope) throw wordbookError("已提交的词库分片不存在", 404);
          json(response, 200, { protocol: WORDBOOK_PROTOCOL, envelope });
          return;
        }
        if (request.method === "PUT" && index !== null) {
          const body = await readJson(request, MAX_WORDBOOK_BODY_BYTES);
          if (body.protocol !== WORDBOOK_PROTOCOL) throw wordbookError("词库同步协议无效", 400);
          await repository.putWordbookChunk(deviceId, bookId, uploadId, index, encryptedValue(body.envelope));
          json(response, 200, { protocol: WORDBOOK_PROTOCOL, stored: true });
          return;
        }
        if (request.method === "POST" && commit) {
          const body = await readJson(request, MAX_WORDBOOK_BODY_BYTES);
          if (body.protocol !== WORDBOOK_PROTOCOL || !Number.isSafeInteger(body.baseVersion) || body.baseVersion < 0 || body.baseVersion >= 2147483647 ||
              !Number.isInteger(body.chunkCount) || body.chunkCount < 0 || body.chunkCount > MAX_WORDBOOK_CHUNKS) {
            throw wordbookError("词库提交格式无效", 400);
          }
          const published = await repository.commitWordbook(deviceId, bookId, uploadId, {
            baseVersion: body.baseVersion, chunkCount: body.chunkCount, manifest: encryptedValue(body.manifest)
          });
          json(response, 200, { protocol: WORDBOOK_PROTOCOL, ...published });
          return;
        }
        json(response, 404, { error: "接口不存在" });
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
          !validEnvelope(envelope)
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

      if (request.method === "POST" && route.resource === "transfers") {
        await authorize(request, route.deviceId, "upload");
        const body = await readJson(request);
        const envelope = body.envelope || {};
        if (
          body.protocol !== PROTOCOL ||
          !DEVICE_ID_PATTERN.test(body.transferId || "") ||
          !SHA256_PATTERN.test(body.accessTokenHash || "") ||
          !validEnvelope(envelope)
        ) {
          throw Object.assign(new Error("电脑接力信息无效"), { status: 400 });
        }
        const transfer = {
          id: body.transferId,
          deviceId: route.deviceId,
          accessTokenHash: body.accessTokenHash,
          envelope: {
            algorithm: "A256GCM",
            nonce: envelope.nonce,
            ciphertext: envelope.ciphertext
          },
          createdAt: Date.now(),
          expiresAt: Date.now() + TRANSFER_TTL_MS
        };
        const created = await repository.createTransfer(transfer);
        json(response, created ? 201 : 409, created
          ? { created: true, expiresAt: transfer.expiresAt, protocol: PROTOCOL }
          : { error: "接力编号已经存在" });
        return;
      }

      if (request.method === "POST" && route.resource === "clients") {
        await authorize(request, route.deviceId, "upload");
        const body = await readJson(request);
        const name = String(body.name || "").trim().slice(0, 80);
        const platform = String(body.platform || "");
        const appVersion = String(body.appVersion || "").trim();
        if (
          !DEVICE_ID_PATTERN.test(body.clientId || "") ||
          !name ||
          !["darwin", "win32", "linux"].includes(platform) ||
          !/^[A-Za-z0-9._+-]{1,32}$/.test(appVersion)
        ) {
          throw Object.assign(new Error("电脑状态信息无效"), { status: 400 });
        }
        await repository.upsertClient(route.deviceId, {
          clientId: body.clientId,
          name,
          platform,
          appVersion
        });
        json(response, 200, { updated: true, protocol: PROTOCOL, serverTime: Date.now() });
        return;
      }

      if (request.method === "GET" && route.resource === "clients") {
        await authorize(request, route.deviceId, "either");
        json(response, 200, {
          protocol: PROTOCOL,
          serverTime: Date.now(),
          clients: await repository.listClients(route.deviceId)
        });
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

export { PROTOCOL, WORDBOOK_PROTOCOL, sha256 };
