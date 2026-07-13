package io.github.zhanfg.sda.xposed;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

/** Central DownloadProvider gate. Source-process dialogs are preferred; a translucent Activity is fallback. */
public final class SystemDownloadConfirmationModule extends XposedModule {
    private static final String TAG = "SysDownloadConfirm";
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final String MODULE_PACKAGE = "io.github.zhanfg.sda";
    private static final String CONFIRM_ACTIVITY =
            "io.github.zhanfg.sda.SystemDownloadConfirmActivity";
    private static final String ACTION_DECISION =
            "io.github.zhanfg.sda.action.SYSTEM_DOWNLOAD_DECISION";
    private static final String ACTION_IN_APP_DECISION =
            "io.github.zhanfg.sda.action.IN_APP_DOWNLOAD_DECISION";
    private static final Uri BRIDGE_URI = Uri.parse("content://io.github.zhanfg.sda.history");
    private static final long FAILSAFE_RESUME_MS = 120_000L;

    private static final AtomicBoolean RECEIVER_REGISTERED = new AtomicBoolean(false);
    private static final Map<String, PendingDownload> PENDING_BY_TOKEN = new ConcurrentHashMap<>();
    private static final Map<Long, PendingDownload> PENDING_BY_ID = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
                    .setId("sda.system-download-confirmation")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ContentValues originalValues = (ContentValues) chain.getArg(1);
                        ContentValues snapshot = originalValues == null
                                ? new ContentValues()
                                : new ContentValues(originalValues);

                        Object result = chain.proceed();
                        if (!(result instanceof Uri)) {
                            return result;
                        }

                        Uri downloadUri = (Uri) result;
                        ContentProvider provider = (ContentProvider) chain.getThisObject();
                        Context context = provider.getContext();
                        if (context == null || shouldBypass(snapshot)) {
                            return result;
                        }

                        ensureDecisionReceiver(context);

                        ContentValues pause = new ContentValues();
                        pause.put("control", 1);
                        update.invoke(provider, downloadUri, pause, null, null);

                        long downloadId = safeId(downloadUri);
                        String token = createToken(downloadUri);
                        PendingDownload pending = new PendingDownload(
                                token,
                                downloadId,
                                provider,
                                update,
                                delete,
                                downloadUri,
                                snapshot
                        );
                        PENDING_BY_TOKEN.put(token, pending);
                        if (downloadId >= 0) {
                            PENDING_BY_ID.put(downloadId, pending);
                        }

                        String sourcePackage = safe(
                                snapshot.getAsString("notificationpackage"), "");
                        boolean handledInSourceApp = consumeInAppMarker(context, sourcePackage);
                        if (handledInSourceApp) {
                            scheduleTimeout(pending);
                            log(Log.INFO, TAG,
                                    "Download paused for in-app confirmation: " + downloadUri
                                            + " source=" + sourcePackage);
                            return result;
                        }

                        if (!openFallbackConfirmation(context, token, downloadUri, snapshot)) {
                            removePending(pending);
                            pending.resume();
                            log(Log.WARN, TAG,
                                    "Unable to open fallback confirmation UI; download resumed");
                            return result;
                        }

