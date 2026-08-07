package io.github.zhanfg.sda;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_TREE = 2101;
    private static final String PREFS = "module_settings";
    private static final int ACCENT = Color.rgb(36, 108, 130);
    private static final int TEXT = Color.rgb(24, 33, 38);
    private static final int SECONDARY = Color.rgb(104, 119, 126);
    private static final int BG = Color.rgb(245, 245, 250);
    private static final int SURFACE = Color.WHITE;

    private SharedPreferences prefs;
    private FrameLayout content;
    private LinearLayout nav;
    private final List<TextView> navItems = new ArrayList<>();
    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        seedDefaults();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildRoot());
        showTab(0);
    }

    private void seedDefaults() {
        if (!prefs.contains("initialized")) {
            prefs.edit()
                    .putBoolean("initialized", true)
                    .putBoolean("enabled", true)
                    .putBoolean("confirm", true)
                    .putBoolean("dynamic_split", true)
                    .putBoolean("strict_range", true)
                    .putBoolean("auto_fallback", true)
                    .putBoolean("classify", true)
                    .putInt("max_threads", 1024)
                    .putInt("initial_threads", 4)
                    .putInt("mobile_threads", 2)
                    .putInt("min_size_mb", 32)
                    .putString("default_path", "Download")
                    .apply();
        }
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(7), dp(8), dp(9));
        String[] labels = {"首页", "配置", "记录", "设置"};
        String[] icons = {"⌂", "≡", "◷", "⚙"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setText(icons[i] + "\n" + labels[i]);
            item.setGravity(Gravity.CENTER);
            item.setTextSize(12);
            item.setLineSpacing(0, 0.92f);
            item.setPadding(dp(4), dp(7), dp(4), dp(7));
            item.setOnClickListener(v -> showTab(index));
            navItems.add(item);
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1f));
        }
        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void showTab(int index) {
        currentTab = index;
        content.removeAllViews();
        for (int i = 0; i < navItems.size(); i++) {
            TextView item = navItems.get(i);
            item.setTextColor(i == index ? ACCENT : SECONDARY);
            item.setTypeface(null, i == index ? Typeface.BOLD : Typeface.NORMAL);
            item.setBackground(i == index ? getDrawable(R.drawable.bg_secondary_button) : null);
        }
        View page;
        if (index == 0) page = homePage();
        else if (index == 1) page = configPage();
        else if (index == 2) page = historyPage();
        else page = settingsPage();
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private ScrollView page(String title) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int side = widthDp >= 840 ? 96 : widthDp >= 600 ? 32 : 16;
        body.setPadding(dp(side), dp(18), dp(side), dp(28));
        TextView heading = text(title, 31, TEXT, true);
        heading.setPadding(0, 0, 0, dp(18));
        body.addView(heading);
        scroll.addView(body);
        scroll.setTag(body);
        return scroll;
    }

    private View homePage() {
        ScrollView scroll = page("首页");
        LinearLayout body = (LinearLayout) scroll.getTag();

        LinearLayout status = card();
        TextView state = text("✓  系统下载增强已启用", 20, Color.rgb(43, 122, 98), true);
        status.addView(state);
        status.addView(spacer(8));
        status.addView(text("作用域：com.android.providers.downloads\n等待 LSPosed API 102 注入后开始工作", 14, SECONDARY, false));
        body.addView(status);
        body.addView(spacer(12));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout m1 = metric(String.valueOf(prefs.getInt("initial_threads", 4)), "初始线程");
        LinearLayout m2 = metric(String.valueOf(prefs.getInt("max_threads", 1024)), "配置上限");
        metrics.addView(m1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        metrics.addView(spacerHorizontal(10));
        metrics.addView(m2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(metrics);
        body.addView(spacer(20));

        body.addView(sectionTitle("当前状态"));
        LinearLayout info = card();
        addKeyValue(info, "模块版本", "0.1.0-alpha1");
        addKeyValue(info, "LSPosed API", "102");
        addKeyValue(info, "Android", android.os.Build.VERSION.RELEASE + " · API " + android.os.Build.VERSION.SDK_INT);
        addKeyValue(info, "默认目录", prefs.getString("default_path", "Download"));
        addKeyValue(info, "下载前确认", prefs.getBoolean("confirm", true) ? "已开启" : "已关闭");
        body.addView(info);
        body.addView(spacer(12));

        Button preview = primaryButton("预览下载确认弹窗");
        preview.setOnClickListener(v -> {
            Intent intent = new Intent(this, DownloadConfirmActivity.class);
            intent.putExtra("url", "https://example.com/releases/SystemDownloadAccelerator.apk");
            intent.putExtra("fileName", "SystemDownloadAccelerator.apk");
            intent.putExtra("size", "12.8 MB");
            intent.putExtra("source", "Via 浏览器");
            startActivity(intent);
        });
        body.addView(preview);
        return scroll;
    }

    private View configPage() {
        ScrollView scroll = page("配置");
        LinearLayout body = (LinearLayout) scroll.getTag();

        body.addView(sectionTitle("下载确认"));
        LinearLayout confirmCard = card();
        addSwitch(confirmCard, "下载前显示确认弹窗", "允许修改文件名、保存目录和当前任务线程", "confirm");
        addSwitch(confirmCard, "显示完整下载链接", "链接可复制，敏感查询参数不会写入日志", "show_url");
        addSwitch(confirmCard, "记住上次保存位置", "下一次优先打开最近使用的目录", "remember_path");
        body.addView(confirmCard);
        body.addView(spacer(18));

        body.addView(sectionTitle("保存位置"));
        LinearLayout pathCard = card();
        TextView currentPath = text("默认下载目录\n" + prefs.getString("default_path", "Download"), 16, TEXT, true);
        currentPath.setPadding(0, 0, 0, dp(10));
        pathCard.addView(currentPath);
        Button choose = secondaryButton("选择目录");
        choose.setOnClickListener(v -> chooseDirectory());
        pathCard.addView(choose);
        addSwitch(pathCard, "按文件类型自动分类", "APK、压缩包、图片、视频和文档使用独立子目录", "classify");
        body.addView(pathCard);
        body.addView(spacer(18));

        body.addView(sectionTitle("多线程下载"));
        LinearLayout threadCard = card();
        addSwitch(threadCard, "多线程下载增强", "服务器支持 HTTP Range 时启用", "enabled");
        addNumber(threadCard, "最高线程数", "允许范围 1–1024；自动模式不会直接创建 1024 个连接", "max_threads", 1024, 1, 1024);
        addNumber(threadCard, "初始线程数", "每个新任务最初建立的连接数量", "initial_threads", 4, 1, 64);
        addNumber(threadCard, "移动网络线程数", "移动网络下的初始连接数量", "mobile_threads", 2, 1, 64);
        addNumber(threadCard, "启用阈值（MiB）", "小于此大小时使用系统原始单线程", "min_size_mb", 32, 1, 4096);
        addSwitch(threadCard, "动态分段", "空闲线程可接管尚未完成的大分段", "dynamic_split");
        addSwitch(threadCard, "严格验证 Range", "检查 206、Content-Range、ETag 和文件长度", "strict_range");
        addSwitch(threadCard, "异常时自动回退", "服务器拒绝多连接时切换到系统下载逻辑", "auto_fallback");
        body.addView(threadCard);
        return scroll;
    }

    private View historyPage() {
        ScrollView scroll = page("下载记录");
        LinearLayout body = (LinearLayout) scroll.getTag();
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = secondaryButton("刷新");
        refresh.setOnClickListener(v -> showTab(2));
        Button openDefault = secondaryButton("打开默认目录");
        openDefault.setOnClickListener(v -> openDirectory(null));
        actions.addView(refresh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(spacerHorizontal(8));
        actions.addView(openDefault, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(actions);
        body.addView(spacer(14));

        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL | DownloadManager.STATUS_FAILED | DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_PENDING);
        int count = 0;
        try (Cursor c = dm.query(query)) {
            if (c != null) {
                int idCol = c.getColumnIndex(DownloadManager.COLUMN_ID);
                int titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
                int statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int sizeCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int localCol = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                int mimeCol = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
                int modifiedCol = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
                while (c.moveToNext() && count < 50) {
                    long id = idCol >= 0 ? c.getLong(idCol) : -1;
                    String title = titleCol >= 0 ? c.getString(titleCol) : "未命名下载";
                    int status = statusCol >= 0 ? c.getInt(statusCol) : 0;
                    long size = sizeCol >= 0 ? c.getLong(sizeCol) : -1;
                    String local = localCol >= 0 ? c.getString(localCol) : null;
                    String mime = mimeCol >= 0 ? c.getString(mimeCol) : null;
                    long modified = modifiedCol >= 0 ? c.getLong(modifiedCol) : 0;
                    body.addView(historyCard(dm, id, title, status, size, local, mime, modified));
                    body.addView(spacer(10));
                    count++;
                }
            }
        } catch (Throwable t) {
            body.addView(messageCard("无法读取系统下载记录", t.getClass().getSimpleName() + ": " + safe(t.getMessage())));
        }
        if (count == 0) {
            body.addView(messageCard("暂无下载记录", "完成系统下载后，可在此直接打开文件或选择文件管理器定位目录。"));
        }
        return scroll;
    }

    private View settingsPage() {
        ScrollView scroll = page("设置");
        LinearLayout body = (LinearLayout) scroll.getTag();

        body.addView(sectionTitle("模块"));
        LinearLayout module = card();
        addKeyValue(module, "API", "libxposed 102.0.0");
        addKeyValue(module, "目标包", "com.android.providers.downloads");
        addKeyValue(module, "固件适配", "Android 16 / ColorOS 下载服务 16.0.0");
        addKeyValue(module, "Hook 通道", "后台 d.u · 前台 e.u");
        body.addView(module);
        body.addView(spacer(14));

        Button appSettings = secondaryButton("打开应用系统设置");
        appSettings.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        body.addView(appSettings);
        body.addView(spacer(10));

        Button reset = secondaryButton("恢复推荐设置");
        reset.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            seedDefaults();
            Toast.makeText(this, "已恢复推荐设置", Toast.LENGTH_SHORT).show();
            showTab(3);
        });
        body.addView(reset);
        body.addView(spacer(18));

        body.addView(sectionTitle("说明"));
        body.addView(messageCard("Alpha 版本", "界面、路径选择、系统下载历史与 API 102 模块入口已完成。多线程引擎会在真机验证后逐步启用，Hook 失败时保持系统原始下载逻辑。"));
        return scroll;
    }

    private LinearLayout historyCard(DownloadManager dm, long id, String title, int status, long size, String local, String mime, long modified) {
        LinearLayout card = card();
        TextView name = text(title == null ? "未命名下载" : title, 17, TEXT, true);
        card.addView(name);
        String detail = statusLabel(status) + " · " + formatSize(size);
        if (modified > 0) detail += " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(modified));
        card.addView(text(detail, 13, SECONDARY, false));
        if (local != null) {
            TextView path = text(local.replace("file://", ""), 12, SECONDARY, false);
            path.setPadding(0, dp(6), 0, dp(10));
            card.addView(path);
        } else card.addView(spacer(8));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button open = primaryButton("打开文件");
        open.setEnabled(status == DownloadManager.STATUS_SUCCESSFUL);
        open.setOnClickListener(v -> openDownloadedFile(dm, id, mime));
        Button folder = secondaryButton("文件管理器");
        folder.setOnClickListener(v -> openDirectory(local));
        buttons.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(spacerHorizontal(8));
        buttons.addView(folder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(buttons);
        return card;
    }

    private void openDownloadedFile(DownloadManager dm, long id, String mime) {
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            toast("文件不存在或已被移动");
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mime == null ? "*/*" : mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(view, "打开文件"));
        } catch (ActivityNotFoundException e) {
            toast("没有可打开此文件的应用");
        }
    }

    private void openDirectory(String localUri) {
        Uri tree = null;
        String stored = prefs.getString("tree_uri", null);
        if (stored != null && !stored.isEmpty()) tree = Uri.parse(stored);
        if (tree == null) {
            String relative = prefs.getString("default_path", "Download");
            if (relative.startsWith("/storage/emulated/0/")) relative = relative.substring("/storage/emulated/0/".length());
            tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A" + Uri.encode(relative, "/"));
        }

        Intent direct = new Intent(Intent.ACTION_VIEW, tree);
        direct.addCategory(Intent.CATEGORY_DEFAULT);
        direct.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(direct, "选择文件管理器"));
            return;
        } catch (Throwable ignored) { }

        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, tree);
        try {
            startActivityForResult(picker, REQ_TREE);
        } catch (Throwable t) {
            toast("无法打开目录，路径已复制到界面：" + prefs.getString("default_path", "Download"));
        }
    }

    private void chooseDirectory() {
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
            String display = displayPath(uri);
            prefs.edit().putString("tree_uri", uri.toString()).putString("default_path", display).apply();
            toast("默认目录已更新");
            showTab(currentTab);
        }
    }

    private String displayPath(Uri uri) {
        try {
            String doc = DocumentsContract.getTreeDocumentId(uri);
            if (doc.startsWith("primary:")) return doc.substring("primary:".length());
            return doc.replace(':', '/');
        } catch (Throwable t) {
            return uri.toString();
        }
    }

    private void addSwitch(LinearLayout parent, String title, String subtitle, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(8));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, TEXT, true));
        labels.addView(text(subtitle, 12, SECONDARY, false));
        Switch sw = new Switch(this);
        boolean def = !key.equals("show_url") && !key.equals("remember_path");
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);
        parent.addView(row);
    }

    private void addNumber(LinearLayout parent, String title, String subtitle, String key, int def, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, TEXT, true));
        labels.addView(text(subtitle, 12, SECONDARY, false));
        EditText value = new EditText(this);
        value.setSingleLine(true);
        value.setGravity(Gravity.CENTER);
        value.setText(String.valueOf(prefs.getInt(key, def)));
        value.setInputType(InputType.TYPE_CLASS_NUMBER);
        value.setSelectAllOnFocus(true);
        value.setTextColor(TEXT);
        value.setTextSize(16);
        value.setBackground(getDrawable(R.drawable.bg_secondary_button));
        value.setPadding(dp(10), dp(8), dp(10), dp(8));
        value.setOnFocusChangeListener((v, focused) -> {
            if (!focused) {
                int parsed = def;
                try { parsed = Integer.parseInt(value.getText().toString()); } catch (Throwable ignored) { }
                parsed = Math.max(min, Math.min(max, parsed));
                value.setText(String.valueOf(parsed));
                prefs.edit().putInt(key, parsed).apply();
            }
        });
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
    }

    private void addKeyValue(LinearLayout parent, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.addView(text(key, 14, SECONDARY, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView right = text(value, 14, TEXT, true);
        right.setGravity(Gravity.END);
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f));
        parent.addView(row);
    }

    private LinearLayout metric(String value, String label) {
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER);
        TextView number = text(value, 28, ACCENT, true);
        number.setGravity(Gravity.CENTER);
        TextView caption = text(label, 13, SECONDARY, false);
        caption.setGravity(Gravity.CENTER);
        card.addView(number);
        card.addView(caption);
        return card;
    }

    private LinearLayout messageCard(String title, String message) {
        LinearLayout card = card();
        card.addView(text(title, 17, TEXT, true));
        card.addView(spacer(5));
        card.addView(text(message, 13, SECONDARY, false));
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(16), dp(17), dp(16));
        card.setBackground(getDrawable(R.drawable.bg_card));
        return card;
    }

    private TextView sectionTitle(String title) {
        TextView text = text(title, 14, SECONDARY, true);
        text.setPadding(dp(4), 0, 0, dp(8));
        return text;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(getDrawable(R.drawable.bg_primary_button));
        button.setMinHeight(dp(54));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(getDrawable(R.drawable.bg_secondary_button));
        button.setMinHeight(dp(50));
        return button;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Space spacer(int heightDp) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return space;
    }

    private Space spacerHorizontal(int widthDp) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        return space;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String statusLabel(int status) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) return "已完成";
        if (status == DownloadManager.STATUS_FAILED) return "失败";
        if (status == DownloadManager.STATUS_RUNNING) return "下载中";
        if (status == DownloadManager.STATUS_PAUSED) return "已暂停";
        if (status == DownloadManager.STATUS_PENDING) return "等待中";
        return "未知";
    }

    private String formatSize(long bytes) {
        if (bytes < 0) return "大小未知";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
        return String.format(Locale.US, index == 0 ? "%.0f %s" : "%.1f %s", value, units[index]);
    }

    private String safe(String value) { return value == null ? "未知错误" : value; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
