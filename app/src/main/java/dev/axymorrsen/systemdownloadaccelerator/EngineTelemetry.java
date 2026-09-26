package dev.axymorrsen.systemdownloadaccelerator;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Diagnostic side channel from injected system processes to the module app.
 *
 * ContentProvider is authoritative/persistent; the explicit broadcast only
 * updates a currently visible UI immediately.
 */
final class EngineTelemetry {
    static final String ACTION =
            "dev.axymorrsen.systemdownloadaccelerator.ENGINE_EVENT";
    static final String EXTRA_PHASE = "phase";
    static final String EXTRA_DETAIL = "detail";
    static final String EXTRA_WHEN = "when";

    private EngineTelemetry() {}

    static void emit(Context context, String phase, String detail) {
        if (context == null) {
            return;
        }

        long when = SystemClock.elapsedRealtime();
        String safePhase = phase == null ? "" : phase;
        String safeDetail = detail == null ? "" : detail;

        try {
            ContentValues values = new ContentValues();
            values.put("phase", safePhase);
            values.put("detail", safeDetail);
            values.put("when", when);
            context.getContentResolver().insert(
                    TelemetryProvider.EVENTS_URI,
                    values);
        } catch (Throwable ignored) {
        }

        try {
            Intent intent = new Intent(ACTION)
                    .setPackage("dev.axymorrsen.systemdownloadaccelerator")
                    .putExtra(EXTRA_PHASE, safePhase)
                    .putExtra(EXTRA_DETAIL, safeDetail)
                    .putExtra(EXTRA_WHEN, when);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
