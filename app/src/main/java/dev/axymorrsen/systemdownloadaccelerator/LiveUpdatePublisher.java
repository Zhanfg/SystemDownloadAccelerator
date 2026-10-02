package dev.axymorrsen.systemdownloadaccelerator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.NotificationCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Android 16 live-update publisher.
 *
 * Uses the same public AndroidX live-activity primitives proven by
 * InstallerX-Revived: NotificationCompat.ProgressStyle,
 * setRequestPromotedOngoing(true), short critical text, a high-importance
 * dedicated channel, and progress segments. No ColorOS private API is used.
 */
final class LiveUpdatePublisher {
    static final String CHANNEL_ID = "sda_live_download";

    private static final int NOTIFICATION_ID = 8200;
    private static final String TAG_PREFIX = "sda-live:";
    private static final int PROGRESS_MAX = 10_000;

    private LiveUpdatePublisher() {}

    static Bundle publish(Context context, Bundle data) {
        Bundle out = new Bundle();
        if (context == null || data == null) {
            out.putBoolean("posted", false);
            out.putString("error", "missing context/data");
            return out;
        }

        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null) {
            out.putBoolean("posted", false);
            out.putString("error", "NotificationManager unavailable");
            return out;
        }

        ensureChannel(manager);

        boolean runtimeGranted =
                Build.VERSION.SDK_INT < 33
                        || context.checkSelfPermission(
                                Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
        boolean enabled =
                runtimeGranted && manager.areNotificationsEnabled();
        boolean allowed =
                queryBoolean(
                        manager,
                        "canPostPromotedNotifications",
                        false);

        out.putBoolean("notificationsEnabled", enabled);
        out.putBoolean("promotionAllowed", allowed);
        out.putInt("sdk", Build.VERSION.SDK_INT);

        if (!enabled) {
            out.putBoolean("posted", false);
            out.putString("error", "notifications disabled");
            return out;
        }

        long id = data.getLong("id", -1L);
        long current = Math.max(0L, data.getLong("current", 0L));
        long total = data.getLong("total", -1L);
        String title = clean(data.getString("title"));
        String source = clean(data.getString("source"));
        boolean paused = data.getBoolean("paused", false);

        try {
            int progress =
                    total > 0L
                            ? basisPoints(current, total)
                            : 0;

            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle(
                                    title == null ? "正在下载" : title)
                            .setContentText(
                                    total > 0L
                                            ? ProgressFormat.bytes(current)
                                                    + " / "
                                                    + ProgressFormat.bytes(total)
                                            : "已下载 "
                                                    + ProgressFormat.bytes(current))
                            .setSubText(source)
                            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .setSilent(true)
                            .setShowWhen(false)
                            .setColorized(false)
                            .setContentIntent(
                                    liveContentIntent(
                                            context,
                                            id))
                            .setRequestPromotedOngoing(true)
                            .setProgress(
                                    PROGRESS_MAX,
                                    progress,
                                    total <= 0L);

            if (total > 0L) {
                builder.setShortCriticalText(
                        compactPercent(
                                Math.max(
                                        0.0,
                                        Math.min(
                                                1.0,
                                                (double) current / total))));
            } else {
                builder.setShortCriticalText(
                        paused ? "已暂停" : "下载中");
            }

            NotificationCompat.ProgressStyle style =
                    createProgressStyle(
                            progress,
                            total > 0L);
            builder.setStyle(style);

            addAction(
                    builder,
                    data,
                    "copyIntent",
                    android.R.drawable.ic_menu_share,
                    "复制链接");
            addAction(
                    builder,
                    data,
                    "toggleIntent",
                    paused
                            ? android.R.drawable.ic_media_play
                            : android.R.drawable.ic_media_pause,
                    paused ? "继续" : "暂停");
            addAction(
                    builder,
                    data,
                    "cancelIntent",
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "取消");

            Notification notification = builder.build();

            boolean promotable =
                    NotificationCompat.hasPromotableCharacteristics(
                            notification);
            boolean requested =
                    NotificationCompat.isRequestPromotedOngoing(
                            notification);

            manager.notify(
                    TAG_PREFIX + id,
                    NOTIFICATION_ID,
                    notification);

            boolean promoted =
                    readPromotedFlag(
                            manager,
                            TAG_PREFIX + id,
                            NOTIFICATION_ID);

            out.putBoolean("posted", true);
            out.putBoolean("promotable", promotable);
            out.putBoolean("promotionRequested", requested);
            out.putBoolean("promoted", promoted);
            out.putInt(
                    "channelImportance",
                    channelImportance(manager));
            out.putString(
                    "detail",
                    "enabled=" + enabled
                            + " requested=" + requested
                            + " promotable=" + promotable
                            + " allowed=" + allowed
                            + " promoted=" + promoted
                            + " style=compat-segments"
                            + " importance="
                            + channelImportance(manager));
            return out;
        } catch (Throwable t) {
            out.putBoolean("posted", false);
            out.putString(
                    "error",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
            return out;
        }
    }

    static Bundle cancel(Context context, long id) {
        Bundle out = new Bundle();
        NotificationManager manager =
                context == null
                        ? null
                        : context.getSystemService(
                                NotificationManager.class);
        if (manager != null && id >= 0L) {
            try {
                manager.cancel(
                        TAG_PREFIX + id,
                        NOTIFICATION_ID);
                out.putBoolean("cancelled", true);
                return out;
            } catch (Throwable t) {
                out.putString(
                        "error",
                        t.getClass().getSimpleName());
            }
        }
        out.putBoolean("cancelled", false);
        return out;
    }

    private static NotificationCompat.ProgressStyle createProgressStyle(
            int progress,
            boolean determinate) {
        NotificationCompat.ProgressStyle style =
                new NotificationCompat.ProgressStyle()
                        .setStyledByProgress(true);

        if (!determinate) {
            return style.setProgressIndeterminate(true);
        }

        // Four equal milestones give SystemUI/OEM renderers an explicit
        // journey structure instead of a bare linear progress value.
        List<NotificationCompat.ProgressStyle.Segment> segments =
                Arrays.asList(
                        new NotificationCompat.ProgressStyle.Segment(2500),
                        new NotificationCompat.ProgressStyle.Segment(2500),
                        new NotificationCompat.ProgressStyle.Segment(2500),
                        new NotificationCompat.ProgressStyle.Segment(2500));

        return style
                .setProgressSegments(segments)
                .setProgress(progress);
    }

    private static PendingIntent liveContentIntent(
            Context context,
            long id) {
        Intent intent =
                new Intent(context, MainActivity.class)
                        .setAction(
                                "dev.axymorrsen.systemdownloadaccelerator.action.OPEN_LIVE_DOWNLOAD")
                        .putExtra("download_id", id)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int requestCode =
                (int) (id ^ (id >>> 32));
        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureChannel(
            NotificationManager manager) {
        NotificationChannel existing =
                manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) return;

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "实时下载 / 流体云",
                        NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(
                "系统下载实时进度、动态通知与流体云");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static int channelImportance(
            NotificationManager manager) {
        try {
            NotificationChannel channel =
                    manager.getNotificationChannel(CHANNEL_ID);
            return channel == null
                    ? NotificationManager.IMPORTANCE_UNSPECIFIED
                    : channel.getImportance();
        } catch (Throwable ignored) {
            return NotificationManager.IMPORTANCE_UNSPECIFIED;
        }
    }

    private static void addAction(
            NotificationCompat.Builder builder,
            Bundle data,
            String key,
            int icon,
            String title) {
        PendingIntent intent = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                intent = data.getParcelable(
                        key,
                        PendingIntent.class);
            } else {
                //noinspection deprecation
                intent = data.getParcelable(key);
            }
        } catch (Throwable ignored) {
        }

        if (intent != null) {
            builder.addAction(
                    icon,
                    title,
                    intent);
        }
    }

