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
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** System DownloadProvider gate with a root-launched transparent confirmation host. */
public final class SystemDownloadConfirmationModule extends XposedModule {
    private static final String TAG = "SysDownloadConfirm";
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final String ACTION_DECISION =
            "io.github.zhanfg.sda.action.SYSTEM_DOWNLOAD_DECISION";
    private static final Uri ROOT_BRIDGE_URI =
            Uri.parse("content://io.github.zhanfg.sda.rootbridge");
    private static final long FAILSAFE_RESUME_MS = 120_000L;
    private static final long RESUME_VERIFY_MS = 450L;

    private static final AtomicBoolean RECEIVER_REGISTERED = new AtomicBoolean(false);
    private static final Map<String, PendingDownload> PENDING = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
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
                    .setId("sda.system-download-confirmation.v11")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ContentValues input = (ContentValues) chain.getArg(1);
                        ContentValues snapshot = input == null
                                ? new ContentValues()
                                : new ContentValues(input);

                        Object result = chain.proceed();
                        if (!(result instanceof Uri)) return result;

                        Uri downloadUri = (Uri) result;
                        ContentProvider provider = (ContentProvider) chain.getThisObject();
                        Context context = provider.getContext();
                        if (context == null || shouldBypass(snapshot)) return result;

                        ensureDecisionReceiver(context);

                        ContentValues pause = new ContentValues();
                        pause.put("control", 1);
                        update.invoke(provider, downloadUri, pause, null, null);

                        String token = createToken(downloadUri);
                        PendingDownload pending = new PendingDownload(
                                token, provider, update, delete, downloadUri);
                        PENDING.put(token, pending);

                        if (!launchThroughRootBridge(context, token, downloadUri, snapshot)) {
                            PENDING.remove(token, pending);
                            pending.resume();
                            log(Log.WARN, TAG,
                                    "Root UI bridge unavailable; download resumed without prompt");
                            return result;
                        }

