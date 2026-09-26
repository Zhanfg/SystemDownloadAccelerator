package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Application;
import android.content.Context;
import android.net.Network;
import android.os.Build;
import android.util.Log;
import android.util.Pair;

import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * API-102 system adapter.
 *
 * DownloadProvider private transfer methods are intentionally not used as an
 * acceleration entry point. The adapter observes connection creation and
 * replaces only the response InputStream with the independent range core.
 */
public final class AcceleratorModule extends XposedModule {
    private static final String TAG = "SysDlAccel";
    private static final String ID_PREFIX = "sysdl2:";

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

    private final Set<String> hookedIds =
            ConcurrentHashMap.newKeySet();
    private final Set<String> connectionClassHooks =
            ConcurrentHashMap.newKeySet();
    private final Set<String> dynamicFactoryHooks =
            ConcurrentHashMap.newKeySet();
    private final Map<Object, Network> httpEngineNetworks =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private int providerHookCount;
    private int providerDeoptimizedCount;
    private int downloadsUiClassCount;
    private int systemUiClassCount;
    private String processName = "";

    private Pair<Pair<String, String>, ClassLoader> reloadState;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        emit(Log.INFO,
                "module loaded process=" + processName
                        + " sdk=" + Build.VERSION.SDK_INT
                        + " api=" + getApiVersion()
                        + " version=" + BuildConfig.VERSION_CODE);
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
            emit(Log.INFO,
                    "SDK < 36; target left untouched: " + scope.packageName);
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
        if (reloadState != null) {
            param.setSavedInstanceState(reloadState);
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
        }

        if (classLoader == null) {
            classLoader = recoverTargetClassLoader(param);
        }

        processName = savedProcessName == null ? "" : savedProcessName;
        Scope scope = packageName == null
                ? Scope.fromPackage(processName)
                : Scope.fromPackage(packageName);

        if (scope == null || classLoader == null) {
            emit(Log.ERROR,
                    "hot reload recovery failed process=" + processName);
            return;
        }

        reloadState = Pair.create(
                Pair.create(scope.packageName, processName),
                classLoader);

