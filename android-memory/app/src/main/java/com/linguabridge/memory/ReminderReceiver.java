package com.linguabridge.memory;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ReviewNotifications.createChannels(context);
        ReviewNotifications.notifyDue(context);
    }
}
