package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delayed cleanup for terminal DownloadProvider notifications.
 *
 * Cleanup survives provider process restarts by reconciling the provider's
 * already-posted notifications whenever a new hook generation is installed.
 * The notification's original "when" timestamp is used as the delay anchor, so
 * reposting a failed/completed notification cannot restart the full timeout.
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

        long ageMs = notificationAgeMs(notification, System.currentTimeMillis());
        long remainingMs = remainingDelayMs(config.delayMs, ageMs);
        scheduleCleanup(
                context,
                manager,
                tag,
                id,
                notification,
                remainingMs,
                classifier,
                ageMs,
                false);
    }

    /**
     * Reconcile terminal notifications that predate this provider process.
     *
     * This is the missing reboot/process-death path: Handler callbacks die with
     * the old process, but NotificationManager keeps/restores the posted
     * notification. A fresh DownloadProvider process therefore adopts those
     * notifications and either clears them immediately or schedules only the
     * remaining delay.
     */
    int reconcileActiveNotifications(
            Context context,
            NotificationManager manager) {
        if (context == null || manager == null) {
            return 0;
        }

        NotificationCleanupConfig config =
                NotificationCleanupConfig.read(context);
        if (!config.enabled) {
            return 0;
        }

        StatusBarNotification[] active;
        try {
            active = manager.getActiveNotifications();
        } catch (Throwable t) {
            EngineTelemetry.emit(
                    context,
                    "NOTIF_RECONCILE_ERROR",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
            return 0;
        }

        if (active == null || active.length == 0) {
            EngineTelemetry.emit(
                    context,
                    "NOTIF_RECONCILE",
                    "active=0 terminal=0");
            return 0;
        }

        int terminal = 0;
        long now = System.currentTimeMillis();
        for (StatusBarNotification sbn : active) {
            if (sbn == null || sbn.getNotification() == null) {
                continue;
            }

            Notification notification = sbn.getNotification();
            String tag = sbn.getTag();
            String classifier = terminalClassifier(tag, notification);
            if (classifier == null) {
                continue;
            }

            terminal++;
            long firstShown = notification.when > 0L
                    ? notification.when
                    : sbn.getPostTime();
            long ageMs = ageFromWallClock(firstShown, now);
            long remainingMs = remainingDelayMs(config.delayMs, ageMs);

            scheduleCleanup(
                    context,
                    manager,
                    tag,
                    sbn.getId(),
                    notification,
                    remainingMs,
                    classifier + "+reconcile",
                    ageMs,
                    true);
        }

        EngineTelemetry.emit(
                context,
                "NOTIF_RECONCILE",
                "active=" + active.length
                        + " terminal=" + terminal);
        return terminal;
    }

    private void scheduleCleanup(
            Context context,
            NotificationManager manager,
            String tag,
            int id,
            Notification notification,
            long delayMs,
            String classifier,
            long ageMs,
            boolean reconciled) {
        String key = key(tag, id);
        long token = sequence.incrementAndGet();
        latest.put(key, token);

        PendingIntent deleteIntent = notification.deleteIntent;

        EngineTelemetry.emit(
                context,
                "NOTIF_CLEANUP_SCHEDULED",
                "tag=" + safeTag(tag)
                        + " id=" + id
                        + " delayMs=" + delayMs
                        + " ageMs=" + ageMs
                        + " reconciled=" + reconciled
                        + " classifier=" + classifier);

        handler.postDelayed(
                () -> clearIfCurrent(
                        context,
                        manager,
                        tag,
                        id,
                        deleteIntent,
                        key,
                        token,
                        classifier),
                Math.max(0L, delayMs));
    }

    private void clearIfCurrent(
            Context context,
            NotificationManager manager,
            String tag,
            int id,
            PendingIntent deleteIntent,
            String key,
            long token,
            String classifier) {
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
                // This is equivalent to the user's swipe-dismiss path. AOSP
                // DownloadProvider handles it with ACTION_HIDE, which changes
                // visibility so a completed/failed item is not reposted later.
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
                        + " deleteSent=" + deleteSent
                        + " classifier=" + classifier);
    }

    private static String terminalClassifier(
            String tag,
            Notification notification) {
        if (notification == null) {
            return null;
        }

        // Never touch AOSP active/waiting clusters even though waiting
        // notifications are not always flagged as ongoing on newer Android.
        if (tag != null
                && (tag.startsWith("1:")
                || tag.startsWith("2:"))) {
            return null;
        }

        String channel = notification.getChannelId();
        if (channel != null) {
            String normalized = channel.toLowerCase(Locale.ROOT);

            if ("active".equals(normalized)
                    || "waiting".equals(normalized)
                    || normalized.contains("running")
                    || normalized.contains("progress")
                    || normalized.contains("queued")
                    || normalized.contains("waiting")) {
                return null;
            }

            if ("complete".equals(normalized)
                    || normalized.contains("complete")
                    || normalized.contains("finish")
                    || normalized.contains("success")
                    || normalized.contains("fail")
                    || normalized.contains("error")) {
                return "channel:" + channel;
            }
        }

        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return null;
        }

        // AOSP DownloadProvider TYPE_COMPLETE = 3 for both success and failure.
        if (tag != null && tag.startsWith("3:")) {
            return "aosp-complete-tag";
        }

        // OEMs sometimes drop AOSP's deleteIntent while retaining AUTO_CANCEL.
        // The old implementation required both and therefore leaked failed
        // notifications on those builds.
        if ((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0) {
            return "terminal-auto-cancel";
        }

        // Last conservative OEM fallback. A deleteIntent on a non-ongoing
        // DownloadProvider notification means the provider supplied an explicit
        // dismissal path even if its channel/tag naming is vendor-specific.
        if (notification.deleteIntent != null) {
            return "terminal-delete-intent";
        }

        return null;
    }

    private static long notificationAgeMs(
            Notification notification,
            long nowWallMs) {
        if (notification == null) {
            return 0L;
        }
        return ageFromWallClock(notification.when, nowWallMs);
    }

    private static long ageFromWallClock(
            long firstShownMs,
            long nowWallMs) {
        if (firstShownMs <= 0L
                || nowWallMs <= 0L
                || firstShownMs > nowWallMs) {
            return 0L;
        }
        return nowWallMs - firstShownMs;
    }

    private static long remainingDelayMs(
            long configuredDelayMs,
            long ageMs) {
        long safeAge = Math.max(0L, ageMs);
        return Math.max(0L, configuredDelayMs - safeAge);
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
