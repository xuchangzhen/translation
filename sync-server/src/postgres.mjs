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
      CREATE TABLE IF NOT EXISTS sync_wordbook_uploads (
        device_id UUID NOT NULL REFERENCES sync_devices(id) ON DELETE CASCADE,
        book_id UUID NOT NULL,
        upload_id UUID NOT NULL,
        chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0 AND chunk_index < 1024),
        algorithm TEXT NOT NULL,
        nonce TEXT NOT NULL,
        ciphertext TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (device_id, book_id, upload_id, chunk_index)
      );
      CREATE TABLE IF NOT EXISTS sync_wordbook_snapshots (
        device_id UUID NOT NULL REFERENCES sync_devices(id) ON DELETE CASCADE,
        book_id UUID NOT NULL,
        upload_id UUID NOT NULL,
        version BIGINT NOT NULL,
        chunk_count INTEGER NOT NULL,
        manifest_algorithm TEXT NOT NULL,
        manifest_nonce TEXT NOT NULL,
        manifest_ciphertext TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (device_id, book_id, version),
        UNIQUE (device_id, book_id, upload_id)
      );
      CREATE INDEX IF NOT EXISTS sync_wordbook_snapshots_retention_idx
        ON sync_wordbook_snapshots(created_at);
      ALTER TABLE sync_wordbook_snapshots ADD COLUMN IF NOT EXISTS retired_at TIMESTAMPTZ;
      UPDATE sync_wordbook_snapshots s SET retired_at = NOW()
        WHERE retired_at IS NULL AND EXISTS (
          SELECT 1 FROM sync_wordbook_snapshots newer
          WHERE newer.device_id=s.device_id AND newer.book_id=s.book_id AND newer.version>s.version
        );
    `);
    await this.pool.query("DELETE FROM sync_batches WHERE created_at < NOW() - INTERVAL '30 days'");
    await this.pool.query("DELETE FROM sync_transfers WHERE expires_at <= NOW()");
    await this.pool.query("DELETE FROM sync_clients WHERE last_seen_at < NOW() - INTERVAL '90 days'");
    await this.cleanupWordbooks();
  }

  async cleanupWordbooks() {
    // The latest version remains available indefinitely. Only superseded snapshots expire.
    await this.pool.query("DELETE FROM sync_wordbook_snapshots WHERE retired_at < NOW() - INTERVAL '7 days'");
    await this.pool.query("DELETE FROM sync_wordbook_uploads WHERE created_at < NOW() - INTERVAL '24 hours' AND NOT EXISTS (SELECT 1 FROM sync_wordbook_snapshots s WHERE s.device_id = sync_wordbook_uploads.device_id AND s.book_id = sync_wordbook_uploads.book_id AND s.upload_id = sync_wordbook_uploads.upload_id)");
  }

  async wordbookTransaction(deviceId, operation) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      // A device row exists even for the first version. Locking it also makes the
      // space-wide book quota atomic across concurrent uploads and commits.
      await client.query("SELECT id FROM sync_devices WHERE id=$1 FOR UPDATE", [deviceId]);
      const result = await operation(client);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      try { await client.query("ROLLBACK"); } catch {}
      throw error;
    } finally { client.release(); }
  }

  async checkWordbookQuota(client, deviceId, bookId) {
    const result = await client.query(`SELECT COUNT(*)::INTEGER AS count,
      COALESCE(BOOL_OR(book_id=$2), FALSE) AS present FROM (
        SELECT book_id FROM sync_wordbook_uploads WHERE device_id=$1
        UNION SELECT book_id FROM sync_wordbook_snapshots WHERE device_id=$1
      ) books`, [deviceId, bookId]);
    if (!result.rows[0].present && result.rows[0].count >= 200) {
      throw Object.assign(new Error("同步空间最多保存 200 个词库"), { status: 413 });
    }
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

  async listWordbooks(deviceId) {
    const result = await this.pool.query(
      `SELECT DISTINCT ON (device_id, book_id) book_id AS id, version,
              upload_id AS "uploadId", chunk_count AS "chunkCount",
              manifest_algorithm AS "manifestAlgorithm", manifest_nonce AS "manifestNonce",
              manifest_ciphertext AS "manifestCiphertext"
       FROM sync_wordbook_snapshots WHERE device_id = $1
       ORDER BY device_id, book_id, version DESC`, [deviceId]
    );
    return result.rows.map((row) => ({ id: row.id, version: Number(row.version), uploadId: row.uploadId,
      chunkCount: row.chunkCount, manifest: { algorithm: row.manifestAlgorithm, nonce: row.manifestNonce, ciphertext: row.manifestCiphertext } }));
  }

  async putWordbookChunk(deviceId, bookId, uploadId, index, envelope) {
    return this.wordbookTransaction(deviceId, async (client) => {
      const existing = await client.query(
        `SELECT algorithm, nonce, ciphertext FROM sync_wordbook_uploads WHERE device_id=$1 AND book_id=$2 AND upload_id=$3 AND chunk_index=$4`,
        [deviceId, bookId, uploadId, index]
      );
      if (existing.rows[0]) {
        const row = existing.rows[0];
        if (row.algorithm !== envelope.algorithm || row.nonce !== envelope.nonce || row.ciphertext !== envelope.ciphertext) {
          throw Object.assign(new Error("此上传分片已经写入，不能替换"), { status: 409 });
        }
        return;
      }
      const published = await client.query("SELECT 1 FROM sync_wordbook_snapshots WHERE device_id=$1 AND book_id=$2 AND upload_id=$3", [deviceId, bookId, uploadId]);
      if (published.rowCount) throw Object.assign(new Error("此上传分片已经固定，请创建新的上传编号"), { status: 409 });
      await this.checkWordbookQuota(client, deviceId, bookId);
      await client.query(
        `INSERT INTO sync_wordbook_uploads (device_id, book_id, upload_id, chunk_index, algorithm, nonce, ciphertext)
         VALUES ($1,$2,$3,$4,$5,$6,$7)`,
        [deviceId, bookId, uploadId, index, envelope.algorithm, envelope.nonce, envelope.ciphertext]
      );
    });
  }

  async commitWordbook(deviceId, bookId, uploadId, data) {
    return this.wordbookTransaction(deviceId, async (client) => {
      const duplicate = await client.query(`SELECT version,chunk_count,manifest_algorithm,manifest_nonce,manifest_ciphertext FROM sync_wordbook_snapshots WHERE device_id=$1 AND book_id=$2 AND upload_id=$3`, [deviceId, bookId, uploadId]);
      if (duplicate.rows[0]) {
        const row = duplicate.rows[0];
        if (Number(row.chunk_count) !== data.chunkCount || row.manifest_algorithm !== data.manifest.algorithm || row.manifest_nonce !== data.manifest.nonce || row.manifest_ciphertext !== data.manifest.ciphertext) {
          throw Object.assign(new Error("此上传编号已用于其他提交"), { status: 409 });
        }
        return { id: bookId, version: Number(row.version), uploadId, chunkCount: Number(row.chunk_count), manifest: data.manifest };
      }
      const current = await client.query(`SELECT version FROM sync_wordbook_snapshots WHERE device_id=$1 AND book_id=$2 ORDER BY version DESC LIMIT 1 FOR UPDATE`, [deviceId, bookId]);
      const currentVersion = Number(current.rows[0]?.version || 0);
      if (currentVersion !== data.baseVersion) throw Object.assign(new Error("词库已由其他设备更新，请重新下载后同步"), { status: 409 });
      await this.checkWordbookQuota(client, deviceId, bookId);
      const chunks = await client.query(`SELECT COUNT(*)::INTEGER AS count, COUNT(*) FILTER (WHERE chunk_index >= 0 AND chunk_index < $4)::INTEGER AS valid FROM sync_wordbook_uploads WHERE device_id=$1 AND book_id=$2 AND upload_id=$3`, [deviceId, bookId, uploadId, data.chunkCount]);
      if (chunks.rows[0].count !== data.chunkCount || chunks.rows[0].valid !== data.chunkCount) throw Object.assign(new Error("词库分片不完整，请完成上传后重试"), { status: 409 });
      const version = data.baseVersion + 1;
      await client.query("UPDATE sync_wordbook_snapshots SET retired_at=NOW() WHERE device_id=$1 AND book_id=$2 AND retired_at IS NULL", [deviceId, bookId]);
      await client.query(`INSERT INTO sync_wordbook_snapshots (device_id,book_id,upload_id,version,chunk_count,manifest_algorithm,manifest_nonce,manifest_ciphertext,created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,NOW())`, [deviceId,bookId,uploadId,version,data.chunkCount,data.manifest.algorithm,data.manifest.nonce,data.manifest.ciphertext]);
      return { id: bookId, version, uploadId, chunkCount: data.chunkCount, manifest: data.manifest };
    });
  }

  async getWordbookChunk(deviceId, bookId, uploadId, index) {
    const result = await this.pool.query(`SELECT u.algorithm,u.nonce,u.ciphertext FROM sync_wordbook_uploads u JOIN sync_wordbook_snapshots s ON s.device_id=u.device_id AND s.book_id=u.book_id AND s.upload_id=u.upload_id WHERE u.device_id=$1 AND u.book_id=$2 AND u.upload_id=$3 AND u.chunk_index=$4`, [deviceId,bookId,uploadId,index]);
    const row = result.rows[0];
    return row ? { algorithm: row.algorithm, nonce: row.nonce, ciphertext: row.ciphertext } : null;
  }

  async close() {
    await this.pool.end();
  }
}
