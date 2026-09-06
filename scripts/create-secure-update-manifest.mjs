import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { canonicalUpdatePayload } = require("../src/lib/secure-mac-updater.cjs");

const archivePath = process.argv[2];
const outputPath = process.argv[3] || "release/latest-mac-secure.json";
const version = process.env.npm_package_version || JSON.parse(
  fs.readFileSync(new URL("../package.json", import.meta.url), "utf8")
).version;
const repository = process.env.GITHUB_REPOSITORY || "xuchangzhen/translation";
const tag = process.env.GITHUB_REF_NAME || `v${version}`;
const privateKeyBase64 = process.env.MAC_UPDATE_PRIVATE_KEY_BASE64 || "";

if (!archivePath || !fs.existsSync(archivePath)) {
  throw new Error("需要提供已经生成的 macOS ZIP 更新包");
}
if (!privateKeyBase64) throw new Error("缺少 MAC_UPDATE_PRIVATE_KEY_BASE64");

const archive = fs.readFileSync(archivePath);
const privateKey = crypto.createPrivateKey(
  Buffer.from(privateKeyBase64, "base64").toString("utf8")
);
if (privateKey.asymmetricKeyType !== "ed25519") {
  throw new Error("更新签名私钥必须是 Ed25519");
}
const signed = {
  version,
  url: `https://github.com/${repository}/releases/download/${tag}/${path.basename(archivePath)}`,
  sha512: crypto.createHash("sha512").update(archive).digest("base64"),
  size: archive.length,
  bundleId: "com.linguabridge.desktop",
  releaseDate: new Date().toISOString()
};
const signature = crypto.sign(
  null,
  Buffer.from(canonicalUpdatePayload(signed), "utf8"),
  privateKey
).toString("base64url");
fs.writeFileSync(outputPath, `${JSON.stringify({ schemaVersion: 1, signed, signature }, null, 2)}\n`, {
  mode: 0o644
});
console.log(`已生成 Ed25519 安全更新清单：${outputPath}`);
