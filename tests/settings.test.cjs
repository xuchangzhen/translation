const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {
  SettingsStore
} = require("../src/lib/settings.cjs");

test("loads the last known-good backup when the current settings are corrupt", () => {
  const userDataPath = fs.mkdtempSync(
    path.join(os.tmpdir(), "translation-settings-")
  );
  try {
    fs.writeFileSync(
      path.join(userDataPath, "settings.json"),
      "{not-valid-json"
    );
    fs.writeFileSync(
      path.join(userDataPath, "settings.backup.json"),
      JSON.stringify({
        provider: "codex",
        targetLanguage: "ja",
        selectionShortcut: "Alt"
      })
    );

    const store = new SettingsStore(userDataPath);
    assert.equal(store.data.provider, "codex");
    assert.equal(store.data.targetLanguage, "ja");
    assert.equal(store.data.selectionShortcut, "Alt");
  } finally {
    fs.rmSync(userDataPath, { recursive: true, force: true });
  }
});

test("saves settings through a temporary file and keeps the previous backup", () => {
  const userDataPath = fs.mkdtempSync(
    path.join(os.tmpdir(), "translation-settings-")
  );
  try {
    fs.writeFileSync(
      path.join(userDataPath, "settings.json"),
      JSON.stringify({
        provider: "ollama",
        targetLanguage: "zh-CN"
      })
    );

    const store = new SettingsStore(userDataPath);
    store.update({ provider: "codex", targetLanguage: "ja" });

    const current = JSON.parse(
      fs.readFileSync(path.join(userDataPath, "settings.json"), "utf8")
    );
    const backup = JSON.parse(
      fs.readFileSync(
        path.join(userDataPath, "settings.backup.json"),
        "utf8"
      )
    );
    assert.equal(current.provider, "codex");
    assert.equal(current.targetLanguage, "ja");
    assert.equal(backup.provider, "ollama");
    assert.equal(backup.targetLanguage, "zh-CN");
    assert.equal(
      fs.readdirSync(userDataPath).some((name) => name.endsWith(".tmp")),
      false
    );
  } finally {
    fs.rmSync(userDataPath, { recursive: true, force: true });
  }
});

test("persists the translation thinking preference", () => {
  const userDataPath = fs.mkdtempSync(
    path.join(os.tmpdir(), "translation-settings-")
  );
  try {
    const store = new SettingsStore(userDataPath);
    assert.equal(store.data.useThinking, false);

    store.update({ useThinking: true });
    const reloaded = new SettingsStore(userDataPath);
    assert.equal(reloaded.data.useThinking, true);
  } finally {
    fs.rmSync(userDataPath, { recursive: true, force: true });
  }
});
