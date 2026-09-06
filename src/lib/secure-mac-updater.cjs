const crypto = require("node:crypto");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const http = require("node:http");
const https = require("node:https");
const path = require("node:path");
const { execFile, spawn } = require("node:child_process");
const { promisify } = require("node:util");

const execFileAsync = promisify(execFile);
const UPDATE_MANIFEST_URL =
  "https://github.com/xuchangzhen/translation/releases/latest/download/latest-mac-secure.json";
const UPDATE_PUBLIC_KEY = `-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAtbYtpP8Tdl93+J+HyTBxNkn/Lj2b8CZ/y652ZYSUQjM=
-----END PUBLIC KEY-----`;
const MAX_MANIFEST_BYTES = 256 * 1024;
const MAX_UPDATE_BYTES = 512 * 1024 * 1024;

function parseVersion(value) {
  const match = String(value || "").trim().match(
    /^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/
  );
  if (!match) throw new Error("更新清单版本号无效");
  return {
    raw: `${match[1]}.${match[2]}.${match[3]}${match[4] ? `-${match[4]}` : ""}`,
    numbers: match.slice(1, 4).map(Number),
    prerelease: match[4] || ""
  };
}

function isNewerVersion(candidateValue, currentValue) {
  const candidate = parseVersion(candidateValue);
  const current = parseVersion(currentValue);
  for (let index = 0; index < 3; index += 1) {
    if (candidate.numbers[index] !== current.numbers[index]) {
      return candidate.numbers[index] > current.numbers[index];
    }
  }
  if (candidate.prerelease === current.prerelease) return false;
  if (!candidate.prerelease) return true;
  if (!current.prerelease) return false;
  return candidate.prerelease.localeCompare(current.prerelease, "en", {
    numeric: true
  }) > 0;
}

function canonicalUpdatePayload(payload) {
  const version = parseVersion(payload?.version).raw;
  let updateUrl;
  try {
    updateUrl = new URL(String(payload?.url || ""));
  } catch {
    throw new Error("更新清单下载地址无效");
  }
  if (updateUrl.protocol !== "https:") throw new Error("更新包必须通过 HTTPS 下载");
  const sha512 = String(payload?.sha512 || "");
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(sha512) || Buffer.from(sha512, "base64").length !== 64) {
    throw new Error("更新清单校验值无效");
  }
  const size = Number(payload?.size);
  if (!Number.isSafeInteger(size) || size < 1 || size > MAX_UPDATE_BYTES) {
    throw new Error("更新包大小无效");
  }
  if (payload?.bundleId !== "com.linguabridge.desktop") {
    throw new Error("更新包应用标识无效");
  }
  const releaseDate = new Date(String(payload?.releaseDate || ""));
  if (!Number.isFinite(releaseDate.getTime())) throw new Error("更新发布日期无效");
  return JSON.stringify({
    version,
    url: updateUrl.toString(),
    sha512,
    size,
    bundleId: "com.linguabridge.desktop",
    releaseDate: releaseDate.toISOString()
  });
}

function verifyUpdateManifest(manifest, publicKey = UPDATE_PUBLIC_KEY) {
  if (manifest?.schemaVersion !== 1 || typeof manifest?.signed !== "object") {
    throw new Error("更新清单格式无效");
  }
  const signature = Buffer.from(String(manifest.signature || ""), "base64url");
  if (signature.length !== 64) throw new Error("更新清单签名无效");
  const canonical = canonicalUpdatePayload(manifest.signed);
  if (!crypto.verify(null, Buffer.from(canonical, "utf8"), publicKey, signature)) {
    throw new Error("更新清单签名验证失败，已停止下载");
  }
  return JSON.parse(canonical);
}