        try {
            resetGenerationState();
            installForScope(scope, classLoader, true);
            unhookUnknownOldHandles(param);
            emit(Log.INFO,
                    "hot reload complete scope=" + scope.packageName);
        } catch (Throwable t) {
            emit(Log.ERROR,
                    "hot reload failed scope=" + scope.packageName, t);
        }
    }

    private void resetGenerationState() {
        providerInstalled.set(false);
        downloadsUiSeen.set(false);
        systemUiSeen.set(false);
        providerHookCount = 0;
        providerDeoptimizedCount = 0;
        downloadsUiClassCount = 0;
        systemUiClassCount = 0;
        hookedIds.clear();
        connectionClassHooks.clear();
        dynamicFactoryHooks.clear();
        httpEngineNetworks.clear();
    }

    private ClassLoader recoverTargetClassLoader(
            HotReloadedParam param) {
        try {
            for (XposedInterface.HookHandle handle
                    : param.getOldHookHandles()) {
                Executable executable = handle.getExecutable();
                if (executable != null
                        && executable.getDeclaringClass() != null) {
                    ClassLoader loader =
                            executable.getDeclaringClass().getClassLoader();
                    if (loader != null) {
                        return loader;
                    }
                }
            }
        } catch (Throwable t) {
            emit(Log.WARN,
                    "old-hook ClassLoader recovery failed", t);
        }
        return Thread.currentThread().getContextClassLoader();
    }

    private void installForScope(
            Scope scope,
            ClassLoader classLoader,
            boolean hotReload) {
        int evidence;
        switch (scope) {
            case PROVIDER:
                evidence = installProviderAdapter(classLoader);
                break;
            case DOWNLOADS_UI:
                evidence = probeDownloadsUi(classLoader);
                break;
            case SYSTEM_UI:
                evidence = probeSystemUi(classLoader);
                break;
            default:
                evidence = 0;
        }

        emit(Log.INFO,
                "scope ready scope=" + scope.packageName
                        + " evidence=" + evidence
                        + " hotReload=" + hotReload);
    }

    private int installProviderAdapter(ClassLoader classLoader) {
        if (!providerInstalled.compareAndSet(false, true)) {
            return providerHookCount;
        }

        Context processContext = resolveProcessContext();
        ConnectionRegistry.initialize(processContext);
        EngineTelemetry.emit(
                processContext,
                "ADAPTER_INSTALL",
                "process=" + processName
                        + " version=" + BuildConfig.VERSION_CODE);

        try {
            Class<?> downloadThread =
                    classLoader.loadClass(
                            "com.android.providers.downloads.DownloadThread");

            providerDeoptimizedCount =
                    deoptimizeDownloadThread(downloadThread);

            int runHooks = 0;
            for (Method method : downloadThread.getDeclaredMethods()) {
                if (!"run".equals(method.getName())
                        || method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    installRunContextHook(method);
                    runHooks++;
                } catch (Throwable t) {
                    emit(Log.WARN,
                            "run hook install failed "
                                    + method.toGenericString(),
                            t);
                    EngineTelemetry.emit(
                            processContext,
                            "HOOK_FAIL",
                            "DownloadThread.run: "
                                    + t.getClass().getSimpleName()
                                    + ": "
                                    + String.valueOf(t.getMessage()));
                }
            }

            int networkHooks =
                    installNetworkOpenHooks(processContext);
            int urlHooks =
                    installUrlOpenHooks(processContext);
            int httpEngineHooks =
                    installPlatformHttpEngineHooks(processContext);

            providerHookCount =
                    runHooks + networkHooks + urlHooks + httpEngineHooks;

            String summary =
                    "hooks=" + providerHookCount
                            + " run=" + runHooks
                            + " network=" + networkHooks
                            + " url=" + urlHooks
                            + " httpEngine=" + httpEngineHooks
                            + " deopt=" + providerDeoptimizedCount;

            emit(Log.INFO, "provider adapter installed " + summary);
            EngineTelemetry.emit(
                    processContext,
                    "ADAPTER_READY",
                    summary);
            return providerHookCount;
        } catch (Throwable t) {
            providerInstalled.set(false);
            providerHookCount = 0;
            emit(Log.ERROR,
                    "provider adapter install failed; native path preserved",
                    t);
            EngineTelemetry.emit(
                    processContext,
                    "ADAPTER_ERROR",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
            return 0;
        }
    }

    private int deoptimizeDownloadThread(Class<?> type) {
        int success = 0;
        for (Method method : type.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isAbstract(modifiers)
                    || Modifier.isNative(modifiers)) {
                continue;
            }
            try {
                method.setAccessible(true);
                if (deoptimize(method)) {
                    success++;
                }
            } catch (Throwable ignored) {
            }
        }
        return success;
    }

    private void installRunContextHook(Method method) {
        String id = ID_PREFIX + "run:" + method.toGenericString();
        if (!hookedIds.add(id)) {
            return;
        }

        hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object thread = chain.getThisObject();
                    ConnectionRegistry.ProviderExecution execution =
                            resolveProviderExecution(thread);

                    if (execution != null) {
                        ConnectionRegistry.enterProviderExecution(execution);
                        installHttpEngineFactoryHook(thread, execution);
                        EngineTelemetry.emit(
                                execution.context,
                                "CORE_ARMED",
                                "AB-style stream adapter armed");
                    }

                    try {
                        return chain.proceed();
                    } finally {
                        ConnectionRegistry.leaveProviderExecution();
                    }
                });
    }

    private int installNetworkOpenHooks(Context processContext) {
        int count = 0;
        for (Method method : Network.class.getDeclaredMethods()) {
            if (!"openConnection".equals(method.getName())) {
                continue;
            }

            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1 || params[0] != URL.class) {
                continue;
            }

            try {
                method.setAccessible(true);
                String id =
                        ID_PREFIX + "network:" + method.toGenericString();
                if (!hookedIds.add(id)) {
                    continue;
                }

                hook(method)
                        .setId(id)
                        .setExceptionMode(
                                XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (!ConnectionRegistry.isInternalRequest()
                                    && result instanceof HttpURLConnection
                                    && chain.getThisObject() instanceof Network) {
                                observeConnection(
                                        (HttpURLConnection) result,
                                        (Network) chain.getThisObject());
                            }
                            return result;
                        });
                count++;
            } catch (Throwable t) {
                emit(Log.WARN,
                        "Network.openConnection hook failed "
                                + method.toGenericString(),
                        t);
                EngineTelemetry.emit(
                        processContext,
                        "HOOK_FAIL",
                        "Network.openConnection: "
                                + t.getClass().getSimpleName()
                                + ": "
                                + String.valueOf(t.getMessage()));
            }
        }
        return count;
    }

    private int installUrlOpenHooks(Context processContext) {
        int count = 0;
        for (Method method : URL.class.getDeclaredMethods()) {
            if (!"openConnection".equals(method.getName())) {
                continue;
            }

            try {
                method.setAccessible(true);
                String id =
                        ID_PREFIX + "url:" + method.toGenericString();
                if (!hookedIds.add(id)) {
                    continue;
                }

                hook(method)
                        .setId(id)
                        .setExceptionMode(
                                XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (!ConnectionRegistry.isInternalRequest()
                                    && result instanceof HttpURLConnection) {
                                observeConnection(
                                        (HttpURLConnection) result,
                                        null);
                            }
                            return result;
                        });
                count++;
            } catch (Throwable t) {
                emit(Log.WARN,
                        "URL.openConnection hook failed "
                                + method.toGenericString(),
                        t);
                EngineTelemetry.emit(
                        processContext,
                        "HOOK_FAIL",
                        "URL.openConnection: "
                                + t.getClass().getSimpleName()
                                + ": "
                                + String.valueOf(t.getMessage()));
            }
        }
        return count;
    }

    private int installPlatformHttpEngineHooks(Context processContext) {
        Class<?> engineClass;
        try {
            engineClass = Class.forName("android.net.http.HttpEngine");
        } catch (Throwable unavailable) {
            EngineTelemetry.emit(
                    processContext,
                    "HTTPENGINE",
                    "platform HttpEngine class unavailable");
            return 0;
        }

        int count = 0;
        for (Method method : engineClass.getMethods()) {
            String name = method.getName();

            if ("bindToNetwork".equals(name)
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Network.class) {
                try {
                    method.setAccessible(true);
                    String id =
                            ID_PREFIX + "httpengine-bind:"
                                    + method.toGenericString();
                    if (hookedIds.add(id)) {
                        hook(method)
                                .setId(id)
                                .setExceptionMode(
                                        XposedInterface.ExceptionMode.PROTECTIVE)
                                .intercept(chain -> {
                                    Object result = chain.proceed();
                                    java.util.List<Object> args =
                                            chain.getArgs();
                                    if (!args.isEmpty()
                                            && args.get(0) instanceof Network) {
                                        httpEngineNetworks.put(
                                                chain.getThisObject(),
                                                (Network) args.get(0));
                                    }
                                    return result;
                                });
                        count++;
                    }
                } catch (Throwable t) {
                    EngineTelemetry.emit(
                            processContext,
                            "HOOK_FAIL",
                            "HttpEngine.bindToNetwork: "
                                    + t.getClass().getSimpleName()
                                    + ": "
                                    + String.valueOf(t.getMessage()));
                }
                continue;
            }

            if (!"openConnection".equals(name)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1 || params[0] != URL.class) {
                continue;
            }

            try {
                method.setAccessible(true);
                String id =
                        ID_PREFIX + "httpengine-open:"
                                + method.toGenericString();
                if (!hookedIds.add(id)) {
                    continue;
                }

                hook(method)
                        .setId(id)
                        .setExceptionMode(
                                XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (!ConnectionRegistry.isInternalRequest()
                                    && result instanceof HttpURLConnection) {
                                Network network =
                                        httpEngineNetworks.get(
                                                chain.getThisObject());
                                observeConnection(
                                        (HttpURLConnection) result,
                                        network);
                            }
                            return result;
                        });
                count++;
            } catch (Throwable t) {
                EngineTelemetry.emit(
                        processContext,
                        "HOOK_FAIL",
                        "HttpEngine.openConnection: "
                                + t.getClass().getSimpleName()
                                + ": "
                                + String.valueOf(t.getMessage()));
            }
        }

        EngineTelemetry.emit(
                processContext,
                "HTTPENGINE",
                "platform hooks=" + count);
        return count;
    }

    private void installHttpEngineFactoryHook(
            Object downloadThread,
            ConnectionRegistry.ProviderExecution execution) {
        Object engine = getFieldQuietly(downloadThread, "mHttpEngine");
        if (engine == null) {
            return;
        }

        for (Method method : engine.getClass().getMethods()) {
            if (!"openConnection".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1
                    || params[0] != URL.class) {
                continue;
            }

            String key =
                    engine.getClass().getName()
                            + "#"
                            + method.toGenericString();
            if (!dynamicFactoryHooks.add(key)) {
                continue;
            }

            method.setAccessible(true);
            String id = ID_PREFIX + "engine:" + key;
            hookedIds.add(id);

            hook(method)
                    .setId(id)
                    .setExceptionMode(
                            XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!ConnectionRegistry.isInternalRequest()
                                && result instanceof HttpURLConnection) {
                            observeConnection(
                                    (HttpURLConnection) result,
                                    execution.network);
                        }
                        return result;
                    });
        }
    }

    private void observeConnection(
            HttpURLConnection connection,
            Network network) {
        ConnectionRegistry.Metadata metadata =
                ConnectionRegistry.register(connection, network);
        ensureConnectionClassHooks(connection.getClass());

        EngineTelemetry.emit(
                metadata.context,
                "CONNECTION",
                connection.getClass().getName()
                        + " url=" + safeUrl(connection.getURL()));
    }

    private void ensureConnectionClassHooks(Class<?> concreteClass) {
        hookConnectionMethod(
                concreteClass,
                "setRequestProperty",
                new Class<?>[]{String.class, String.class},
                HookKind.SET_HEADER);
        hookConnectionMethod(
                concreteClass,
                "addRequestProperty",
                new Class<?>[]{String.class, String.class},
                HookKind.ADD_HEADER);
        hookConnectionMethod(
                concreteClass,
                "getInputStream",
                new Class<?>[0],
                HookKind.GET_STREAM);
    }

    private enum HookKind {
        SET_HEADER,
        ADD_HEADER,
        GET_STREAM
    }

    private void hookConnectionMethod(
            Class<?> concreteClass,
            String name,
            Class<?>[] params,
            HookKind kind) {
        Method method;
        try {
            method = concreteClass.getMethod(name, params);
            method.setAccessible(true);
        } catch (Throwable t) {
            emit(Log.DEBUG,
                    "connection method unavailable "
                            + concreteClass.getName()
                            + "#"
                            + name);
            return;
        }

        String key =
                method.getDeclaringClass().getName()
                        + "#"
                        + method.toGenericString()
                        + ":"
                        + kind;
        if (!connectionClassHooks.add(key)) {
            return;
        }

        String id = ID_PREFIX + "conn:" + key;
        hookedIds.add(id);

        hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object thisObject = chain.getThisObject();
                    if (!(thisObject instanceof HttpURLConnection)) {
                        return chain.proceed();
                    }

                    HttpURLConnection connection =
                            (HttpURLConnection) thisObject;

                    if (kind == HookKind.GET_STREAM) {
                        if (ConnectionRegistry.isInternalRequest()) {
                            return chain.proceed();
                        }

                        ConnectionRegistry.Metadata metadata =
                                ConnectionRegistry.get(connection);
                        if (metadata == null) {
                            return chain.proceed();
                        }

                        InputStream parallel =
                                ParallelRangeEngine.maybeCreate(
                                        connection,
                                        metadata);
                        if (parallel != null) {
                            EngineTelemetry.emit(
                                    metadata.context,
                                    "STREAM_REPLACED",
                                    "native response body replaced");
                            return parallel;
                        }
                        return chain.proceed();
                    }

                    Object result = chain.proceed();
                    if (!ConnectionRegistry.isInternalRequest()) {
                        java.util.List<Object> args = chain.getArgs();
                        if (args.size() >= 2
                                && args.get(0) instanceof String
                                && args.get(1) instanceof String) {
                            if (kind == HookKind.SET_HEADER) {
                                ConnectionRegistry.recordSet(
                                        connection,
                                        (String) args.get(0),
                                        (String) args.get(1));
                            } else {
                                ConnectionRegistry.recordAdd(
                                        connection,
                                        (String) args.get(0),
                                        (String) args.get(1));
                            }
                        }
                    }
                    return result;
                });
    }

    private Context resolveProcessContext() {
        try {
            Class<?> activityThread =
                    Class.forName("android.app.ActivityThread");
            Method currentApplication =
                    activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> appGlobals =
                    Class.forName("android.app.AppGlobals");
            Method initial =
                    appGlobals.getDeclaredMethod("getInitialApplication");
            initial.setAccessible(true);
            Object app = initial.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }

        return ConnectionRegistry.processContext();
    }

    private ConnectionRegistry.ProviderExecution resolveProviderExecution(
            Object thread) {
        if (thread == null) return null;

        Context context =
                (Context) getFieldQuietly(thread, "mContext");
        Network network =
                (Network) getFieldQuietly(thread, "mNetwork");
        Object info =
                getFieldQuietly(thread, "mInfo");
        int uid = getIntFieldQuietly(info, "mUid", -1);

        if (context == null) {
            return null;
        }

        return new ConnectionRegistry.ProviderExecution(
                context,
                network,
                uid);
    }

    private Object getFieldQuietly(
            Object target,
            String name) {
        if (target == null) return null;

        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                cursor = cursor.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private int getIntFieldQuietly(
            Object target,
            String name,
            int fallback) {
        if (target == null) return fallback;

        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(target);
            } catch (NoSuchFieldException e) {
                cursor = cursor.getSuperclass();
            } catch (Throwable t) {
                return fallback;
            }
        }
        return fallback;
    }

    private void unhookUnknownOldHandles(
            HotReloadedParam param) {
        param.getOldHookHandles().forEach(handle -> {
            String id = handle.getId();
            if (id == null || !hookedIds.contains(id)) {
                try {
                    handle.unhook();
                } catch (Throwable t) {
                    emit(Log.WARN,
                            "unable to remove stale hook", t);
                }
            }
        });
    }

    private int probeDownloadsUi(ClassLoader classLoader) {
        if (!downloadsUiSeen.compareAndSet(false, true)) {
            return downloadsUiClassCount;
        }

        downloadsUiClassCount =
                Probe.countAvailable(
                        classLoader,
                        DOWNLOADS_UI_CLASSES);
        return downloadsUiClassCount;
    }

    private int probeSystemUi(ClassLoader classLoader) {
        if (!systemUiSeen.compareAndSet(false, true)) {
            return systemUiClassCount;
        }

        systemUiClassCount =
                Probe.countAvailable(
                        classLoader,
                        SYSTEM_UI_CLASSES);
        return systemUiClassCount;
    }

    private static String safeUrl(URL url) {
        if (url == null) return "(null)";
        String protocol = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort();

        StringBuilder out = new StringBuilder();
        if (protocol != null) {
            out.append(protocol).append("://");
        }
        if (host != null) {
            out.append(host);
        }
        if (port >= 0) {
            out.append(":").append(port);
        }
        out.append("/…");
        return out.toString();
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

    private void emit(
            int priority,
            String message,
            Throwable throwable) {
        try {
            log(priority, TAG, message, throwable);
        } catch (Throwable ignored) {
        }
        try {
            Log.println(
                    priority,
                    TAG,
                    message + "\n"
                            + Log.getStackTraceString(throwable));
        } catch (Throwable ignored) {
        }
    }
}
