import pg from "pg";

const { Pool } = pg;

export class PostgresMemoryRepository {
  constructor(connectionString) {
    this.pool = new Pool({
      ...(connectionString ? { connectionString } : {}),
      max: 10,
      idleTimeoutMillis: 30_000
    });
  }

  async initialize() {
    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS sync_devices (
        id UUID PRIMARY KEY,
        upload_token_hash CHAR(64) NOT NULL,
        read_token_hash CHAR(64) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE TABLE IF NOT EXISTS sync_batches (
        id UUID PRIMARY KEY,
        device_id UUID NOT NULL REFERENCES sync_devices(id) ON DELETE CASCADE,
        protocol TEXT NOT NULL,
        item_count INTEGER NOT NULL,
        algorithm TEXT NOT NULL,
        nonce TEXT NOT NULL,
        ciphertext TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE INDEX IF NOT EXISTS sync_batches_device_created_idx
        ON sync_batches(device_id, created_at);
    `);
    await this.pool.query("DELETE FROM sync_batches WHERE created_at < NOW() - INTERVAL '30 days'");
  }

  async createDevice(device) {
    const result = await this.pool.query(
      `INSERT INTO sync_devices (id, upload_token_hash, read_token_hash)
       VALUES ($1, $2, $3) ON CONFLICT (id) DO NOTHING`,
      [device.id, device.uploadTokenHash, device.readTokenHash]
    );
    return result.rowCount === 1;
  }

  async getDevice(id) {
    const result = await this.pool.query(
      `SELECT id, upload_token_hash AS "uploadTokenHash",
              read_token_hash AS "readTokenHash"
       FROM sync_devices WHERE id = $1`,
      [id]
    );
    return result.rows[0] || null;
  }

  async touchDevice(id) {
    await this.pool.query("UPDATE sync_devices SET last_seen_at = NOW() WHERE id = $1", [id]);
  }

  async enqueueBatch(deviceId, batch) {
    await this.pool.query(
      `INSERT INTO sync_batches
       (id, device_id, protocol, item_count, algorithm, nonce, ciphertext, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, to_timestamp($8 / 1000.0))`,
      [
        batch.id,
        deviceId,
        batch.protocol,
        batch.itemCount,
        batch.envelope.algorithm,
        batch.envelope.nonce,
        batch.envelope.ciphertext,
        batch.createdAt
      ]
    );
  }

  async listBatches(deviceId) {
    const result = await this.pool.query(
      `SELECT id, protocol, item_count AS "itemCount", algorithm, nonce,
              ciphertext, EXTRACT(EPOCH FROM created_at) * 1000 AS "createdAt"
       FROM sync_batches WHERE device_id = $1 ORDER BY created_at LIMIT 20`,
      [deviceId]
    );
    return result.rows.map((row) => ({
      id: row.id,
      protocol: row.protocol,
      itemCount: row.itemCount,
      envelope: {
        algorithm: row.algorithm,
        nonce: row.nonce,
        ciphertext: row.ciphertext
      },
      createdAt: Number(row.createdAt)
    }));
  }

  async countPending(deviceId) {
    const result = await this.pool.query(
      "SELECT COUNT(*)::INTEGER AS count FROM sync_batches WHERE device_id = $1",
      [deviceId]
    );
    return result.rows[0]?.count || 0;
  }

  async ackBatch(deviceId, batchId) {
    const result = await this.pool.query(
      "DELETE FROM sync_batches WHERE device_id = $1 AND id = $2",
      [deviceId, batchId]
    );
    return result.rowCount === 1;
  }

  async close() {
    await this.pool.end();
  }
}
