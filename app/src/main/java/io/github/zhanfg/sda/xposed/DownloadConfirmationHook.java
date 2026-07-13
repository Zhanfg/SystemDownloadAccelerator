package io.github.zhanfg.sda.xposed;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/** Intercepts app-side DownloadManager.enqueue while the caller is visible. */
final class DownloadConfirmationHook {
    private static final String TAG = "SysDownloadAccel";
    private static final String MODULE_PACKAGE = "io.github.zhanfg.sda";
    private static final String CONFIRM_ACTIVITY =
            "io.github.zhanfg.sda.InterceptDownloadConfirmActivity";
    private static final AtomicLong TEMP_ID = new AtomicLong(Long.MAX_VALUE - 4096L);
    private static final ThreadLocal<Boolean> IN_HOOK = new ThreadLocal<>();

    private DownloadConfirmationHook() {
    }

    static void install(
            RealDownloadAcceleratorModule module,
            XposedModuleInterface.PackageReadyParam param
    ) throws Exception {
        final String sourcePackage = param.getPackageName();
        if (MODULE_PACKAGE.equals(sourcePackage)
                || "com.android.providers.downloads".equals(sourcePackage)) {
            return;
        }

        Method enqueue = DownloadManager.class.getDeclaredMethod(
                "enqueue", DownloadManager.Request.class);
        enqueue.setAccessible(true);

        module.hook(enqueue)
                .setId("sda.download-confirm.enqueue")
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (Boolean.TRUE.equals(IN_HOOK.get())) {
                        return chain.proceed();
                    }

                    DownloadManager.Request request =
                            (DownloadManager.Request) chain.getArg(0);
                    Context context = findContext(chain.getThisObject());
                    if (context == null || !isProcessVisible()) {
                        return chain.proceed();
                    }
                    if (!popupEnabled(module)) {
                        return chain.proceed();
                    }

                    CapturedRequest captured = capture(request);
                    if (captured.url == null || !isHttpUrl(captured.url)) {
                        return chain.proceed();
                    }

                    Intent intent = new Intent();
                    intent.setClassName(MODULE_PACKAGE, CONFIRM_ACTIVITY);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    intent.putExtra("url", captured.url);
                    intent.putExtra("fileName", captured.title);
                    intent.putExtra("mime", captured.mime);
                    intent.putExtra("description", captured.description);
                    intent.putExtra("sourcePackage", sourcePackage);
                    intent.putExtra("sourceLabel", loadLabel(context, sourcePackage));
                    intent.putExtra("headers", captured.headers);
                    if (captured.destination != null) {
                        intent.putExtra("destination", captured.destination);
                    }

                    try {
                        IN_HOOK.set(Boolean.TRUE);
                        context.startActivity(intent);
                        module.log(Log.INFO, TAG,
                                "Download confirmation opened for " + sourcePackage);
                        // enqueue() is synchronous while user confirmation is asynchronous. Most
                        // browsers only retain this value for cancellation/UI bookkeeping. A large
                        // positive temporary ID avoids being interpreted as an immediate failure.
                        return TEMP_ID.getAndDecrement();
                    } catch (Throwable launchError) {
                        module.log(Log.WARN, TAG,
                                "Unable to open confirmation UI; using original enqueue",
                                launchError);
                        return chain.proceed();
                    } finally {
                        IN_HOOK.remove();
                    }
                });

        module.log(Log.INFO, TAG,
                "Download confirmation hook installed in " + sourcePackage);
    }

    private static boolean popupEnabled(RealDownloadAcceleratorModule module) {
        try {
            SharedPreferences preferences = module.getRemotePreferences("module_settings");
            return preferences.getBoolean("download_confirm", true)
                    && preferences.getBoolean("popup_enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isProcessVisible() {
        ActivityManager.RunningAppProcessInfo info =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }

    private static boolean isHttpUrl(String url) {
        try {
            String scheme = Uri.parse(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Context findContext(Object downloadManager) {
        if (downloadManager != null) {
            for (Field field : DownloadManager.class.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(downloadManager);
                    if (value instanceof Context) {
                        return (Context) value;
                    }
                    if (value instanceof ContentResolver) {
                        Context context = findContextInResolver((ContentResolver) value);
                        if (context != null) {
                            return context;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context findContextInResolver(ContentResolver resolver) {
        Class<?> cursor = resolver.getClass();
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(resolver);
                    if (value instanceof Context) {
                        return (Context) value;
                    }
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static CapturedRequest capture(DownloadManager.Request request) {
        Uri source = readField(request, "mUri", Uri.class);
        Uri destination = readField(request, "mDestinationUri", Uri.class);
        CharSequence title = readField(request, "mTitle", CharSequence.class);
        CharSequence description = readField(request, "mDescription", CharSequence.class);
        String mime = readField(request, "mMimeType", String.class);

        if (source == null) {
            source = findSourceUri(request);
        }

        ArrayList<String> encodedHeaders = new ArrayList<>();
        Object headers = readAnyField(request, "mRequestHeaders");
        if (headers instanceof List<?>) {
            for (Object item : (List<?>) headers) {
                if (!(item instanceof Pair<?, ?>)) {
                    continue;
                }
                Pair<?, ?> pair = (Pair<?, ?>) item;
                if (pair.first == null || pair.second == null) {
                    continue;
                }
                String name = String.valueOf(pair.first);
                String value = String.valueOf(pair.second);
                if (!name.isEmpty() && !value.isEmpty()) {
                    encodedHeaders.add(name);
                    encodedHeaders.add(value);
                }
            }
        }

        return new CapturedRequest(
                source == null ? null : source.toString(),
                title == null ? null : title.toString(),
                description == null ? null : description.toString(),
                mime,
                destination == null ? null : destination.toString(),
                encodedHeaders.toArray(new String[0])
        );
    }

    private static Uri findSourceUri(DownloadManager.Request request) {
        for (Field field : DownloadManager.Request.class.getDeclaredFields()) {
            if (!Uri.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(request);
                if (value instanceof Uri) {
                    Uri uri = (Uri) value;
                    String scheme = uri.getScheme();
                    if ("http".equalsIgnoreCase(scheme)
                            || "https".equalsIgnoreCase(scheme)) {
                        return uri;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String name, Class<T> type) {
        Object value = readAnyField(target, name);
        return type.isInstance(value) ? (T) value : null;
    }

    private static Object readAnyField(Object target, String name) {
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static String loadLabel(Context context, String packageName) {
        try {
            return context.getPackageManager()
                    .getApplicationLabel(context.getPackageManager()
                            .getApplicationInfo(packageName, 0))
                    .toString();
        } catch (Throwable ignored) {
            return packageName;
        }
    }

    private static final class CapturedRequest {
        final String url;
        final String title;
        final String description;
        final String mime;
        final String destination;
        final String[] headers;

        CapturedRequest(String url, String title, String description, String mime,
                        String destination, String[] headers) {
            this.url = url;
            this.title = title;
            this.description = description;
            this.mime = mime;
            this.destination = destination;
            this.headers = headers;
        }
    }
}