function request(urlValue, redirectCount = 0) {
  return new Promise((resolve, reject) => {
    if (redirectCount > 5) {
      reject(new Error("更新服务器重定向次数过多"));
      return;
    }
    const url = new URL(urlValue);
    if (url.protocol !== "https:" && !(url.protocol === "http:" && url.hostname === "127.0.0.1")) {
      reject(new Error("更新服务器必须使用 HTTPS"));
      return;
    }
    const transport = url.protocol === "https:" ? https : http;
    const req = transport.get(url, {
      timeout: 15_000,
      headers: {
        Accept: "application/json, application/zip, application/octet-stream",
        "Cache-Control": "no-cache",
        "User-Agent": "LinguaBridge-Secure-Updater/1"
      }
    }, (response) => {
      if ([301, 302, 303, 307, 308].includes(response.statusCode || 0)) {
        const location = response.headers.location;
        response.resume();
        if (!location) {
          reject(new Error("更新服务器返回了空重定向"));
          return;
        }
        resolve(request(new URL(location, url).toString(), redirectCount + 1));
        return;
      }
      if ((response.statusCode || 500) >= 400) {
        response.resume();
        reject(new Error(`更新服务器返回 ${response.statusCode}`));
        return;
      }
      resolve(response);
    });
    req.on("timeout", () => req.destroy(new Error("连接更新服务器超时")));
    req.on("error", reject);
  });
}

async function fetchJson(url) {
  const response = await request(url);
  const chunks = [];
  let size = 0;
  for await (const chunk of response) {
    size += chunk.length;
    if (size > MAX_MANIFEST_BYTES) throw new Error("更新清单过大");
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new Error("更新服务器返回了无法识别的清单");
  }
}

async function downloadVerifiedUpdate(payload, destination, onProgress) {
  const response = await request(payload.url);
  const output = fs.createWriteStream(destination, { mode: 0o600 });
  const hash = crypto.createHash("sha512");
  let received = 0;
  try {
    for await (const chunk of response) {
      received += chunk.length;
      if (received > payload.size || received > MAX_UPDATE_BYTES) {
        throw new Error("更新包大小与签名清单不一致");
      }
      hash.update(chunk);
      if (!output.write(chunk)) {
        await new Promise((resolve) => output.once("drain", resolve));
      }
      onProgress?.(Math.min(100, Math.round((received / payload.size) * 100)));
    }
    await new Promise((resolve, reject) => {
      output.end(resolve);
      output.once("error", reject);
    });
  } catch (error) {
    output.destroy();
    await fsp.rm(destination, { force: true });
    throw error;
  }
  if (received !== payload.size || hash.digest("base64") !== payload.sha512) {
    await fsp.rm(destination, { force: true });
    throw new Error("更新包完整性验证失败，已删除下载文件");
  }
}

function currentAppBundle(executablePath) {
  const bundle = path.dirname(path.dirname(path.dirname(executablePath)));
  if (path.extname(bundle) !== ".app") throw new Error("无法定位当前应用目录");
  return bundle;
}

async function validateExtractedApp(appPath, payload) {
  const plist = path.join(appPath, "Contents", "Info.plist");
  const [{ stdout: bundleId }, { stdout: version }] = await Promise.all([
    execFileAsync("/usr/libexec/PlistBuddy", ["-c", "Print :CFBundleIdentifier", plist]),
    execFileAsync("/usr/libexec/PlistBuddy", ["-c", "Print :CFBundleShortVersionString", plist])
  ]);
  if (bundleId.trim() !== payload.bundleId || version.trim() !== payload.version) {
    throw new Error("更新包身份或版本与签名清单不一致");
  }
  await execFileAsync("/usr/bin/codesign", ["--verify", "--deep", "--strict", appPath]);
}

class SecureMacUpdater {
  constructor({ app, onState, manifestUrl = UPDATE_MANIFEST_URL, publicKey = UPDATE_PUBLIC_KEY }) {
    this.app = app;
    this.onState = onState;
    this.manifestUrl = manifestUrl;
    this.publicKey = publicKey;
    this.payload = null;
    this.preparedApp = "";
    this.cacheRoot = "";
  }

  state(patch) {
    this.onState(patch);
    return patch;
  }

