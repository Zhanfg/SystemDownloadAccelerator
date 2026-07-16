package io.github.zhanfg.sda.domo;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.SystemClock;

import java.lang.reflect.Method;

import de.robv.android.xposed.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class DomoProbeHook implements IXposedHookLoadPackage {
    static final String DOWNLOAD_PACKAGE = "com.android.providers.downloads";
    static final String MODULE_PACKAGE = "io.github.zhanfg.sda.domo";
    static final String RECEIVER_CLASS = MODULE_PACKAGE + ".ProbeResultReceiver";
    static final String ACTION_MATCH = MODULE_PACKAGE + ".ACTION_NOTIFICATION_MATCH";
    static final String LOG_PREFIX = "[SDA-DOMO] ";

    private static volatile boolean installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!DOWNLOAD_PACKAGE.equals(lpparam.packageName) || installed) {
            return;
        }
        installed = true;

        int hookCount = 0;
        for (Method method : NotificationManager.class.getDeclaredMethods()) {
            if (!"notify".equals(method.getName()) || !containsNotification(method)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        onNotify(param.args);
                    }
                });
                hookCount++;
            } catch (Throwable throwable) {
                XposedBridge.log(LOG_PREFIX + "hook failed: " + method + " :: " + throwable);
            }
        }
        XposedBridge.log(LOG_PREFIX + "HOOK_INSTALLED count=" + hookCount);
    }

    private static boolean containsNotification(Method method) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (Notification.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }
        return false;
    }

    private static void onNotify(Object[] args) {
        Notification notification = null;
        String tag = null;
        int id = 0;

        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Notification) {
                    notification = (Notification) arg;
                } else if (arg instanceof String && tag == null) {
                    tag = (String) arg;
                } else if (arg instanceof Integer) {
                    id = (Integer) arg;
                }
            }
        }
        if (notification == null) {
            return;
        }

        String channel = notification.getChannelId();
        boolean active = "active".equals(channel);
        long elapsed = SystemClock.elapsedRealtime();
        XposedBridge.log(LOG_PREFIX + "NOTIFICATION_MATCHED tag=" + tag
                + " id=" + id + " channel=" + channel + " active=" + active);

        try {
            Application application = AndroidAppHelper.currentApplication();
            if (application == null) {
                XposedBridge.log(LOG_PREFIX + "broadcast skipped: currentApplication=null");
                return;
            }
            Intent result = new Intent(ACTION_MATCH);
            result.setClassName(MODULE_PACKAGE, RECEIVER_CLASS);
            result.putExtra("tag", tag == null ? "<null>" : tag);
            result.putExtra("id", id);
            result.putExtra("channel", channel == null ? "<null>" : channel);
            result.putExtra("active", active);
            result.putExtra("elapsed", elapsed);
            result.putExtra("process", DOWNLOAD_PACKAGE);
            application.sendBroadcast(result);
        } catch (Throwable throwable) {
            XposedBridge.log(LOG_PREFIX + "broadcast failed: " + throwable);
        }
    }
}
