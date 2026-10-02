package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Android 16+ progress-centric notification bridge.
 *
 * Uses the public ProgressStyle template and requests promoted-ongoing
 * treatment through the documented extras key. Promotion is ultimately a
 * SystemUI/OEM policy decision; failure to promote still leaves a valid
 * progress-centric notification.
 */
final class LiveDownloadNotificationController {
    private static final int STYLE_MAX = 10_000;
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING =
            "android.requestPromotedOngoing";
    private static final String EXTRA_SHORT_CRITICAL_TEXT =
            "android.shortCriticalText";

    private LiveDownloadNotificationController() {}

    static Notification enhance(
            Context context,
            Notification notification) {
        if (context == null
                || notification == null
                || Build.VERSION.SDK_INT < 36) {
            return notification;
        }

        try {
            Bundle extras = notification.extras;
            int max = extras == null
                    ? 0
                    : extras.getInt(
                            Notification.EXTRA_PROGRESS_MAX,
                            0);
            int current = extras == null
                    ? 0
                    : extras.getInt(
                            Notification.EXTRA_PROGRESS,
                            0);
            boolean indeterminate =
                    extras == null
                            || extras.getBoolean(
                                    Notification.EXTRA_PROGRESS_INDETERMINATE,
                                    max <= 0);

            Notification.Builder builder =
                    Notification.Builder.recoverBuilder(
                            context,
                            notification);

            applyStyle(
                    builder,
                    current,
                    max,
                    indeterminate);

            // A download is an ongoing, user-initiated task. Request the
            // public Live Update treatment, but let SystemUI/OEM policy decide
            // whether the card is actually promoted.
            builder.setOngoing(true)
                    .setOnlyAlertOnce(true);
            builder.getExtras().putBoolean(
                    EXTRA_REQUEST_PROMOTED_ONGOING,
                    true);

            if (!indeterminate && max > 0) {
                double ratio = Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                (double) current / max));
                builder.getExtras().putCharSequence(
                        EXTRA_SHORT_CRITICAL_TEXT,
                        compactPercent(ratio));
            }

            Notification rebuilt = builder.build();

            // recoverBuilder normally preserves actions, but keep the
            // deterministic control order explicit across OEM builders.
            if (notification.actions != null) {
                rebuilt.actions = notification.actions;
            }
            return rebuilt;
        } catch (Throwable t) {
            EngineTelemetry.emit(
                    context,
                    "LIVE_UPDATE_ERROR",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
            return notification;
        }
    }

    static void configureBuilder(
            Notification.Builder builder,
            long currentBytes,
            long totalBytes) {
        if (builder == null
                || Build.VERSION.SDK_INT < 36) {
            return;
        }

        try {
            boolean indeterminate = totalBytes <= 0L;
            int progress = 0;
            if (!indeterminate) {
                progress = basisPoints(
                        currentBytes,
                        totalBytes);
            }

            applyStyle(
                    builder,
                    progress,
                    STYLE_MAX,
                    indeterminate);
            builder.setOngoing(true)
                    .setOnlyAlertOnce(true);
            builder.getExtras().putBoolean(
                    EXTRA_REQUEST_PROMOTED_ONGOING,
                    true);

            if (!indeterminate) {
                builder.getExtras().putCharSequence(
                        EXTRA_SHORT_CRITICAL_TEXT,
                        compactPercent(
                                Math.max(
                                        0.0,
                                        Math.min(
                                                1.0,
                                                (double) currentBytes
                                                        / totalBytes))));
            }
        } catch (Throwable ignored) {
            // ProgressStyle is additive. The ordinary notification remains
            // valid if an OEM implementation rejects it.
        }
    }

    private static void applyStyle(
            Notification.Builder builder,
            int current,
            int max,
            boolean indeterminate) {
        Notification.ProgressStyle style =
                new Notification.ProgressStyle();

        if (indeterminate || max <= 0) {
            style.setProgressIndeterminate(true);
        } else {
            int progress = (int) Math.round(
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    (double) current / max))
                            * STYLE_MAX);

            List<Notification.ProgressStyle.Segment> segments =
                    new ArrayList<>(1);
            segments.add(
                    new Notification.ProgressStyle.Segment(
                            STYLE_MAX));

            style.setProgressSegments(segments)
                    .setStyledByProgress(true)
                    .setProgress(progress)
                    .setProgressIndeterminate(false);
        }

        builder.setStyle(style);
    }

    private static int basisPoints(
            long current,
            long total) {
        if (total <= 0L) return 0;
        double ratio = Math.max(
                0.0,
                Math.min(
                        1.0,
                        (double) Math.max(0L, current)
                                / total));
        return (int) Math.round(ratio * STYLE_MAX);
    }

    static String compactPercent(double ratio) {
        double bounded = Math.max(
                0.0,
                Math.min(1.0, ratio));
        int percent = (int) Math.round(bounded * 100.0);
        return percent + "%";
    }
}
