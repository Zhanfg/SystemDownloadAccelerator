package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * libxposed API 102 entry point.
 *
 * Runtime status is authoritative from XposedService.getRunningTargets().
 * Injected processes must not attempt to write RemotePreferences: the module-side
 * RemotePreferences view is read-only in hooked processes.
 */
public final class AcceleratorModule extends XposedModule {
    private static final String TAG = "SysDlAccel";
    private static final String HOOK_PREFIX = "sysdl:";

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
    private int providerDeoptimizedCount;
    private int providerDeoptFailedCount;
    private int providerConnectionCandidateCount;
    private String providerHookInventory = "";
    private int downloadsUiClassCount;
    private int systemUiClassCount;
    private String processName = "";

    private Pair<Pair<String, String>, ClassLoader> reloadState;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        emit(Log.INFO, "module loaded process=" + processName
                + " sdk=" + Build.VERSION.SDK_INT
                + " api=" + getApiVersion()
                + " version=" + BuildConfig.VERSION_CODE);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        Scope scope = Scope.fromPackage(param.getPackageName());
        if (scope != null) {
            emit(Log.INFO, "package loaded package=" + param.getPackageName()
                    + " process=" + processName
                    + " first=" + param.isFirstPackage());
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        Scope scope = Scope.fromPackage(param.getPackageName());
        if (scope == null) {
            return;
        }

        emit(Log.INFO, "package ready package=" + param.getPackageName()
                + " process=" + processName
                + " first=" + param.isFirstPackage());

        if (!param.isFirstPackage()) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            emit(Log.INFO, "SDK < 36; target left untouched: " + scope.packageName);
            return;
        }

        if (processName == null || processName.isBlank()) {
            processName = scope.packageName;
        }

        ClassLoader classLoader = param.getClassLoader();
        reloadState = Pair.create(
                Pair.create(scope.packageName, processName),
                classLoader);
        installForScope(scope, classLoader, false);
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        if (reloadState != null) {
            param.setSavedInstanceState(reloadState);
            emit(Log.INFO, "hot reload preparing scope=" + reloadState.first.first
                    + " process=" + reloadState.first.second);
        } else {
            emit(Log.WARN, "hot reload preparing without saved target context");
        }
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        Object saved = param.getSavedInstanceState();
        String packageName = null;
        String savedProcessName = param.getProcessName();
        ClassLoader classLoader = null;

        if (saved instanceof Pair<?, ?> outer
                && outer.first instanceof Pair<?, ?> meta
                && meta.first instanceof String savedPackage
                && meta.second instanceof String oldProcess
                && outer.second instanceof ClassLoader savedLoader) {
            packageName = savedPackage;
            savedProcessName = oldProcess;
            classLoader = savedLoader;
        } else if (saved instanceof Pair<?, ?> legacy
                && legacy.first instanceof String savedPackage
                && legacy.second instanceof ClassLoader savedLoader) {
            packageName = savedPackage;
            classLoader = savedLoader;
            emit(Log.INFO, "hot reload migrated legacy saved-state schema");
        }

        if (classLoader == null) {
            classLoader = recoverTargetClassLoader(param);
        }

        if (savedProcessName == null || savedProcessName.isBlank()) {
            savedProcessName = param.getProcessName();
        }
        processName = savedProcessName == null ? "" : savedProcessName;

        Scope scope = packageName == null ? null : Scope.fromPackage(packageName);
        if (scope == null) {
            scope = inferScope(processName, classLoader);
        }

        if (scope == null || classLoader == null) {
            emit(Log.ERROR, "hot reload recovery failed; preserving old hooks process="
                    + processName);
            return;
        }

        reloadState = Pair.create(
                Pair.create(scope.packageName, processName),
                classLoader);

        boolean installed = false;
        try {
            installForScope(scope, classLoader, true);
            installed = true;
            emit(Log.INFO, "hot reload complete scope=" + scope.packageName
                    + " process=" + processName);
        } catch (Throwable t) {
            emit(Log.ERROR, "hot reload install failed; preserving old hooks scope="
                    + scope.packageName, t);
        }

