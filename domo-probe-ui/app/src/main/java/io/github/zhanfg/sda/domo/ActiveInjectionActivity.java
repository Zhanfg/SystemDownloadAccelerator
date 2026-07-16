package io.github.zhanfg.sda.domo;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ActiveInjectionActivity extends Activity {
    private static final String TARGET_PROCESS = "com.android.providers.downloads";
    private static final String TEST_URL = "https://speed.cloudflare.com/__down?bytes=8388608";
    private static final long TIMEOUT_MS = 25_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;
    private DownloadManager downloadManager;
    private TextView statusView;
    private TextView detailView;
    private long testStartedAt;
    private long testDownloadId = -1L;
    private String rootSummary = "尚未执行 Root 重启";
    private boolean waiting;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(ProbeProvider.PREFS, MODE_PRIVATE);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        setContentView(buildContent());
        renderCurrentState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderCurrentState();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(pollRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Domo 主动注入测试", 25, Color.rgb(25, 28, 33));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView description = text(
                "LSPosed 不能附加到已经运行的进程。本页面会申请 Root，精确终止系统下载进程，"
                        + "再通过测试下载让它从 Zygote 重新创建并接受注入。",
                15,
                Color.rgb(78, 84, 94));
        description.setPadding(0, dp(8), 0, dp(18));
        root.addView(description);

        statusView = text("等待检查", 17, Color.rgb(60, 64, 72));
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusView.setBackground(rounded(Color.rgb(239, 241, 245), 14));
        root.addView(statusView, matchWrap());

        detailView = text("尚未执行。", 14, Color.rgb(60, 64, 72));
        detailView.setTextIsSelectable(true);
        detailView.setPadding(0, dp(14), 0, dp(18));
        root.addView(detailView);

        Button restart = button("申请 Root，重启目标并检测");
        restart.setOnClickListener(v -> restartAndTest());
        root.addView(restart, matchWrap());

        Button normalTest = button("不重启，仅提交测试下载");
        normalTest.setOnClickListener(v -> beginTestDownload(false));
        root.addView(normalTest, matchWrapWithTop(10));

        Button refresh = button("刷新注入状态");
        refresh.setOnClickListener(v -> renderCurrentState());
        root.addView(refresh, matchWrapWithTop(10));

        Button copy = button("复制诊断摘要");
        copy.setOnClickListener(v -> copySummary());
        root.addView(copy, matchWrapWithTop(10));

        TextView warning = text(
                "注意：Root 重启会中断当时正在由系统下载服务处理的任务。测试期间不要同时进行重要下载。",
                13,
                Color.rgb(155, 83, 20));
        warning.setPadding(0, dp(18), 0, 0);
        root.addView(warning);

        return scroll;
    }

    private void restartAndTest() {
        if (waiting) {
            Toast.makeText(this, "测试正在进行", Toast.LENGTH_SHORT).show();
            return;
        }

        clearRuntimeState();
        setStatus("正在申请 Root 并重启目标…",
                Color.rgb(42, 91, 176), Color.rgb(229, 238, 255));
        detailView.setText("请在 Root 管理器中允许本应用获得临时 Root 权限。");

        executor.execute(() -> {
            RootResult result = runRootRestart();
            mainHandler.post(() -> {
                rootSummary = result.summary;
                if (!result.success) {
                    setStatus("Root 重启失败",
                            Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
                    detailView.setText(result.summary
                            + "\n\n没有执行任何通知 Hook 或下载修改。请检查 Root 授权。" );
                    return;
                }
                beginTestDownload(true);
            });
        });
    }

    private RootResult runRootRestart() {
        String command = "old=$(pidof " + TARGET_PROCESS + " 2>/dev/null); "
                + "echo old_pid=$old; "
                + "if [ -n \"$old\" ]; then kill -9 $old 2>/dev/null; rc=$?; else rc=0; fi; "
                + "sleep 1; "
                + "alive=$(pidof " + TARGET_PROCESS + " 2>/dev/null); "
                + "echo after_kill_pid=$alive; "
                + "echo kill_rc=$rc; "
                + "exit $rc";

        StringBuilder output = new StringBuilder();
        int exitCode = -1;
        try {
            Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            exitCode = process.waitFor();
        } catch (Throwable throwable) {
            output.append(throwable.getClass().getSimpleName())
                    .append(": ")
                    .append(throwable.getMessage());
        }

        String summary = "Root exitCode=" + exitCode + "\n" + output.toString().trim();
        return new RootResult(exitCode == 0, summary);
    }

    private void beginTestDownload(boolean afterRootRestart) {
        mainHandler.removeCallbacks(pollRunnable);
        testStartedAt = System.currentTimeMillis();
        waiting = true;

        preferences.edit()
                .remove("hook_ready_at")
                .remove("hook_error_at")
                .remove("hook_error")
                .remove("last_received_at")
                .remove("last_active_at")
                .apply();

        setStatus(
                afterRootRestart ? "目标已终止，正在促使新进程启动…" : "正在提交测试下载…",
                Color.rgb(42, 91, 176), Color.rgb(229, 238, 255));
        detailView.setText(rootSummary
                + "\n\n等待新进程 Hook 心跳和 channel=active 通知，最长 25 秒。" );

        try {
            String fileName = "domo_active_injection_" + System.currentTimeMillis() + ".bin";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(TEST_URL))
                    .setTitle("下载测试文件")
                    .setDescription("正在检查系统下载通知")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(
                            this,
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName);
            testDownloadId = downloadManager.enqueue(request);
            preferences.edit().putLong("current_download_id", testDownloadId).apply();
            mainHandler.post(pollRunnable);
        } catch (Throwable throwable) {
            waiting = false;
            setStatus("测试下载提交失败",
                    Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
            detailView.setText(rootSummary + "\n\n"
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private void pollState() {
        if (!waiting) {
            return;
        }

        long readyAt = preferences.getLong("hook_ready_at", 0L);
        long notificationAt = preferences.getLong("last_received_at", 0L);
        long activeAt = preferences.getLong("last_active_at", 0L);
        String error = preferences.getString("hook_error", null);

        if (activeAt >= testStartedAt) {
            waiting = false;
            setStatus("主动重启后已成功注入并命中",
                    Color.rgb(22, 122, 72), Color.rgb(225, 247, 235));
            renderCurrentState();
            cancelTestDownload();
            return;
        }

        if (notificationAt >= testStartedAt) {
            setStatus("已注入，已捕获通知，等待 active…",
                    Color.rgb(150, 92, 17), Color.rgb(255, 242, 219));
        } else if (readyAt >= testStartedAt) {
            setStatus("新进程已获得 LSPosed 注入",
                    Color.rgb(42, 91, 176), Color.rgb(229, 238, 255));
        } else if (error != null && !error.isEmpty()) {
            setStatus("Hook 初始化报错",
                    Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
        }

        if (System.currentTimeMillis() - testStartedAt >= TIMEOUT_MS) {
            waiting = false;
            String stage;
            if (readyAt >= testStartedAt) {
                stage = "新进程已经被 LSPosed 注入，但没有捕获 active 通知。";
            } else {
                stage = "系统下载进程已被重新拉起，但 LSPosed 没有加载本模块。"
                        + "这通常表示模块尚未在 LSPosed 中启用，或模块更新后 Zygote 尚未重新加载模块列表。";
            }
            setStatus("主动注入检测超时",
                    Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
            detailView.setText(rootSummary + "\n\n" + stage
                    + "\n\n这不是下载服务本身没有运行，而是 LSPosed 注入链没有建立。" );
            cancelTestDownload();
            return;
        }

        renderDetailsOnly();
        mainHandler.postDelayed(pollRunnable, 400L);
    }

    private void renderCurrentState() {
        long readyAt = preferences.getLong("hook_ready_at", 0L);
        long notificationAt = preferences.getLong("last_received_at", 0L);
        long activeAt = preferences.getLong("last_active_at", 0L);

        if (activeAt > 0L) {
            setStatus("active 下载通知已命中",
                    Color.rgb(22, 122, 72), Color.rgb(225, 247, 235));
        } else if (notificationAt > 0L) {
            setStatus("已捕获 DownloadProvider 通知",
                    Color.rgb(150, 92, 17), Color.rgb(255, 242, 219));
        } else if (readyAt > 0L) {
            setStatus("LSPosed 已注入 DownloadProvider",
                    Color.rgb(42, 91, 176), Color.rgb(229, 238, 255));
        } else if (!waiting) {
            setStatus("尚未确认 LSPosed 注入",
                    Color.rgb(60, 64, 72), Color.rgb(239, 241, 245));
        }
        renderDetailsOnly();
    }

    private void renderDetailsOnly() {
        long readyAt = preferences.getLong("hook_ready_at", 0L);
        int hookCount = preferences.getInt("hook_count", 0);
        String hookProcess = preferences.getString("hook_process", "<none>");
        String hookError = preferences.getString("hook_error", null);
        long notificationAt = preferences.getLong("last_received_at", 0L);
        long activeAt = preferences.getLong("last_active_at", 0L);
        String tag = preferences.getString("last_tag", "<none>");
        int id = preferences.getInt("last_id", 0);
        String channel = preferences.getString("last_channel", "<none>");
        String source = preferences.getString("last_source", "<none>");

        StringBuilder text = new StringBuilder();
        text.append(rootSummary);
        text.append("\n\nHook 心跳：")
                .append(readyAt > 0L ? formatTime(readyAt) : "无")
                .append("\nHook 数量：").append(hookCount)
                .append("\n目标进程：").append(hookProcess)
                .append("\n通知回执：")
                .append(notificationAt > 0L ? formatTime(notificationAt) : "无")
                .append("\nactive 回执：")
                .append(activeAt > 0L ? formatTime(activeAt) : "无")
                .append("\ntag：").append(tag)
                .append("\nid：").append(id)
                .append("\nchannel：").append(channel)
                .append("\nHook 入口：").append(source);
        if (hookError != null && !hookError.isEmpty()) {
            text.append("\nHook 错误：").append(hookError);
        }
        detailView.setText(text.toString());
    }

    private void clearRuntimeState() {
        waiting = false;
        mainHandler.removeCallbacks(pollRunnable);
        cancelTestDownload();
        preferences.edit()
                .remove("hook_ready_at")
                .remove("hook_count")
                .remove("hook_process")
                .remove("hook_error_at")
                .remove("hook_error")
                .remove("last_received_at")
                .remove("last_active_at")
                .remove("last_tag")
                .remove("last_id")
                .remove("last_channel")
                .remove("last_source")
                .apply();
    }

    private void cancelTestDownload() {
        long id = preferences.getLong("current_download_id", testDownloadId);
        if (id >= 0L && downloadManager != null) {
            try {
                downloadManager.remove(id);
            } catch (Throwable ignored) {
            }
        }
        testDownloadId = -1L;
        preferences.edit().remove("current_download_id").apply();
    }

    private void copySummary() {
        String summary = statusView.getText() + "\n" + detailView.getText();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Domo active injection result", summary));
            Toast.makeText(this, "诊断摘要已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private String formatTime(long time) {
        return DateFormat.getDateTimeInstance().format(new Date(time));
    }

    private void setStatus(String text, int foreground, int background) {
        statusView.setText(text);
        statusView.setTextColor(foreground);
        statusView.setBackground(rounded(background, 14));
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class RootResult {
        final boolean success;
        final String summary;

        RootResult(boolean success, String summary) {
            this.success = success;
            this.summary = summary;
        }
    }
}
