package dev.axymorrsen.systemdownloadaccelerator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class LiveUpdatePublisher {
    static final String CHANNEL_ID = "sda_live_download";
    private static final int NOTIFICATION_ID = 8200;
    private static final String TAG_PREFIX = "sda-live:";

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

        try {
            int max = total > 0L ? 10_000 : 100;
            int value = total > 0L
                    ? basisPoints(current, total)
                    : 0;

            Notification.Builder builder =
                    new Notification.Builder(context, CHANNEL_ID);

            Icon providerSmallIcon = null;
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    providerSmallIcon =
                            data.getParcelable(
                                    "smallIcon",
                                    Icon.class);
                } else {
                    //noinspection deprecation
                    providerSmallIcon =
                            data.getParcelable("smallIcon");
                }
            } catch (Throwable ignored) {
            }

            if (providerSmallIcon != null) {
                builder.setSmallIcon(providerSmallIcon);
            } else {
                builder.setSmallIcon(
                        DownloadIconResolver.fallbackDownloadIcon());
            }

            builder.setContentTitle(
                                    title == null ? "正在下载" : title)
                            .setContentText(
                                    total > 0L
                                            ? ProgressFormat.bytes(current)
                                                    + " / "
                                                    + ProgressFormat.bytes(total)
                                            : "已下载 "
                                                    + ProgressFormat.bytes(current))
                            .setSubText(source)
                            .setCategory(Notification.CATEGORY_STATUS)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .setShowWhen(false)
                            .setProgress(max, value, total <= 0L);

            Notification.ProgressStyle style =
                    new Notification.ProgressStyle()
                            .setStyledByProgress(false);
            if (total > 0L) {
                style.setProgress(value);
            } else {
                style.setProgressIndeterminate(true);
            }
            builder.setStyle(style);

            builder.getExtras().putBoolean(
                    "android.requestPromotedOngoing",
                    true);
            invokeBuilderBoolean(
                    builder,
                    "setRequestPromotedOngoing",
                    true);

            if (total > 0L) {
                invokeBuilderString(
                        builder,
                        "setShortCriticalText",
                        compactPercent(
                                Math.max(
                                        0.0,
                                        Math.min(
                                                1.0,
                                                (double) current / total))));
            }

            addAction(
                    builder,
                    data,
                    "copyIntent",
                    DownloadIconResolver.copyIcon(),
                    "复制链接");
            addAction(
                    builder,
                    data,
                    "toggleIntent",
                    data.getBoolean("paused", false)
                            ? DownloadIconResolver.resumeIcon()
                            : DownloadIconResolver.pauseIcon(),
                    data.getBoolean("paused", false)
                            ? "继续"
                            : "暂停");
            addAction(
                    builder,
                    data,
                    "cancelIntent",
                    DownloadIconResolver.cancelIcon(),
                    "取消");

            Notification notification = builder.build();
            boolean promotable =
                    queryBoolean(
                            notification,
                            "hasPromotableCharacteristics",
                            false);

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
            out.putBoolean("promoted", promoted);
            out.putString(
                    "detail",
                    "enabled=" + enabled
                            + " promotable=" + promotable
                            + " allowed=" + allowed
                            + " promoted=" + promoted);
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

    private static void addAction(
            Notification.Builder builder,
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
                    new Notification.Action.Builder(
                            icon,
                            title,
                            intent)
                            .build());
        }
    }

    private static void invokeBuilderBoolean(
            Notification.Builder builder,
            String name,
            boolean value) {
        try {
            Method method =
                    Notification.Builder.class.getMethod(
                            name,
                            boolean.class);
            method.invoke(builder, value);
        } catch (Throwable ignored) {
        }
    }

    private static void invokeBuilderString(
            Notification.Builder builder,
            String name,
            String value) {
        try {
            Method method =
                    Notification.Builder.class.getMethod(
                            name,
                            String.class);
            method.invoke(builder, value);
        } catch (Throwable ignored) {
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
        return (int) Math.round(ratio * 10_000.0);
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
