package io.github.zhanfg.sda.xposed;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Mirrors system rows to history and to the module-owned Android 16 Live Update provider. */
public final class HistoryMirrorModule extends XposedModule {
    private static final String TAG = "SysDownloadHistory";
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final Uri HISTORY_URI =
            Uri.parse("content://io.github.zhanfg.sda.history/records");
    private static final Uri LIVE_UPDATE_URI =
            Uri.parse("content://io.github.zhanfg.sda.liveupdate");
    private static final String ACTION_CONTROL =
            "io.github.zhanfg.sda.action.DOWNLOAD_CONTROL";
    private static final AtomicBoolean CONTROL_RECEIVER_REGISTERED = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        try {
            Class<?> providerClass = Class.forName(
                    "com.android.providers.downloads.DownloadProvider",
                    false,
                    param.getClassLoader()
            );
            Method insert = providerClass.getDeclaredMethod(
                    "insert", Uri.class, ContentValues.class);
            Method update = providerClass.getDeclaredMethod(
                    "update", Uri.class, ContentValues.class, String.class, String[].class);
            Method delete = providerClass.getDeclaredMethod(
                    "delete", Uri.class, String.class, String[].class);
            insert.setAccessible(true);
            update.setAccessible(true);
            delete.setAccessible(true);

            hook(insert)
                    .setId("sda.history.insert.v11")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Uri) {
                            ContentProvider provider = (ContentProvider) chain.getThisObject();
                            ensureControlReceiver(provider, update, delete);
                            mirrorCurrentRow(provider, (Uri) result);
                        }
                        return result;
                    });

            hook(update)
                    .setId("sda.history.update.v11")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Uri uri = (Uri) chain.getArg(0);
                        ContentProvider provider = (ContentProvider) chain.getThisObject();
                        ensureControlReceiver(provider, update, delete);
                        if (isItemUri(uri)) mirrorCurrentRow(provider, uri);
                        return result;
                    });

            hook(delete)
                    .setId("sda.history.delete.v11")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Uri uri = (Uri) chain.getArg(0);
                        long id = parseId(uri);
                        Object result = chain.proceed();
                        if (id >= 0) {
                            ContentProvider provider = (ContentProvider) chain.getThisObject();
                            deleteMirroredRow(provider, id);
                            cancelLiveUpdate(provider, id);
                        }
                        return result;
                    });

            log(Log.INFO, TAG, "V11 history and Live Update mirror installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install V11 history mirror", error);
        }
    }

    private void ensureControlReceiver(ContentProvider provider, Method update, Method delete) {
        Context context = provider.getContext();
        if (context == null || !CONTROL_RECEIVER_REGISTERED.compareAndSet(false, true)) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (!ACTION_CONTROL.equals(intent.getAction())) return;
                long id = intent.getLongExtra("download_id", -1L);
                String command = intent.getStringExtra("command");
                if (id < 0 || command == null) return;
                Uri uri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/all_downloads"), id);
                try {
                    if ("cancel".equals(command)) {
                        delete.invoke(provider, uri, null, null);
                        return;
                    }
                    ContentValues values = new ContentValues();
                    if ("pause".equals(command)) {
                        values.put("control", 1);
                    } else if ("resume".equals(command) || "retry".equals(command)) {
                        values.put("control", 0);
                        values.put("status", 190);
                    } else {
                        return;
                    }
                    update.invoke(provider, uri, values, null, null);
                    kickScheduler(context, uri);
                    if ("resume".equals(command) || "retry".equals(command)) {
                        MAIN.postDelayed(() -> verifyAndRepair(provider, update, uri), 450L);
                    }
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "Download control failed: " + command + " id=" + id, error);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_CONTROL);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private static void verifyAndRepair(ContentProvider provider, Method update, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = provider.query(uri, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = intValue(cursor, "status", 190);
            int control = intValue(cursor, "control", 0);
            Context context = provider.getContext();
            if (status == 193 || control == 1) {
                ContentValues repair = new ContentValues();
                repair.put("control", 0);
                repair.put("status", 190);
                update.invoke(provider, uri, repair, null, null);
                if (context != null) kickScheduler(context, uri);
            } else if (context != null && (status == 190 || status == 194
                    || status == 195 || status == 196)) {
                kickScheduler(context, uri);
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to verify notification resume for " + uri, error);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static void kickScheduler(Context context, Uri uri) {
        context.getContentResolver().notifyChange(uri, null);
        Intent wakeup = new Intent("android.intent.action.DOWNLOAD_WAKEUP")
                .setPackage(TARGET_PACKAGE);
        context.sendBroadcast(wakeup);
    }

    private void mirrorCurrentRow(ContentProvider provider, Uri downloadUri) {
        Context context = provider.getContext();
        if (context == null) return;
        Cursor cursor = null;
        try {
            cursor = provider.query(downloadUri, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return;

            ContentValues values = new ContentValues();
            long id = longValue(cursor, "_id", parseId(downloadUri));
            if (id < 0) return;
            values.put("download_id", id);
            put(values, "title", stringValue(cursor, "title"));
            put(values, "source_url", redactUrl(stringValue(cursor, "uri")));
            put(values, "source_package", stringValue(cursor, "notificationpackage"));
            put(values, "mime_type", firstNonEmpty(
                    stringValue(cursor, "mimetype"), stringValue(cursor, "media_type")));
            values.put("status", longValue(cursor, "status", 190));
            values.put("total_bytes", longValue(cursor, "total_bytes", -1));
            values.put("current_bytes", longValue(cursor, "current_bytes", 0));
            values.put("last_modified", longValue(cursor, "lastmod", System.currentTimeMillis()));
            put(values, "local_uri", firstNonEmpty(
                    stringValue(cursor, "local_uri"),
                    ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), id).toString()));
            put(values, "local_path", firstNonEmpty(
                    stringValue(cursor, "_data"), stringValue(cursor, "data")));
            put(values, "destination_hint", stringValue(cursor, "hint"));
            put(values, "error_text", firstNonEmpty(
                    stringValue(cursor, "errorMsg"), stringValue(cursor, "error_message")));

            context.getContentResolver().insert(HISTORY_URI, values);
            Bundle live = new Bundle();
            live.putLong("download_id", id);
            live.putString("title", stringValue(values, "title", "download.bin"));
            live.putString("mime_type", stringValue(values, "mime_type", "application/octet-stream"));
            live.putInt("status", (int) longValue(values, "status", 190));
            live.putLong("total_bytes", longValue(values, "total_bytes", -1));
            live.putLong("current_bytes", longValue(values, "current_bytes", 0));
            live.putString("local_uri", stringValue(values, "local_uri", null));
            live.putString("error_text", stringValue(values, "error_text", null));
            context.getContentResolver().call(LIVE_UPDATE_URI, "update", null, live);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to mirror " + downloadUri, error);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void deleteMirroredRow(ContentProvider provider, long id) {
        Context context = provider.getContext();
        if (context == null) return;
        try {
            context.getContentResolver().delete(
                    ContentUris.withAppendedId(HISTORY_URI, id), null, null);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to remove mirrored history " + id, error);
        }
    }

    private void cancelLiveUpdate(ContentProvider provider, long id) {
        Context context = provider.getContext();
        if (context == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putLong("download_id", id);
            context.getContentResolver().call(LIVE_UPDATE_URI, "cancel", null, extras);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to cancel Live Update " + id, error);
        }
    }

    private static boolean isItemUri(Uri uri) { return parseId(uri) >= 0; }

    private static long parseId(Uri uri) {
        if (uri == null) return -1;
        try { return ContentUris.parseId(uri); }
        catch (Throwable ignored) { return -1; }
    }

    private static long longValue(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }

    private static int intValue(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
    }

    private static String stringValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static long longValue(ContentValues values, String key, long fallback) {
        Long value = values.getAsLong(key);
        return value == null ? fallback : value;
    }

    private static String stringValue(ContentValues values, String key, String fallback) {
        String value = values.getAsString(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static void put(ContentValues values, String key, String value) {
        if (value != null && !value.isEmpty()) values.put(key, value);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : second;
    }

    private static String redactUrl(String value) {
        if (value == null || value.isEmpty()) return value;
        try {
            Uri uri = Uri.parse(value);
            return uri.buildUpon().clearQuery().fragment(null).build().toString();
        } catch (Throwable ignored) {
            return value;
        }
    }
}