    private static boolean queryBoolean(
            Object target,
            String name,
            boolean fallback) {
        if (target == null) return fallback;
        try {
            Method method =
                    target.getClass().getMethod(name);
            Object value = method.invoke(target);
            return value instanceof Boolean
                    ? (Boolean) value
                    : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean readPromotedFlag(
            NotificationManager manager,
            String tag,
            int id) {
        try {
            Field field =
                    Notification.class.getField(
                            "FLAG_PROMOTED_ONGOING");
            int flag = field.getInt(null);
            for (android.service.notification.StatusBarNotification sbn
                    : manager.getActiveNotifications()) {
                if (sbn == null
                        || sbn.getNotification() == null
                        || sbn.getId() != id) {
                    continue;
                }
                String candidate = sbn.getTag();
                if (tag == null
                        ? candidate != null
                        : !tag.equals(candidate)) {
                    continue;
                }
                return (sbn.getNotification().flags & flag) != 0;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int basisPoints(long current, long total) {
        if (total <= 0L) return 0;
        double ratio = Math.max(
                0.0,
                Math.min(
                        1.0,
                        (double) current / total));
        return (int) Math.round(ratio * PROGRESS_MAX);
    }

    private static String compactPercent(double ratio) {
        return ((int) Math.round(
                Math.max(
                        0.0,
                        Math.min(1.0, ratio))
                        * 100.0))
                + "%";
    }

    private static String clean(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
