package io.github.zhanfg.sda;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Dialog-style UI launched from a scoped browser before DownloadManager.enqueue. */
public final class InterceptDownloadConfirmActivity extends Activity {
    private static final int BG = Color.rgb(246, 246, 252);
    private static final int CARD = Color.rgb(235, 237, 247);
    private static final int PRIMARY = Color.rgb(89, 104, 138);
    private static final int TEXT = Color.rgb(38, 40, 49);
    private static final int MUTED = Color.rgb(96, 99, 112);

    private SharedPreferences preferences;
    private String url;
    private String mime;
    private String sourcePackage;
    private String sourceLabel;
    private String[] headers;
    private EditText fileName;
    private TextView directoryValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        preferences = getSharedPreferences("module_settings", MODE_PRIVATE);
        Intent intent = getIntent();
        url = safe(intent.getStringExtra("url"), "");
        mime = intent.getStringExtra("mime");
        sourcePackage = safe(intent.getStringExtra("sourcePackage"), "未知应用");
        sourceLabel = safe(intent.getStringExtra("sourceLabel"), sourcePackage);
        headers = intent.getStringArrayExtra("headers");

        if (!isHttpUrl(url)) {
            Toast.makeText(this, "下载地址无效", Toast.LENGTH_LONG).show();
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

        String initialName = safe(intent.getStringExtra("fileName"), guessName(url));
        View root = build(initialName);
        SystemBarInsets.apply(root);
        setContentView(root);
    }

    private View build(String initialName) {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER);
        screen.setPadding(dp(16), dp(16), dp(16), dp(16));
        screen.setBackgroundColor(Color.TRANSPARENT);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.gravity = Gravity.CENTER;
        scroll.setLayoutParams(scrollParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(24), dp(22), dp(20));
        card.setBackground(roundRect(BG, 30));
        card.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        iconParams.bottomMargin = dp(14);
        icon.setLayoutParams(iconParams);
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(sourcePackage, 0);
            icon.setImageDrawable(getPackageManager().getApplicationIcon(appInfo));
        } catch (Throwable ignored) {
            icon.setImageResource(getApplicationInfo().icon);
        }
        card.addView(icon);

        card.addView(text("下载此文件？", 25, TEXT, true, Gravity.CENTER));
        card.addView(space(8));

        fileName = new EditText(this);
        fileName.setText(initialName);
        fileName.setTextSize(18);
        fileName.setTextColor(TEXT);
        fileName.setSingleLine(true);
        fileName.setGravity(Gravity.CENTER);
        fileName.setSelectAllOnFocus(false);
        fileName.setBackgroundColor(Color.TRANSPARENT);
        fileName.setPadding(dp(8), dp(4), dp(8), dp(10));
        card.addView(fileName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout information = panel();
        addInfo(information, "文件大小", "开始后获取");
        addInfo(information, "来源", sourceLabel);
        addInfo(information, "下载线程", "自动 · 由模块动态调度");
        card.addView(information);
        card.addView(space(12));

        LinearLayout directory = panel();
        TextView directoryTitle = text("保存位置", 13, MUTED, false, Gravity.START);
        directory.addView(directoryTitle);
        directoryValue = text(displayDirectory(), 16, TEXT, true, Gravity.START);
        directoryValue.setPadding(0, dp(6), 0, dp(2));
        directory.addView(directoryValue);
        directory.setOnClickListener(v -> cycleDirectory());
        card.addView(directory);
        card.addView(space(12));

        LinearLayout linkPanel = panel();
        linkPanel.addView(text("下载链接", 13, MUTED, false, Gravity.START));
        TextView link = text(url, 13, TEXT, false, Gravity.START);
        link.setMaxLines(3);
        link.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        link.setPadding(0, dp(6), 0, 0);
        link.setOnClickListener(v -> copyLink());
        linkPanel.addView(link);
        card.addView(linkPanel);
        card.addView(space(16));

        Button download = button("开始下载", true);
        download.setOnClickListener(v -> enqueue());
        card.addView(download, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        card.addView(space(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button menu = button("复制链接", false);
        menu.setOnClickListener(v -> copyLink());
        Button cancel = button("取消", false);
        cancel.setOnClickListener(v -> finish());
        actions.addView(menu, new LinearLayout.LayoutParams(0, dp(52), 1f));
        actions.addView(horizontalSpace(10));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1f));
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(card);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return screen;
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

    private void addInfo(LinearLayout parent, String name, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView key = text(name, 14, MUTED, false, Gravity.START);
        TextView val = text(value, 14, TEXT, false, Gravity.END);
        val.setMaxLines(2);
        row.addView(key, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.38f));
        row.addView(val, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.62f));
        parent.addView(row);
        parent.addView(space(7));
    }

    private void cycleDirectory() {
        String current = preferences.getString("default_path", "Download");
        String next;
        if ("Download".equals(current)) {
            next = "Download/APK";
        } else if ("Download/APK".equals(current)) {
            next = "Download/Archive";
        } else if ("Download/Archive".equals(current)) {
            next = "Download/Video";
        } else {
            next = "Download";
        }
        preferences.edit().putString("default_path", next).apply();
        directoryValue.setText(next);
    }

    private String displayDirectory() {
        return preferences.getString("default_path", "Download");
    }

    private void enqueue() {
        String name = fileName.getText().toString().trim();
        if (name.isEmpty()) {
            name = guessName(url);
        }
        name = sanitizeFileName(name);

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription("系统下载加速 · " + sourceLabel);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (!TextUtils.isEmpty(mime)) {
                request.setMimeType(mime);
            }
            restoreHeaders(request);

            String configured = displayDirectory();
            String relative = configured;
            if (relative.startsWith("Download/")) {
                relative = relative.substring("Download/".length());
            } else if ("Download".equals(relative)) {
                relative = "";
            }
            String relativeFile = relative.isEmpty() ? name : relative + "/" + name;
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, relativeFile);

            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            long id = manager.enqueue(request);
            Toast.makeText(this, "已加入系统下载，ID " + id, Toast.LENGTH_SHORT).show();
            finish();
        } catch (Throwable error) {
            Toast.makeText(this,
                    "无法开始下载：" + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void restoreHeaders(DownloadManager.Request request) {
        if (headers == null) {
            return;
        }
        for (int index = 0; index + 1 < headers.length; index += 2) {
            String name = headers[index];
            String value = headers[index + 1];
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if ("range".equals(lower)
                    || "connection".equals(lower)
                    || "accept-encoding".equals(lower)) {
                continue;
            }
            try {
                request.addRequestHeader(name, value);
            } catch (Throwable ignored) {
            }
        }
    }

    private void copyLink() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("download-url", url));
        Toast.makeText(this, "下载链接已复制", Toast.LENGTH_SHORT).show();
    }

    private boolean isHttpUrl(String value) {
        try {
            String scheme = Uri.parse(value).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String guessName(String value) {
        try {
            Uri uri = Uri.parse(value);
            String segment = uri.getLastPathSegment();
            if (!TextUtils.isEmpty(segment)) {
                return URLDecoder.decode(segment, StandardCharsets.UTF_8.name());
            }
        } catch (Throwable ignored) {
        }
        return "download.bin";
    }

    private String sanitizeFileName(String value) {
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return sanitized.isEmpty() ? "download.bin" : sanitized;
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
        button.setPadding(dp(12), 0, dp(12), 0);
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

    private View horizontalSpace(int widthDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
