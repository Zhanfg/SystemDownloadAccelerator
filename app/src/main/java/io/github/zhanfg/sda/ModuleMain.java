package io.github.zhanfg.sda;

import android.util.Log;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private static final String TAG = "SysDownloadAccel";
    private final Set<String> hooked = new HashSet<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in " + param.getProcessName() + ", API " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.android.providers.downloads".equals(param.getPackageName())) return;
        hookTransferMethod(param, "com.android.providers.downloads.d");
        hookTransferMethod(param, "com.android.providers.downloads.e");
    }

    private void hookTransferMethod(PackageReadyParam param, String className) {
        try {
            Class<?> target = Class.forName(className, false, param.getClassLoader());
            for (Method method : target.getDeclaredMethods()) {
                if (!"u".equals(method.getName())) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1
                        || !HttpURLConnection.class.isAssignableFrom(parameters[0])) {
                    continue;
                }
                String signature = className + "#" + method.toGenericString();
                synchronized (hooked) {
                    if (!hooked.add(signature)) return;
                }
                method.setAccessible(true);
                hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    log(Log.INFO, TAG, "Download transfer intercepted: " + className + ".u");
                    // Alpha safety mode: preserve both the original execution and return value.
                    // A ranged-transfer coordinator must not replace this path until device tests pass.
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "Hook installed: " + signature);
                return;
            }
            log(Log.WARN, TAG, "Compatible transfer method not found in " + className);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Hook failed for " + className, error);
        }
    }
}
