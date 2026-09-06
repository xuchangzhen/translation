package com.linguabridge.memory;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateManager {
    private static final String MANIFEST_URL =
            "https://memory.xuchangzhen968.top/android/latest.json";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 160L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int UPDATE_NOTIFICATION_ID = 2003;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private AppUpdateManager() {}

    public static void checkAsync(Context context, boolean force) {
        Context appContext = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                String version = checkAndDownload(appContext, force);
                if (force) showToast(
                        appContext,
                        version == null ? "当前已是最新版本" : "新版本 " + version + " 已下载"
                );
            } catch (Exception error) {
                if (force) showToast(appContext, "检查更新失败，请稍后重试");
            }
        });
    }

    private static void showToast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        );
    }

    public static String checkAndDownload(Context context, boolean force) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastCheck = preferences.getLong("last-check-at", 0L);
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) return null;
        preferences.edit().putLong("last-check-at", now).apply();

        JSONObject manifest = fetchManifest();
        validateManifestOrigin(manifest);
        if (!context.getPackageName().equals(manifest.optString("packageName"))) {
            throw new IllegalStateException("更新包名不匹配");
        }
        long versionCode = manifest.optLong("versionCode", 0L);
        String versionName = manifest.optString("versionName", "").trim();
        long size = manifest.optLong("size", 0L);
        String expectedSha256 = manifest.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (
                versionCode <= currentVersionCode(context) ||
                versionName.isEmpty() ||
                size < 1L ||
                size > MAX_APK_BYTES ||
                !expectedSha256.matches("^[0-9a-f]{64}$")
        ) {
            return null;
        }

        File updateDirectory = new File(context.getCacheDir(), "updates");
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建更新目录");
        }
        String fileName = "linguabridge-memory-" + versionCode + ".apk";
        File apkFile = new File(updateDirectory, fileName);
        if (!validExistingFile(context, apkFile, versionCode, size, expectedSha256)) {
            File temporary = new File(updateDirectory, fileName + ".part");
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("无法清理旧更新文件");
            }
            download(
                    new URL(manifest.getString("url")),
                    temporary,
                    size,
                    expectedSha256
            );
            if (!verifyApk(context, temporary, versionCode)) {
                temporary.delete();
                throw new SecurityException("APK 包名、版本或签名验证失败");
            }
            if (apkFile.exists() && !apkFile.delete()) {
                temporary.delete();
                throw new IllegalStateException("无法替换旧更新文件");
            }
            if (!temporary.renameTo(apkFile)) {
                temporary.delete();
                throw new IllegalStateException("无法保存更新文件");
            }
        }

        notifyReady(context, versionName, fileName);
        return versionName;
    }

    private static JSONObject fetchManifest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readLimited(stream, MAX_MANIFEST_BYTES);
        connection.disconnect();
        if (status >= 400) throw new IllegalStateException("更新服务器错误 " + status);
        JSONObject manifest = new JSONObject(text);
        if (manifest.optInt("schemaVersion", 0) != 1) {
            throw new IllegalStateException("更新清单版本不兼容");
        }
        return manifest;
    }

    private static void validateManifestOrigin(JSONObject manifest) throws Exception {
        URL manifestUrl = new URL(MANIFEST_URL);
        URL apkUrl = new URL(manifest.optString("url"));
        if (
                !"https".equalsIgnoreCase(apkUrl.getProtocol()) ||
                !manifestUrl.getHost().equalsIgnoreCase(apkUrl.getHost()) ||
                effectivePort(manifestUrl) != effectivePort(apkUrl) ||
                !apkUrl.getPath().startsWith("/android/")
        ) {
            throw new SecurityException("更新下载地址不可信");
        }
    }

    private static int effectivePort(URL url) {
        return url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
    }

    private static void download(URL url, File destination, long expectedSize, String expectedSha256)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
        int status = connection.getResponseCode();
        if (status >= 400) {
            connection.disconnect();
            throw new IllegalStateException("APK 下载失败 " + status);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long received = 0L;
        try (
                InputStream input = connection.getInputStream();
                FileOutputStream output = new FileOutputStream(destination)
        ) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                received += read;
                if (received > expectedSize || received > MAX_APK_BYTES) {
                    throw new SecurityException("APK 大小超过签名清单");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
        String actualSha256 = hex(digest.digest());
        if (received != expectedSize || !actualSha256.equals(expectedSha256)) {
            destination.delete();
            throw new SecurityException("APK 完整性校验失败");
        }
    }

    private static boolean validExistingFile(
            Context context,
            File file,
            long versionCode,
            long size,
            String sha256
    ) throws Exception {
        return file.isFile() && file.length() == size && sha256(file).equals(sha256) &&
                verifyApk(context, file, versionCode);
    }

    private static boolean verifyApk(Context context, File file, long versionCode) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo current = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        return candidate != null &&
                context.getPackageName().equals(candidate.packageName) &&
                packageVersionCode(candidate) == versionCode &&
                signerSet(current).equals(signerSet(candidate));
    }

    private static long currentVersionCode(Context context) throws Exception {
        return packageVersionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    }

    private static long packageVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signerSet(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures != null) {
            Arrays.stream(signatures).forEach(signature -> result.add(hex(signature.toByteArray())));
        }
        return result;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return output.toString();
    }

    private static String readLimited(InputStream input, int maximum) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > maximum) throw new IllegalStateException("更新清单过大");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void notifyReady(Context context, String versionName, String fileName) {
        ReviewNotifications.createChannels(context);
        Intent install = new Intent(context, UpdateInstallActivity.class)
                .putExtra(UpdateInstallActivity.EXTRA_FILE_NAME, fileName);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                30,
                install,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(context, ReviewNotifications.UPDATE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("单词记忆 " + versionName + " 已准备好")
                .setContentText("APK 已通过哈希、包名和签名验证，点击安装")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        context.getSystemService(NotificationManager.class)
                .notify(UPDATE_NOTIFICATION_ID, notification);
    }
}
