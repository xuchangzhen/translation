const fs = require("node:fs");
const path = require("node:path");
const { execFile } = require("node:child_process");
const { promisify } = require("node:util");
const { app } = require("electron");
const { SettingsStore } = require("../src/lib/settings.cjs");
const { parsePairing } = require("../src/lib/android-sync.cjs");

const execFileAsync = promisify(execFile);

function adbCandidates() {
  const home = process.env.HOME || "";
  const sdkRoots = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    "/opt/homebrew/share/android-commandlinetools",
    path.join(home, "Library", "Android", "sdk")
  ].filter(Boolean);
  return [
    process.env.LINGUABRIDGE_ADB,
    ...sdkRoots.map((root) => path.join(root, "platform-tools", "adb")),
    "/opt/homebrew/bin/adb",
    "/usr/local/bin/adb"
  ].filter(Boolean);
}

function findAdb() {
  const adb = adbCandidates().find((candidate) => fs.existsSync(candidate));
  if (!adb) throw new Error("没有找到 Android 调试工具 adb");
  return adb;
}

async function connectedDevice(adb) {
  const { stdout } = await execFileAsync(adb, ["devices"], { timeout: 10_000 });
  const devices = stdout
    .split(/\r?\n/)
    .map((line) => line.match(/^([^\s]+)\s+device$/)?.[1])
    .filter(Boolean);
  if (!devices.length) throw new Error("没有发现已连接并授权的 Android 手机");
  if (devices.length > 1) throw new Error("检测到多台手机，请只连接需要配置的一台");
  return devices[0];
}

app.whenReady().then(async () => {
  let stage = "读取桌面配对信息";
  try {
    const store = new SettingsStore(path.join(app.getPath("appData"), "translation"));
    const pairingUri = store.androidMemoryPairing();
    const pairing = parsePairing(pairingUri);
    const shellSafePairingUri = `'${pairingUri.replaceAll("'", "'%27'")}'`;
    stage = "查找 Android 调试工具";
    const adb = findAdb();
    stage = "检查已连接手机";
    const device = await connectedDevice(adb);
    stage = "启动手机配对页面";
    await execFileAsync(
      adb,
      [
        "-s",
        device,
        "shell",
        "am",
        "start",
        "-W",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        shellSafePairingUri,
        "-n",
        "com.linguabridge.memory/.MainActivity"
      ],
      { timeout: 20_000, maxBuffer: 1024 * 1024 }
    );
    console.log(JSON.stringify({
      pairingStarted: true,
      device,
      serverUrl: pairing.serverUrl,
      deviceId: pairing.deviceId
    }, null, 2));
    app.exit(0);
  } catch (error) {
    const allowed = [
      "没有找到 Android 调试工具 adb",
      "没有发现已连接并授权的 Android 手机",
      "检测到多台手机，请只连接需要配置的一台"
    ];
    const safeMessage = allowed.includes(error?.message)
      ? error.message
      : `${stage}失败，请保持手机已解锁后重试`;
    console.error(safeMessage);
    app.exit(1);
  }
});
