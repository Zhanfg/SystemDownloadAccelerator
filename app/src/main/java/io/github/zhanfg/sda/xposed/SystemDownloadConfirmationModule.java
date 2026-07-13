package io.github.zhanfg.sda.xposed;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
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

/**
 * Central confirmation hook for every request that reaches the system DownloadProvider.
 *
 * <p>This intentionally does not depend on browser/app scopes. Any app using Android's default
 * DownloadManager reaches DownloadProvider.insert(), including in-app sandbox links.</p>
 */
public final class SystemDownloadConfirmationModule extends XposedModule {
    private static final String TAG = "SysDownloadConfirm";
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final String MODULE_PACKAGE = "io.github.zhanfg.sda";
    private static final String CONFIRM_ACTIVITY =
            "io.github.zhanfg.sda.SystemDownloadConfirmActivity";
    private static final String ACTION_DECISION =
            "io.github.zhanfg.sda.action.SYSTEM_DOWNLOAD_DECISION";
    private static final long FAILSAFE_RESUME_MS = 120_000L;

    private static final AtomicBoolean RECEIVER_REGISTERED = new AtomicBoolean(false);
    private static final Map<String, PendingDownload> PENDING = new ConcurrentHashMap<>();
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
                    "insert",
                    Uri.class,
                    ContentValues.class
            );
            Method update = providerClass.getDeclaredMethod(
                    "update",
                    Uri.class,
                    ContentValues.class,
                    String.class,
                    String[].class
            );
            Method delete = providerClass.getDeclaredMethod(
                    "delete",
                    Uri.class,
                    String.class,
                    String[].class
            );
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

                        // Pause immediately after insertion, before DownloadService/JobScheduler starts work.
                        ContentValues pause = new ContentValues();
                        pause.put("control", 1);
                        update.invoke(provider, downloadUri, pause, null, null);

                        String token = createToken(downloadUri);
                        PendingDownload pending = new PendingDownload(
                                provider,
                                update,
                                delete,
                                downloadUri,
                                snapshot
                        );
                        PENDING.put(token, pending);

                        if (!openConfirmation(context, token, downloadUri, snapshot)) {
                            PENDING.remove(token);
                            pending.resume();
                            log(Log.WARN, TAG, "Unable to open confirmation UI; download resumed");
                            return result;
                        }

                        MAIN.postDelayed(() -> {
                            PendingDownload timedOut = PENDING.remove(token);
                            if (timedOut != null) {
                                timedOut.resume();
                                log(Log.WARN, TAG, "Confirmation timed out; download resumed");
                            }
                        }, FAILSAFE_RESUME_MS);

                        log(Log.INFO, TAG, "System download paused for confirmation: " + downloadUri);
                        return result;
                    });

            log(Log.INFO, TAG, "Central DownloadProvider.insert confirmation hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install central confirmation hook", error);
        }
    }

    private boolean shouldBypass(ContentValues values) {
        // Internal/system maintenance rows and module-generated resume operations must not re-prompt.
        if (values.getAsBoolean("sda_bypass_confirmation") == Boolean.TRUE) {
            values.remove("sda_bypass_confirmation");
            return true;
        }
        String uri = values.getAsString("uri");
        return uri == null || uri.isEmpty();
    }

    private void ensureDecisionReceiver(Context context) {
        if (!RECEIVER_REGISTERED.compareAndSet(false, true)) {
            return;
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (!ACTION_DECISION.equals(intent.getAction())) {
                    return;
                }
                String token = intent.getStringExtra("token");
                int decision = intent.getIntExtra("decision", 0);
                PendingDownload pending = token == null ? null : PENDING.remove(token);
                if (pending == null) {
                    return;
                }

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
        };

        IntentFilter filter = new IntentFilter(ACTION_DECISION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private boolean openConfirmation(Context context, String token, Uri uri, ContentValues values) {
        Intent activity = new Intent();
        activity.setComponent(new ComponentName(MODULE_PACKAGE, CONFIRM_ACTIVITY));
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        activity.putExtra("token", token);
        activity.putExtra("download_uri", uri.toString());
        activity.putExtra("source_package", safe(values.getAsString("notificationpackage"), "系统应用"));
        activity.putExtra("url", safe(values.getAsString("uri"), ""));
        activity.putExtra("file_name", resolveFileName(values));
        activity.putExtra("mime_type", safe(values.getAsString("mimetype"), "application/octet-stream"));
        Long total = values.getAsLong("total_bytes");
        activity.putExtra("file_size", total == null ? -1L : total.longValue());

        try {
            context.startActivity(activity);
            return true;
        } catch (Throwable directError) {
            log(Log.WARN, TAG, "Direct confirmation Activity launch rejected; trying PendingIntent", directError);
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
            log(Log.ERROR, TAG, "PendingIntent confirmation launch failed", pendingIntentError);
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
        final Object provider;
        final Method update;
        final Method delete;
        final Uri uri;
        final ContentValues original;

        PendingDownload(Object provider, Method update, Method delete, Uri uri,
                        ContentValues original) {
            this.provider = provider;
            this.update = update;
            this.delete = delete;
            this.uri = uri;
            this.original = original;
        }

        void resume() throws Exception {
            ContentValues values = new ContentValues();
            values.put("control", 0);
            values.put("status", 190);
            update.invoke(provider, uri, values, null, null);
        }

        void cancel() throws Exception {
            delete.invoke(provider, uri, null, null);
        }
    }
}
