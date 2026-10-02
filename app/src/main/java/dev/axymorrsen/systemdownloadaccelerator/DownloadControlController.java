package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Notification actions for active/failed DownloadProvider tasks.
 *
 * Pause/resume uses DownloadProvider's own control column. Cancel mirrors
 * DownloadManager.remove() semantics through the provider URI. Copy-link never
 * writes the URL to disk; it only reaches the clipboard after an explicit tap.
 */
final class DownloadControlController {
    private static final String ACTION_COPY =
            "dev.axymorrsen.systemdownloadaccelerator.action.COPY_DOWNLOAD_LINK";
    private static final String ACTION_TOGGLE =
            "dev.axymorrsen.systemdownloadaccelerator.action.TOGGLE_DOWNLOAD_PAUSE";
    private static final String ACTION_CANCEL =
            "dev.axymorrsen.systemdownloadaccelerator.action.CANCEL_DOWNLOAD";

    private static final String EXTRA_IDS = "ids";
    private static final String EXTRA_SOURCE = "source";

    private static final int CONTROL_RUN = 0;
    private static final int CONTROL_PAUSED = 1;
    private static final Uri ALL_DOWNLOADS =
            Uri.parse("content://downloads/all_downloads");

    private static final AtomicBoolean INSTALLED =
            new AtomicBoolean(false);

    private static final class Target {
        final long id;
        final String sourcePackage;
        final String uri;
        final int control;
        final int status;
        final boolean deleted;

        Target(
                long id,
                String sourcePackage,
                String uri,
                int control,
                int status,
                boolean deleted) {
            this.id = id;
            this.sourcePackage = sourcePackage;
            this.uri = uri;
            this.control = control;
            this.status = status;
            this.deleted = deleted;
        }

        boolean unfinished() {
            return !deleted && status < 200;
        }
    }

    private static final BroadcastReceiver RECEIVER =
            new BroadcastReceiver() {
                @Override
                public void onReceive(
                        Context context,
                        Intent intent) {
                    if (context == null || intent == null) return;
                    String action = intent.getAction();
                    long[] ids = intent.getLongArrayExtra(EXTRA_IDS);
                    String source = intent.getStringExtra(EXTRA_SOURCE);

                    if (ACTION_COPY.equals(action)) {
                        copyLinks(context, ids, source);
                    } else if (ACTION_TOGGLE.equals(action)) {
                        togglePause(context, ids, source);
                    } else if (ACTION_CANCEL.equals(action)) {
                        cancel(context, ids, source);
                    }
                }
            };

    private DownloadControlController() {}

    static void install(Context context) {
        if (context == null
                || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_COPY);
            filter.addAction(ACTION_TOGGLE);
            filter.addAction(ACTION_CANCEL);
            context.registerReceiver(
                    RECEIVER,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            INSTALLED.set(false);
            EngineTelemetry.emit(
                    context,
                    "CONTROL_INSTALL_ERROR",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
        }
    }

    static void enhanceNativeNotification(
            Context context,
            String tag,
            Notification notification) {
        if (context == null || notification == null) return;

        List<Target> targets = targetsForTag(context, tag);
        if (targets.isEmpty()) {
            return;
        }

        long[] ids = ids(targets);
        String source = firstSource(targets);
        boolean unfinished = false;
        boolean allPaused = true;

        for (Target target : targets) {
            if (!target.unfinished()) continue;
            unfinished = true;
            if (target.control != CONTROL_PAUSED) {
                allPaused = false;
            }
        }

        addActionIfMissing(
                notification,
                context,
                android.R.drawable.ic_menu_share,
                "复制链接",
                pending(
                        context,
                        ACTION_COPY,
                        ids,
                        source,
                        tag));

        if (unfinished) {
            addActionIfMissing(
                    notification,
                    context,
                    allPaused
                            ? android.R.drawable.ic_media_play
                            : android.R.drawable.ic_media_pause,
                    allPaused ? "继续" : "暂停",
                    pending(
                            context,
                            ACTION_TOGGLE,
                            ids,
                            source,
                            tag));

            if (!hasCancelAction(notification)) {
                addActionIfMissing(
                        notification,
                        context,
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "取消",
                        pending(
                                context,
                                ACTION_CANCEL,
                                ids,
                                source,
                                tag));
            }
        }
    }

    static void addOverlayActions(
            Context context,
            Notification.Builder builder,
            long downloadId) {
        if (context == null
                || builder == null
                || downloadId < 0L) {
            return;
        }

        List<Target> targets =
                queryTargets(context, new long[]{downloadId}, null);
        if (targets.isEmpty()) return;

        Target target = targets.get(0);
        long[] ids = new long[]{downloadId};
        boolean paused =
                target.control == CONTROL_PAUSED;

        builder.addAction(
                android.R.drawable.ic_menu_share,
                "复制链接",
                pending(
                        context,
                        ACTION_COPY,
                        ids,
                        target.sourcePackage,
                        "overlay:" + downloadId));
        builder.addAction(
                paused
                        ? android.R.drawable.ic_media_play
                        : android.R.drawable.ic_media_pause,
                paused ? "继续" : "暂停",
                pending(
                        context,
                        ACTION_TOGGLE,
                        ids,
                        target.sourcePackage,
                        "overlay:" + downloadId));
        builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                pending(
                        context,
                        ACTION_CANCEL,
                        ids,
                        target.sourcePackage,
                        "overlay:" + downloadId));
    }

