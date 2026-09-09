import assert from "node:assert/strict";
import crypto from "node:crypto";
import http from "node:http";
import test from "node:test";
import { createHandler, MemoryRepository } from "../src/app.mjs";

const registrationKey = "test-registration-key-123";

function request(port, method, pathname, body, headers = {}) {
  const payload = body ? Buffer.from(JSON.stringify(body)) : null;
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: "127.0.0.1",
      port,
      method,
      path: pathname,
      headers: {
        ...headers,
        ...(payload ? { "Content-Type": "application/json", "Content-Length": payload.length } : {})
      }
    }, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks)) }));
    });
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

test("registers a device and relays only encrypted batches", async () => {
  const repository = new MemoryRepository();
  const server = http.createServer(createHandler({ repository, registrationKey, longPollMs: 20 }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  const deviceId = crypto.randomUUID();
  const uploadToken = crypto.randomBytes(32).toString("base64url");
  const readToken = crypto.randomBytes(32).toString("base64url");
  const nonce = crypto.randomBytes(12).toString("base64url");
  const ciphertext = crypto.randomBytes(64).toString("base64url");
  try {
    const health = await request(port, "GET", "/healthz");
    assert.equal(health.body.protocol, "linguabridge-memory/1");
    assert.ok(health.body.protocols.includes("linguabridge-wordbooks/1"));
    const created = await request(port, "POST", "/v1/devices", {
      deviceId,
      uploadToken,
      readToken
    }, { "X-Registration-Key": registrationKey });
    assert.equal(created.status, 201);

    const queued = await request(port, "POST", `/v1/devices/${deviceId}/batches`, {
      protocol: "linguabridge-memory/1",
      itemCount: 2,
      envelope: { algorithm: "A256GCM", nonce, ciphertext }
    }, { Authorization: `Bearer ${uploadToken}` });
    assert.equal(queued.status, 201);

    const received = await request(
      port,
      "GET",
      `/v1/devices/${deviceId}/batches?wait=1`,
      null,
      { Authorization: `Bearer ${readToken}` }
    );
    assert.equal(received.status, 200);
    assert.equal(received.body.batches.length, 1);
    assert.equal(received.body.batches[0].envelope.ciphertext, ciphertext);

    const acked = await request(
      port,
      "POST",
      `/v1/devices/${deviceId}/batches/${queued.body.batchId}/ack`,
      {},
      { Authorization: `Bearer ${readToken}` }
    );
    assert.equal(acked.status, 200);
    assert.equal(await repository.countPending(deviceId), 0);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("rejects wrong registration and device tokens", async () => {
  const repository = new MemoryRepository();
  const server = http.createServer(createHandler({ repository, registrationKey }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  try {
    const denied = await request(port, "POST", "/v1/devices", {
      deviceId: crypto.randomUUID(),
      uploadToken: crypto.randomBytes(32).toString("base64url"),
      readToken: crypto.randomBytes(32).toString("base64url")
    }, { "X-Registration-Key": "wrong" });
    assert.equal(denied.status, 401);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("syncs encrypted wordbook snapshots with read-token ACL and CAS", async () => {
  const repository = new MemoryRepository();
  const server = http.createServer(createHandler({ repository, registrationKey }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  const deviceId = crypto.randomUUID();
  const bookId = crypto.randomUUID();
  const uploadId = crypto.randomUUID();
  const uploadToken = crypto.randomBytes(32).toString("base64url");
  const readToken = crypto.randomBytes(32).toString("base64url");
  const envelope = { algorithm: "A256GCM", nonce: crypto.randomBytes(12).toString("base64url"), ciphertext: crypto.randomBytes(40).toString("base64url") };
  try {
    assert.equal((await request(port, "POST", "/v1/devices", { deviceId, uploadToken, readToken }, { "X-Registration-Key": registrationKey })).status, 201);
    const base = `/v1/devices/${deviceId}/wordbooks/${bookId}/uploads/${uploadId}`;
    assert.equal((await request(port, "PUT", `${base}/chunks/0`, { protocol: "linguabridge-wordbooks/1", envelope }, { Authorization: `Bearer ${uploadToken}` })).status, 401);
    assert.equal((await request(port, "PUT", `${base}/chunks/0`, { protocol: "linguabridge-wordbooks/1", envelope }, { Authorization: `Bearer ${readToken}` })).status, 200);
    assert.equal((await request(port, "POST", `${base}/commit`, { protocol: "linguabridge-wordbooks/1", baseVersion: 0, chunkCount: 1, manifest: envelope }, { Authorization: `Bearer ${readToken}` })).status, 200);
    const list = await request(port, "GET", `/v1/devices/${deviceId}/wordbooks`, null, { Authorization: `Bearer ${readToken}` });
    assert.equal(list.status, 200); assert.equal(list.body.wordbooks[0].version, 1);
    const chunk = await request(port, "GET", `${base}/chunks/0`, null, { Authorization: `Bearer ${readToken}` });
    assert.equal(chunk.status, 200); assert.equal(chunk.body.envelope.ciphertext, envelope.ciphertext);
    const idempotent = await request(port, "POST", `${base}/commit`, { protocol: "linguabridge-wordbooks/1", baseVersion: 0, chunkCount: 1, manifest: envelope }, { Authorization: `Bearer ${readToken}` });
    assert.equal(idempotent.status, 200); assert.equal(idempotent.body.version, 1);
    const conflict = await request(port, "POST", `${base}/commit`, { protocol: "linguabridge-wordbooks/1", baseVersion: 0, chunkCount: 1, manifest: envelope }, { Authorization: `Bearer ${readToken}` });
    assert.equal(conflict.status, 200); // same upload ID is explicitly idempotent
    const secondUpload = crypto.randomUUID();
    const second = `/v1/devices/${deviceId}/wordbooks/${bookId}/uploads/${secondUpload}`;
    await request(port, "PUT", `${second}/chunks/0`, { protocol: "linguabridge-wordbooks/1", envelope }, { Authorization: `Bearer ${readToken}` });
    const cas = await request(port, "POST", `${second}/commit`, { protocol: "linguabridge-wordbooks/1", baseVersion: 0, chunkCount: 1, manifest: envelope }, { Authorization: `Bearer ${readToken}` });
    assert.equal(cas.status, 409);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});

test("reports desktop presence and allows the paired phone to read it", async () => {
  const repository = new MemoryRepository();
  const server = http.createServer(createHandler({ repository, registrationKey }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  const deviceId = crypto.randomUUID();
  const clientId = crypto.randomUUID();
  const uploadToken = crypto.randomBytes(32).toString("base64url");
  const readToken = crypto.randomBytes(32).toString("base64url");
  try {
    assert.equal((await request(port, "POST", "/v1/devices", {
      deviceId,
      uploadToken,
      readToken
    }, { "X-Registration-Key": registrationKey })).status, 201);

    const heartbeat = await request(
      port,
      "POST",
      `/v1/devices/${deviceId}/clients`,
      { clientId, name: "Mac mini", platform: "darwin", appVersion: "0.8.0" },
      { Authorization: `Bearer ${uploadToken}` }
    );
    assert.equal(heartbeat.status, 200);
    assert.equal(heartbeat.body.updated, true);

    const visibleToPhone = await request(
      port,
      "GET",
      `/v1/devices/${deviceId}/clients`,
      null,
      { Authorization: `Bearer ${readToken}` }
    );
    assert.equal(visibleToPhone.status, 200);
    assert.equal(visibleToPhone.body.clients.length, 1);
    assert.equal(visibleToPhone.body.clients[0].name, "Mac mini");
    assert.equal(visibleToPhone.body.clients[0].clientId, clientId);

    const denied = await request(
      port,
      "GET",
      `/v1/devices/${deviceId}/clients`,
      null,
      { Authorization: `Bearer ${crypto.randomBytes(32).toString("base64url")}` }
    );
    assert.equal(denied.status, 401);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("relays a one-time encrypted desktop join without seeing its contents", async () => {
  const repository = new MemoryRepository();
  const server = http.createServer(createHandler({ repository, registrationKey }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  const deviceId = crypto.randomUUID();
  const uploadToken = crypto.randomBytes(32).toString("base64url");
  const readToken = crypto.randomBytes(32).toString("base64url");
  const transferId = crypto.randomUUID();
  const accessToken = crypto.randomBytes(32).toString("base64url");
  const nonce = crypto.randomBytes(12).toString("base64url");
  const ciphertext = crypto.randomBytes(128).toString("base64url");
  try {
    const createdDevice = await request(port, "POST", "/v1/devices", {
      deviceId,
      uploadToken,
      readToken
    }, { "X-Registration-Key": registrationKey });
    assert.equal(createdDevice.status, 201);

    const createdTransfer = await request(
      port,
      "POST",
      `/v1/devices/${deviceId}/transfers`,
      {
        protocol: "linguabridge-memory/1",
        transferId,
        accessTokenHash: crypto.createHash("sha256").update(accessToken).digest("hex"),
        envelope: { algorithm: "A256GCM", nonce, ciphertext }
      },
      { Authorization: `Bearer ${uploadToken}` }
    );
    assert.equal(createdTransfer.status, 201);

    const wrongClaim = await request(
      port,
      "POST",
      `/v1/transfers/${transferId}/claim`,
      {},
      { Authorization: `Bearer ${crypto.randomBytes(32).toString("base64url")}` }
    );
    assert.equal(wrongClaim.status, 404);

    const claimed = await request(
      port,
      "POST",
      `/v1/transfers/${transferId}/claim`,
      {},
      { Authorization: `Bearer ${accessToken}` }
    );
    assert.equal(claimed.status, 200);
    assert.equal(claimed.body.envelope.ciphertext, ciphertext);
    assert.equal(JSON.stringify(claimed.body).includes(uploadToken), false);

    const replayed = await request(
      port,
      "POST",
      `/v1/transfers/${transferId}/claim`,
      {},
      { Authorization: `Bearer ${accessToken}` }
    );
    assert.equal(replayed.status, 404);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
