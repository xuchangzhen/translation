import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const apkPath = process.argv[2];
const metadataPath = process.argv[3];
const outputPath = process.argv[4] || "release/latest-android.json";
const baseUrl = String(
  process.env.LINGUABRIDGE_ANDROID_BASE_URL || "https://memory.xuchangzhen968.top"
).replace(/\/+$/, "");
const repository = process.env.GITHUB_REPOSITORY || "";
const releaseTag = process.env.GITHUB_REF_NAME || "";

if (!apkPath || !fs.existsSync(apkPath)) throw new Error("缺少 Android APK");
if (!metadataPath || !fs.existsSync(metadataPath)) throw new Error("缺少 Android 构建元数据");
const metadata = JSON.parse(fs.readFileSync(metadataPath, "utf8"));
const element = metadata.elements?.[0];
if (!Number.isSafeInteger(element?.versionCode) || !element?.versionName) {
  throw new Error("Android 构建元数据中缺少版本号");
}
const base = new URL(baseUrl);
if (base.protocol !== "https:" || base.username || base.password || base.search || base.hash) {
  throw new Error("Android 更新地址必须是纯 HTTPS 域名");
}
const apk = fs.readFileSync(apkPath);
const manifest = {
  schemaVersion: 1,
  packageName: "com.linguabridge.memory",
  versionCode: element.versionCode,
  versionName: String(element.versionName),
  minSdk: 26,
  url: `${baseUrl}/android/${encodeURIComponent(path.basename(apkPath))}`,
  sha256: crypto.createHash("sha256").update(apk).digest("hex"),
  size: apk.length,
  releaseDate: new Date().toISOString()
};
if (repository && releaseTag) {
  manifest.sourceUrl = `https://github.com/${repository}/releases/download/${releaseTag}/${encodeURIComponent(path.basename(apkPath))}`;
}
fs.writeFileSync(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o644 });
console.log(`已生成 Android OTA 清单：${outputPath}`);
