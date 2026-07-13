package io.github.zhanfg.sda;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import java.util.Locale;

/** Translucent fallback UI used only when the originating app is not in module scope. */
public final class SystemDownloadConfirmActivity extends Activity {
    private static final String ACTION_DECISION =
            "io.github.zhanfg.sda.action.SYSTEM_DOWNLOAD_DECISION";
    private static final String DOWNLOAD_PROVIDER = "com.android.providers.downloads";

    private String token;
    private boolean decisionSent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        overridePendingTransition(0, 0);

        Intent intent = getIntent();
        token = intent.getStringExtra("token");
        if (TextUtils.isEmpty(token)) {
            finishWithoutAnimation();
            return;
        }

        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.48f);
        window.setWindowAnimations(0);
        setFinishOnTouchOutside(false);

        String fileName = safe(intent.getStringExtra("file_name"), "download.bin");
        String url = safe(intent.getStringExtra("url"), "");
        String source = safe(intent.getStringExtra("source_package"), "系统应用");
        String mime = safe(intent.getStringExtra("mime_type"), "application/octet-stream");
        long size = intent.getLongExtra("file_size", -1L);

        setContentView(build(fileName, url, source, mime, size));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenWidth - dp(32), dp(520));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.CENTER);
    }

    private View build(String fileName, String url, String source, String mime, long size) {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int background = dark ? Color.rgb(30, 33, 39) : Color.rgb(248, 249, 252);
        int panel = dark ? Color.rgb(42, 46, 54) : Color.rgb(237, 241, 246);
        int textPrimary = dark ? Color.rgb(241, 243, 248) : Color.rgb(30, 34, 41);
        int textSecondary = dark ? Color.rgb(178, 184, 194) : Color.rgb(92, 100, 112);
        int primary = Color.rgb(42, 122, 143);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(18));
        card.setBackground(roundRect(background, 28));

        TextView title = text("下载文件", 23, textPrimary, true, Gravity.START);
        card.addView(title);

        TextView name = text(fileName, 17, textPrimary, true, Gravity.START);
        name.setPadding(0, dp(14), 0, 0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(name);

        TextView link = text(url, 13, textSecondary, false, Gravity.START);
        link.setPadding(0, dp(7), 0, 0);
        link.setMaxLines(2);
        link.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        link.setTextIsSelectable(true);
        card.addView(link);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(16), dp(13), dp(16), dp(13));
        details.setBackground(roundRect(panel, 18));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailsParams.topMargin = dp(16);
        card.addView(details, detailsParams);

        addInfo(details, "文件大小", formatSize(size), textPrimary, textSecondary);
        addInfo(details, "来源应用", source, textPrimary, textSecondary);
        addInfo(details, "文件类型", mime, textPrimary, textSecondary);
        addInfo(details, "保存位置", "由发起应用指定", textPrimary, textSecondary);
        addInfo(details, "下载方式", "自动 · 多线程可用时启用", textPrimary, textSecondary);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        actionsParams.topMargin = dp(18);
        card.addView(actions, actionsParams);

        Button cancel = button("取消", textPrimary, panel);
        cancel.setOnClickListener(v -> sendDecision(false));
        Button confirm = button("开始下载", Color.WHITE, primary);
        confirm.setOnClickListener(v -> sendDecision(true));
        actions.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        actions.addView(confirm, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return card;
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
        finishWithoutAnimation();
    }

    @Override
    public void onBackPressed() {
        sendDecision(false);
    }

    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    private void addInfo(LinearLayout parent, String keyText, String valueText,
                         int textPrimary, int textSecondary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView key = text(keyText, 14, textSecondary, false, Gravity.START);
        TextView value = text(valueText, 14, textPrimary, false, Gravity.END);
        value.setMaxLines(2);
        row.addView(key, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.34f));
        row.addView(value, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.66f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        parent.addView(row, params);
    }

    private TextView text(String value, float size, int color, boolean bold, int gravity) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(gravity);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private Button button(String label, int textColor, int background) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(textColor);
        button.setBackground(roundRect(background, 17));
        return button;
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
