const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const {
  canonicalUpdatePayload,
  currentAppBundle,
  isNewerVersion,
  verifyUpdateManifest
} = require("../src/lib/secure-mac-updater.cjs");

function signedManifest(overrides = {}) {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ed25519");
  const signed = {
    version: "0.7.1",
    url: "https://github.com/example/app/releases/download/v0.7.1/app.zip",
    sha512: crypto.createHash("sha512").update("archive").digest("base64"),
    size: 7,
    bundleId: "com.linguabridge.desktop",
    releaseDate: "2026-09-05T10:00:00.000Z",
    ...overrides
  };
  const signature = crypto.sign(
    null,
    Buffer.from(canonicalUpdatePayload(signed), "utf8"),
    privateKey
  ).toString("base64url");
  return {
    publicKey,
    manifest: { schemaVersion: 1, signed, signature }
  };
}

test("compares stable update versions without allowing a downgrade", () => {
  assert.equal(isNewerVersion("0.7.1", "0.7.0"), true);
  assert.equal(isNewerVersion("0.7.0", "0.7.0"), false);
  assert.equal(isNewerVersion("0.6.9", "0.7.0"), false);
  assert.equal(isNewerVersion("0.8.0-beta.2", "0.8.0-beta.1"), true);
  assert.equal(isNewerVersion("0.8.0-beta.2", "0.8.0"), false);
});

test("accepts an authentic Ed25519 update manifest", () => {
  const { manifest, publicKey } = signedManifest();
  const payload = verifyUpdateManifest(manifest, publicKey);
  assert.equal(payload.version, "0.7.1");
  assert.equal(payload.bundleId, "com.linguabridge.desktop");
});

test("rejects a tampered update manifest before download", () => {
  const { manifest, publicKey } = signedManifest();
  manifest.signed.url = "https://attacker.invalid/replaced.zip";
  assert.throws(
    () => verifyUpdateManifest(manifest, publicKey),
    /签名验证失败/
  );
});

test("resolves the installed macOS app bundle from its executable", () => {
  assert.equal(
    currentAppBundle("/Applications/翻译.app/Contents/MacOS/翻译"),
    "/Applications/翻译.app"
  );
});
