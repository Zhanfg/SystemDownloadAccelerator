package io.github.zhanfg.sda.domo;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
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
    private static final String DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=5242880";
    private static final long TEST_TIMEOUT_MS = 20_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private DownloadManager downloadManager;
    private TextView statusView;
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
        preferences = getSharedPreferences(ProbeResultReceiver.PREFS, MODE_PRIVATE);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        setContentView(buildContent());
        renderStoredResult(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStoredResult(false);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Domo 下载通知探针", 25, Color.rgb(25, 28, 33));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = text(
                "用于验证系统下载服务的通知 Hook。当前版本只读取 tag、id 和 channel，"
                        + "不会修改通知，也不会接管下载。",
                15,
                Color.rgb(80, 86, 96));
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        statusView = text("尚未测试", 17, Color.rgb(60, 64, 72));
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusView.setBackground(rounded(Color.rgb(239, 241, 245), 14));
        root.addView(statusView, matchWrap());

        detailView = text("等待 Hook 回执。", 14, Color.rgb(60, 64, 72));
        detailView.setTextIsSelectable(true);
        detailView.setPadding(0, dp(14), 0, dp(16));
        root.addView(detailView);

        TextView scopeTitle = text("测试前检查", 16, Color.rgb(25, 28, 33));
        scopeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(scopeTitle);

        TextView scope = text(
                "1. 在 LSPosed 中仅勾选 com.android.providers.downloads\n"
                        + "2. 安装或更新后重启一次该作用域\n"
                        + "3. 不需要勾选 SystemUI，也不需要运行终端",
                14,
                Color.rgb(70, 76, 86));
        scope.setPadding(0, dp(8), 0, dp(18));
        root.addView(scope);

        Button listen = button("监听下一次系统下载");
        listen.setOnClickListener(v -> beginListening(false));
        root.addView(listen, matchWrapWithTop(0));

        TextView urlLabel = text("一键测试下载地址", 14, Color.rgb(70, 76, 86));
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

        Button refresh = button("刷新结果");
        refresh.setOnClickListener(v -> renderStoredResult(true));
        row.addView(refresh, weightedButton());

        Button cancel = button("取消测试任务");
        cancel.setOnClickListener(v -> cancelTest(false));
        LinearLayout.LayoutParams cancelParams = weightedButton();
        cancelParams.setMarginStart(dp(8));
        row.addView(cancel, cancelParams);

        Button copy = button("复制诊断摘要");
        copy.setOnClickListener(v -> copySummary());
        root.addView(copy, matchWrapWithTop(10));

        Button clear = button("清除测试结果");
        clear.setOnClickListener(v -> clearResult());
        root.addView(clear, matchWrapWithTop(8));

        TextView footer = text(
                "判定标准：收到 channel=active 的回执即表示 DownloadProvider 通知 Hook 已准确命中。",
                13,
                Color.rgb(100, 106, 116));
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);
        return scrollView;
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
                testDownload ? "测试下载已提交，等待通知 Hook…" : "正在监听下一次系统下载…",
                Color.rgb(42, 91, 176),
                Color.rgb(229, 238, 255));
        detailView.setText("最多等待 20 秒。收到系统下载 active 通知后会自动显示结果。");
        handler.post(pollRunnable);
    }

    private void startTestDownload() {
        String rawUrl = urlView.getText().toString().trim();
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, "请输入下载地址", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri uri = Uri.parse(rawUrl);
            if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("只支持 HTTP/HTTPS 地址");
            }

            beginListening(true);
            String fileName = "domo_probe_" + System.currentTimeMillis() + ".bin";
            DownloadManager.Request request = new DownloadManager.Request(uri)
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
        } catch (Throwable throwable) {
            waiting = false;
            handler.removeCallbacks(pollRunnable);
            setStatus("无法提交测试下载", Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
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
            setStatus("Hook 已准确命中", Color.rgb(22, 122, 72), Color.rgb(225, 247, 235));
            renderStoredResult(true);
            cancelTest(true);
            return;
        }

        long anyAt = preferences.getLong("last_received_at", 0L);
        if (anyAt >= testStartedAt) {
            String channel = preferences.getString("last_channel", "<null>");
            detailView.setText("已收到 DownloadProvider 通知，但当前 channel=" + channel
                    + "。继续等待 active 通知…");
        }

        if (System.currentTimeMillis() - testStartedAt >= TEST_TIMEOUT_MS) {
            waiting = false;
            setStatus("未收到 Hook 回执", Color.rgb(170, 45, 45), Color.rgb(255, 232, 232));
            detailView.setText(
                    "请确认 LSPosed 已启用本模块、作用域仅勾选 com.android.providers.downloads，"
                            + "并重启过该作用域。无需运行终端。");
            return;
        }
        handler.postDelayed(pollRunnable, 400L);
    }

    private void renderStoredResult(boolean announce) {
        long activeAt = preferences.getLong("last_active_at", 0L);
        long anyAt = preferences.getLong("last_received_at", 0L);
        if (activeAt > 0L) {
            String tag = preferences.getString("last_active_tag", "<null>");
            int id = preferences.getInt("last_active_id", 0);
            String channel = preferences.getString("last_active_channel", "<null>");
            setStatus("Hook 已准确命中", Color.rgb(22, 122, 72), Color.rgb(225, 247, 235));
            detailView.setText(buildDetails(activeAt, tag, id, channel, true));
            if (announce) {
                Toast.makeText(this, "已读取最新 active 回执", Toast.LENGTH_SHORT).show();
            }
        } else if (anyAt > 0L) {
            String tag = preferences.getString("last_tag", "<null>");
            int id = preferences.getInt("last_id", 0);
            String channel = preferences.getString("last_channel", "<null>");
            setStatus("已命中非活动通知", Color.rgb(150, 92, 17), Color.rgb(255, 242, 219));
            detailView.setText(buildDetails(anyAt, tag, id, channel, false));
        } else if (!waiting) {
            setStatus("尚未测试", Color.rgb(60, 64, 72), Color.rgb(239, 241, 245));
            detailView.setText("点击“一键触发并检查”，或先点击“监听下一次系统下载”再从其他应用下载文件。");
        }
    }

    private String buildDetails(long time, String tag, int id, String channel, boolean active) {
        return "结果：" + (active ? "PASS" : "WAIT")
                + "\n时间：" + DateFormat.getDateTimeInstance().format(new Date(time))
                + "\n进程：com.android.providers.downloads"
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
            Toast.makeText(this, "已请求取消测试任务", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearResult() {
        waiting = false;
        handler.removeCallbacks(pollRunnable);
        preferences.edit().clear().apply();
        testDownloadId = -1L;
        renderStoredResult(false);
        Toast.makeText(this, "测试结果已清除", Toast.LENGTH_SHORT).show();
    }

    private void copySummary() {
        String text = statusView.getText() + "\n" + detailView.getText();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Domo probe result", text));
            Toast.makeText(this, "诊断摘要已复制", Toast.LENGTH_SHORT).show();
        }
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

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
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
