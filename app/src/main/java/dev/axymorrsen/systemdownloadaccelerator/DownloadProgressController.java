package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns DownloadProvider's indeterminate spinner into real progress whenever
 * current/total bytes are knowable. Accelerated transfers can also expose a
 * low-importance fallback notification when the requesting app hid the native
 * DownloadManager notification.
 */
final class DownloadProgressController {
    private static final String OVERLAY_CHANNEL = "sda_progress";
    private static final String OVERLAY_TAG_PREFIX = "sda-progress:";
    private static final int OVERLAY_ID = 0;
    private static final long OVERLAY_INTERVAL_MS = 500L;
    private static final int STATUS_RUNNING = 192;
    private static final int VISIBILITY_VISIBLE = 0;
    private static final int VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1;
    private static final Uri ALL_DOWNLOADS =
            Uri.parse("content://downloads/all_downloads");

    private static final AtomicBoolean CHANNEL_READY =
            new AtomicBoolean(false);
    private static final ConcurrentHashMap<Long, Long> LAST_OVERLAY_MS =
            new ConcurrentHashMap<>();

    private static final class Aggregate {
        long current;
        long total;
        boolean totalKnown = true;
        int count;
        String title;
    }

    private DownloadProgressController() {}

    static Notification enhanceBeforePost(
            Context context,
            String tag,
            Notification notification) {
        if (context == null || notification == null) {
            return notification;
        }

        String channel = notification.getChannelId();
        if (OVERLAY_CHANNEL.equals(channel)) {
            return notification;
        }

        DownloadControlController.enhanceNativeNotification(
                context,
                tag,
                notification);

        if (!isActive(tag, channel, notification)) {
            return notification;
        }

        Bundle extras = notification.extras;
        if (extras == null) {
            return LiveDownloadNotificationController.enhance(
                    context,
                    notification);
        }

        // If Android already has determinate progress, preserve its own byte
        // accounting and simply make the numeric percent explicit.
        int nativeMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        int nativeProgress = extras.getInt(Notification.EXTRA_PROGRESS, 0);
        boolean nativeIndeterminate =
                extras.getBoolean(
                        Notification.EXTRA_PROGRESS_INDETERMINATE,
                        false);

        if (nativeMax > 0 && !nativeIndeterminate) {
            double ratio = Math.max(
                    0.0,
                    Math.min(
                            1.0,
                            (double) nativeProgress / nativeMax));
            extras.putCharSequence(
                    Notification.EXTRA_INFO_TEXT,
                    ProgressFormat.percent(ratio));
            return LiveDownloadNotificationController.enhance(
                    context,
                    notification);
        }

        String sourcePackage = sourcePackageFromTag(tag);
        Aggregate aggregate =
                aggregateRegistry(sourcePackage);
        if (aggregate.count == 0) {
            aggregate = queryProvider(context, sourcePackage);
        }
        if (aggregate == null || aggregate.count == 0) {
            return LiveDownloadNotificationController.enhance(
                    context,
                    notification);
        }

        if (aggregate.totalKnown && aggregate.total > 0L) {
            applyDeterminate(
                    notification,
                    aggregate.current,
                    aggregate.total,
                    0L);
        } else if (aggregate.current > 0L) {
            extras.putBoolean(
                    Notification.EXTRA_PROGRESS_INDETERMINATE,
                    true);
            extras.putCharSequence(
                    Notification.EXTRA_SUB_TEXT,
                    "已下载 " + ProgressFormat.bytes(aggregate.current));
        }

        cancelOverlayForSource(
                context,
                sourcePackage);

        return LiveDownloadNotificationController.enhance(
                context,
                notification);
    }

