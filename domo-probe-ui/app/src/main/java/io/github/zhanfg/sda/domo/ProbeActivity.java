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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

public final class ProbeActivity extends Activity {
    private static final String DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=33554432";
    private static final long TEST_TIMEOUT_MS = 25_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private DownloadManager downloadManager;
    private TextView statusView;
    private TextView stageView;
    private TextView detailView;
    private EditText urlView;
    private long testStartedAt;
    private long testDownloadId = -1L;
    private boolean waiting;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollResult();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(ProbeProvider.PREFS, MODE_PRIVATE);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        setContentView(buildContent());
        renderCurrentState(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderCurrentState(false);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(pollRunnable);
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

        TextView title = text("Domo 下载通知探针", 25, Color.rgb(25, 28, 33));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = text(
                "依次检查 LSPosed 注入、通知提交入口和 active 下载通知。"
                        + "本版本不会修改通知，也不会改变下载行为。",
                15,
                Color.rgb(80, 86, 96));
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        statusView = text("正在读取状态", 18, Color.rgb(60, 64, 72));
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(dp(16), dp(16), dp(16), dp(10));
        statusView.setBackground(rounded(Color.rgb(239, 241, 245), 14));
        root.addView(statusView, matchWrap());

        stageView = text("① 模块安装  ② LSPosed 注入  ③ 通知命中  ④ active 命中",
                13,
                Color.rgb(85, 91, 102));
        stageView.setPadding(dp(16), 0, dp(16), dp(16));
        root.addView(stageView, matchWrapWithTop(-8));

        detailView = text("", 14, Color.rgb(60, 64, 72));
        detailView.setTextIsSelectable(true);
        detailView.setPadding(0, dp(14), 0, dp(18));
        root.addView(detailView);

        TextView scope = text(
                "LSPosed 作用域只勾选 com.android.providers.downloads。更新 APK 后需要重启一次该作用域，"
                        + "不需要勾选 SystemUI，也不需要执行终端命令。",
                14,
                Color.rgb(70, 76, 86));
        scope.setPadding(0, 0, 0, dp(16));
        root.addView(scope);

        Button listen = button("监听下一次系统下载");
        listen.setOnClickListener(v -> beginListening(false));
        root.addView(listen, matchWrap());

        TextView urlLabel = text("一键测试地址", 14, Color.rgb(70, 76, 86));
        urlLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(urlLabel);

        urlView = new EditText(this);
        urlView.setSingleLine(true);
        urlView.setText(DEFAULT_URL);
        urlView.setTextSize(14);
        urlView.setPadding(dp(12), dp(10), dp(12), dp(10));
        urlView.setBackground(rounded(Color.rgb(244, 245, 247), 10));
        root.addView(urlView, matchWrap());

        Button start = button("一键触发并检查");
        start.setOnClickListener(v -> startTestDownload());
        root.addView(start, matchWrapWithTop(10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(row, matchWrapWithTop(10));

        Button refresh = button("刷新状态");
        refresh.setOnClickListener(v -> renderCurrentState(true));
        row.addView(refresh, weightedButton());

        Button cancel = button("取消测试下载");
        cancel.setOnClickListener(v -> cancelTest(false));
        LinearLayout.LayoutParams cancelParams = weightedButton();
        cancelParams.setMarginStart(dp(8));
        row.addView(cancel, cancelParams);

        Button copy = button("复制诊断摘要");
        copy.setOnClickListener(v -> copySummary());
        root.addView(copy, matchWrapWithTop(10));

        Button clear = button("清除检测记录");
        clear.setOnClickListener(v -> clearResult());
        root.addView(clear, matchWrapWithTop(8));
        return scroll;
    }

    private void beginListening(boolean testDownload) {
        handler.removeCallbacks(pollRunnable);
        testStartedAt = System.currentTimeMillis();
        waiting = true;
        preferences.edit()
                .putLong("current_test_started_at", testStartedAt)
                .remove("last_active_at")
                .remove("last_received_at")
                .apply();
        setStatus(
                testDownload ? "测试下载已提交" : "正在监听下一次下载",
                Color.rgb(42, 91, 176),
                Color.rgb(229, 238, 255));
        detailView.setText("正在等待 DownloadProvider 启动并返回各阶段状态，最长 25 秒。");
        handler.post(pollRunnable);
    }

    private void startTestDownload() {
        String rawUrl = urlView.getText().toString().trim();
        try {
            Uri uri = Uri.parse(rawUrl);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("只支持 HTTP/HTTPS 地址");
            }

            beginListening(true);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle("下载测试文件")
                    .setDescription("正在检查系统下载通知")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(
                            this,
                            Environment.DIRECTORY_DOWNLOADS,
                            "domo_probe_" + System.currentTimeMillis() + ".bin");
            testDownloadId = downloadManager.enqueue(request);
            preferences.edit().putLong("current_download_id", testDownloadId).apply();
        } catch (Throwable throwable) {
            waiting = false;
            handler.removeCallbacks(pollRunnable);
            setStatus("测试下载提交失败", Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
            detailView.setText(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private void pollResult() {
        if (!waiting) {
            return;
        }

        long activeAt = preferences.getLong("last_active_at", 0L);
        if (activeAt >= testStartedAt) {
            waiting = false;
            renderCurrentState(true);
            cancelTest(true);
            return;
        }

        renderCurrentState(false);
        if (System.currentTimeMillis() - testStartedAt >= TEST_TIMEOUT_MS) {
            waiting = false;
            renderTimeoutState();
            return;
        }
        handler.postDelayed(pollRunnable, 350L);
    }

    private void renderCurrentState(boolean announce) {
        long activeAt = preferences.getLong("last_active_at", 0L);
        long anyAt = preferences.getLong("last_received_at", 0L);
        long readyAt = preferences.getLong("hook_ready_at", 0L);
        int hookCount = preferences.getInt("hook_count", 0);
        String hookError = preferences.getString("hook_error", null);

        if (activeAt > 0L) {
            setStatus("④ active 下载通知已命中", Color.rgb(22, 122, 72), Color.rgb(225, 247, 235));
            detailView.setText(buildNotificationDetails(true));
        } else if (anyAt > 0L) {
            setStatus("③ 已命中通知提交入口", Color.rgb(150, 92, 17), Color.rgb(255, 242, 219));
            detailView.setText(buildNotificationDetails(false));
        } else if (readyAt > 0L && hookCount > 0) {
            setStatus("② LSPosed 已注入", Color.rgb(42, 91, 176), Color.rgb(229, 238, 255));
            detailView.setText("Hook 安装时间：" + formatTime(readyAt)
                    + "\n已安装通知入口数：" + hookCount
                    + "\n当前尚未捕获通知，请触发一次系统下载。"
                    + (hookError == null ? "" : "\n附加信息：" + hookError));
        } else {
            setStatus("① 应用已安装，但未确认注入", Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
            detailView.setText("尚未收到 DownloadProvider 的注入握手。"
                    + "\n请在 LSPosed 中启用模块，只勾选 com.android.providers.downloads，"
                    + "然后重启该作用域，再点击“一键触发并检查”。");
        }

        if (announce) {
            Toast.makeText(this, "状态已刷新", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderTimeoutState() {
        long anyAt = preferences.getLong("last_received_at", 0L);
        long readyAt = preferences.getLong("hook_ready_at", 0L);
        int hookCount = preferences.getInt("hook_count", 0);

        if (anyAt >= testStartedAt) {
            setStatus("超时：仅命中非 active 通知", Color.rgb(150, 92, 17), Color.rgb(255, 242, 219));
            detailView.setText(buildNotificationDetails(false));
        } else if (readyAt > 0L && hookCount > 0) {
            setStatus("超时：已注入但未捕获通知", Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
            detailView.setText("LSPosed 注入已确认，Hook 数量=" + hookCount
                    + "，但测试期间没有收到通知提交。下一版将据此调整通知入口。");
        } else {
            setStatus("超时：LSPosed 未注入", Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
            detailView.setText("DownloadProvider 没有返回注入握手。请检查模块是否启用、作用域是否正确，"
                    + "以及更新后是否重启了下载服务作用域。");
        }
    }

    private String buildNotificationDetails(boolean active) {
        String prefix = active ? "last_active_" : "last_";
        long time = preferences.getLong(active ? "last_active_at" : "last_received_at", 0L);
        String tag = preferences.getString(prefix + "tag", "<null>");
        int id = preferences.getInt(prefix + "id", 0);
        String channel = preferences.getString(prefix + "channel", "<null>");
        String source = preferences.getString(prefix + "source", "<unknown>");
        return "结果：" + (active ? "PASS" : "PARTIAL")
                + "\n时间：" + formatTime(time)
                + "\n进程：com.android.providers.downloads"
                + "\n入口：" + source
                + "\ntag：" + tag
                + "\nid：" + id
                + "\nchannel：" + channel
                + "\n通知内容：未修改";
    }

    private void cancelTest(boolean quiet) {
        long storedId = preferences.getLong("current_download_id", testDownloadId);
        if (storedId >= 0L && downloadManager != null) {
            try {
                downloadManager.remove(storedId);
            } catch (Throwable ignored) {
            }
        }
        testDownloadId = -1L;
        preferences.edit().remove("current_download_id").apply();
        if (!quiet) {
            Toast.makeText(this, "已请求取消测试下载", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearResult() {
        waiting = false;
        handler.removeCallbacks(pollRunnable);
        preferences.edit().clear().apply();
        testDownloadId = -1L;
        renderCurrentState(false);
        Toast.makeText(this, "检测记录已清除", Toast.LENGTH_SHORT).show();
    }

    private void copySummary() {
        String summary = statusView.getText() + "\n" + detailView.getText();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Domo probe result", summary));
            Toast.makeText(this, "诊断摘要已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private String formatTime(long time) {
        return time <= 0L ? "<none>" : DateFormat.getDateTimeInstance().format(new Date(time));
    }

    private void setStatus(String value, int foreground, int background) {
        statusView.setText(value);
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

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
