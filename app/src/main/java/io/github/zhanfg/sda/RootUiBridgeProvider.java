package io.github.zhanfg.sda;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.github.zhanfg.sda.ui.DownloadUiState;

/** Narrow privileged bridge for confirmation UI and sanitized engine telemetry. */
public final class RootUiBridgeProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.zhanfg.sda.rootbridge";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    private static final String DOWNLOADS_PACKAGE = "com.android.providers.downloads";
    private static final String ACTIVITY_COMPONENT =
            "io.github.zhanfg.sda/.SystemDownloadConfirmActivity";
    private static final String PREFS = "module_settings";
    private static final Set<String> ENGINE_STATES = new HashSet<>(Arrays.asList(
            "disabled", "fallback", "active", "complete", "error"
    ));

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("report_engine_status".equals(method)) {
            enforceDownloadsCaller();
            return storeEngineStatus(extras == null ? Bundle.EMPTY : extras);
        }
        if (!"show_download_confirmation".equals(method)) {
            return super.call(method, arg, extras);
        }

        enforceDownloadsCaller();
        String token = arg;
        if (token == null || !token.matches("[a-fA-F0-9]{16,160}")) {
            throw new IllegalArgumentException("Invalid confirmation token");
        }

        Context context = providerContext();
        DownloadUiState.store(context, token, extras == null ? Bundle.EMPTY : extras);
        LaunchResult launch = launchConfirmation(context, token);

        Bundle result = new Bundle();
        result.putBoolean("scheduled", launch.started);
        result.putBoolean("root", launch.usedRoot);
        result.putString("detail", launch.detail);
        return result;
    }

    private Bundle storeEngineStatus(Bundle extras) {
        String status = extras.getString("status", "error");
        if (!ENGINE_STATES.contains(status)) status = "error";
        String detail = extras.getString("detail", "");
        if (detail == null) detail = "";
        if (detail.length() > 256) detail = detail.substring(0, 256);
        int threads = Math.max(0, Math.min(16, extras.getInt("threads", 0)));
        long bytes = Math.max(0L, extras.getLong("bytes", 0L));
        long updatedAt = System.currentTimeMillis();

        SharedPreferences preferences = providerContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit()
                .putString("engine_status", status)
                .putString("engine_detail", detail)
                .putInt("engine_threads", threads)
                .putLong("engine_bytes", bytes)
                .putLong("engine_updated_at", updatedAt)
                .apply();

        Bundle result = new Bundle();
        result.putBoolean("stored", true);
        result.putString("status", status);
        result.putInt("threads", threads);
        result.putLong("updated_at", updatedAt);
        return result;
    }

    private LaunchResult launchConfirmation(Context context, String token) {
        java.lang.Process process = null;
        try {
            String command = "am start --user current --activity-no-animation "
                    + "--activity-clear-top -n " + ACTIVITY_COMPONENT + " --es token " + token;
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                return new LaunchResult(true, true, "root activity launch succeeded");
            }
            if (!finished) process.destroyForcibly();
        } catch (Throwable ignored) {
            // Fall through to normal Activity launch. The caller receives the actual outcome.
        } finally {
            if (process != null) process.destroy();
        }

        try {
            Intent intent = new Intent(context, SystemDownloadConfirmActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("token", token);
            context.startActivity(intent);
            return new LaunchResult(true, false, "normal activity launch requested");
        } catch (Throwable error) {
            return new LaunchResult(false, false,
                    "activity launch failed: " + error.getClass().getSimpleName());
        }
    }

    private void enforceDownloadsCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return;
        PackageManager packageManager = providerContext().getPackageManager();
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages != null) {
            for (String packageName : packages) {
                if (DOWNLOADS_PACKAGE.equals(packageName)) return;
            }
        }
        throw new SecurityException("Root UI bridge denied for uid " + uid);
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context unavailable");
        return context;
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

    private static final class LaunchResult {
        final boolean started;
        final boolean usedRoot;
        final String detail;

        LaunchResult(boolean started, boolean usedRoot, String detail) {
            this.started = started;
            this.usedRoot = usedRoot;
            this.detail = detail;
        }
    }
}
