// Run explicitly after deployment: node src/verify-wordbooks.mjs
// Registers one temporary device, tests encrypted HTTP transfer, then deletes
// only that device. Never prints tokens, payloads, connection strings or errors.
import crypto from "node:crypto";
import { PostgresMemoryRepository } from "./postgres.mjs";

const protocol = "linguabridge-wordbooks/1";
const baseUrl = process.env.SYNC_VERIFY_URL || `http://127.0.0.1:${process.env.PORT || 8787}`;
const repo = new PostgresMemoryRepository(process.env.DATABASE_URL || "");
const deviceId = crypto.randomUUID();
const bookId = crypto.randomUUID();
const uploadId = crypto.randomUUID();
const uploadToken = crypto.randomBytes(32).toString("base64url");
const readToken = crypto.randomBytes(32).toString("base64url");
const key = crypto.randomBytes(32);
const plaintext = JSON.stringify([{ term: "verification", translation: "验收测试" }]);
function encrypt(value) {
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, nonce);
  const bytes = Buffer.concat([cipher.update(value, "utf8"), cipher.final(), cipher.getAuthTag()]);
  return { algorithm: "A256GCM", nonce: nonce.toString("base64url"), ciphertext: bytes.toString("base64url") };
}
const envelope = encrypt(plaintext);
const manifest = encrypt(JSON.stringify({ name: "临时验收词库", itemCount: 1 }));
const path = `/v1/devices/${deviceId}/wordbooks/${bookId}/uploads/${uploadId}`;
let stage = "configuration";
let registered = false;
const passed = [];
async function request(label, method, route, body, expected, token = readToken) {
  stage = label;
  const response = await fetch(`${baseUrl}${route}`, {
    method, signal: AbortSignal.timeout(15000),
    headers: {
      "Content-Type": "application/json", Authorization: `Bearer ${token}`,
      ...(route === "/v1/devices" ? { "X-Registration-Key": process.env.SYNC_REGISTRATION_KEY } : {})
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  if (response.status !== expected) throw new Error("unexpected status");
  const data = await response.json();
  passed.push(label);
  return data;
}
try {
  if (!process.env.SYNC_REGISTRATION_KEY || (!process.env.DATABASE_URL && !process.env.PGHOST)) throw new Error("missing environment");
  const health = await request("health", "GET", "/healthz", undefined, 200);
  if (!health.protocols?.includes(protocol)) throw new Error("missing protocol");
  await request("registration", "POST", "/v1/devices", { deviceId, uploadToken, readToken }, 201);
  registered = true;
  const data = { protocol, baseVersion: 0, chunkCount: 1, manifest };
  await request("upload-token-denied", "PUT", `${path}/chunks/0`, { protocol, envelope }, 401, uploadToken);
  await request("missing-chunk", "POST", `${path}/commit`, data, 409);
  await request("chunk", "PUT", `${path}/chunks/0`, { protocol, envelope }, 200);
  await request("duplicate-chunk", "PUT", `${path}/chunks/0`, { protocol, envelope }, 200);
  await request("changed-chunk-denied", "PUT", `${path}/chunks/0`, { protocol, envelope: encrypt("different") }, 409);
  await request("commit", "POST", `${path}/commit`, data, 200);
  await request("duplicate-commit", "POST", `${path}/commit`, data, 200);
  const list = await request("list", "GET", `/v1/devices/${deviceId}/wordbooks`, undefined, 200);
  if (list.protocol !== protocol || list.wordbooks.length !== 1 || list.wordbooks[0].version !== 1) throw new Error("invalid list");
  const chunk = await request("download", "GET", `${path}/chunks/0`, undefined, 200);
  stage = "decrypt";
  const bytes = Buffer.from(chunk.envelope.ciphertext, "base64url");
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(chunk.envelope.nonce, "base64url"));
  decipher.setAuthTag(bytes.subarray(-16));
  if (Buffer.concat([decipher.update(bytes.subarray(0, -16)), decipher.final()]).toString("utf8") !== plaintext) throw new Error("mismatch");
  passed.push(stage);
  const next = `/v1/devices/${deviceId}/wordbooks/${bookId}/uploads/${crypto.randomUUID()}`;
  await request("next-upload", "PUT", `${next}/chunks/0`, { protocol, envelope }, 200);
  await request("stale-version-denied", "POST", `${next}/commit`, data, 409);
  await request("invalid-protocol", "PUT", `${next}/chunks/1`, { protocol: "invalid", envelope }, 400);
  await request("chunk-limit", "PUT", `${next}/chunks/1024`, { protocol, envelope }, 400);
  await request("body-limit", "PUT", `${next}/chunks/1`, { protocol, envelope, padding: "x".repeat(512 * 1024) }, 413);
  console.log(JSON.stringify({ verified: true, checks: passed }));
} catch {
  console.error(JSON.stringify({ verified: false, failedStage: stage, passed }));
  process.exitCode = 1;
} finally {
  try {
    if (registered) {
      const result = await repo.pool.query("DELETE FROM sync_devices WHERE id=$1", [deviceId]);
      if (result.rowCount !== 1) throw new Error("cleanup failed");
    }
    console.log(JSON.stringify({ temporaryDeviceCleanup: "complete" }));
  } catch {
    console.error(JSON.stringify({ temporaryDeviceCleanup: "failed", temporaryDeviceId: deviceId }));
    process.exitCode = 1;
  }
  await repo.close();
}
