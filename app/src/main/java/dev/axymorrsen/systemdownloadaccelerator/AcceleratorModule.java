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
 * Phase 1 is observation-only: verify DownloadProvider hook points and collect
 * sanitized metadata while leaving the original network/file behavior untouched.
 */
public final class AcceleratorModule extends XposedModule {
    private static final String TAG = "SysDlAccel";
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final Set<String> PROBE_METHODS = new HashSet<>(Arrays.asList(
            "run", "executeDownload", "transferData", "addRequestHeaders"
    ));

    private final AtomicBoolean installed = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded: process=" + param.getProcessName()
                + ", sdk=" + Build.VERSION.SDK_INT);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage() || !TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            log(Log.INFO, TAG, "SDK < 36: probe disabled, DownloadProvider left untouched");
            return;
        }
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        installDownloadThreadHooks(param.getClassLoader());
    }

    private void installDownloadThreadHooks(ClassLoader classLoader) {
        try {
            Class<?> downloadThread =
                    classLoader.loadClass("com.android.providers.downloads.DownloadThread");
            int count = 0;
            for (Method method : downloadThread.getDeclaredMethods()) {
                if (!PROBE_METHODS.contains(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                installProbeHook(method);
                count++;
            }
            log(Log.INFO, TAG, "DownloadThread probe ready; hooks=" + count);
        } catch (Throwable t) {
            installed.set(false);
            log(Log.ERROR, TAG, "Unable to install DownloadThread probe", t);
        }
    }

    private void installProbeHook(Method method) {
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
}
