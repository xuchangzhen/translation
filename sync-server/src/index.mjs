import http from "node:http";
import { createHandler } from "./app.mjs";
import { PostgresMemoryRepository } from "./postgres.mjs";

const port = Number(process.env.PORT || 8787);
const databaseUrl = process.env.DATABASE_URL || "";

if (!databaseUrl && !process.env.PGHOST) {
  throw new Error("缺少 DATABASE_URL 或 PGHOST 等 PostgreSQL 连接参数");
}
const repository = new PostgresMemoryRepository(databaseUrl);
await repository.initialize();
let cleaningWordbooks = false;
const cleanupTimer = setInterval(async () => {
  if (cleaningWordbooks) return;
  cleaningWordbooks = true;
  try { await repository.cleanupWordbooks(); }
  catch { console.error("Wordbook retention cleanup failed; will retry next hour"); }
  finally { cleaningWordbooks = false; }
}, 60 * 60 * 1000);
cleanupTimer.unref();

const server = http.createServer(createHandler({ repository }));
server.requestTimeout = 35_000;
server.headersTimeout = 40_000;
server.listen(port, "0.0.0.0", () => {
  console.log(`LinguaBridge memory relay listening on ${port}`);
});

async function shutdown() {
  clearInterval(cleanupTimer);
  server.close(async () => {
    await repository.close();
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
