package dev.axymorrsen.systemdownloadaccelerator;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Modern libxposed entry point with API-102 hot reload support and
 * runtime-state publication for the module app diagnostics screen.
 */
public final class AcceleratorModule extends XposedModule {
    private static final String TAG = "SysDlAccel";
    private static final String HOOK_PREFIX = "sysdl:";
    private static final String RUNTIME_PREFS = "runtime";

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
    private final Set<String> hookedIds = new HashSet<>();

    private int providerHookCount;
    private int downloadsUiClassCount;
    private int systemUiClassCount;
    private String processName = "";

    /**
     * The saved hot-reload state uses only framework-owned Pair/String/ClassLoader
     * objects so the new module ClassLoader can safely read the old generation state.
     *
     * first.first  = package name
     * first.second = process name
     * second       = target ClassLoader
     */
    private Pair<Pair<String, String>, ClassLoader> reloadState;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "module loaded: process=" + processName
                + ", sdk=" + Build.VERSION.SDK_INT
                + ", api=" + getApiVersion());
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

        ClassLoader classLoader = param.getClassLoader();
        reloadState = Pair.create(
                Pair.create(scope.packageName, processName),
                classLoader);
        installForScope(scope, classLoader, false);
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        if (reloadState == null) {
            log(Log.WARN, TAG, "hot reload rejected: target context not ready");
            return false;
        }

        param.setSavedInstanceState(reloadState);
        log(Log.INFO, TAG, "hot reload preparing: " + reloadState.first.first);
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        Object saved = param.getSavedInstanceState();
        if (!(saved instanceof Pair<?, ?> outer)
                || !(outer.first instanceof Pair<?, ?> meta)
                || !(meta.first instanceof String packageName)
                || !(meta.second instanceof String savedProcessName)
                || !(outer.second instanceof ClassLoader classLoader)) {
            log(Log.ERROR, TAG, "hot reload failed: missing target context");
            unhookUnknownOldHandles(param);
            return;
        }

        Scope scope = Scope.fromPackage(packageName);
        if (scope == null) {
            log(Log.ERROR, TAG, "hot reload failed: unsupported target " + packageName);
            unhookUnknownOldHandles(param);
            return;
        }

        processName = savedProcessName;
        reloadState = Pair.create(
                Pair.create(packageName, savedProcessName),
                classLoader);

        try {
            installForScope(scope, classLoader, true);
            log(Log.INFO, TAG, "hot reload complete: " + packageName);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hot reload install failed: " + packageName, t);
        } finally {
            unhookUnknownOldHandles(param);
        }
    }

    private void installForScope(Scope scope, ClassLoader classLoader, boolean hotReload) {
        int evidenceCount;
        switch (scope) {
            case PROVIDER:
                evidenceCount = installProviderHooks(classLoader);
                break;
            case DOWNLOADS_UI:
                evidenceCount = probeDownloadsUi(classLoader);
                break;
            case SYSTEM_UI:
                evidenceCount = probeSystemUi(classLoader);
                break;
            default:
                evidenceCount = 0;
                break;
        }

        publishRuntime(scope, evidenceCount, hotReload);

        if (hotReload) {
            log(Log.DEBUG, TAG, "scope refreshed from new generation: " + scope.packageName);
        }
    }

    private int installProviderHooks(ClassLoader classLoader) {
        if (!providerInstalled.compareAndSet(false, true)) {
            return providerHookCount;
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

            providerHookCount = count;
            log(Log.INFO, TAG, "provider ready; DownloadThread hooks=" + count);
            return count;
        } catch (Throwable t) {
            providerInstalled.set(false);
            providerHookCount = 0;
            log(Log.ERROR, TAG, "provider probe unavailable; native path preserved", t);
            return 0;
        }
    }

    private void installProviderProbe(Method method) {
        String id = HOOK_PREFIX + method.toGenericString();
        hookedIds.add(id);

        hook(method)
                .setId(id)
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

    private void unhookUnknownOldHandles(HotReloadedParam param) {
        param.getOldHookHandles().forEach(handle -> {
            String id = handle.getId();
            if (id == null || !hookedIds.contains(id)) {
                try {
                    handle.unhook();
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "unable to remove stale hook", t);
                }
            }
        });
    }

    private int probeDownloadsUi(ClassLoader classLoader) {
        if (!downloadsUiSeen.compareAndSet(false, true)) {
            return downloadsUiClassCount;
        }

        downloadsUiClassCount =
                Probe.countAvailable(classLoader, DOWNLOADS_UI_CLASSES);
        log(Log.INFO, TAG, "downloads-ui ready; known classes="
                + downloadsUiClassCount + "/" + DOWNLOADS_UI_CLASSES.length);
        return downloadsUiClassCount;
    }

    private int probeSystemUi(ClassLoader classLoader) {
        if (!systemUiSeen.compareAndSet(false, true)) {
            return systemUiClassCount;
        }

        systemUiClassCount =
                Probe.countAvailable(classLoader, SYSTEM_UI_CLASSES);
        log(Log.INFO, TAG, "systemui ready; known classes="
                + systemUiClassCount + "/" + SYSTEM_UI_CLASSES.length);
        return systemUiClassCount;
    }

    private void publishRuntime(Scope scope, int evidenceCount, boolean hotReload) {
        try {
            SharedPreferences prefs = getRemotePreferences(RUNTIME_PREFS);
            String prefix = "scope." + scope.packageName + ".";
            long now = System.currentTimeMillis();

            SharedPreferences.Editor editor = prefs.edit()
                    .putString(prefix + "process", processName == null ? "" : processName)
                    .putLong(prefix + "version", BuildConfig.VERSION_CODE)
                    .putLong(prefix + "loadedAt", now)
                    .putInt(prefix + "evidenceCount", evidenceCount)
                    .putBoolean(prefix + "hotReload", hotReload);

            if (hotReload) {
                editor.putLong(prefix + "lastReload", now);
            }

            editor.apply();
        } catch (Throwable t) {
            log(Log.WARN, TAG, "runtime status publish failed: " + scope.packageName, t);
        }
    }
}
