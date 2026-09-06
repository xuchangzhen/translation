const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

test("creates a versioned Android OTA manifest with an authenticated release source", () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "linguabridge-android-update-"));
  try {
    const apk = path.join(temporary, "linguabridge-memory-0.2.0.apk");
    const metadata = path.join(temporary, "output-metadata.json");
    const output = path.join(temporary, "latest-android.json");
    fs.writeFileSync(apk, "signed apk fixture");
    fs.writeFileSync(metadata, JSON.stringify({
      elements: [{ versionCode: 2, versionName: "0.2.0" }]
    }));
    execFileSync(process.execPath, [
      path.join(__dirname, "../scripts/create-android-update-manifest.mjs"),
      apk,
      metadata,
      output
    ], {
      env: {
        ...process.env,
        LINGUABRIDGE_ANDROID_BASE_URL: "https://memory.example.com",
        GITHUB_REPOSITORY: "example/translation",
        GITHUB_REF_NAME: "v1.2.3"
      }
    });
    const manifest = JSON.parse(fs.readFileSync(output, "utf8"));
    assert.equal(manifest.packageName, "com.linguabridge.memory");
    assert.equal(manifest.versionCode, 2);
    assert.equal(manifest.url, "https://memory.example.com/android/linguabridge-memory-0.2.0.apk");
    assert.equal(
      manifest.sourceUrl,
      "https://github.com/example/translation/releases/download/v1.2.3/linguabridge-memory-0.2.0.apk"
    );
    assert.equal(
      manifest.sha256,
      crypto.createHash("sha256").update("signed apk fixture").digest("hex")
    );
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
