package io.github.zhanfg.sda;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Removes a completed promoted ongoing notification after its short success presentation. */
public final class LiveUpdateDismissReceiver extends BroadcastReceiver {
    public static final String ACTION =
            "io.github.zhanfg.sda.action.DISMISS_COMPLETED_LIVE_UPDATE";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        if (notificationId < 0) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(notificationId);
        }
    }
}
