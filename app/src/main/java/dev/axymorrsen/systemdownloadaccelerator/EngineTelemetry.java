package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Best-effort diagnostic side channel from the injected DownloadProvider
 * process back to the module UI. It is informational only and never affects
 * download control flow.
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
        try {
            Intent intent = new Intent(ACTION)
                    .setPackage("dev.axymorrsen.systemdownloadaccelerator")
                    .putExtra(EXTRA_PHASE, phase == null ? "" : phase)
                    .putExtra(EXTRA_DETAIL, detail == null ? "" : detail)
                    .putExtra(EXTRA_WHEN, SystemClock.elapsedRealtime());
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