        if (installed) {
            unhookUnknownOldHandles(param);
        }
    }

    private ClassLoader recoverTargetClassLoader(HotReloadedParam param) {
        try {
            for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
                Executable executable = handle.getExecutable();
                if (executable != null && executable.getDeclaringClass() != null) {
                    ClassLoader loader = executable.getDeclaringClass().getClassLoader();
                    if (loader != null) {
                        emit(Log.INFO, "hot reload recovered ClassLoader from old hook");
                        return loader;
                    }
                }
            }
        } catch (Throwable t) {
            emit(Log.WARN, "old-hook ClassLoader recovery failed", t);
        }

        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader != null) {
                emit(Log.INFO, "hot reload using thread context ClassLoader");
                return loader;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Scope inferScope(String process, ClassLoader classLoader) {
        Scope direct = Scope.fromPackage(process);
        if (direct != null) {
            return direct;
        }
        if (classLoader == null) {
            return null;
        }

        try {
            classLoader.loadClass("com.android.providers.downloads.DownloadThread");
            return Scope.PROVIDER;
        } catch (Throwable ignored) {
        }

        if (Probe.countAvailable(classLoader, DOWNLOADS_UI_CLASSES) > 0) {
            return Scope.DOWNLOADS_UI;
        }

        if (Probe.countAvailable(classLoader, SYSTEM_UI_CLASSES) > 0) {
            return Scope.SYSTEM_UI;
        }

        return null;
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

        emit(Log.INFO, "scope ready scope=" + scope.packageName
                + " process=" + processName
                + " evidence=" + evidenceCount
                + " hotReload=" + hotReload);
    }

    private int installProviderHooks(ClassLoader classLoader) {
        if (!providerInstalled.compareAndSet(false, true)) {
            return providerHookCount;
        }

        try {
            Class<?> downloadThread =
                    classLoader.loadClass("com.android.providers.downloads.DownloadThread");
            Method[] methods = downloadThread.getDeclaredMethods();

            int deoptimized = 0;
            int deoptFailed = 0;

            /*
             * We intentionally deoptimize the complete DownloadThread body.
             * Private transfer helpers are common ART inlining targets, and on
             * OEM builds the caller graph can differ from AOSP. Network I/O
             * dominates this class, so interpreter overhead here is negligible
             * compared with missing the actual transfer path.
             */
            for (Method method : methods) {
                int modifiers = method.getModifiers();
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    continue;
                }
                method.setAccessible(true);
                try {
                    if (deoptimize(method)) {
                        deoptimized++;
                    } else {
                        deoptFailed++;
                    }
                } catch (Throwable t) {
                    deoptFailed++;
                    emit(Log.DEBUG,
                            "deoptimize unavailable for " + method.toGenericString(), t);
                }
            }

            int count = 0;
            int connectionCandidates = 0;
            StringBuilder inventory = new StringBuilder();

            for (Method method : methods) {
                if (!shouldHookProviderMethod(method)) {
                    continue;
                }

                method.setAccessible(true);
                boolean declaredConnection = hasDeclaredHttpConnection(method);
                installProviderProbe(method, declaredConnection);
                count++;

                if (declaredConnection) {
                    connectionCandidates++;
                }

                if (inventory.length() < 1800) {
                    if (inventory.length() > 0) {
                        inventory.append(" | ");
                    }
                    inventory.append(method.getName())
                            .append("(");
                    Class<?>[] params = method.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) inventory.append(",");
                        inventory.append(params[i].getSimpleName());
                    }
                    inventory.append(")");
                    if (declaredConnection) {
                        inventory.append("[HTTP]");
                    }
                }
            }

            providerHookCount = count;
            providerDeoptimizedCount = deoptimized;
            providerDeoptFailedCount = deoptFailed;
            providerConnectionCandidateCount = connectionCandidates;
            providerHookInventory = inventory.toString();

            emit(Log.INFO, "provider hooks installed=" + count
                    + " httpCandidates=" + connectionCandidates
                    + " deoptimized=" + deoptimized
                    + " deoptFailed=" + deoptFailed
                    + " inventory=" + providerHookInventory);
            return count;
        } catch (Throwable t) {
            providerInstalled.set(false);
            providerHookCount = 0;
            providerDeoptimizedCount = 0;
            providerDeoptFailedCount = 0;
            providerConnectionCandidateCount = 0;
            providerHookInventory = "";
            emit(Log.ERROR, "provider hook install unavailable; native path preserved", t);
            return 0;
        }
    }

    private boolean shouldHookProviderMethod(Method method) {
        String name = method.getName();
        if ("run".equals(name)
                || PROVIDER_METHODS.contains(name)
                || hasDeclaredHttpConnection(method)) {
            return true;
        }

        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("execute")
                || lower.contains("download")
                || lower.contains("transfer");
    }

    private boolean hasDeclaredHttpConnection(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (HttpURLConnection.class.isAssignableFrom(type)
                    || type.isAssignableFrom(HttpURLConnection.class)) {
                return true;
            }
        }
        return false;
    }

    private void installProviderProbe(Method method, boolean declaredConnection) {
        String id = HOOK_PREFIX + method.toGenericString();
        hookedIds.add(id);

        final String methodName = method.getName();
        final boolean runEntry = "run".equals(methodName);
        final boolean executeEntry =
                methodName.toLowerCase(java.util.Locale.ROOT).contains("execute");
        final boolean headerMethod = "addRequestHeaders".equals(methodName);
        final boolean canReturnVoid = method.getReturnType() == void.class;

        hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    long startedNs = SystemClock.elapsedRealtimeNanos();
                    Object thisObject = chain.getThisObject();
                    Context targetContext = resolveTargetContext(thisObject);
                    java.util.List<Object> args = chain.getArgs();

                    if (runEntry) {
                        ProbeSnapshot snapshot = DownloadInfoInspector.inspect(thisObject);
                        emit(Log.INFO, "download start " + snapshot.toSafeLogString());

                        EngineTelemetry.emit(
                                targetContext,
                                "HOOK_MAP",
                                "hooks=" + providerHookCount
                                        + " httpCandidates=" + providerConnectionCandidateCount
                                        + " deopt=" + providerDeoptimizedCount
                                        + " failed=" + providerDeoptFailedCount
                                        + " :: " + providerHookInventory);
                    }

                    if (executeEntry) {
                        EngineTelemetry.emit(
                                targetContext,
                                "EXECUTE_ENTRY",
                                method.toGenericString()
                                        + " args=[" + describeArgs(args) + "]");
                    }

                    HttpURLConnection connection = findHttpConnection(args);

                    if (connection != null && !headerMethod && canReturnVoid) {
                        String signature = method.toGenericString();
                        EngineTelemetry.emit(
                                targetContext,
                                "HTTP_METHOD_ENTRY",
                                signature + " args=[" + describeArgs(args) + "]");
                        emit(Log.INFO, "HTTP-bearing method entered signature="
                                + signature + " declaredHttp=" + declaredConnection
                                + " args=[" + describeArgs(args) + "]");

                        try {
                            if (SegmentedTransfer.tryAccelerate(thisObject, connection)) {
                                EngineTelemetry.emit(
                                        targetContext,
                                        "INTERCEPTED",
                                        signature);
                                emit(Log.INFO,
                                        "HTTP-bearing method handled by segmented engine");
                                return null;
                            }
                        } catch (Throwable t) {
                            EngineTelemetry.emit(
                                    targetContext,
                                    "ERROR",
                                    "interceptor: " + t.getClass().getSimpleName()
                                            + ": " + String.valueOf(t.getMessage()));
                            emit(Log.WARN,
                                    "segmented engine interceptor failed; native fallback", t);
                        }
                    }

                    try {
                        return chain.proceed();
                    } finally {
                        long elapsedMs =
                                (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L;
                        if (runEntry || executeEntry) {
                            emit(Log.DEBUG,
                                    methodName + " finished in " + elapsedMs + " ms");
                        }
                    }
                });
    }

    private HttpURLConnection findHttpConnection(java.util.List<Object> args) {
        for (Object arg : args) {
            if (arg instanceof HttpURLConnection) {
                return (HttpURLConnection) arg;
            }
        }
        return null;
    }

    private String describeArgs(java.util.List<Object> args) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) out.append(", ");
            Object arg = args.get(i);
            out.append(arg == null ? "null" : arg.getClass().getName());
        }
        return out.toString();
    }

    private Context resolveTargetContext(Object thread) {
        if (thread == null) {
            return null;
        }
        Class<?> cursor = thread.getClass();
        while (cursor != null) {
            try {
                java.lang.reflect.Field field = cursor.getDeclaredField("mContext");
                field.setAccessible(true);
                Object value = field.get(thread);
                return value instanceof Context ? (Context) value : null;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            } catch (Throwable t) {
                emit(Log.DEBUG, "unable to resolve target context", t);
                return null;
            }
        }
        return null;
    }

    private void unhookUnknownOldHandles(HotReloadedParam param) {
        param.getOldHookHandles().forEach(handle -> {
            String id = handle.getId();
            if (id == null || !hookedIds.contains(id)) {
                try {
                    handle.unhook();
                } catch (Throwable t) {
                    emit(Log.WARN, "unable to remove stale hook", t);
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
        emit(Log.INFO, "downloads-ui classes=" + downloadsUiClassCount
                + "/" + DOWNLOADS_UI_CLASSES.length);
        return downloadsUiClassCount;
    }

    private int probeSystemUi(ClassLoader classLoader) {
        if (!systemUiSeen.compareAndSet(false, true)) {
            return systemUiClassCount;
        }

        systemUiClassCount =
                Probe.countAvailable(classLoader, SYSTEM_UI_CLASSES);
        emit(Log.INFO, "systemui classes=" + systemUiClassCount
                + "/" + SYSTEM_UI_CLASSES.length);
        return systemUiClassCount;
    }

    private void emit(int priority, String message) {
        try {
            log(priority, TAG, message);
        } catch (Throwable ignored) {
        }
        try {
            Log.println(priority, TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private void emit(int priority, String message, Throwable throwable) {
        try {
            log(priority, TAG, message, throwable);
        } catch (Throwable ignored) {
        }
        try {
            Log.println(priority, TAG, message + "\n" + Log.getStackTraceString(throwable));
        } catch (Throwable ignored) {
        }
    }
}
