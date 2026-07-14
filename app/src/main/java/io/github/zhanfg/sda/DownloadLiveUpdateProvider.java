package io.github.zhanfg.sda;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posts one stable Android 16 Live Update / promoted ongoing notification per real system download.
 * The DownloadProvider process only sends state snapshots; all notifications belong to this app.
 */
public final class DownloadLiveUpdateProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.zhanfg.sda.liveupdate";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    private static final String DOWNLOADS_PACKAGE = "com.android.providers.downloads";
    private static final String CHANNEL_ID = "sda_live_downloads";
    private static final String ACTION_CONTROL =
            "io.github.zhanfg.sda.action.DOWNLOAD_CONTROL";
    private static final long MIN_NOTIFY_INTERVAL_MS = 500L;
    private static final long SPEED_TEXT_INTERVAL_MS = 1_000L;

    private static final Map<Long, Sample> SAMPLES = new ConcurrentHashMap<>();

    @Override
    public boolean onCreate() {
        createChannel(providerContext());
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceDownloadsCaller();
        if ("update".equals(method)) {
            if (extras != null) {
                updateNotification(extras, false);
            }
            return Bundle.EMPTY;
        }
        if ("prime".equals(method)) {
            if (extras != null) {
                updateNotification(extras, true);
            }
            return Bundle.EMPTY;
        }
        if ("cancel".equals(method)) {
            long id = extras == null ? -1L : extras.getLong("download_id", -1L);
            if (id >= 0) {
                NotificationManagerCompat.from(providerContext()).cancel(notificationId(id));
                SAMPLES.remove(id);
            }
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }

    private void updateNotification(Bundle state, boolean primedFromDialog) {
        Context context = providerContext();
        long id = state.getLong("download_id", -1L);
        if (id < 0 || !notificationsAllowed(context)) {
            return;
        }

        String title = value(state.getString("title"), "download.bin");
        String mime = value(state.getString("mime_type"), "application/octet-stream");
        String localUri = state.getString("local_uri");
        String error = state.getString("error_text");
        long total = state.getLong("total_bytes", -1L);
        long current = Math.max(0L, state.getLong("current_bytes", 0L));
        int status = state.getInt("status", 190);
        if (primedFromDialog) {
            status = 192;
        }

        long now = SystemClock.elapsedRealtime();
        Sample sample = SAMPLES.computeIfAbsent(id, ignored -> new Sample());
        int percent = total > 0 ? clampPercent(current, total) : -1;
        boolean critical = sample.status != status || isTerminal(status) || primedFromDialog;
        if (!critical && now - sample.lastNotifyAt < MIN_NOTIFY_INTERVAL_MS
                && percent == sample.lastPercent) {
            updateSpeedSample(sample, current, now);
            return;
        }

        double speed = updateSpeedSample(sample, current, now);
        long remainingSeconds = speed > 1024d && total > current
                ? Math.max(1L, Math.round((total - current) / speed))
                : -1L;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setColor(resolveAccent(context))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(!isTerminal(status))
                .setSilent(!isTerminal(status))
                .setAutoCancel(isTerminal(status))
                .setOngoing(isActive(status))
                .setContentIntent(openManagerIntent(context, id));

        if (Build.VERSION.SDK_INT >= 36 && isActive(status)) {
            builder.setRequestPromotedOngoing(true);
        }

        String shortText = shortText(status, percent);
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(shortText);
        }

        if (total > 0) {
            NotificationCompat.ProgressStyle style = new NotificationCompat.ProgressStyle()
                    .setProgress(percent)
                    .setStyledByProgress(true);
            builder.setStyle(style);
        } else if (isActive(status)) {
            builder.setProgress(0, 0, true);
        }

        String content = contentText(status, percent, speed, remainingSeconds, error);
        builder.setContentText(content);

        if (status == 192) {
            builder.addAction(0, "暂停", controlIntent(context, id, "pause"));
            builder.addAction(0, "取消", controlIntent(context, id, "cancel"));
        } else if (status == 193 || status == 194 || status == 195 || status == 196) {
            builder.addAction(0, "继续", controlIntent(context, id, "resume"));
            builder.addAction(0, "取消", controlIntent(context, id, "cancel"));
        } else if (status == 200) {
            PendingIntent open = openFileIntent(context, id, localUri, mime);
            if (open != null) {
                builder.addAction(0, "打开", open);
            }
            builder.addAction(0, "记录", openManagerIntent(context, id));
        } else if (status >= 400) {
            builder.addAction(0, "重试", controlIntent(context, id, "retry"));
            builder.addAction(0, "详情", openManagerIntent(context, id));
        }

        Notification notification = builder.build();
        NotificationManagerCompat.from(context).notify(notificationId(id), notification);

        sample.status = status;
        sample.lastPercent = percent;
        sample.lastNotifyAt = now;
        if (isTerminal(status)) {
            sample.speedBytesPerSecond = 0d;
        }
    }

    private static double updateSpeedSample(Sample sample, long current, long now) {
        if (sample.sampleAt <= 0L) {
            sample.sampleAt = now;
            sample.sampleBytes = current;
            return sample.speedBytesPerSecond;
        }
        long elapsed = now - sample.sampleAt;
        long delta = current - sample.sampleBytes;
        if (elapsed >= 250L && delta >= 0L) {
            double instant = delta * 1000d / elapsed;
            sample.speedBytesPerSecond = sample.speedBytesPerSecond <= 0d
                    ? instant
                    : sample.speedBytesPerSecond * 0.68d + instant * 0.32d;
            sample.sampleAt = now;
            sample.sampleBytes = current;
            if (now - sample.lastSpeedTextAt >= SPEED_TEXT_INTERVAL_MS) {
                sample.lastSpeedTextAt = now;
            }
        }
        return sample.speedBytesPerSecond;
    }

    private static String contentText(int status, int percent, double speed,
                                      long remainingSeconds, String error) {
        if (status == 200) {
            return "下载完成 · 文件已写入";
        }
        if (status >= 400) {
            return value(error, "下载失败");
        }
        if (status == 193) {
            return percent >= 0 ? "已暂停 · " + percent + "%" : "已暂停";
        }
        if (status == 194) {
            return "等待重试";
        }
        if (status == 195) {
            return "等待网络连接";
        }
        if (status == 196) {
            return "等待其他下载完成";
        }
        if (percent < 0) {
            return "正在连接并获取文件信息…";
        }
        StringBuilder text = new StringBuilder();
        text.append(percent).append('%');
        if (speed > 1024d) {
            text.append(" · ").append(formatSpeed(speed));
        }
        if (remainingSeconds > 0L) {
            text.append(" · ").append(formatRemaining(remainingSeconds));
        }
        return text.toString();
    }

    private static String shortText(int status, int percent) {
        if (status == 200) return "已完成";
        if (status >= 400) return "下载失败";
        if (status == 193) return percent >= 0 ? "暂停 " + percent + "%" : "已暂停";
        if (status == 194 || status == 195 || status == 196) return "等待中";
        return percent >= 0 ? "下载 " + percent + "%" : "连接中";
    }

    private static PendingIntent controlIntent(Context context, long id, String command) {
        Intent intent = new Intent(ACTION_CONTROL)
                .setPackage(DOWNLOADS_PACKAGE)
                .putExtra("download_id", id)
                .putExtra("command", command);
        return PendingIntent.getBroadcast(
                context,
                (int) (id ^ command.hashCode()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent openManagerIntent(Context context, long id) {
        Intent intent = new Intent(context, ModernMainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open_download_id", id);
        return PendingIntent.getActivity(
                context,
                (int) (id ^ 0x54A9),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent openFileIntent(Context context, long id,
                                                String localUri, String mime) {
        Uri uri;
        try {
            uri = localUri == null || localUri.isEmpty()
                    ? Uri.parse("content://downloads/public_downloads/" + id)
                    : Uri.parse(localUri);
        } catch (Throwable ignored) {
            return null;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return PendingIntent.getActivity(
                context,
                (int) (id ^ 0x19F3),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "实时下载",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("系统下载进度、速度、剩余时间和快速操作");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private static boolean notificationsAllowed(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void enforceDownloadsCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return;
        String[] packages = providerContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String packageName : packages) {
                if (DOWNLOADS_PACKAGE.equals(packageName)) return;
            }
        }
        throw new SecurityException("Live update bridge denied for uid " + uid);
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context unavailable");
        return context;
    }

    private static int resolveAccent(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return context.getColor(android.R.color.system_accent1_600);
            } catch (Throwable ignored) {
            }
        }
        return Color.rgb(42, 122, 143);
    }

    private static int notificationId(long id) {
        return (int) (id ^ (id >>> 32)) & Integer.MAX_VALUE;
    }

    private static int clampPercent(long current, long total) {
        if (total <= 0L) return -1;
        return (int) Math.max(0L, Math.min(100L, current * 100L / total));
    }

    private static boolean isActive(int status) {
        return status >= 190 && status < 200;
    }

    private static boolean isTerminal(int status) {
        return status == 200 || status >= 400;
    }

    private static String formatSpeed(double bytesPerSecond) {
        String[] units = {"B/s", "KiB/s", "MiB/s", "GiB/s"};
        double value = bytesPerSecond;
        int unit = 0;
        while (value >= 1024d && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100d ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private static String formatRemaining(long seconds) {
        if (seconds < 60L) return "剩余约 " + seconds + " 秒";
        long minutes = seconds / 60L;
        if (minutes < 60L) return "剩余约 " + minutes + " 分钟";
        return "剩余约 " + (minutes / 60L) + " 小时";
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private static final class Sample {
        long sampleAt;
        long sampleBytes;
        long lastNotifyAt;
        long lastSpeedTextAt;
        double speedBytesPerSecond;
        int lastPercent = -2;
        int status = Integer.MIN_VALUE;
    }
}
