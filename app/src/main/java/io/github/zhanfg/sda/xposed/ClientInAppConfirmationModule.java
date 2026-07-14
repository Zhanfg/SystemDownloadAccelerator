package io.github.zhanfg.sda.xposed;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Shows the confirmation as a real Dialog attached to the originating Activity.
 * This avoids launching the module Activity and therefore keeps the source app visible underneath.
 */
public final class ClientInAppConfirmationModule extends XposedModule {
    private static final String DOWNLOADS_PACKAGE = "com.android.providers.downloads";
    private static final String MODULE_PACKAGE = "io.github.zhanfg.sda";
    private static final String BRIDGE_AUTHORITY = "io.github.zhanfg.sda.history";
    private static final Uri BRIDGE_URI = Uri.parse("content://" + BRIDGE_AUTHORITY);
    private static final String ACTION_IN_APP_DECISION =
            "io.github.zhanfg.sda.action.IN_APP_DOWNLOAD_DECISION";

    private static volatile WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean(false);

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (DOWNLOADS_PACKAGE.equals(packageName)
                || MODULE_PACKAGE.equals(packageName)
                || "android".equals(packageName)) {
            return;
        }
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            Method onPause = Activity.class.getDeclaredMethod("onPause");
            Method enqueue = DownloadManager.class.getDeclaredMethod(
                    "enqueue", DownloadManager.Request.class);
            onResume.setAccessible(true);
            onPause.setAccessible(true);
            enqueue.setAccessible(true);

