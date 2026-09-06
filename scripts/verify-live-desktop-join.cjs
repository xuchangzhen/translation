const path = require("node:path");
const { app } = require("electron");
const { SettingsStore } = require("../src/lib/settings.cjs");
const {
  claimDesktopJoinLink,
  createDesktopJoinLink,
  listDesktopClients,
  reportDesktopPresence,
  parsePairing
} = require("../src/lib/android-sync.cjs");

app.whenReady().then(async () => {
  try {
    const store = new SettingsStore(path.join(app.getPath("appData"), "translation"));
    const original = parsePairing(store.androidMemoryPairing());
    const transfer = await createDesktopJoinLink(store.androidMemoryPairing(), "release-verifier");
    const claimed = await claimDesktopJoinLink(transfer.joinLink);
    const restored = parsePairing(claimed.pairingUri);
    if (
      original.serverUrl !== restored.serverUrl ||
      original.deviceId !== restored.deviceId ||
      original.uploadToken !== restored.uploadToken ||
      original.readToken !== restored.readToken ||
      original.encryptionKey !== restored.encryptionKey
    ) {
      throw new Error("一次性接力恢复的同步空间不一致");
    }
    await reportDesktopPresence(claimed.pairingUri, {
      clientId: store.data.syncClientId,
      name: store.data.syncClientName,
      platform: process.platform,
      appVersion: app.getVersion()
    });
    const presence = await listDesktopClients(claimed.pairingUri);
    if (!presence.clients.some((client) => client.clientId === store.data.syncClientId)) {
      throw new Error("服务器未返回本机在线状态");
    }
    console.log(JSON.stringify({
      verified: true,
      serverUrl: restored.serverUrl,
      deviceId: restored.deviceId,
      latencyMs: claimed.connection.latencyMs,
      oneTimeTransferConsumed: true,
      desktopPresenceVerified: true,
      connectedDesktopCount: presence.clients.length
    }, null, 2));
    app.exit(0);
  } catch (error) {
    console.error(error?.message || error);
    app.exit(1);
  }
});
