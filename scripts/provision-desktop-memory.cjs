const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const { execFile } = require("node:child_process");
const { promisify } = require("node:util");
const QRCode = require("qrcode");
const { app } = require("electron");
const { SettingsStore } = require("../src/lib/settings.cjs");
const {
  parsePairing,
  provisionAndroidMemoryConnection,
  testAndroidMemoryConnection
} = require("../src/lib/android-sync.cjs");

const execFileAsync = promisify(execFile);
const projectDir = path.join(__dirname, "..");
const receiptPath = path.join(projectDir, "sync-server", "deployment.receipt.env");
const qrPath = path.join(projectDir, "release", "android-memory-pairing.png");

function readReceipt() {
  const values = {};
  for (const line of fs.readFileSync(receiptPath, "utf8").split(/\r?\n/)) {
    const separator = line.indexOf("=");
    if (separator > 0) values[line.slice(0, separator)] = line.slice(separator + 1);
  }
  for (const key of ["SYNC_SERVER_URL", "KEYCHAIN_SERVICE", "KEYCHAIN_ACCOUNT"]) {
    if (!values[key]) throw new Error(`部署回执缺少 ${key}`);
  }
  return values;
}

async function registrationKey(receipt) {
  const { stdout } = await execFileAsync("/usr/bin/security", [
    "find-generic-password",
    "-s",
    receipt.KEYCHAIN_SERVICE,
    "-a",
    receipt.KEYCHAIN_ACCOUNT,
    "-w"
  ]);
  const value = stdout.trim();
  if (value.length < 16) throw new Error("钥匙串中的服务器注册码无效");
  return value;
}

async function saveQr(pairingUri) {
  await fsp.mkdir(path.dirname(qrPath), { recursive: true });
  await QRCode.toFile(qrPath, pairingUri, {
    errorCorrectionLevel: "M",
    margin: 3,
    width: 900,
    color: { dark: "#111827", light: "#ffffff" }
  });
  await fsp.chmod(qrPath, 0o600);
}

app.whenReady().then(async () => {
  try {
    const receipt = readReceipt();
    const userDataPath = path.join(app.getPath("appData"), "translation");
    const store = new SettingsStore(userDataPath);
    let pairingUri = store.androidMemoryPairing();
    let reused = false;
    if (pairingUri) {
      try {
        const parsed = parsePairing(pairingUri);
        if (parsed.serverUrl === receipt.SYNC_SERVER_URL && parsed.readToken) {
          await testAndroidMemoryConnection(pairingUri);
          reused = true;
        } else {
          pairingUri = "";
        }
      } catch {
        pairingUri = "";
      }
    }
    if (!pairingUri) {
      const provisioned = await provisionAndroidMemoryConnection(
        receipt.SYNC_SERVER_URL,
        await registrationKey(receipt)
      );
      pairingUri = provisioned.pairingUri;
      store.update({
        androidMemoryPairing: pairingUri,
        androidMemorySyncEnabled: true
      });
    }
    const connection = await testAndroidMemoryConnection(pairingUri);
    await saveQr(pairingUri);
    console.log(JSON.stringify({
      configured: true,
      reused,
      serverUrl: connection.serverUrl,
      deviceId: connection.deviceId,
      pendingBatches: connection.pendingBatches,
      latencyMs: connection.latencyMs,
      qrPath
    }, null, 2));
    app.exit(0);
  } catch (error) {
    console.error(error?.message || error);
    app.exit(1);
  }
});
