package io.github.zhanfg.sda.xposed;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Mirrors real system download rows to the module application's private history database. */
public final class HistoryMirrorModule extends XposedModule {
    private static final String TAG = "SysDownloadHistory";
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final Uri HISTORY_URI =
            Uri.parse("content://io.github.zhanfg.sda.history/records");

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
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
                    .setId("sda.history.insert")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Uri) {
                            mirrorCurrentRow((ContentProvider) chain.getThisObject(), (Uri) result);
                        }
                        return result;
                    });

            hook(update)
                    .setId("sda.history.update")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Uri uri = (Uri) chain.getArg(0);
                        if (isItemUri(uri)) {
                            mirrorCurrentRow((ContentProvider) chain.getThisObject(), uri);
                        }
                        return result;
                    });

            hook(delete)
                    .setId("sda.history.delete")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Uri uri = (Uri) chain.getArg(0);
                        long id = parseId(uri);
                        Object result = chain.proceed();
                        if (id >= 0) {
                            deleteMirroredRow((ContentProvider) chain.getThisObject(), id);
                        }
                        return result;
                    });

            log(Log.INFO, TAG, "DownloadProvider history mirror installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install history mirror", error);
        }
    }

    private void mirrorCurrentRow(ContentProvider provider, Uri downloadUri) {
        Context context = provider.getContext();
        if (context == null) {
            return;
        }
        Cursor cursor = null;
        try {
            cursor = provider.query(downloadUri, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }
            ContentValues values = new ContentValues();
            long id = longValue(cursor, "_id", parseId(downloadUri));
            if (id < 0) {
                return;
            }
            values.put("download_id", id);
            put(values, "title", stringValue(cursor, "title"));
            put(values, "source_url", redactUrl(stringValue(cursor, "uri")));
            put(values, "source_package", stringValue(cursor, "notificationpackage"));
            put(values, "mime_type", firstNonEmpty(
                    stringValue(cursor, "mimetype"),
                    stringValue(cursor, "media_type")
            ));
            values.put("status", longValue(cursor, "status", 190));
            values.put("total_bytes", longValue(cursor, "total_bytes", -1));
            values.put("current_bytes", longValue(cursor, "current_bytes", 0));
            values.put("last_modified", longValue(cursor, "lastmod",
                    System.currentTimeMillis()));
            put(values, "local_uri", firstNonEmpty(
                    stringValue(cursor, "local_uri"),
                    ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), id).toString()
            ));
            put(values, "local_path", firstNonEmpty(
                    stringValue(cursor, "_data"),
                    stringValue(cursor, "data")
            ));
            put(values, "destination_hint", stringValue(cursor, "hint"));
            put(values, "error_text", firstNonEmpty(
                    stringValue(cursor, "errorMsg"),
                    stringValue(cursor, "error_message")
            ));
            context.getContentResolver().insert(HISTORY_URI, values);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to mirror " + downloadUri, error);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
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

    private static boolean isItemUri(Uri uri) {
        return parseId(uri) >= 0;
    }

    private static long parseId(Uri uri) {
        if (uri == null) return -1;
        try {
            return ContentUris.parseId(uri);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static long longValue(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }

    private static String stringValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static void put(ContentValues values, String key, String value) {
        if (value != null && !value.isEmpty()) {
            values.put(key, value);
        }
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
