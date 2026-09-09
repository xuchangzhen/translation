import assert from "node:assert/strict";
import crypto from "node:crypto";
import test from "node:test";
import pg from "pg";
import { PostgresMemoryRepository } from "../src/postgres.mjs";

// Explicit opt-in: use a disposable database, never production credentials.
const databaseUrl = process.env.TEST_DATABASE_URL;
test("PostgreSQL wordbook migration, concurrency, quota and retention", { skip: !databaseUrl }, async (t) => {
  const admin = new pg.Pool({ connectionString: databaseUrl });
  const schema = `wordbook_test_${crypto.randomBytes(10).toString("hex")}`;
  await admin.query(`CREATE SCHEMA ${schema}`);
  const url = new URL(databaseUrl);
  url.searchParams.set("options", `-c search_path=${schema}`);
  const repo = new PostgresMemoryRepository(url.toString());
  const envelope = { algorithm: "A256GCM", nonce: crypto.randomBytes(12).toString("base64url"), ciphertext: crypto.randomBytes(40).toString("base64url") };
  const device = crypto.randomUUID();
  const book = crypto.randomUUID();
  const uploads = [crypto.randomUUID(), crypto.randomUUID()];
  const commit = { baseVersion: 0, chunkCount: 1, manifest: envelope };
  let winner;
  try {
    await t.test("initialization preserves legacy data and is repeatable", async () => {
      await repo.initialize();
      await repo.createDevice({ id: device, uploadTokenHash: "1".repeat(64), readTokenHash: "2".repeat(64) });
      // Reproduce the deployed four-table schema inside our isolated test schema.
      await repo.pool.query("DROP TABLE sync_wordbook_uploads, sync_wordbook_snapshots");
      await repo.initialize();
      await repo.initialize();
      assert.ok(await repo.getDevice(device));
    });
    await t.test("concurrent duplicate chunks are idempotent and immutable", async () => {
      await Promise.all(Array.from({ length: 8 }, () => repo.putWordbookChunk(device, book, uploads[0], 0, envelope)));
      await assert.rejects(repo.putWordbookChunk(device, book, uploads[0], 0, { ...envelope, ciphertext: "changed" }), { status: 409 });
    });
    await t.test("incomplete and extra chunks cannot be committed", async () => {
      await assert.rejects(repo.commitWordbook(device, book, uploads[0], { ...commit, chunkCount: 2 }), { status: 409 });
      await assert.rejects(repo.commitWordbook(device, book, uploads[0], { ...commit, chunkCount: 0 }), { status: 409 });
    });
    await t.test("concurrent first commits yield one winner and one 409", async () => {
      await repo.putWordbookChunk(device, book, uploads[1], 0, envelope);
      const results = await Promise.allSettled(uploads.map(id => repo.commitWordbook(device, book, id, commit)));
      assert.equal(results.filter(r => r.status === "fulfilled").length, 1);
      assert.equal(results.find(r => r.status === "rejected").reason.status, 409);
      winner = results.find(r => r.status === "fulfilled").value.uploadId;
      const retries = await Promise.all(Array.from({ length: 5 }, () => repo.commitWordbook(device, book, winner, commit)));
      assert.ok(retries.every(r => r.version === 1));
      await assert.rejects(repo.putWordbookChunk(device, book, winner, 1, envelope), { status: 409 });
    });
    await t.test("latest snapshots persist; replaced snapshots expire after retirement", async () => {
      await repo.pool.query("UPDATE sync_wordbook_snapshots SET created_at=NOW()-INTERVAL '30 days'");
      await repo.pool.query("UPDATE sync_wordbook_uploads SET created_at=NOW()-INTERVAL '30 days'");
      await repo.initialize();
      assert.equal((await repo.listWordbooks(device))[0].version, 1);
      assert.ok(await repo.getWordbookChunk(device, book, winner, 0));
      const next = crypto.randomUUID();
      await repo.putWordbookChunk(device, book, next, 0, envelope);
      await repo.commitWordbook(device, book, next, { ...commit, baseVersion: 1 });
      await repo.cleanupWordbooks();
      assert.ok(await repo.getWordbookChunk(device, book, winner, 0));
      await repo.pool.query("UPDATE sync_wordbook_snapshots SET retired_at=NOW()-INTERVAL '8 days' WHERE version=1");
      await repo.cleanupWordbooks();
      assert.equal(await repo.getWordbookChunk(device, book, winner, 0), null);
      assert.equal((await repo.listWordbooks(device))[0].version, 2);
    });
    await t.test("the 200-book quota is enforced for both chunks and empty books", async () => {
      for (let i = 1; i < 200; i++) {
        await repo.commitWordbook(device, crypto.randomUUID(), crypto.randomUUID(), { ...commit, chunkCount: 0 });
      }
      await assert.rejects(repo.commitWordbook(device, crypto.randomUUID(), crypto.randomUUID(), { ...commit, chunkCount: 0 }), { status: 413 });
      await assert.rejects(repo.putWordbookChunk(device, crypto.randomUUID(), crypto.randomUUID(), 0, envelope), { status: 413 });
    });
  } finally {
    await repo.close();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