    static void onTransferProgress(
            Context context,
            ConnectionRegistry.Metadata metadata,
            long currentBytes,
            long totalBytes,
            boolean force) {
        if (context == null || metadata == null) return;
        if (metadata.downloadId < 0L) return;

        long current = Math.max(0L, currentBytes);
        long total = Math.max(-1L, totalBytes);

        DownloadProgressRegistry.update(
                metadata.downloadId,
                metadata.sourcePackage,
                metadata.title,
                current,
                total);

        long now = SystemClock.elapsedRealtime();
        Long previous = LAST_OVERLAY_MS.get(metadata.downloadId);
        if (!force
                && previous != null
                && now - previous < OVERLAY_INTERVAL_MS) {
            return;
        }
        LAST_OVERLAY_MS.put(metadata.downloadId, now);

        LiveUpdateBridge.publish(
                context,
                metadata,
                current,
                total);

        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        if (hasNativeActiveNotification(
                manager,
                metadata.sourcePackage)) {
            cancelOverlay(manager, metadata.downloadId);
            return;
        }

        ensureChannel(manager);

        Notification.Builder builder =
                new Notification.Builder(context, OVERLAY_CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true)
                        .setCategory(Notification.CATEGORY_PROGRESS)
                        .setContentTitle(progressTitle(metadata));

        DownloadControlController.addOverlayActions(
                context,
                builder,
                metadata.downloadId);
        LiveDownloadNotificationController.configureBuilder(
                builder,
                current,
                total);

        if (total > 0L) {
            int progress = basisPoints(current, total);
            builder.setProgress(10_000, progress, false)
                    .setContentInfo(
                            ProgressFormat.percent(
                                    Math.min(
                                            1.0,
                                            (double) current / total)))
                    .setContentText(
                            ProgressFormat.bytes(current)
                                    + " / "
                                    + ProgressFormat.bytes(total));
        } else {
            builder.setProgress(100, 0, true)
                    .setContentText(
                            "已下载 " + ProgressFormat.bytes(current));
        }

        try {
            manager.notify(
                    OVERLAY_TAG_PREFIX + metadata.downloadId,
                    OVERLAY_ID,
                    builder.build());
        } catch (Throwable ignored) {
        }
    }