                        scheduleTimeout(pending);
                        log(Log.INFO, TAG,
                                "Download paused for V11 confirmation: " + downloadUri);
                        return result;
                    });

            log(Log.INFO, TAG,
                    "V11 confirmation gate installed; verified resume path enabled");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install V11 confirmation hook", error);
        }
    }

    private boolean shouldBypass(ContentValues values) {
        String source = values.getAsString("uri");
        return source == null || source.isEmpty();
    }

    private boolean launchThroughRootBridge(
            Context context, String token, Uri downloadUri, ContentValues values) {
        Bundle request = new Bundle();
        request.putLong("download_id", parseId(downloadUri));
        request.putString("file_name", resolveFileName(values));
        request.putString("url", safe(values.getAsString("uri"), ""));
        request.putString("source_package",
                safe(values.getAsString("notificationpackage"), "系统应用"));
        request.putString("mime_type",
                safe(values.getAsString("mimetype"), "application/octet-stream"));
        Long total = values.getAsLong("total_bytes");
        request.putLong("file_size", total == null ? -1L : total);
        request.putString("destination", resolveDestination(values));
        request.putString("thread_mode", "自动 · 多线程可用时启用");

        try {
            Bundle response = context.getContentResolver().call(
                    ROOT_BRIDGE_URI,
                    "show_download_confirmation",
                    token,
                    request
            );
            return response != null && response.getBoolean("scheduled", false);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Root UI bridge call failed", error);
            return false;
        }
    }

    private void ensureDecisionReceiver(Context context) {
        if (!RECEIVER_REGISTERED.compareAndSet(false, true)) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (!ACTION_DECISION.equals(intent.getAction())) return;
                String token = intent.getStringExtra("token");
                PendingDownload pending = token == null ? null : PENDING.remove(token);
                if (pending == null) return;
                applyDecision(pending, intent.getIntExtra("decision", 0));
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_DECISION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private void applyDecision(PendingDownload pending, int decision) {
        try {
            if (decision == 1) {
                pending.resume();
                log(Log.INFO, TAG, "Confirmed and resumed system download: " + pending.uri);
            } else {
                pending.cancel();
                log(Log.INFO, TAG, "Cancelled system download: " + pending.uri);
            }
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to apply confirmation decision", error);
            try { pending.resume(); } catch (Throwable ignored) { }
        }
    }

    private void scheduleTimeout(PendingDownload pending) {
        MAIN.postDelayed(() -> {
            if (!PENDING.remove(pending.token, pending)) return;
            try {
                pending.resume();
                log(Log.WARN, TAG, "Confirmation timed out; download resumed");
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Failed to resume timed-out download", error);
            }
        }, FAILSAFE_RESUME_MS);
    }

    private static long parseId(Uri uri) {
        try { return ContentUris.parseId(uri); }
        catch (Throwable ignored) { return -1L; }
    }

    private static String resolveFileName(ContentValues values) {
        String title = values.getAsString("title");
        if (title != null && !title.trim().isEmpty()) return title;
        String fromHint = lastSegment(values.getAsString("hint"));
        if (fromHint != null) return fromHint;
        String fromUrl = lastSegment(values.getAsString("uri"));
        return fromUrl == null ? "download.bin" : fromUrl;
    }

    private static String resolveDestination(ContentValues values) {
        String hint = values.getAsString("hint");
        if (hint == null || hint.isEmpty()) return "内部存储 / Download";
        try {
            Uri uri = Uri.parse(hint);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return hint;
            int index = path.indexOf("/Download");
            return index >= 0 ? "内部存储" + path.substring(index) : path;
        } catch (Throwable ignored) {
            return hint;
        }
    }

    private static String lastSegment(String source) {
        if (source == null || source.isEmpty()) return null;
        try {
            String segment = Uri.parse(source).getLastPathSegment();
            return segment == null || segment.isEmpty() ? null : segment;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String createToken(Uri uri) {
        return Long.toHexString(System.nanoTime())
                + Long.toHexString(RANDOM.nextLong())
                + Integer.toHexString(uri.hashCode())
                + Integer.toHexString(Process.myPid());
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static final class PendingDownload {
        final String token;
        final Object provider;
        final Method update;
        final Method delete;
        final Uri uri;

        PendingDownload(String token, Object provider, Method update,
                        Method delete, Uri uri) {
            this.token = token;
            this.provider = provider;
            this.update = update;
            this.delete = delete;
            this.uri = uri;
        }

        void resume() throws Exception {
            writeRunnableState();
            kickScheduler();
            MAIN.postDelayed(this::verifyResumeState, RESUME_VERIFY_MS);
        }

        private void writeRunnableState() throws Exception {
            ContentValues values = new ContentValues();
            values.put("control", 0);
            values.put("status", 190);
            try {
                update.invoke(provider, uri, values, null, null);
            } catch (Throwable firstError) {
                ContentValues fallback = new ContentValues();
                fallback.put("control", 0);
                update.invoke(provider, uri, fallback, null, null);
            }
        }

        private void verifyResumeState() {
            ContentProvider contentProvider = (ContentProvider) provider;
            Cursor cursor = null;
            try {
                cursor = contentProvider.query(uri, null, null, null, null);
                if (cursor == null || !cursor.moveToFirst()) return;
                int status = intValue(cursor, "status", 190);
                int control = intValue(cursor, "control", 0);
                if (status == 193 || control == 1) {
                    writeRunnableState();
                    kickScheduler();
                    log(Log.WARN, TAG,
                            "Paused row repaired after confirmation: " + uri);
                } else if (status == 190 || status == 194 || status == 195 || status == 196) {
                    kickScheduler();
                }
            } catch (Throwable error) {
                log(Log.WARN, TAG, "Unable to verify resume state for " + uri, error);
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        private void kickScheduler() {
            ContentProvider contentProvider = (ContentProvider) provider;
            Context context = contentProvider.getContext();
            if (context == null) return;
            context.getContentResolver().notifyChange(uri, null);
            Intent wakeup = new Intent("android.intent.action.DOWNLOAD_WAKEUP")
                    .setPackage(TARGET_PACKAGE);
            context.sendBroadcast(wakeup);
        }

        void cancel() throws Exception {
            delete.invoke(provider, uri, null, null);
        }

        private static int intValue(Cursor cursor, String column, int fallback) {
            int index = cursor.getColumnIndex(column);
            return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
        }
    }
}
