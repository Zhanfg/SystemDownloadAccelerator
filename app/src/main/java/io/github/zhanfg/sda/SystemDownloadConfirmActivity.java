package io.github.zhanfg.sda;

import android.app.Activity;
import android.content.ComponentName;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Confirmation UI for downloads already inserted and paused by DownloadProvider. */
public final class SystemDownloadConfirmActivity extends Activity {
    private static final String ACTION_DECISION =
            "io.github.zhanfg.sda.action.SYSTEM_DOWNLOAD_DECISION";
    private static final String DOWNLOAD_PROVIDER = "com.android.providers.downloads";

    private static final int BG = Color.rgb(246, 246, 252);
    private static final int CARD = Color.rgb(235, 237, 247);
    private static final int PRIMARY = Color.rgb(89, 104, 138);
    private static final int TEXT = Color.rgb(38, 40, 49);
    private static final int MUTED = Color.rgb(96, 99, 112);

    private String token;
    private boolean decisionSent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Intent intent = getIntent();
        token = intent.getStringExtra("token");
        if (TextUtils.isEmpty(token)) {
            finish();
            return;
        }

        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setDimAmount(0.45f);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        setFinishOnTouchOutside(false);

        String fileName = safe(intent.getStringExtra("file_name"), "download.bin");
        String url = safe(intent.getStringExtra("url"), "");
        String source = safe(intent.getStringExtra("source_package"), "系统应用");
        String mime = safe(intent.getStringExtra("mime_type"), "application/octet-stream");
        long size = intent.getLongExtra("file_size", -1L);

        View root = build(fileName, url, source, mime, size);
        SystemBarInsets.apply(root);
        setContentView(root);
    }

    private View build(String fileName, String url, String source, String mime, long size) {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER);
        screen.setPadding(dp(16), dp(16), dp(16), dp(16));
        screen.setBackgroundColor(Color.TRANSPARENT);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(24), dp(22), dp(20));
        card.setBackground(roundRect(BG, 30));

        ImageView icon = new ImageView(this);
        icon.setImageResource(getApplicationInfo().icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        iconParams.bottomMargin = dp(14);
        card.addView(icon, iconParams);

        card.addView(text("下载此文件？", 25, TEXT, true, Gravity.CENTER));
        card.addView(space(8));

        TextView name = text(fileName, 18, TEXT, true, Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(space(14));

        LinearLayout information = panel();
        addInfo(information, "文件大小", formatSize(size));
        addInfo(information, "来源", source);
        addInfo(information, "文件类型", mime);
        addInfo(information, "下载线程", "自动 · 由模块动态调度");
        card.addView(information);
        card.addView(space(12));

        LinearLayout directory = panel();
        directory.addView(text("保存位置", 13, MUTED, false, Gravity.START));
        TextView destination = text("由发起应用指定", 16, TEXT, true, Gravity.START);
        destination.setPadding(0, dp(6), 0, 0);
        directory.addView(destination);
        card.addView(directory);
        card.addView(space(12));

        LinearLayout linkPanel = panel();
        linkPanel.addView(text("下载链接", 13, MUTED, false, Gravity.START));
        TextView link = text(url, 13, TEXT, false, Gravity.START);
        link.setMaxLines(3);
        link.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        link.setPadding(0, dp(6), 0, 0);
        linkPanel.addView(link);
        card.addView(linkPanel);
        card.addView(space(16));

        Button confirm = button("开始下载", true);
        confirm.setOnClickListener(v -> sendDecision(true));
        card.addView(confirm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        card.addView(space(10));

        Button cancel = button("取消", false);
        cancel.setOnClickListener(v -> sendDecision(false));
        card.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        scroll.addView(card, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return screen;
    }

    private void sendDecision(boolean allow) {
        if (decisionSent) {
            return;
        }
        decisionSent = true;
        Intent decision = new Intent(ACTION_DECISION);
        decision.setPackage(DOWNLOAD_PROVIDER);
        decision.putExtra("token", token);
        decision.putExtra("decision", allow ? 1 : 0);
        sendBroadcast(decision);
        finish();
    }

    @Override
    public void onBackPressed() {
        sendDecision(false);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(13), dp(16), dp(13));
        panel.setBackground(roundRect(CARD, 18));
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private void addInfo(LinearLayout parent, String keyText, String valueText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView key = text(keyText, 14, MUTED, false, Gravity.START);
        TextView value = text(valueText, 14, TEXT, false, Gravity.END);
        value.setMaxLines(2);
        row.addView(key, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.38f));
        row.addView(value, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.62f));
        parent.addView(row);
        parent.addView(space(7));
    }

    private TextView text(String value, float size, int color, boolean bold, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(gravity);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : TEXT);
        button.setBackground(roundRect(primary ? PRIMARY : CARD, 18));
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) {
            return "开始后获取";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
