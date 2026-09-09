package com.linguabridge.memory;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CloudSyncService extends Service {
    public static final String ACTION_SYNC_STATE = "com.linguabridge.memory.SYNC_STATE";
    private static final int NOTIFICATION_ID = 2001;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private ExecutorService worker;

    public static void start(Context context) {
        Intent intent = new Intent(context, CloudSyncService.class);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CloudSyncService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ReviewNotifications.createChannels(this);
        startForeground(NOTIFICATION_ID, notification("正在等待桌面端的新翻译"));
        worker = Executors.newSingleThreadExecutor();
        worker.execute(this::receiveLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopping.set(true);
        if (worker != null) worker.shutdownNow();
        broadcast("stopped", "自动接收已停止", 0);
        super.onDestroy();
    }

    private void receiveLoop() {
        MemoryDb db = new MemoryDb(this);
        while (!stopping.get()) {
            SyncConfig config = new SecureStore(this).load();
            if (config == null) {
                stopSelf();
                return;
            }
            try {
                try {
                    String updateVersion = AppUpdateManager.checkAndDownload(this, false);
                    if (updateVersion != null) {
                        broadcast("update", "新版本 " + updateVersion + " 已下载，点击更新通知即可安装", 0);
                    }
                } catch (Exception ignored) {
                    // Updating must never interrupt encrypted memory delivery.
                }
                broadcast("connected", "加密通道已连接", 0);
                JSONObject response = CloudApi.receive(config);
                JSONArray batches = response.optJSONArray("batches");
                int imported = 0;
                if (batches != null) {
                    for (int index = 0; index < batches.length(); index++) {
                        JSONObject batch = batches.getJSONObject(index);
                        JSONObject payload = CryptoBox.decrypt(
                                batch.getJSONObject("envelope"),
                                config.contentKey
                        );
                        MemoryDb.ImportResult result = db.importPayload(payload);
                        CloudApi.acknowledge(config, batch.getString("id"));
                        imported += result.accepted + result.updated;
                    }
                }
                if (imported > 0) {
                    broadcast("received", "已自动接收 " + imported + " 条", imported);
                    ReviewNotifications.notifyDue(this);
                }
                syncCloudWordbooks(config, db);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                broadcast("waiting", "网络不可用，10 秒后自动重连", 0);
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void syncCloudWordbooks(SyncConfig config, MemoryDb db) {
        try {
            org.json.JSONArray summaries = CloudApi.listWordbooks(config);
            for (int i = 0; i < summaries.length() && !stopping.get(); i++) {
                org.json.JSONObject summary = summaries.getJSONObject(i);
                if (db.cloudWordbookVersion(summary.optString("id"), config.deviceId) >= summary.optLong("version", 0)) continue;
                CloudApi.DownloadedWordbook downloaded = CloudApi.downloadWordbook(config, summary);
                String json = downloaded.items.toString();
                ImportPreview preview = WordbookImporter.parse(json, downloaded.name + ".json");
                MemoryDb.WordbookImportResult result = db.applyCloudWordbook(
                        preview, downloaded.id, config.deviceId, downloaded.version
                );
                if (result.added + result.updated > 0) {
                    broadcast("wordbooks", "已同步词库“" + downloaded.name + "”", result.added + result.updated);
                }
            }
        } catch (Exception ignored) {
            // Wordbook sync is independent from desktop translation delivery.
        }
    }

    private Notification notification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                10,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, ReviewNotifications.SYNC_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("单词记忆 · 自动接收已开启")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    private void broadcast(String state, String message, int imported) {
        Intent intent = new Intent(ACTION_SYNC_STATE)
                .setPackage(getPackageName())
                .putExtra("state", state)
                .putExtra("message", message)
                .putExtra("imported", imported);
        sendBroadcast(intent);
    }
}
