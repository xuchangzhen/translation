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
      CREATE TABLE IF NOT EXISTS sync_transfers (
        id UUID PRIMARY KEY,
        device_id UUID NOT NULL REFERENCES sync_devices(id) ON DELETE CASCADE,
        access_token_hash CHAR(64) NOT NULL,
        algorithm TEXT NOT NULL,
        nonce TEXT NOT NULL,
        ciphertext TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        expires_at TIMESTAMPTZ NOT NULL
      );
      CREATE INDEX IF NOT EXISTS sync_transfers_expires_idx
        ON sync_transfers(expires_at);
      CREATE TABLE IF NOT EXISTS sync_clients (
        device_id UUID NOT NULL REFERENCES sync_devices(id) ON DELETE CASCADE,
        client_id UUID NOT NULL,
        name TEXT NOT NULL,
        platform TEXT NOT NULL,
        app_version TEXT NOT NULL,
        first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (device_id, client_id)
      );
      CREATE INDEX IF NOT EXISTS sync_clients_device_seen_idx
        ON sync_clients(device_id, last_seen_at DESC);
    `);
    await this.pool.query("DELETE FROM sync_batches WHERE created_at < NOW() - INTERVAL '30 days'");
    await this.pool.query("DELETE FROM sync_transfers WHERE expires_at <= NOW()");
    await this.pool.query("DELETE FROM sync_clients WHERE last_seen_at < NOW() - INTERVAL '90 days'");
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

  async createTransfer(transfer) {
    await this.pool.query("DELETE FROM sync_transfers WHERE expires_at <= NOW()");
    const result = await this.pool.query(
      `INSERT INTO sync_transfers
       (id, device_id, access_token_hash, algorithm, nonce, ciphertext, created_at, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6, to_timestamp($7 / 1000.0), to_timestamp($8 / 1000.0))
       ON CONFLICT (id) DO NOTHING`,
      [
        transfer.id,
        transfer.deviceId,
        transfer.accessTokenHash,
        transfer.envelope.algorithm,
        transfer.envelope.nonce,
        transfer.envelope.ciphertext,
        transfer.createdAt,
        transfer.expiresAt
      ]
    );
    return result.rowCount === 1;
  }

  async claimTransfer(id, accessTokenHash) {
    const result = await this.pool.query(
      `DELETE FROM sync_transfers
       WHERE id = $1 AND access_token_hash = $2 AND expires_at > NOW()
       RETURNING id, device_id AS "deviceId", algorithm, nonce, ciphertext,
                 EXTRACT(EPOCH FROM created_at) * 1000 AS "createdAt",
                 EXTRACT(EPOCH FROM expires_at) * 1000 AS "expiresAt"`,
      [id, accessTokenHash]
    );
    const row = result.rows[0];
    return row ? {
      id: row.id,
      deviceId: row.deviceId,
      accessTokenHash,
      envelope: {
        algorithm: row.algorithm,
        nonce: row.nonce,
        ciphertext: row.ciphertext
      },
      createdAt: Number(row.createdAt),
      expiresAt: Number(row.expiresAt)
    } : null;
  }

  async upsertClient(deviceId, client) {
    await this.pool.query(
      `INSERT INTO sync_clients
       (device_id, client_id, name, platform, app_version, first_seen_at, last_seen_at)
       VALUES ($1, $2, $3, $4, $5, NOW(), NOW())
       ON CONFLICT (device_id, client_id) DO UPDATE SET
         name = EXCLUDED.name,
         platform = EXCLUDED.platform,
         app_version = EXCLUDED.app_version,
         last_seen_at = NOW()`,
      [deviceId, client.clientId, client.name, client.platform, client.appVersion]
    );
  }

  async listClients(deviceId) {
    const result = await this.pool.query(
      `SELECT client_id AS "clientId", name, platform,
              app_version AS "appVersion",
              EXTRACT(EPOCH FROM first_seen_at) * 1000 AS "firstSeenAt",
              EXTRACT(EPOCH FROM last_seen_at) * 1000 AS "lastSeenAt"
       FROM sync_clients
       WHERE device_id = $1 AND last_seen_at >= NOW() - INTERVAL '90 days'
       ORDER BY last_seen_at DESC`,
      [deviceId]
    );
    return result.rows.map((row) => ({
      ...row,
      firstSeenAt: Number(row.firstSeenAt),
      lastSeenAt: Number(row.lastSeenAt)
    }));
  }

  async close() {
    await this.pool.end();
  }
}
