package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delayed cleanup for terminal DownloadProvider notifications.
 *
 * Prefer the notification's own deleteIntent so DownloadProvider updates its
 * visibility state exactly as if the user dismissed the notification. A direct
 * NotificationManager.cancel() follows as a final removal fallback.
 */
final class NotificationCleanupController {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> latest =
            new ConcurrentHashMap<>();

    void cancelPendingGenerationTasks() {
        handler.removeCallbacksAndMessages(null);
        latest.clear();
    }

    void observePost(
            Context context,
            NotificationManager manager,
            String tag,
            int id,
            Notification notification) {
        if (context == null
                || manager == null
                || notification == null) {
            return;
        }

        String classifier = terminalClassifier(tag, notification);
        if (classifier == null) {
            return;
        }

        NotificationCleanupConfig config =
                NotificationCleanupConfig.read(context);
        if (!config.enabled) {
            return;
        }

        String key = key(tag, id);
        long token = sequence.incrementAndGet();
        latest.put(key, token);

        PendingIntent deleteIntent = notification.deleteIntent;
        long delayMs = config.delayMs;

        EngineTelemetry.emit(
                context,
                "NOTIF_CLEANUP_SCHEDULED",
                "tag=" + safeTag(tag)
                        + " id=" + id
                        + " delayMs=" + delayMs
                        + " classifier=" + classifier);

        handler.postDelayed(
                () -> {
                    Long current = latest.get(key);
                    if (current == null || current.longValue() != token) {
                        return;
                    }

                    NotificationCleanupConfig latestConfig =
                            NotificationCleanupConfig.read(context);
                    if (!latestConfig.enabled) {
                        latest.remove(key, token);
                        EngineTelemetry.emit(
                                context,
                                "NOTIF_CLEANUP_SKIPPED",
                                "tag=" + safeTag(tag)
                                        + " id=" + id
                                        + " reason=disabled");
                        return;
                    }

                    String mode = "cancel";
                    boolean deleteSent = false;
                    if (deleteIntent != null) {
                        try {
                            deleteIntent.send();
                            deleteSent = true;
                            mode = "deleteIntent+cancel";
                        } catch (PendingIntent.CanceledException ignored) {
                            mode = "cancel(deleteIntent-cancelled)";
                        } catch (Throwable t) {
                            mode = "cancel(deleteIntent-error)";
                        }
                    }

                    try {
                        if (tag == null) {
                            manager.cancel(id);
                        } else {
                            manager.cancel(tag, id);
                        }
                    } catch (Throwable t) {
                        EngineTelemetry.emit(
                                context,
                                "NOTIF_CLEANUP_ERROR",
                                "tag=" + safeTag(tag)
                                        + " id=" + id
                                        + " "
                                        + t.getClass().getSimpleName()
                                        + ": "
                                        + String.valueOf(t.getMessage()));
                        latest.remove(key, token);
                        return;
                    }

                    latest.remove(key, token);
                    EngineTelemetry.emit(
                            context,
                            "NOTIF_CLEARED",
                            "tag=" + safeTag(tag)
                                    + " id=" + id
                                    + " mode=" + mode
                                    + " deleteSent=" + deleteSent);
                },
                delayMs);
    }

    private static String terminalClassifier(
            String tag,
            Notification notification) {
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return null;
        }

        String channel = notification.getChannelId();
        if (channel != null) {
            String normalized =
                    channel.toLowerCase(Locale.ROOT);
            if ("complete".equals(normalized)
                    || normalized.contains("complete")
                    || normalized.contains("finish")
                    || normalized.contains("success")
                    || normalized.contains("fail")
                    || normalized.contains("error")) {
                return "channel:" + channel;
            }
        }

        // AOSP DownloadProvider TYPE_COMPLETE = 3 and tags are "3:<id>".
        if (tag != null && tag.startsWith("3:")) {
            return "aosp-complete-tag";
        }

        // OEM fallback: terminal download notifications are normally
        // auto-cancelable and carry a deleteIntent; active/waiting downloads do
        // not satisfy both conditions.
        boolean autoCancel =
                (notification.flags & Notification.FLAG_AUTO_CANCEL) != 0;
        if (autoCancel && notification.deleteIntent != null) {
            return "terminal-flags";
        }

        return null;
    }

    private static String key(String tag, int id) {
        return (tag == null ? "\u0000" : tag)
                + "#"
                + id;
    }

    private static String safeTag(String tag) {
        if (tag == null) return "(null)";
        return tag.length() <= 96
                ? tag
                : tag.substring(0, 96);
    }
}