            hook(onResume)
                    .setId("sda.client.activity-resume")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        if (!activity.isFinishing()) {
                            resumedActivity = new WeakReference<>(activity);
                        }
                        return result;
                    });

            hook(onPause)
                    .setId("sda.client.activity-pause")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Activity activity = (Activity) chain.getThisObject();
                        Activity current = resumedActivity.get();
                        if (current == activity) {
                            resumedActivity = new WeakReference<>(null);
                        }
                        return chain.proceed();
                    });

            hook(enqueue)
                    .setId("sda.client.in-app-confirmation")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> interceptEnqueue(packageName, chain));

            log(android.util.Log.INFO, "SysDownloadClient",
                    "In-app confirmation hook installed in " + packageName);
        } catch (Throwable error) {
            log(android.util.Log.ERROR, "SysDownloadClient",
                    "Failed to install in-app confirmation hook in " + packageName, error);
        }
    }

    private Object interceptEnqueue(String packageName, XposedInterface.Chain chain) throws Throwable {
        Activity activity = resumedActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return chain.proceed();
        }

        if (!markNextDownloadForInAppUi(activity, packageName)) {
            return chain.proceed();
        }

        DownloadManager.Request request = (DownloadManager.Request) chain.getArg(0);
        Object result = chain.proceed();
        if (!(result instanceof Long)) {
            return result;
        }

        long downloadId = (Long) result;
        RequestInfo info = RequestInfo.from(request);
        activity.runOnUiThread(() -> {
            Activity current = resumedActivity.get();
            if (current == null || current.isFinishing() || current.isDestroyed()) {
                sendDecision(activity, downloadId, true);
                return;
            }
            try {
                showDialog(current, downloadId, info);
            } catch (Throwable error) {
                sendDecision(current, downloadId, true);
            }
        });
        return result;
    }

    private boolean markNextDownloadForInAppUi(Context context, String packageName) {
        try {
            Bundle result = context.getContentResolver().call(
                    BRIDGE_URI,
                    "mark_in_app_confirmation",
                    packageName,
                    null
            );
            return result != null && result.getBoolean("marked", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showDialog(Activity activity, long downloadId, RequestInfo info) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(true);

        boolean dark = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int background = dark ? Color.rgb(30, 33, 39) : Color.rgb(248, 249, 252);
        int panel = dark ? Color.rgb(42, 46, 54) : Color.rgb(237, 241, 246);
        int textPrimary = dark ? Color.rgb(241, 243, 248) : Color.rgb(30, 34, 41);
        int textSecondary = dark ? Color.rgb(178, 184, 194) : Color.rgb(92, 100, 112);
        int primary = Color.rgb(42, 122, 143);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 22), dp(activity, 22), dp(activity, 22), dp(activity, 18));
        card.setBackground(roundRect(activity, background, 28));

        TextView title = text(activity, "下载文件", 23, textPrimary, true);
        card.addView(title);

        TextView fileName = text(activity, info.fileName, 17, textPrimary, true);
        fileName.setPadding(0, dp(activity, 14), 0, 0);
        fileName.setMaxLines(2);
        fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(fileName);

        TextView url = text(activity, info.url, 13, textSecondary, false);
        url.setPadding(0, dp(activity, 7), 0, 0);
        url.setMaxLines(2);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        url.setTextIsSelectable(true);
        card.addView(url);

        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(activity, 16), dp(activity, 13), dp(activity, 16), dp(activity, 13));
        details.setBackground(roundRect(activity, panel, 18));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailsParams.topMargin = dp(activity, 16);
        card.addView(details, detailsParams);

        addInfoRow(activity, details, "文件类型", empty(info.mimeType) ? "未知" : info.mimeType,
                textPrimary, textSecondary);
        addInfoRow(activity, details, "保存位置", empty(info.destination)
                        ? "内部存储 / Download" : info.destination,
                textPrimary, textSecondary);
        addInfoRow(activity, details, "下载方式", "自动 · 多线程可用时启用",
                textPrimary, textSecondary);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52));
        actionsParams.topMargin = dp(activity, 18);
        card.addView(actions, actionsParams);

        Button cancel = button(activity, "取消", textPrimary, panel);
        Button confirm = button(activity, "开始下载", Color.WHITE, primary);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        actions.addView(cancel, half);
        View gap = new View(activity);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(activity, 10), 1));
        actions.addView(confirm, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        AtomicBoolean handled = new AtomicBoolean(false);
        cancel.setOnClickListener(v -> {
            if (handled.compareAndSet(false, true)) {
                sendDecision(activity, downloadId, false);
            }
            dialog.dismiss();
        });
        confirm.setOnClickListener(v -> {
            if (handled.compareAndSet(false, true)) {
                sendDecision(activity, downloadId, true);
            }
            dialog.dismiss();
        });
        dialog.setOnCancelListener(ignored -> {
            if (handled.compareAndSet(false, true)) {
                sendDecision(activity, downloadId, false);
            }
        });

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.48f);
            window.setWindowAnimations(0);
        }
        dialog.show();
        window = dialog.getWindow();
        if (window != null) {
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int maxWidth = dp(activity, 520);
            int width = Math.min(screenWidth - dp(activity, 32), maxWidth);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }

    private static void sendDecision(Context context, long downloadId, boolean allow) {
        Intent decision = new Intent(ACTION_IN_APP_DECISION);
        decision.setPackage(DOWNLOADS_PACKAGE);
        decision.putExtra("download_id", downloadId);
        decision.putExtra("decision", allow ? 1 : 0);
        context.sendBroadcast(decision);
    }

    private static void addInfoRow(Context context, LinearLayout parent, String key, String value,
                                   int textPrimary, int textSecondary) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView keyView = text(context, key, 14, textSecondary, false);
        TextView valueView = text(context, value, 14, textPrimary, false);
        valueView.setGravity(Gravity.END);
        valueView.setMaxLines(2);
        row.addView(keyView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.34f));
        row.addView(valueView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.66f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 8);
        parent.addView(row, params);
    }

    private static TextView text(Context context, String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private static Button button(Context context, String label, int textColor, int background) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(textColor);
        button.setBackground(roundRect(context, background, 17));
        return button;
    }

    private static GradientDrawable roundRect(Context context, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class RequestInfo {
        final String fileName;
        final String url;
        final String mimeType;
        final String destination;

        RequestInfo(String fileName, String url, String mimeType, String destination) {
            this.fileName = fileName;
            this.url = url;
            this.mimeType = mimeType;
            this.destination = destination;
        }

        static RequestInfo from(DownloadManager.Request request) {
            Uri source = readUri(request, "mUri");
            Uri destination = readUri(request, "mDestinationUri");
            String title = readString(request, "mTitle");
            String mime = readString(request, "mMimeType");
            String url = source == null ? "" : source.toString();
            String fileName = title;
            if (empty(fileName) && destination != null) {
                fileName = destination.getLastPathSegment();
            }
            if (empty(fileName) && source != null) {
                fileName = source.getLastPathSegment();
            }
            if (empty(fileName)) {
                fileName = "download.bin";
            }
            String destinationText = destination == null ? "" : destination.toString();
            return new RequestInfo(fileName, url, mime, destinationText);
        }

        private static Uri readUri(Object object, String name) {
            Object value = readField(object, name);
            return value instanceof Uri ? (Uri) value : null;
        }

        private static String readString(Object object, String name) {
            Object value = readField(object, name);
            return value == null ? null : String.valueOf(value);
        }

        private static Object readField(Object object, String name) {
            Class<?> type = object.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(object);
                } catch (Throwable ignored) {
                    type = type.getSuperclass();
                }
            }
            return null;
        }
    }
}
