package dev.axymorrsen.systemdownloadaccelerator;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Modern libxposed entry point.
 *
 * Provider owns transfer state. Downloads UI and SystemUI are read/control surfaces
 * only; they must never become independent sources of download truth.
 */
public final class AcceleratorModule extends XposedModule {
    private static final String TAG = "SysDlAccel";

    private static final Set<String> PROVIDER_METHODS = new HashSet<>(Arrays.asList(
            "run", "executeDownload", "transferData", "addRequestHeaders"
    ));

    private static final String[] DOWNLOADS_UI_CLASSES = {
            "com.android.providers.downloads.ui.DownloadList",
            "com.android.providers.downloads.ui.DownloadItem",
            "com.android.providers.downloads.ui.DownloadActivity"
    };

    private static final String[] SYSTEM_UI_CLASSES = {
            "com.android.systemui.SystemUIApplication",
            "com.android.systemui.statusbar.notification.collection.NotifPipeline",
            "com.android.systemui.statusbar.notification.collection.NotificationEntry"
    };

    private final AtomicBoolean providerInstalled = new AtomicBoolean(false);
    private final AtomicBoolean downloadsUiSeen = new AtomicBoolean(false);
    private final AtomicBoolean systemUiSeen = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded: process=" + param.getProcessName()
                + ", sdk=" + Build.VERSION.SDK_INT);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) {
            return;
        }

        Scope scope = Scope.fromPackage(param.getPackageName());
        if (scope == null) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            log(Log.INFO, TAG, "SDK < 36: target left untouched: " + scope.packageName);
            return;
        }

        switch (scope) {
            case PROVIDER:
                installProviderHooks(param.getClassLoader());
                break;
            case DOWNLOADS_UI:
                probeDownloadsUi(param.getClassLoader());
                break;
            case SYSTEM_UI:
                probeSystemUi(param.getClassLoader());
                break;
        }
    }

    private void installProviderHooks(ClassLoader classLoader) {
        if (!providerInstalled.compareAndSet(false, true)) {
            return;
        }

        try {
            Class<?> downloadThread =
                    classLoader.loadClass("com.android.providers.downloads.DownloadThread");

            int count = 0;
            for (Method method : downloadThread.getDeclaredMethods()) {
                if (!PROVIDER_METHODS.contains(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                installProviderProbe(method);
                count++;
            }

            log(Log.INFO, TAG, "provider ready; DownloadThread hooks=" + count);
        } catch (Throwable t) {
            providerInstalled.set(false);
            log(Log.ERROR, TAG, "provider probe unavailable; native path preserved", t);
        }
    }

    private void installProviderProbe(Method method) {
        hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    long startedNs = SystemClock.elapsedRealtimeNanos();

                    if ("run".equals(method.getName())) {
                        ProbeSnapshot snapshot =
                                DownloadInfoInspector.inspect(chain.getThisObject());
                        log(Log.INFO, TAG, "download start " + snapshot.toSafeLogString());
                    }

                    try {
                        return chain.proceed();
                    } finally {
                        long elapsedMs =
                                (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L;
                        if ("run".equals(method.getName())
                                || "executeDownload".equals(method.getName())) {
                            log(Log.DEBUG, TAG,
                                    method.getName() + " finished in " + elapsedMs + " ms");
                        }
                    }
                });
    }

    private void probeDownloadsUi(ClassLoader classLoader) {
        if (!downloadsUiSeen.compareAndSet(false, true)) {
            return;
        }

        int available = Probe.countAvailable(classLoader, DOWNLOADS_UI_CLASSES);
        log(Log.INFO, TAG, "downloads-ui ready; known classes="
                + available + "/" + DOWNLOADS_UI_CLASSES.length);
    }

    private void probeSystemUi(ClassLoader classLoader) {
        if (!systemUiSeen.compareAndSet(false, true)) {
            return;
        }

        int available = Probe.countAvailable(classLoader, SYSTEM_UI_CLASSES);
        log(Log.INFO, TAG, "systemui ready; known classes="
                + available + "/" + SYSTEM_UI_CLASSES.length);
    }
}