                        scheduleTimeout(pending);
                        log(Log.INFO, TAG,
                                "Download paused for fallback confirmation: " + downloadUri);
                        return result;
                    });

            log(Log.INFO, TAG,
                    "Central confirmation gate installed; source-app dialogs preferred");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install central confirmation hook", error);
        }
    }

    private boolean shouldBypass(ContentValues values) {
        if (Boolean.TRUE.equals(values.getAsBoolean("sda_bypass_confirmation"))) {
            values.remove("sda_bypass_confirmation");
            return true;
        }
        String uri = values.getAsString("uri");
        return uri == null || uri.isEmpty();
    }

    private boolean consumeInAppMarker(Context context, String sourcePackage) {
        if (sourcePackage == null || sourcePackage.isEmpty()) {
            return false;
        }
        try {
            Bundle result = context.getContentResolver().call(
                    BRIDGE_URI,
                    "consume_in_app_confirmation",
                    sourcePackage,
                    null
            );
            return result != null && result.getBoolean("consumed", false);
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "Unable to consume source-app confirmation marker for " + sourcePackage,
                    error);
            return false;
        }
    }

    private void ensureDecisionReceiver(Context context) {
        if (!RECEIVER_REGISTERED.compareAndSet(false, true)) {
            return;
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent.getAction();
                PendingDownload pending;
                if (ACTION_IN_APP_DECISION.equals(action)) {
                    long id = intent.getLongExtra("download_id", -1L);
                    pending = id < 0 ? null : PENDING_BY_ID.get(id);
                } else if (ACTION_DECISION.equals(action)) {
                    String token = intent.getStringExtra("token");
                    pending = token == null ? null : PENDING_BY_TOKEN.get(token);
                } else {
                    return;
                }

                if (pending == null) {
                    return;
                }
                removePending(pending);
                applyDecision(pending, intent.getIntExtra("decision", 0));
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DECISION);
        filter.addAction(ACTION_IN_APP_DECISION);
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
                log(Log.INFO, TAG, "Confirmed system download: " + pending.uri);
            } else {
                pending.cancel();
                log(Log.INFO, TAG, "Cancelled system download: " + pending.uri);
            }
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to apply confirmation decision", error);
            try {
                pending.resume();
            } catch (Throwable ignored) {
            }
        }
    }

    private void scheduleTimeout(PendingDownload pending) {
        MAIN.postDelayed(() -> {
            PendingDownload current = PENDING_BY_TOKEN.get(pending.token);
            if (current != pending) {
                return;
            }
            removePending(pending);
            try {
                pending.resume();
                log(Log.WARN, TAG, "Confirmation timed out; download resumed");
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Failed to resume timed-out download", error);
            }
        }, FAILSAFE_RESUME_MS);
    }

    private static void removePending(PendingDownload pending) {
        PENDING_BY_TOKEN.remove(pending.token, pending);
        if (pending.downloadId >= 0) {
            PENDING_BY_ID.remove(pending.downloadId, pending);
        }
    }

    private boolean openFallbackConfirmation(
            Context context, String token, Uri uri, ContentValues values) {
        Intent activity = new Intent();
        activity.setComponent(new ComponentName(MODULE_PACKAGE, CONFIRM_ACTIVITY));
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.putExtra("token", token);
        activity.putExtra("download_uri", uri.toString());
        activity.putExtra("source_package",
                safe(values.getAsString("notificationpackage"), "系统应用"));
        activity.putExtra("url", safe(values.getAsString("uri"), ""));
        activity.putExtra("file_name", resolveFileName(values));
        activity.putExtra("mime_type",
                safe(values.getAsString("mimetype"), "application/octet-stream"));
        Long total = values.getAsLong("total_bytes");
        activity.putExtra("file_size", total == null ? -1L : total.longValue());

        try {
            context.startActivity(activity);
            return true;
        } catch (Throwable directError) {
            log(Log.WARN, TAG,
                    "Direct fallback Activity launch rejected; trying PendingIntent",
                    directError);
        }

        try {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    token.hashCode(),
                    activity,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                );
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle());
            } else {
                pendingIntent.send();
            }
            return true;
        } catch (Throwable pendingIntentError) {
            log(Log.ERROR, TAG, "PendingIntent fallback launch failed", pendingIntentError);
            return false;
        }
    }

    private static String resolveFileName(ContentValues values) {
        String title = values.getAsString("title");
        if (title != null && !title.trim().isEmpty()) {
            return title;
        }
        String hint = values.getAsString("hint");
        if (hint != null && !hint.isEmpty()) {
            try {
                String last = Uri.parse(hint).getLastPathSegment();
                if (last != null && !last.isEmpty()) {
                    return last;
                }
            } catch (Throwable ignored) {
            }
        }
        String source = values.getAsString("uri");
        if (source != null && !source.isEmpty()) {
            try {
                String last = Uri.parse(source).getLastPathSegment();
                if (last != null && !last.isEmpty()) {
                    return last;
                }
            } catch (Throwable ignored) {
            }
        }
        return "download.bin";
    }

    private static long safeId(Uri uri) {
        try {
            return ContentUris.parseId(uri);
        } catch (Throwable ignored) {
            return -1L;
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
        final long downloadId;
        final Object provider;
        final Method update;
        final Method delete;
        final Uri uri;
        final ContentValues original;

        PendingDownload(String token, long downloadId, Object provider,
                        Method update, Method delete, Uri uri, ContentValues original) {
            this.token = token;
            this.downloadId = downloadId;
            this.provider = provider;
            this.update = update;
            this.delete = delete;
            this.uri = uri;
            this.original = original;
        }

        void resume() throws Exception {
            ContentValues values = new ContentValues();
            values.put("control", 0);
            update.invoke(provider, uri, values, null, null);
            ContentProvider contentProvider = (ContentProvider) provider;
            Context context = contentProvider.getContext();
            if (context != null) {
                context.getContentResolver().notifyChange(uri, null);
                Intent wakeup = new Intent("android.intent.action.DOWNLOAD_WAKEUP");
                wakeup.setPackage(TARGET_PACKAGE);
                context.sendBroadcast(wakeup);
            }
        }

        void cancel() throws Exception {
            delete.invoke(provider, uri, null, null);
        }
    }
}
