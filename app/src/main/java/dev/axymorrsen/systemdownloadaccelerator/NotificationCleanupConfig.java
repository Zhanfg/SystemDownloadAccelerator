package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.database.Cursor;

/** Cross-process configuration for terminal download notification cleanup. */
final class NotificationCleanupConfig {
    static final String PREFS = "notification_cleanup";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_DELAY_MS = "delay_ms";

    static final boolean DEFAULT_ENABLED = true;
    static final long DEFAULT_DELAY_MS = 60_000L;
    static final long MIN_DELAY_MS = 5_000L;
    static final long MAX_DELAY_MS = 60L * 60L * 1000L;

    final boolean enabled;
    final long delayMs;

    NotificationCleanupConfig(boolean enabled, long delayMs) {
        this.enabled = enabled;
        this.delayMs = clamp(delayMs);
    }

    static NotificationCleanupConfig read(Context context) {
        if (context == null) {
            return defaults();
        }

        try (Cursor cursor = context.getContentResolver().query(
                TelemetryProvider.CONFIG_URI,
                null,
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                boolean enabled = cursor.getInt(
                        cursor.getColumnIndexOrThrow("enabled")) != 0;
                long delayMs = cursor.getLong(
                        cursor.getColumnIndexOrThrow("delay_ms"));
                return new NotificationCleanupConfig(
                        enabled,
                        delayMs);
            }
        } catch (Throwable ignored) {
        }
        return defaults();
    }

    static NotificationCleanupConfig readLocal(Context context) {
        if (context == null) {
            return defaults();
        }
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);
        return new NotificationCleanupConfig(
                prefs.getBoolean(
                        KEY_ENABLED,
                        DEFAULT_ENABLED),
                prefs.getLong(
                        KEY_DELAY_MS,
                        DEFAULT_DELAY_MS));
    }

    static void writeLocal(
            Context context,
            boolean enabled,
            long delayMs) {
        if (context == null) return;
        context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putLong(KEY_DELAY_MS, clamp(delayMs))
                .apply();
        context.getContentResolver().notifyChange(
                TelemetryProvider.CONFIG_URI,
                null);
    }

    static NotificationCleanupConfig defaults() {
        return new NotificationCleanupConfig(
                DEFAULT_ENABLED,
                DEFAULT_DELAY_MS);
    }

    static long clamp(long delayMs) {
        return Math.max(
                MIN_DELAY_MS,
                Math.min(MAX_DELAY_MS, delayMs));
    }
}
