const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {
  migrateLegacyUserData
} = require("../src/lib/user-data.cjs");

test("migrates legacy settings and OCR data into the stable directory", () => {
  const appDataPath = fs.mkdtempSync(
    path.join(os.tmpdir(), "translation-user-data-")
  );
  try {
    const legacyPath = path.join(appDataPath, "linguabridge");
    fs.mkdirSync(path.join(legacyPath, "ocr-cache"), { recursive: true });
    fs.writeFileSync(
      path.join(legacyPath, "settings.json"),
      JSON.stringify({ provider: "codex", targetLanguage: "ja" })
    );
    fs.writeFileSync(
      path.join(legacyPath, "ocr-cache", "eng.traineddata"),
      "cached"
    );

    const result = migrateLegacyUserData({
      appDataPath,
      currentUserDataPath: legacyPath,
      platform: "win32"
    });

    assert.equal(result.migrated, true);
    assert.equal(result.sourcePath, legacyPath);
    assert.deepEqual(
      JSON.parse(
        fs.readFileSync(
          path.join(appDataPath, "translation", "settings.json"),
          "utf8"
        )
      ),
      { provider: "codex", targetLanguage: "ja" }
    );
    assert.equal(
      fs.readFileSync(
        path.join(
          appDataPath,
          "translation",
          "ocr-cache",
          "eng.traineddata"
        ),
        "utf8"
      ),
      "cached"
    );
    assert.equal(
      fs.existsSync(path.join(legacyPath, "settings.json")),
      true
    );
  } finally {
    fs.rmSync(appDataPath, { recursive: true, force: true });
  }
});

test("never overwrites settings already stored in the stable directory", () => {
  const appDataPath = fs.mkdtempSync(
    path.join(os.tmpdir(), "translation-user-data-")
  );
  try {
    const stablePath = path.join(appDataPath, "translation");
    const legacyPath = path.join(appDataPath, "linguabridge");
    fs.mkdirSync(stablePath, { recursive: true });
    fs.mkdirSync(legacyPath, { recursive: true });
    fs.writeFileSync(
      path.join(stablePath, "settings.json"),
      JSON.stringify({ provider: "openai" })
    );
    fs.writeFileSync(
      path.join(legacyPath, "settings.json"),
      JSON.stringify({ provider: "ollama" })
    );

    const result = migrateLegacyUserData({
      appDataPath,
      currentUserDataPath: legacyPath,
      platform: "win32"
    });

    assert.equal(result.migrated, false);
    assert.equal(
      JSON.parse(
        fs.readFileSync(path.join(stablePath, "settings.json"), "utf8")
      ).provider,
      "openai"
    );
  } finally {
    fs.rmSync(appDataPath, { recursive: true, force: true });
  }
});