  async check() {
    this.state({ status: "checking", message: "正在验证安全更新清单…", progress: 0 });
    try {
      const manifest = await fetchJson(this.manifestUrl);
      const payload = verifyUpdateManifest(manifest, this.publicKey);
      this.payload = payload;
      if (!isNewerVersion(payload.version, this.app.getVersion())) {
        return this.state({
          status: "current",
          message: "当前已是最新版本",
          progress: 0,
          version: this.app.getVersion()
        });
      }
      return this.state({
        status: "available",
        message: `发现新版本 v${payload.version}，签名验证通过`,
        progress: 0,
        version: payload.version
      });
    } catch (error) {
      throw error;
    }
  }

  async download() {
    if (!this.payload || !isNewerVersion(this.payload.version, this.app.getVersion())) {
      await this.check();
    }
    if (!this.payload || !isNewerVersion(this.payload.version, this.app.getVersion())) {
      return false;
    }
    const payload = this.payload;
    this.state({
      status: "downloading",
      message: "正在下载已签名更新 0%",
      progress: 0,
      version: payload.version
    });
    const cacheRoot = path.join(
      this.app.getPath("userData"),
      "secure-update",
      payload.version
    );
    const archivePath = path.join(cacheRoot, "update.zip");
    const extractRoot = path.join(cacheRoot, "extracted");
    await fsp.mkdir(cacheRoot, { recursive: true, mode: 0o700 });
    await fsp.rm(extractRoot, { recursive: true, force: true });
    await downloadVerifiedUpdate(payload, archivePath, (progress) => {
      this.state({
        status: "downloading",
        message: `正在下载已签名更新 ${progress}%`,
        progress,
        version: payload.version
      });
    });
    await fsp.mkdir(extractRoot, { recursive: true, mode: 0o700 });
    await execFileAsync("/usr/bin/ditto", ["-x", "-k", archivePath, extractRoot]);
    const entries = await fsp.readdir(extractRoot, { withFileTypes: true });
    const apps = entries.filter((entry) => entry.isDirectory() && entry.name.endsWith(".app"));
    if (apps.length !== 1) throw new Error("更新包中未找到唯一应用");
    const preparedApp = path.join(extractRoot, apps[0].name);
    await validateExtractedApp(preparedApp, payload);
    this.preparedApp = preparedApp;
    this.cacheRoot = cacheRoot;
    this.state({
      status: "downloaded",
      message: `v${payload.version} 已通过签名和完整性验证，可立即安装`,
      progress: 100,
      version: payload.version
    });
    return true;
  }

  install() {
    if (!this.preparedApp || !this.cacheRoot) return false;
    const targetApp = currentAppBundle(this.app.getPath("exe"));
    const helper = `
set -eu
pid="$1"
source_app="$2"
target_app="$3"
cache_root="$4"
backup_app="$target_app.previous"
attempt=0
while kill -0 "$pid" 2>/dev/null && [ "$attempt" -lt 60 ]; do
  sleep 1
  attempt=$((attempt + 1))
done
if kill -0 "$pid" 2>/dev/null; then
  exit 1
fi
if [ -e "$backup_app" ]; then
  /bin/rm -rf "$backup_app"
fi
/bin/mv "$target_app" "$backup_app"
if /usr/bin/ditto "$source_app" "$target_app" && /usr/bin/codesign --verify --deep --strict "$target_app"; then
  /usr/bin/open "$target_app"
  exit 0
fi
if [ -e "$target_app" ]; then
  /bin/rm -rf "$target_app"
fi
/bin/mv "$backup_app" "$target_app"
/usr/bin/open "$target_app"
exit 1
`;
    const child = spawn(
      "/bin/sh",
      ["-c", helper, "linguabridge-update-helper", String(process.pid), this.preparedApp, targetApp, this.cacheRoot],
      { detached: true, stdio: "ignore" }
    );
    child.unref();
    this.app.quit();
    return true;
  }
}

module.exports = {
  MAX_UPDATE_BYTES,
  SecureMacUpdater,
  UPDATE_MANIFEST_URL,
  UPDATE_PUBLIC_KEY,
  canonicalUpdatePayload,
  currentAppBundle,
  downloadVerifiedUpdate,
  isNewerVersion,
  parseVersion,
  validateExtractedApp,
  verifyUpdateManifest
};
