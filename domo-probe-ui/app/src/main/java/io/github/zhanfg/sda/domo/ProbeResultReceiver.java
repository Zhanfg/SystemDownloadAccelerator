package io.github.zhanfg.sda.domo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public final class ProbeResultReceiver extends BroadcastReceiver {
    static final String PREFS = "domo_probe_results";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DomoProbeHook.ACTION_MATCH.equals(intent.getAction())) {
            return;
        }

        long receivedAt = System.currentTimeMillis();
        String tag = intent.getStringExtra("tag");
        String channel = intent.getStringExtra("channel");
        int id = intent.getIntExtra("id", 0);
        boolean active = intent.getBooleanExtra("active", false);

        SharedPreferences.Editor editor = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("last_received_at", receivedAt)
                .putString("last_tag", tag)
                .putInt("last_id", id)
                .putString("last_channel", channel)
                .putBoolean("last_active", active);

        if (active) {
            editor.putLong("last_active_at", receivedAt)
                    .putString("last_active_tag", tag)
                    .putInt("last_active_id", id)
                    .putString("last_active_channel", channel);
        }
        editor.apply();
    }
}
