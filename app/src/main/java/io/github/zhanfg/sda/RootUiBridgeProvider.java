package io.github.zhanfg.sda;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.zhanfg.sda.ui.DownloadUiState;

/**
 * Narrow privileged bridge. It never accepts shell commands: the caller can only request that the
 * module's fixed transparent confirmation Activity be shown for a stored random token.
 */
public final class RootUiBridgeProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.zhanfg.sda.rootbridge";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    private static final String DOWNLOADS_PACKAGE = "com.android.providers.downloads";
    private static final String ACTIVITY_COMPONENT =
            "io.github.zhanfg.sda/.SystemDownloadConfirmActivity";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SDA-root-ui-bridge");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"show_download_confirmation".equals(method)) {
            return super.call(method, arg, extras);
        }
        enforceDownloadsCaller();
        String token = arg;
        if (token == null || !token.matches("[a-fA-F0-9]{16,160}")) {
            throw new IllegalArgumentException("Invalid confirmation token");
        }
        Context context = requireContext();
        DownloadUiState.store(context, token, extras == null ? Bundle.EMPTY : extras);
        EXECUTOR.execute(() -> launchConfirmation(context, token));
        Bundle result = new Bundle();
        result.putBoolean("scheduled", true);
        return result;
    }

    private void launchConfirmation(Context context, String token) {
        boolean rootStarted = false;
        try {
            String command = "am start --user current --activity-no-animation "
                    + "-n " + ACTIVITY_COMPONENT + " --es token " + token;
            Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(12, TimeUnit.SECONDS);
            rootStarted = finished && process.exitValue() == 0;
            if (!finished) {
                process.destroyForcibly();
            }
        } catch (Throwable ignored) {
            rootStarted = false;
        }

        if (rootStarted) {
            return;
        }

        // Compatibility fallback for devices where background Activity launch is still permitted.
        try {
            Intent intent = new Intent(context, SystemDownloadConfirmActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("token", token);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // The DownloadProvider-side timeout will safely resume the task.
        }
    }

    private void enforceDownloadsCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) {
            return;
        }
        Context context = requireContext();
        PackageManager packageManager = context.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages != null) {
            for (String packageName : packages) {
                if (DOWNLOADS_PACKAGE.equals(packageName)) {
                    return;
                }
            }
        }
        throw new SecurityException("Root UI bridge denied for uid " + uid);
    }

    private Context requireContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider context unavailable");
        }
        return context;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
