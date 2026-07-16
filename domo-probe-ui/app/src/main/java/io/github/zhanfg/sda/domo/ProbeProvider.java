package io.github.zhanfg.sda.domo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class ProbeProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.zhanfg.sda.domo.probe";
    public static final String PREFS = "domo_probe_results";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return Bundle.EMPTY;
        }

        SharedPreferences.Editor editor = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit();
        long now = System.currentTimeMillis();

        if ("ready".equals(method)) {
            editor.putLong("hook_ready_at", now)
                    .putInt("hook_count", extras == null ? 0 : extras.getInt("hook_count", 0))
                    .putString("hook_process", extras == null ? "<unknown>" : extras.getString("process", "<unknown>"))
                    .putString("hook_error", extras == null ? null : extras.getString("error"));
        } else if ("notification".equals(method)) {
            String tag = extras == null ? "<null>" : extras.getString("tag", "<null>");
            int id = extras == null ? 0 : extras.getInt("id", 0);
            String channel = extras == null ? "<null>" : extras.getString("channel", "<null>");
            boolean active = extras != null && extras.getBoolean("active", false);
            String source = extras == null ? "<unknown>" : extras.getString("source", "<unknown>");

            editor.putLong("last_received_at", now)
                    .putString("last_tag", tag)
                    .putInt("last_id", id)
                    .putString("last_channel", channel)
                    .putBoolean("last_active", active)
                    .putString("last_source", source);
            if (active) {
                editor.putLong("last_active_at", now)
                        .putString("last_active_tag", tag)
                        .putInt("last_active_id", id)
                        .putString("last_active_channel", channel)
                        .putString("last_active_source", source);
            }
        } else if ("error".equals(method)) {
            editor.putLong("hook_error_at", now)
                    .putString("hook_error", extras == null ? "unknown" : extras.getString("error", "unknown"));
        }

        editor.apply();
        Bundle result = new Bundle();
        result.putBoolean("ok", true);
        return result;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