    static void endTransfer(
            Context context,
            ConnectionRegistry.Metadata metadata) {
        if (metadata == null) return;

        LiveUpdateBridge.end(
                context,
                metadata);

        DownloadProgressRegistry.remove(metadata.downloadId);
        LAST_OVERLAY_MS.remove(metadata.downloadId);

        if (context == null || metadata.downloadId < 0L) {
            return;
        }

        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager != null) {
            cancelOverlay(manager, metadata.downloadId);
        }
    }

    private static Aggregate aggregateRegistry(String sourcePackage) {
        List<DownloadProgressRegistry.Snapshot> snapshots =
                DownloadProgressRegistry.forSource(sourcePackage);
        Aggregate aggregate = new Aggregate();

        for (DownloadProgressRegistry.Snapshot snapshot : snapshots) {
            aggregate.count++;
            aggregate.current += Math.max(
                    0L,
                    snapshot.currentBytes);
            if (snapshot.totalBytes > 0L) {
                aggregate.total += snapshot.totalBytes;
            } else {
                aggregate.totalKnown = false;
            }
            if (aggregate.title == null
                    && snapshot.title != null) {
                aggregate.title = snapshot.title;
            }
        }
        return aggregate;
    }

    private static Aggregate queryProvider(
            Context context,
            String sourcePackage) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ALL_DOWNLOADS,
                    new String[]{
                            "_id",
                            "current_bytes",
                            "total_bytes",
                            "notificationpackage",
                            "status",
                            "visibility",
                            "deleted",
                            "title"
                    },
                    null,
                    null,
                    null);
            if (cursor == null) return null;

            Aggregate aggregate = new Aggregate();
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                long current = Math.max(0L, cursor.getLong(1));
                long total = cursor.getLong(2);
                String pkg = cursor.getString(3);
                int status = cursor.getInt(4);
                int visibility = cursor.getInt(5);
                boolean deleted = cursor.getInt(6) != 0;
                String title = cursor.getString(7);

                if (deleted
                        || status != STATUS_RUNNING
                        || (visibility != VISIBILITY_VISIBLE
                        && visibility
                        != VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        || !samePackage(sourcePackage, pkg)) {
                    continue;
                }

                DownloadProgressRegistry.Snapshot exact =
                        DownloadProgressRegistry.get(id);
                if (exact != null) {
                    current = Math.max(
                            current,
                            exact.currentBytes);
                    if (total <= 0L
                            && exact.totalBytes > 0L) {
                        total = exact.totalBytes;
                    }
                }

                aggregate.count++;
                aggregate.current += current;
                if (total > 0L) {
                    aggregate.total += total;
                } else {
                    aggregate.totalKnown = false;
                }
                if (aggregate.title == null
                        && title != null
                        && !title.isBlank()) {
                    aggregate.title = title;
                }
            }
            return aggregate;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void applyDeterminate(
            Notification notification,
            long current,
            long total,
            long speedBytesPerSecond) {
        Bundle extras = notification.extras;
        if (extras == null || total <= 0L) return;

        int progress = basisPoints(current, total);
        extras.putInt(Notification.EXTRA_PROGRESS_MAX, 10_000);
        extras.putInt(Notification.EXTRA_PROGRESS, progress);
        extras.putBoolean(
                Notification.EXTRA_PROGRESS_INDETERMINATE,
                false);

        double ratio = Math.max(
                0.0,
                Math.min(1.0, (double) current / total));
        extras.putCharSequence(
                Notification.EXTRA_INFO_TEXT,
                ProgressFormat.percent(ratio));

        String detail =
                ProgressFormat.bytes(current)
                        + " / "
                        + ProgressFormat.bytes(total);
        if (speedBytesPerSecond > 0L) {
            detail += " · "
                    + ProgressFormat.bytes(speedBytesPerSecond)
                    + "/s";
        }
        extras.putCharSequence(
                Notification.EXTRA_SUB_TEXT,
                detail);
    }

    private static int basisPoints(long current, long total) {
        if (total <= 0L) return 0;
        double ratio = Math.max(
                0.0,
                Math.min(
                        1.0,
                        (double) current / total));
        return (int) Math.round(ratio * 10_000.0);
    }

    private static boolean isActive(
            String tag,
            String channel,
            Notification notification) {
        if (tag != null && tag.startsWith("1:")) {
            return true;
        }

        if (channel != null) {
            String value = channel.toLowerCase(Locale.ROOT);
            if ("active".equals(value)
                    || value.contains("download")
                    && value.contains("progress")) {
                return true;
            }
        }

        return (notification.flags
                & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private static String sourcePackageFromTag(String tag) {
        if (tag == null || !tag.startsWith("1:")) {
            return null;
        }
        String source = tag.substring(2).trim();
        if (source.isEmpty() || "null".equals(source)) {
            return null;
        }
        return source.toLowerCase(Locale.ROOT);
    }

    private static boolean samePackage(
            String expected,
            String actual) {
        if (expected == null) {
            return actual == null || actual.isBlank();
        }
        return actual != null
                && expected.equals(
                        actual.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean hasNativeActiveNotification(
            NotificationManager manager,
            String sourcePackage) {
        try {
            StatusBarNotification[] active =
                    manager.getActiveNotifications();
            if (active == null) return false;

            for (StatusBarNotification sbn : active) {
                if (sbn == null
                        || sbn.getNotification() == null) {
                    continue;
                }
                Notification notification =
                        sbn.getNotification();
                if (OVERLAY_CHANNEL.equals(
                        notification.getChannelId())) {
                    continue;
                }

                String tag = sbn.getTag();
                if (sourcePackage != null
                        && ("1:" + sourcePackage).equals(tag)) {
                    return true;
                }

                String channel = notification.getChannelId();
                if (channel != null
                        && "active".equalsIgnoreCase(channel)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void cancelOverlayForSource(
            Context context,
            String sourcePackage) {
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        for (DownloadProgressRegistry.Snapshot snapshot
                : DownloadProgressRegistry.forSource(sourcePackage)) {
            cancelOverlay(manager, snapshot.id);
        }
    }

    private static void cancelOverlay(
            NotificationManager manager,
            long id) {
        if (id < 0L) return;
        try {
            manager.cancel(
                    OVERLAY_TAG_PREFIX + id,
                    OVERLAY_ID);
        } catch (Throwable ignored) {
        }
    }

    private static void ensureChannel(
            NotificationManager manager) {
        if (!CHANNEL_READY.compareAndSet(false, true)) {
            return;
        }
        try {
            NotificationChannel channel =
                    new NotificationChannel(
                            OVERLAY_CHANNEL,
                            "下载进度",
                            NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(
                    "显示系统下载的真实字节进度");
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        } catch (Throwable ignored) {
            CHANNEL_READY.set(false);
        }
    }

    private static String progressTitle(
            ConnectionRegistry.Metadata metadata) {
        if (metadata.title != null
                && !metadata.title.isBlank()) {
            return metadata.title;
        }
        if (metadata.sourcePackage != null
                && !metadata.sourcePackage.isBlank()) {
            return metadata.sourcePackage;
        }
        return "正在下载";
    }

}
