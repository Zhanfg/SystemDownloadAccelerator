package io.github.zhanfg.sda;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class DownloadConfirmActivity extends Activity {
    private static final int REQ_TREE = 2201;
    private static final int TEXT = Color.rgb(24, 33, 38);
    private static final int SECONDARY = Color.rgb(104, 119, 126);
    private SharedPreferences prefs;
    private EditText fileName;
    private TextView pathText;
    private String url;
    private String mime;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("module_settings", MODE_PRIVATE);
        url = getIntent().getStringExtra("url");
        if (url == null) url = "https://example.com/file.bin";
        mime = getIntent().getStringExtra("mime");
        String name = getIntent().getStringExtra("fileName");
        if (name == null) name = guessName(url);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = getWindow();
        w.setDimAmount(0.45f);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        setFinishOnTouchOutside(false);
        setContentView(build(name));
    }

    private View build(String name) {
        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(16), dp(24), dp(16), dp(24));
        outer.setBackgroundColor(Color.TRANSPARENT);

        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(22), dp(25), dp(22), dp(22));
        panel.setBackground(getDrawable(R.drawable.bg_card));
        scroll.addView(panel);

        TextView icon = text("⇩", 40, Color.rgb(36, 108, 130), true);
        icon.setGravity(Gravity.CENTER);
        panel.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView title = text("下载此文件？", 24, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(12));
        panel.addView(title);

        fileName = new EditText(this);
        fileName.setText(name);
        fileName.setSingleLine(true);
        fileName.setGravity(Gravity.CENTER);
        fileName.setTextColor(TEXT);
        fileName.setTextSize(17);
        fileName.setBackground(getDrawable(R.drawable.bg_secondary_button));
        fileName.setPadding(dp(12), dp(11), dp(12), dp(11));
        panel.addView(fileName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(space(12));

        addInfo(panel, "文件大小", safe(getIntent().getStringExtra("size"), "未知"));
        addInfo(panel, "来源", safe(getIntent().getStringExtra("source"), "系统下载请求"));
        addInfo(panel, "下载线程", "自动 · 上限 " + prefs.getInt("max_threads", 1024));

        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout pathLabels = new LinearLayout(this);
        pathLabels.setOrientation(LinearLayout.VERTICAL);
        pathLabels.addView(text("保存位置", 13, SECONDARY, false));
        pathText = text(prefs.getString("default_path", "Download"), 16, TEXT, true);
        pathLabels.addView(pathText);
        Button choose = secondaryButton("更改");
        choose.setOnClickListener(v -> choosePath());
        pathRow.addView(pathLabels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pathRow.addView(choose, new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT));
        pathRow.setPadding(0, dp(8), 0, dp(10));
        panel.addView(pathRow);

        TextView link = text(url, 12, SECONDARY, false);
        link.setMaxLines(2);
        link.setPadding(0, dp(6), 0, dp(14));
        link.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("下载链接", url));
            Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
        });
        panel.addView(link);

        Button start = primaryButton("开始下载");
        start.setOnClickListener(v -> enqueue());
        panel.addView(start, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(space(9));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        Button menu = secondaryButton("使用系统原始下载");
        menu.setOnClickListener(v -> enqueue());
        Button cancel = secondaryButton("取消");
        cancel.setOnClickListener(v -> finish());
        bottom.addView(menu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(horizontalSpace(8));
        bottom.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(bottom);

        int widthDp = getResources().getConfiguration().screenWidthDp;
        int max = Math.min(widthDp - 32, 560);
        outer.addView(scroll, new LinearLayout.LayoutParams(dp(max), ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private void enqueue() {
        String finalName = fileName.getText().toString().trim();
        if (finalName.isEmpty()) finalName = guessName(url);
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(finalName);
            request.setDescription("系统下载加速");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (mime != null) request.setMimeType(mime);
            String path = prefs.getString("default_path", "Download");
            String relative = path;
            if (relative.startsWith("Download/")) relative = relative.substring("Download/".length());
            else if (relative.equals("Download")) relative = "";
            else relative = "";
            String child = relative.isEmpty() ? finalName : relative + "/" + finalName;
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, child);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            long id = dm.enqueue(request);
            Toast.makeText(this, "已加入系统下载，ID " + id, Toast.LENGTH_LONG).show();
            finish();
        } catch (Throwable t) {
            Toast.makeText(this, "无法开始下载：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void choosePath() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        String old = prefs.getString("tree_uri", null);
        if (old != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(old));
        startActivityForResult(intent, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Throwable ignored) { }
            String path;
            try {
                String doc = DocumentsContract.getTreeDocumentId(uri);
                path = doc.startsWith("primary:") ? doc.substring(8) : doc.replace(':', '/');
            } catch (Throwable t) { path = uri.toString(); }
            prefs.edit().putString("tree_uri", uri.toString()).putString("default_path", path).apply();
            pathText.setText(path);
        }
    }

    private void addInfo(LinearLayout parent, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.addView(text(key, 13, SECONDARY, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView right = text(value, 14, TEXT, true);
        right.setGravity(Gravity.END);
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        parent.addView(row);
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setAllCaps(false);
        b.setBackground(getDrawable(R.drawable.bg_primary_button)); b.setMinHeight(dp(54));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(TEXT); b.setTextSize(13); b.setAllCaps(false);
        b.setBackground(getDrawable(R.drawable.bg_secondary_button)); b.setMinHeight(dp(50));
        return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private View space(int dp) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp))); return v; }
    private View horizontalSpace(int dp) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(dp), 1)); return v; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String safe(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private String guessName(String value) {
        try {
            String last = Uri.parse(value).getLastPathSegment();
            return last == null || last.isEmpty() ? "download.bin" : last;
        } catch (Throwable t) { return "download.bin"; }
    }
}
