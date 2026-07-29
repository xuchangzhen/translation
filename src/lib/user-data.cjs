const fs = require("node:fs");
const path = require("node:path");

const STABLE_USER_DATA_NAME = "translation";

function comparablePath(filePath, platform = process.platform) {
  const resolved = path.resolve(String(filePath || ""));
  return ["darwin", "win32"].includes(platform)
    ? resolved.toLowerCase()
    : resolved;
}

function legacyUserDataCandidates(
  appDataPath,
  currentUserDataPath,
  platform = process.platform
) {
  const candidates = [
    currentUserDataPath,
    path.join(appDataPath, "linguabridge"),
    path.join(appDataPath, "LinguaBridge"),
    path.join(appDataPath, "翻译"),
    path.join(appDataPath, "Translation"),
    path.join(appDataPath, "com.linguabridge.desktop")
  ];
  const seen = new Set();
  return candidates.filter((candidate) => {
    const comparable = comparablePath(candidate, platform);
    if (seen.has(comparable)) return false;
    seen.add(comparable);
    return true;
  });
}

function settingsModifiedAt(directory) {
  try {
    return fs.statSync(path.join(directory, "settings.json")).mtimeMs;
  } catch {
    return -1;
  }
}

function migrateLegacyUserData({
  appDataPath,
  currentUserDataPath,
  platform = process.platform,
  stableName = STABLE_USER_DATA_NAME
}) {
  const stablePath = path.join(appDataPath, stableName);
  const stableSettingsPath = path.join(stablePath, "settings.json");
  if (fs.existsSync(stableSettingsPath)) {
    return { stablePath, migrated: false, sourcePath: "" };
  }

  const stableComparable = comparablePath(stablePath, platform);
  const sourcePath = legacyUserDataCandidates(
    appDataPath,
    currentUserDataPath,
    platform
  )
    .filter(
      (candidate) =>
        comparablePath(candidate, platform) !== stableComparable &&
        fs.existsSync(path.join(candidate, "settings.json"))
    )
    .sort((left, right) => settingsModifiedAt(right) - settingsModifiedAt(left))[0];

  if (!sourcePath) {
    return { stablePath, migrated: false, sourcePath: "" };
  }

  fs.mkdirSync(stablePath, { recursive: true });
  for (const fileName of ["settings.json", "settings.backup.json"]) {
    const source = path.join(sourcePath, fileName);
    const target = path.join(stablePath, fileName);
    if (fs.existsSync(source) && !fs.existsSync(target)) {
      fs.copyFileSync(source, target);
    }
  }

  const sourceOcrCache = path.join(sourcePath, "ocr-cache");
  const targetOcrCache = path.join(stablePath, "ocr-cache");
  if (fs.existsSync(sourceOcrCache) && !fs.existsSync(targetOcrCache)) {
    fs.cpSync(sourceOcrCache, targetOcrCache, { recursive: true });
  }

  return { stablePath, migrated: true, sourcePath };
}

module.exports = {
  STABLE_USER_DATA_NAME,
  comparablePath,
  legacyUserDataCandidates,
  migrateLegacyUserData
};
