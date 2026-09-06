const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const http = require("node:http");
const {
  claimDesktopJoinLink,
  createDesktopJoinLink,
  decryptBatch,
  encryptBatch,
  listDesktopClients,
  parsePairing,
  provisionAndroidMemoryConnection,
  reportDesktopPresence,
  syncAndroidMemory,
  testAndroidMemoryConnection
} = require("../src/lib/android-sync.cjs");

function listen(server) {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

function pairingValue(port, token, key) {
  const params = new URLSearchParams({
    server: `http://127.0.0.1:${port}`,
    device: "123e4567-e89b-42d3-a456-426614174000",
    token,
    key
  });
  return `linguabridge-memory://pair?${params}`;
}

test("validates secure pairing payloads", () => {
  const token = crypto.randomBytes(32).toString("base64url");
  const key = crypto.randomBytes(32).toString("base64url");
  const parsed = parsePairing(pairingValue(8787, token, key));
  assert.equal(parsed.deviceId, "123e4567-e89b-42d3-a456-426614174000");
  assert.equal(parsed.encryptionKey, key);
  assert.throws(
    () => parsePairing(pairingValue(8787, "short", key)),
    /上传凭据/
  );
  const insecure = pairingValue(8787, token, key).replace(
    encodeURIComponent("http://127.0.0.1:8787"),
    encodeURIComponent("http://example.com")
  );
  assert.throws(() => parsePairing(insecure), /HTTPS/);
});

test("AES-GCM batch encryption round-trips and rejects a wrong key", () => {
  const key = crypto.randomBytes(32).toString("base64url");
  const otherKey = crypto.randomBytes(32).toString("base64url");
  const items = [{ id: "one", front: "cache", back: "缓存" }];
  const envelope = encryptBatch(items, key);
  assert.deepEqual(decryptBatch(envelope, key).items, items);
  assert.throws(() => decryptBatch(envelope, otherKey));
});

test("desktop provisions a complete phone pairing without exposing secrets to the relay response", async () => {
  const registrationKey = crypto.randomBytes(32).toString("base64url");
  let registrationBody;
  const server = http.createServer((request, response) => {
    assert.equal(request.method, "POST");
    assert.equal(request.url, "/v1/devices");
    assert.equal(request.headers["x-registration-key"], registrationKey);
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      registrationBody = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      response.statusCode = 201;
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({ created: true, protocol: "linguabridge-memory/1" }));
    });
  });
  await listen(server);
  try {
    const port = server.address().port;
    const result = await provisionAndroidMemoryConnection(
      `http://127.0.0.1:${port}`,
      registrationKey
    );
    const parsed = parsePairing(result.pairingUri);
    assert.equal(parsed.deviceId, registrationBody.deviceId);
    assert.equal(parsed.uploadToken, registrationBody.uploadToken);
    assert.equal(parsed.readToken, registrationBody.readToken);
    assert.equal(parsed.encryptionKey, result.encryptionKey);
    assert.equal(Object.hasOwn(registrationBody, "contentKey"), false);
    assert.equal(Object.hasOwn(registrationBody, "encryptionKey"), false);
  } finally {
    await close(server);
  }
});

test("tests the cloud relay and uploads ciphertext only", async () => {
  const uploadToken = crypto.randomBytes(32).toString("base64url");
  const key = crypto.randomBytes(32).toString("base64url");
  const requests = [];
  const server = http.createServer((request, response) => {
    assert.equal(request.headers.authorization, `Bearer ${uploadToken}`);
    if (request.method === "GET") {
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({
        protocol: "linguabridge-memory/1",
        pendingBatches: 0
      }));
      return;
    }
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      requests.push(body);
      response.statusCode = 201;
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({ queued: true, batchId: "batch-one" }));
    });
  });
  await listen(server);
  const port = server.address().port;
  const pairing = pairingValue(port, uploadToken, key);
  const item = {
    id: "one",
    key: "cache",
    front: "cache",
    back: "缓存",
    updatedAt: 1
  };
  let marked = false;
  const outbox = {
    pending: () => [item],
    markSynced: (items) => { marked = items[0] === item; },
    status: () => ({ total: 1, pending: 0, synced: 1, lastSyncedAt: 2 })
  };
  try {
    const status = await testAndroidMemoryConnection(pairing);
    assert.equal(status.pendingBatches, 0);
    const result = await syncAndroidMemory(outbox, pairing);
    assert.equal(result.sent, 1);
    assert.equal(marked, true);
    const serialized = JSON.stringify(requests[0]);
    assert.doesNotMatch(serialized, /cache|缓存/);
    assert.equal(requests[0].envelope.algorithm, "A256GCM");
    assert.deepEqual(decryptBatch(requests[0].envelope, key).items, [item]);
  } finally {
    await close(server);
  }
});

test("adds another desktop to the same phone space through a one-time encrypted link", async () => {
  const { createHandler, MemoryRepository } = await import("../sync-server/src/app.mjs");
  const registrationKey = crypto.randomBytes(32).toString("base64url");
  const repository = new MemoryRepository();
  const server = http.createServer(createHandler({ repository, registrationKey }));
  await listen(server);
  try {
    const port = server.address().port;
    const original = await provisionAndroidMemoryConnection(
      `http://127.0.0.1:${port}`,
      registrationKey
    );
    const transfer = await createDesktopJoinLink(original.pairingUri, "Mac mini");
    assert.match(transfer.joinLink, /^linguabridge-space:\/\/join\?/);
    assert.doesNotMatch(transfer.joinLink, new RegExp(original.encryptionKey));

    const claimed = await claimDesktopJoinLink(transfer.joinLink);
    assert.equal(claimed.computerName, "Mac mini");
    assert.deepEqual(parsePairing(claimed.pairingUri), parsePairing(original.pairingUri));
    assert.equal(claimed.connection.deviceId, original.deviceId);

    const clientId = crypto.randomUUID();
    await reportDesktopPresence(claimed.pairingUri, {
      clientId,
      name: "Windows 工作站",
      platform: "win32",
      appVersion: "0.8.0"
    });
    const presence = await listDesktopClients(original.pairingUri);
    assert.equal(presence.clients[0].clientId, clientId);
    assert.equal(presence.clients[0].name, "Windows 工作站");

    await assert.rejects(
      () => claimDesktopJoinLink(transfer.joinLink),
      /已使用或已过期/
    );
  } finally {
    await close(server);
  }
});
