package com.linguabridge.memory;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class ReviewNotifications {
    public static final String REVIEW_CHANNEL = "memory-review";
    public static final String SYNC_CHANNEL = "memory-sync";
    public static final String UPDATE_CHANNEL = "memory-update";

    private ReviewNotifications() {}

    public static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel review = new NotificationChannel(
                REVIEW_CHANNEL,
                "复习提醒",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        review.setDescription("提醒需要复习的单词和句子");
        NotificationChannel sync = new NotificationChannel(
                SYNC_CHANNEL,
                "自动接收",
                NotificationManager.IMPORTANCE_LOW
        );
        sync.setDescription("保持与加密同步服务的连接");
        NotificationChannel update = new NotificationChannel(
                UPDATE_CHANNEL,
                "软件更新",
                NotificationManager.IMPORTANCE_HIGH
        );
        update.setDescription("新版本已安全下载并等待安装");
        manager.createNotificationChannel(review);
        manager.createNotificationChannel(sync);
        manager.createNotificationChannel(update);
    }

    public static void notifyDue(Context context) {
        MemoryDb db = new MemoryDb(context);
        int due = db.stats(System.currentTimeMillis()).due;
        if (due <= 0) {
            scheduleNext(context);
            return;
        }
        Intent open = new Intent(context, MainActivity.class).putExtra("openReview", true);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                20,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        android.app.Notification notification = new android.app.Notification.Builder(context, REVIEW_CHANNEL)
                .setSmallIcon(com.linguabridge.memory.R.drawable.ic_notification_sync)
                .setContentTitle("有 " + due + " 条内容需要复习")
                .setContentText("先回忆，再查看答案")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        context.getSystemService(NotificationManager.class).notify(2002, notification);
        scheduleNext(context);
    }

    public static void scheduleNext(Context context) {
        long now = System.currentTimeMillis();
        MemoryDb db = new MemoryDb(context);
        long next = db.stats(now).due > 0
                ? now + 30L * 60L * 1000L
                : db.nextFutureDue(now);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                21,
                new Intent(context, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        manager.cancel(pendingIntent);
        if (next > 0) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent);
        }
    }
}
