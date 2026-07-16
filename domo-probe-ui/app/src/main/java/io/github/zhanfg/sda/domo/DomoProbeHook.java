package io.github.zhanfg.sda.domo;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;

import de.robv.android.xposed.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class DomoProbeHook implements IXposedHookLoadPackage {
    static final String DOWNLOAD_PACKAGE = "com.android.providers.downloads";
    static final String LOG_PREFIX = "[SDA-DOMO] ";
    private static final Uri PROVIDER_URI = Uri.parse("content://" + ProbeProvider.AUTHORITY);

    private static volatile boolean installed;
    private static volatile int installedHookCount;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!DOWNLOAD_PACKAGE.equals(lpparam.packageName) || installed) {
            return;
        }
        installed = true;

        int hookCount = 0;
        hookCount += hookNotificationBoundary(NotificationManager.class, "NotificationManager");

        try {
            Class<?> proxyClass = Class.forName(
                    "android.app.INotificationManager$Stub$Proxy",
                    false,
                    lpparam.classLoader);
            hookCount += hookNotificationBoundary(proxyClass, "INotificationManager.Proxy");
        } catch (Throwable throwable) {
            XposedBridge.log(LOG_PREFIX + "binder fallback unavailable: " + throwable);
        }

        installedHookCount = hookCount;
        hookApplicationAttach();
        XposedBridge.log(LOG_PREFIX + "HOOK_INSTALLED count=" + hookCount);

        Application application = AndroidAppHelper.currentApplication();
        if (application != null) {
            reportReady(application, null);
        }
    }

    private static int hookNotificationBoundary(Class<?> owner, String source) {
        int count = 0;
        for (Method method : owner.getDeclaredMethods()) {
            String name = method.getName();
            boolean candidate = "notify".equals(name)
                    || "notifyAsUser".equals(name)
                    || "enqueueNotificationWithTag".equals(name);
            if (!candidate || !containsNotification(method)) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        onNotify(param.args, source + "." + method.getName());
                    }
                });
                count++;
            } catch (Throwable throwable) {
                XposedBridge.log(LOG_PREFIX + "hook failed: " + method + " :: " + throwable);
            }
        }
        return count;
    }

    private static void hookApplicationAttach() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Application) {
                        reportReady((Application) param.thisObject, null);
                    }
                }
            });
        } catch (Throwable throwable) {
            XposedBridge.log(LOG_PREFIX + "Application.attach hook failed: " + throwable);
            Application application = AndroidAppHelper.currentApplication();
            if (application != null) {
                reportReady(application, throwable.toString());
            }
        }
    }

    private static boolean containsNotification(Method method) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (Notification.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }
        return false;
    }

    private static void onNotify(Object[] args, String source) {
        Notification notification = null;
        int notificationIndex = -1;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Notification) {
                    notification = (Notification) args[i];
                    notificationIndex = i;
                    break;
                }
            }
        }
        if (notification == null) {
            return;
        }

        int id = 0;
        int idIndex = -1;
        for (int i = notificationIndex - 1; i >= 0; i--) {
            if (args[i] instanceof Integer) {
                id = (Integer) args[i];
                idIndex = i;
                break;
            }
        }

        String tag = null;
        int stringSearchFrom = idIndex >= 0 ? idIndex - 1 : notificationIndex - 1;
        for (int i = stringSearchFrom; i >= 0; i--) {
            if (args[i] instanceof String) {
                tag = (String) args[i];
                break;
            }
        }

        String channel = notification.getChannelId();
        boolean active = "active".equals(channel);
        XposedBridge.log(LOG_PREFIX + "NOTIFICATION_MATCHED source=" + source
                + " tag=" + tag + " id=" + id + " channel=" + channel
                + " active=" + active);

        Application application = AndroidAppHelper.currentApplication();
        if (application == null) {
            XposedBridge.log(LOG_PREFIX + "result skipped: currentApplication=null");
            return;
        }

        Bundle extras = new Bundle();
        extras.putString("tag", tag == null ? "<null>" : tag);
        extras.putInt("id", id);
        extras.putString("channel", channel == null ? "<null>" : channel);
        extras.putBoolean("active", active);
        extras.putString("source", source);
        report(application, "notification", extras);
    }

    private static void reportReady(Application application, String error) {
        Bundle extras = new Bundle();
        extras.putInt("hook_count", installedHookCount);
        extras.putString("process", DOWNLOAD_PACKAGE);
        if (error != null) {
            extras.putString("error", error);
        }
        report(application, "ready", extras);
    }

    private static void report(Application application, String method, Bundle extras) {
        try {
            Bundle result = application.getContentResolver().call(PROVIDER_URI, method, null, extras);
            boolean ok = result != null && result.getBoolean("ok", false);
            XposedBridge.log(LOG_PREFIX + "provider report method=" + method + " ok=" + ok);
        } catch (Throwable throwable) {
            XposedBridge.log(LOG_PREFIX + "provider report failed method=" + method + ": " + throwable);
        }
    }
}
