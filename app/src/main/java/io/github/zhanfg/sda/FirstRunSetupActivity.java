package io.github.zhanfg.sda;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** First-launch setup for root, notifications and background reliability. */
public final class FirstRunSetupActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 4101;
    private static final String PREFS = "module_settings";
    private static final String KEY_SETUP_SHOWN = "first_run_setup_shown";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;
    private TextView rootStatus;
    private TextView notificationStatus;
    private TextView batteryStatus;
    private Button rootButton;
    private Button notificationButton;
    private Button batteryButton;
    private boolean rootGranted;
    private boolean rootRequestRunning;
    private boolean automaticSequenceStarted;

    private int background;
    private int surface;
    private int surfaceHigh;
    private int primary;
    private int textPrimary;
    private int textSecondary;
    private int success;
    private int warning;
    private int error;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        resolveColors();
        setContentView(buildContent());

        boolean manual = getIntent().getBooleanExtra("manual", false);
        if (!manual && !preferences.getBoolean("root_prompt_attempted", false)) {
            automaticSequenceStarted = true;
            preferences.edit().putBoolean("root_prompt_attempted", true).apply();
            main.postDelayed(() -> requestRoot(true), 420L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStates();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(background);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("首次运行设置", 28, textPrimary, true);
        root.addView(title);
        TextView intro = text(
                "先授予必要权限，再启用 LSPosed 作用域。Root 只用于可靠显示下载确认窗口，不接受任意 Shell 指令。",
                14, textSecondary, false);
        intro.setPadding(0, dp(8), 0, dp(18));
        root.addView(intro);

        LinearLayout rootCard = card();
        rootCard.addView(text("1 · Root 权限", 18, textPrimary, true));
        rootCard.addView(description("必需。用于从系统下载服务可靠启动透明确认窗口。"));
        rootStatus = status("等待检查", warning);
        rootCard.addView(rootStatus);
        rootButton = button("授予 / 重新检查 Root", true);
        rootButton.setOnClickListener(v -> requestRoot(false));
        addButton(rootCard, rootButton);
        root.addView(rootCard);

        LinearLayout notificationCard = card();
        notificationCard.addView(text("2 · 通知与实时活动", 18, textPrimary, true));
        notificationCard.addView(description(
                "必需。用于显示下载百分比、速度、暂停操作和 ColorOS 流体云。"));
        notificationStatus = status("等待检查", warning);
        notificationCard.addView(notificationStatus);
        notificationButton = button("授予通知权限", true);
        notificationButton.setOnClickListener(v -> requestNotificationPermission());
        addButton(notificationCard, notificationButton);
        root.addView(notificationCard);

        LinearLayout batteryCard = card();
        batteryCard.addView(text("3 · 后台运行", 18, textPrimary, true));
        batteryCard.addView(description(
                "推荐。关闭本模块的电池优化，可减少实时活动或完成态清理被系统延迟。"));
        batteryStatus = status("等待检查", warning);
        batteryCard.addView(batteryStatus);
        batteryButton = button("允许后台可靠运行", false);
        batteryButton.setOnClickListener(v -> requestBatteryExemption());
        addButton(batteryCard, batteryButton);
        root.addView(batteryCard);

        LinearLayout lsposedCard = card();
        lsposedCard.addView(text("4 · LSPosed", 18, textPrimary, true));
        lsposedCard.addView(description(
                "在 LSPosed 中启用模块，作用域只勾选 com.android.providers.downloads，然后重启下载服务或手机。"));
        TextView scope = status("此步骤必须在 LSPosed 管理器中手动完成", warning);
        lsposedCard.addView(scope);
        root.addView(lsposedCard);

        LinearLayout noteCard = card();
        noteCard.addView(text("权限边界", 17, textPrimary, true));
        noteCard.addView(description(
                "本版本不需要存储权限，也不申请“显示在其他应用上层”权限。文件由系统 DownloadManager 管理，界面使用透明 Activity。"));
        root.addView(noteCard);

        Button finish = button("完成设置", true);
        finish.setOnClickListener(v -> finishSetup());
        LinearLayout.LayoutParams finishParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        finishParams.topMargin = dp(10);
        root.addView(finish, finishParams);

        Button later = button("稍后再处理", false);
        later.setOnClickListener(v -> finishSetup());
        LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        laterParams.topMargin = dp(8);
        root.addView(later, laterParams);
        return scroll;
    }

    private void requestRoot(boolean continueSequence) {
        if (rootRequestRunning) return;
        rootRequestRunning = true;
        rootButton.setEnabled(false);
        rootStatus.setText("正在请求 Root，请在 Root 管理器中确认…");
        rootStatus.setTextColor(warning);
        executor.execute(() -> {
            RootAccess.Result result = RootAccess.request(20L);
            main.post(() -> {
                rootRequestRunning = false;
                rootGranted = result.granted;
                rootButton.setEnabled(true);
                rootStatus.setText(result.message);
                rootStatus.setTextColor(result.granted ? success : error);
                preferences.edit()
                        .putBoolean("root_granted_last_check", result.granted)
                        .putLong("root_checked_at", System.currentTimeMillis())
                        .apply();
                if (continueSequence && Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
                    main.postDelayed(this::requestNotificationPermission, 350L);
                }
            });
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission()) {
            refreshPermissionStates();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            refreshPermissionStates();
            if (automaticSequenceStarted && !isIgnoringBatteryOptimizations()) {
                Toast.makeText(this,
                        "后台运行权限为推荐项，可在下方手动授予。",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) {
            refreshPermissionStates();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable first) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable second) {
                Toast.makeText(this, "无法打开电池优化设置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void refreshPermissionStates() {
        boolean notificationGranted = hasNotificationPermission();
        notificationStatus.setText(notificationGranted ? "通知权限已授予" : "通知权限未授予");
        notificationStatus.setTextColor(notificationGranted ? success : error);
        notificationButton.setEnabled(!notificationGranted);
        notificationButton.setText(notificationGranted ? "已授予" : "授予通知权限");

        boolean batteryGranted = isIgnoringBatteryOptimizations();
        batteryStatus.setText(batteryGranted ? "已允许后台可靠运行" : "仍受系统电池优化限制");
        batteryStatus.setTextColor(batteryGranted ? success : warning);
        batteryButton.setEnabled(!batteryGranted);
        batteryButton.setText(batteryGranted ? "已允许" : "允许后台可靠运行");

        if (!rootRequestRunning) {
            rootGranted = preferences.getBoolean("root_granted_last_check", false);
            rootStatus.setText(rootGranted ? "Root 权限已授予" : "尚未确认 Root 权限");
            rootStatus.setTextColor(rootGranted ? success : warning);
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager == null || Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled();
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager manager = getSystemService(PowerManager.class);
        return manager != null && manager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void finishSetup() {
        preferences.edit().putBoolean(KEY_SETUP_SHOWN, true).apply();
        finish();
    }

    @Override
    public void onBackPressed() {
        finishSetup();
    }

    private void resolveColors() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        background = dark ? Color.rgb(17, 19, 23) : Color.rgb(246, 247, 251);
        surface = dark ? Color.rgb(28, 31, 37) : Color.WHITE;
        surfaceHigh = dark ? Color.rgb(38, 42, 50) : Color.rgb(235, 240, 246);
        textPrimary = dark ? Color.rgb(239, 241, 247) : Color.rgb(28, 32, 39);
        textSecondary = dark ? Color.rgb(174, 180, 190) : Color.rgb(94, 102, 114);
        success = dark ? Color.rgb(95, 211, 154) : Color.rgb(20, 127, 81);
        warning = dark ? Color.rgb(246, 190, 92) : Color.rgb(165, 103, 0);
        error = dark ? Color.rgb(255, 180, 171) : Color.rgb(186, 26, 26);
        if (Build.VERSION.SDK_INT >= 31) {
            try { primary = getColor(android.R.color.system_accent1_600); }
            catch (Throwable ignored) { primary = Color.rgb(42, 122, 143); }
        } else primary = Color.rgb(42, 122, 143);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setBackground(roundRect(surface, 24));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private TextView description(String value) {
        TextView view = text(value, 14, textSecondary, false);
        view.setPadding(0, dp(7), 0, dp(10));
        return view;
    }

    private TextView status(String value, int color) {
        TextView view = text(value, 13, color, true);
        view.setPadding(dp(12), dp(9), dp(12), dp(9));
        view.setBackground(roundRect(surfaceHigh, 16));
        return view;
    }

    private void addButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        params.topMargin = dp(12);
        parent.addView(button, params);
    }

    private Button button(String label, boolean filled) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(filled ? Color.WHITE : primary);
        button.setBackground(roundRect(filled ? primary : surfaceHigh, 18));
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