    private static void copyLinks(
            Context context,
            long[] ids,
            String source) {
        List<Target> targets =
                queryTargets(context, ids, source);
        Set<String> links = new LinkedHashSet<>();

        for (Target target : targets) {
            DownloadProgressRegistry.Snapshot snapshot =
                    DownloadProgressRegistry.get(target.id);
            if (snapshot != null
                    && snapshot.resolvedUrl != null) {
                links.add(snapshot.resolvedUrl);
            } else if (target.uri != null
                    && !target.uri.isBlank()) {
                links.add(target.uri);
            }
        }

        if (links.isEmpty()) {
            toast(context, "没有可复制的下载链接");
            return;
        }

        String text = String.join("\n", links);
        try {
            ClipboardManager clipboard =
                    context.getSystemService(
                            ClipboardManager.class);
            if (clipboard == null) {
                toast(context, "剪贴板不可用");
                return;
            }
            clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                            "下载链接",
                            text));
            toast(
                    context,
                    links.size() == 1
                            ? "下载链接已复制"
                            : "已复制 " + links.size() + " 个下载链接");
            EngineTelemetry.emit(
                    context,
                    "CONTROL_COPY",
                    "count=" + links.size());
        } catch (Throwable t) {
            EngineTelemetry.emit(
                    context,
                    "CONTROL_ERROR",
                    "copy "
                            + t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
        }
    }

    private static void togglePause(
            Context context,
            long[] ids,
            String source) {
        List<Target> targets =
                queryTargets(context, ids, source);
        if (targets.isEmpty()) return;

        boolean allPaused = true;
        for (Target target : targets) {
            if (target.unfinished()
                    && target.control != CONTROL_PAUSED) {
                allPaused = false;
                break;
            }
        }

        int nextControl =
                allPaused ? CONTROL_RUN : CONTROL_PAUSED;
        int changed = 0;
        for (Target target : targets) {
            if (!target.unfinished()) continue;
            ContentValues values = new ContentValues();
            values.put("control", nextControl);
            try {
                changed += context.getContentResolver().update(
                        ContentUris.withAppendedId(
                                ALL_DOWNLOADS,
                                target.id),
                        values,
                        null,
                        null);
            } catch (Throwable t) {
                EngineTelemetry.emit(
                        context,
                        "CONTROL_ERROR",
                        "toggle id=" + target.id
                                + " "
                                + t.getClass().getSimpleName()
                                + ": "
                                + String.valueOf(t.getMessage()));
            }
        }

        toast(
                context,
                nextControl == CONTROL_PAUSED
                        ? "下载已暂停"
                        : "下载已继续");
        EngineTelemetry.emit(
                context,
                "CONTROL_TOGGLE",
                "control=" + nextControl
                        + " changed=" + changed);
    }

    private static void cancel(
            Context context,
            long[] ids,
            String source) {
        List<Target> targets =
                queryTargets(context, ids, source);
        int changed = 0;
        for (Target target : targets) {
            if (!target.unfinished()) continue;
            try {
                changed += context.getContentResolver().delete(
                        ContentUris.withAppendedId(
                                ALL_DOWNLOADS,
                                target.id),
                        null,
                        null);
                DownloadProgressRegistry.remove(target.id);
            } catch (Throwable t) {
                EngineTelemetry.emit(
                        context,
                        "CONTROL_ERROR",
                        "cancel id=" + target.id
                                + " "
                                + t.getClass().getSimpleName()
                                + ": "
                                + String.valueOf(t.getMessage()));
            }
        }

        if (changed > 0) {
            toast(context, "下载已取消");
        }
        EngineTelemetry.emit(
                context,
                "CONTROL_CANCEL",
                "changed=" + changed);
    }

    private static List<Target> targetsForTag(
            Context context,
            String tag) {
        if (tag == null || tag.isBlank()) {
            return new ArrayList<>();
        }

        if (tag.startsWith("1:")
                || tag.startsWith("2:")) {
            String source = tag.substring(2).trim();
            return queryTargets(
                    context,
                    null,
                    source.isEmpty() ? null : source);
        }

        if (tag.startsWith("3:")) {
            try {
                long id =
                        Long.parseLong(
                                tag.substring(2).trim());
                return queryTargets(
                        context,
                        new long[]{id},
                        null);
            } catch (Throwable ignored) {
            }
        }

        return new ArrayList<>();
    }

    private static List<Target> queryTargets(
            Context context,
            long[] ids,
            String sourcePackage) {
        ArrayList<Target> out = new ArrayList<>();
        if (context == null) return out;

        Set<Long> filter = new LinkedHashSet<>();
        if (ids != null) {
            for (long id : ids) {
                if (id >= 0L) filter.add(id);
            }
        }
        String source = normalize(sourcePackage);

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ALL_DOWNLOADS,
                    new String[]{
                            "_id",
                            "notificationpackage",
                            "uri",
                            "control",
                            "status",
                            "deleted"
                    },
                    null,
                    null,
                    null);
            if (cursor == null) return out;

            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String pkg = cursor.getString(1);
                String uri = cursor.getString(2);
                int control = cursor.getInt(3);
                int status = cursor.getInt(4);
                boolean deleted = cursor.getInt(5) != 0;

                if (!filter.isEmpty()
                        && !filter.contains(id)) {
                    continue;
                }
                if (source != null
                        && !source.equals(normalize(pkg))) {
                    continue;
                }
                if (filter.isEmpty()
                        && source == null) {
                    continue;
                }

                out.add(
                        new Target(
                                id,
                                normalize(pkg),
                                uri,
                                control,
                                status,
                                deleted));
            }
        } catch (Throwable t) {
            EngineTelemetry.emit(
                    context,
                    "CONTROL_QUERY_ERROR",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static PendingIntent pending(
            Context context,
            String action,
            long[] ids,
            String source,
            String identity) {
        Intent intent = new Intent(action);
        intent.setPackage(context.getPackageName());
        intent.setData(
                new Uri.Builder()
                        .scheme("sda-control")
                        .authority(
                                Integer.toHexString(
                                        action.hashCode()))
                        .appendPath(
                                identity == null
                                        ? "none"
                                        : Integer.toHexString(
                                                identity.hashCode()))
                        .build());
        intent.putExtra(EXTRA_IDS, ids);
        intent.putExtra(EXTRA_SOURCE, source);

        int requestCode =
                31 * action.hashCode()
                        + java.util.Arrays.hashCode(ids);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void addActionIfMissing(
            Notification notification,
            Context context,
            int icon,
            String title,
            PendingIntent pendingIntent) {
        if (hasActionTitle(notification, title)) {
            return;
        }

        Notification.Action action =
                new Notification.Action.Builder(
                        icon,
                        title,
                        pendingIntent)
                        .build();

        Notification.Action[] old =
                notification.actions;
        int length = old == null ? 0 : old.length;
        Notification.Action[] next =
                new Notification.Action[length + 1];
        if (length > 0) {
            System.arraycopy(
                    old,
                    0,
                    next,
                    0,
                    length);
        }
        next[length] = action;
        notification.actions = next;
    }

    private static boolean hasCancelAction(
            Notification notification) {
        if (notification.actions == null) return false;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.title == null) continue;
            String title =
                    action.title.toString()
                            .trim()
                            .toLowerCase(Locale.ROOT);
            if (title.contains("cancel")
                    || title.contains("取消")
                    || title.contains("停止")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasActionTitle(
            Notification notification,
            String expected) {
        if (notification.actions == null) return false;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.title == null) continue;
            if (expected.equals(
                    action.title.toString().trim())) {
                return true;
            }
        }
        return false;
    }

    private static long[] ids(List<Target> targets) {
        long[] ids = new long[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            ids[i] = targets.get(i).id;
        }
        return ids;
    }

    private static String firstSource(
            List<Target> targets) {
        for (Target target : targets) {
            if (target.sourcePackage != null) {
                return target.sourcePackage;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        if (cleaned.isEmpty()
                || "null".equalsIgnoreCase(cleaned)) {
            return null;
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private static void toast(
            Context context,
            String message) {
        try {
            Toast.makeText(
                    context,
                    message,
                    Toast.LENGTH_SHORT)
                    .show();
        } catch (Throwable ignored) {
        }
    }
}
