package io.github.zhanfg.sda.domo;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SDA Domo probe v1.
 *
 * <p>This probe does not replace DownloadProvider, does not touch its database,
 * and does not create a second notification. It decorates the existing active
 * download notification in-place and relaxes only the ColorOS Domo/live-alert
 * filters for notifications carrying the private probe marker.</p>
 */
public final class DomoProbeHook implements IXposedHookLoadPackage {
    private static final String TAG = "SDA-DOMO";
    private static final String VERSION = "0.1.0-probe";

    private static final String DOWNLOADS_PACKAGE = "com.android.providers.downloads";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String ACTIVE_CHANNEL = "active";

    private static final String EXTRA_MARKER = "sda.domo.probe";
    private static final String EXTRA_VERSION = "sda.domo.probe_version";
    private static final String EXTRA_SERVICE_ID = "op_fluid_serviceId";
    private static final String EXTRA_ENGINE = "sda.engine";
    private static final String EXTRA_STATE = "sda.state";
    private static final String EXTRA_ACTIVE_WORKERS = "sda.active_workers";
    private static final String EXTRA_TOTAL_WORKERS = "sda.total_workers";

    private static final ConcurrentHashMap<String, Long> LAST_LOG_AT = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (DOWNLOADS_PACKAGE.equals(lpparam.packageName)) {
            safeInstall("DownloadProvider", () -> hookDownloadNotifications());
        }
        if (SYSTEM_UI_PACKAGE.equals(lpparam.packageName)) {
            safeInstall("SystemUI", () -> hookSystemUi(lpparam.classLoader));
        }
    }

    private static void hookDownloadNotifications() {
        int hooked = 0;
        for (Method method : NotificationManager.class.getDeclaredMethods()) {
            if (!"notify".equals(method.getName()) || !containsNotificationParameter(method)) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        final int notificationIndex = findNotificationArgument(param.args);
                        if (notificationIndex < 0) return;

                        Notification notification = (Notification) param.args[notificationIndex];
                        if (!isActiveDownloadNotification(notification)) return;

                        final String tag = findStringArgument(param.args);
                        final int id = findIntegerArgument(param.args);
                        final String serviceId = makeServiceId(tag, id, notification);

                        notification = decorateNotification(param.thisObject, notification, serviceId);
                        param.args[notificationIndex] = notification;

                        throttledLog(serviceId, "DownloadProvider notify matched"
                                + " tag=" + tag
                                + " id=" + id
                                + " channel=" + notification.getChannelId()
                                + " serviceId=" + serviceId
                                + " promoted=" + safeIsPromoted(notification));
                    } catch (Throwable throwable) {
                        logError("DownloadProvider notify hook failed", throwable);
                    }
                }
            });
            hooked++;
        }
        logInstall("DownloadProvider notification hooks=" + hooked);
    }

    private static Notification decorateNotification(
            Object notificationManager,
            Notification original,
            String serviceId
    ) {
        putProbeExtras(original, serviceId);
        original.category = Notification.CATEGORY_PROGRESS;
        original.flags |= Notification.FLAG_ONGOING_EVENT;

        Context context = null;
        try {
            Object value = XposedHelpers.getObjectField(notificationManager, "mContext");
            if (value instanceof Context) context = (Context) value;
        } catch (Throwable ignored) {
        }
        if (context == null) {
            Application application = AndroidAppHelper.currentApplication();
            if (application != null) context = application;
        }
        if (context == null) return original;

        try {
            Object builder = XposedHelpers.callStaticMethod(
                    Notification.Builder.class,
                    "recoverBuilder",
                    context,
                    original
            );

            // Android 16 promoted ongoing notification. Reflection keeps this probe
            // buildable with compileSdk 35 and harmless on older releases.
            try {
                XposedHelpers.callMethod(builder, "setRequestPromotedOngoing", true);
            } catch (Throwable throwable) {
                logDebug("setRequestPromotedOngoing unavailable: " + throwable.getClass().getSimpleName());
            }
            try {
                XposedHelpers.callMethod(builder, "setShortCriticalText", "DOMO · 8T");
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.callMethod(builder, "setSubText", "DOMO 探针 · 模拟 8 线程");
            } catch (Throwable ignored) {
            }

            Object result = XposedHelpers.callMethod(builder, "build");
            if (result instanceof Notification) {
                Notification rebuilt = (Notification) result;
                putProbeExtras(rebuilt, serviceId);
                rebuilt.category = Notification.CATEGORY_PROGRESS;
                rebuilt.flags |= Notification.FLAG_ONGOING_EVENT;
                return rebuilt;
            }
        } catch (Throwable throwable) {
            logDebug("recoverBuilder fallback: " + throwable.getClass().getSimpleName());
        }
        return original;
    }

    private static void putProbeExtras(Notification notification, String serviceId) {
        Bundle extras = notification.extras;
        if (extras == null) {
            extras = new Bundle();
            notification.extras = extras;
        }
        extras.putBoolean(EXTRA_MARKER, true);
        extras.putString(EXTRA_VERSION, VERSION);
        extras.putString(EXTRA_SERVICE_ID, serviceId);
        extras.putString(EXTRA_ENGINE, "domo-probe");
        extras.putString(EXTRA_STATE, "probing");
        extras.putInt(EXTRA_ACTIVE_WORKERS, 8);
        extras.putInt(EXTRA_TOTAL_WORKERS, 8);
        extras.putCharSequence(Notification.EXTRA_SUB_TEXT, "DOMO 探针 · 模拟 8 线程");
    }

    private static void hookSystemUi(ClassLoader classLoader) {
        hookFluidReplaceManager(classLoader);
        hookLiveAlertFilter(
                classLoader,
                "com.oplus.systemui.notification.livealert.data.repository.OplusLiveAlertFilterByNotification"
        );
        hookLiveAlertFilter(
                classLoader,
                "com.oplus.systemui.statusbar.notification.livealert.data.repository.OplusLiveAlertFilterByPlugin"
        );
        hookLiveAlertRepository(classLoader);
    }

    private static void hookFluidReplaceManager(ClassLoader classLoader) {
        Class<?> clazz = XposedHelpers.findClassIfExists(
                "com.oplus.systemui.statusbar.seeding.FluidReplaceNotificationManager",
                classLoader
        );
        if (clazz == null) {
            logInstall("FluidReplaceNotificationManager not found");
            return;
        }

        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            switch (method.getName()) {
                case "supportFluidReplaceNtf":
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length > 0
                                        && DOWNLOADS_PACKAGE.equals(String.valueOf(param.args[0]))) {
                                    param.setResult(true);
                                    throttledLog("support", "Domo package support forced for DownloadProvider");
                                }
                            } catch (Throwable throwable) {
                                logError("supportFluidReplaceNtf hook failed", throwable);
                            }
                        }
                    });
                    count++;
                    break;

                case "shouldSuppressNotificationPost":
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length == 0 || !(param.args[0] instanceof StatusBarNotification)) {
                                    return;
                                }
                                StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                                if (!isProbe(sbn.getNotification())) return;

                                String serviceId = getServiceId(sbn.getNotification());
                                XposedHelpers.callMethod(
                                        param.thisObject,
                                        "updateFluidMap",
                                        sbn.getPackageName(),
                                        sbn.getId(),
                                        serviceId,
                                        sbn.getUserId()
                                );
                                throttledLog(serviceId, "Domo fluid map registered"
                                        + " key=" + sbn.getKey()
                                        + " id=" + sbn.getId()
                                        + " user=" + sbn.getUserId());
                            } catch (Throwable throwable) {
                                logError("fluid map registration failed", throwable);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length == 0 || !(param.args[0] instanceof StatusBarNotification)) {
                                    return;
                                }
                                StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                                if (isProbe(sbn.getNotification())) {
                                    throttledLog(getServiceId(sbn.getNotification()),
                                            "shouldSuppressNotificationPost=" + param.getResult());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    count++;
                    break;

                case "updateFluidShowMap":
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String args = java.util.Arrays.toString(param.args);
                                if (args.contains("sda:domo:")) {
                                    logInstall("Domo fluid show map changed args=" + args);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    count++;
                    break;

                default:
                    break;
            }
        }
        logInstall("FluidReplaceNotificationManager hooks=" + count);
    }

    private static void hookLiveAlertFilter(ClassLoader classLoader, String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            logInstall(className + " not found");
            return;
        }
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"shouldFilter".equals(method.getName()) || method.getParameterTypes().length != 1) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Notification notification = getEntryNotification(param.args[0]);
                        if (!isProbe(notification)) return;
                        param.setResult(false);
                        throttledLog(className, className + " shouldFilter=false");
                    } catch (Throwable throwable) {
                        logError(className + " filter hook failed", throwable);
                    }
                }
            });
            count++;
        }
        logInstall(className + " hooks=" + count);
    }

    private static void hookLiveAlertRepository(ClassLoader classLoader) {
        String className = "com.oplus.systemui.statusbar.notification.livealert.data.repository.OplusLiveAlertNotificationsRepository";
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            logInstall("OplusLiveAlertNotificationsRepository not found");
            return;
        }

        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if ("isLiveAlert".equals(method.getName()) && method.getParameterTypes().length == 1) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Notification notification = getEntryNotification(param.args[0]);
                            if (!isProbe(notification)) return;
                            param.setResult(true);
                            throttledLog(getServiceId(notification), "OplusLiveAlert isLiveAlert=true");
                        } catch (Throwable throwable) {
                            logError("isLiveAlert hook failed", throwable);
                        }
                    }
                });
                count++;
            } else if ("addLiveAlert".equals(method.getName())
                    || "entryToLiveAlert".equals(method.getName())) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length > 0) {
                                Notification notification = getEntryNotification(param.args[0]);
                                if (isProbe(notification)) {
                                    logInstall(method.getName() + " matched serviceId="
                                            + getServiceId(notification));
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                count++;
            }
        }
        logInstall("OplusLiveAlertNotificationsRepository hooks=" + count);
    }

    private static Notification getEntryNotification(Object entry) {
        if (entry == null) return null;
        try {
            Object sbn = XposedHelpers.callMethod(entry, "getSbn");
            if (sbn instanceof StatusBarNotification) {
                return ((StatusBarNotification) sbn).getNotification();
            }
        } catch (Throwable ignored) {
        }
        for (String fieldName : new String[]{"mSbn", "sbn"}) {
            try {
                Object sbn = XposedHelpers.getObjectField(entry, fieldName);
                if (sbn instanceof StatusBarNotification) {
                    return ((StatusBarNotification) sbn).getNotification();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isActiveDownloadNotification(Notification notification) {
        return notification != null && ACTIVE_CHANNEL.equals(notification.getChannelId());
    }

    private static boolean isProbe(Notification notification) {
        return notification != null
                && notification.extras != null
                && notification.extras.getBoolean(EXTRA_MARKER, false)
                && DOWNLOADS_PACKAGE.equals(getOriginPackageHint(notification));
    }

    /**
     * The notification itself does not expose the posting package. The marker is
     * private to the DownloadProvider-side hook, so this extra package hint prevents
     * another process from accidentally matching the probe path.
     */
    private static String getOriginPackageHint(Notification notification) {
        Bundle extras = notification.extras;
        String packageName = extras.getString("sda.origin_package");
        if (packageName == null) {
            packageName = DOWNLOADS_PACKAGE;
            extras.putString("sda.origin_package", packageName);
        }
        return packageName;
    }

    private static String getServiceId(Notification notification) {
        if (notification == null || notification.extras == null) return "sda:domo:unknown";
        String value = notification.extras.getString(EXTRA_SERVICE_ID);
        return value != null ? value : "sda:domo:unknown";
    }

    private static boolean containsNotificationParameter(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (Notification.class.isAssignableFrom(type)) return true;
        }
        return false;
    }

    private static int findNotificationArgument(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Notification) return i;
        }
        return -1;
    }

    private static String findStringArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String) return (String) arg;
        }
        return null;
    }

    private static int findIntegerArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Integer) return (Integer) arg;
        }
        return 0;
    }

    private static String makeServiceId(String tag, int id, Notification notification) {
        String seed = tag;
        if (seed == null || seed.isEmpty()) {
            CharSequence title = notification.extras != null
                    ? notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                    : null;
            seed = "id=" + id + ";title=" + String.valueOf(title);
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < seed.length(); i++) {
            hash ^= seed.charAt(i);
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.US, "sda:domo:%016x", hash);
    }

    private static boolean safeIsPromoted(Notification notification) {
        try {
            Object result = XposedHelpers.callMethod(notification, "isPromotedOngoing");
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void safeInstall(String target, ThrowingRunnable runnable) {
        try {
            runnable.run();
            logInstall(target + " installed, version=" + VERSION);
        } catch (Throwable throwable) {
            logError(target + " installation failed", throwable);
        }
    }

    private static void throttledLog(String key, String message) {
        long now = System.currentTimeMillis();
        Long previous = LAST_LOG_AT.put(key, now);
        if (previous == null || now - previous >= 1500L) {
            Log.i(TAG, message);
        }
    }

    private static void logInstall(String message) {
        String line = "[" + TAG + "] " + message;
        Log.i(TAG, message);
        XposedBridge.log(line);
    }

    private static void logDebug(String message) {
        Log.d(TAG, message);
    }

    private static void logError(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        XposedBridge.log("[" + TAG + "] " + message + ": " + throwable);
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
