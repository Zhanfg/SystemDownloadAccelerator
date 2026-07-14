package io.github.zhanfg.sda;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

/**
 * Compact adaptive UI inspired by InstallerX's Material 3 Expressive information hierarchy.
 * No InstallerX source code is copied; this activity uses platform views only.
 */
public final class ModernMainActivity extends Activity {
    private static final int PAGE_HOME = 0;
    private static final int PAGE_CONFIG = 1;
    private static final int PAGE_HISTORY = 2;
    private static final int PAGE_SETTINGS = 3;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private FrameLayout pageHost;
    private LinearLayout navigation;
    private TextView titleView;
    private TextView subtitleView;
    private int selectedPage = PAGE_HOME;

    private int background;
    private int surface;
    private int surfaceHigh;
    private int primary;
    private int onPrimary;
    private int textPrimary;
    private int textSecondary;
    private int success;
    private int warning;
    private int error;

    private final ContentObserver historyObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (selectedPage == PAGE_HISTORY || selectedPage == PAGE_HOME) {
                showPage(selectedPage, false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        preferences = getSharedPreferences("module_settings", MODE_PRIVATE);
        configureWindow();
        resolveColors();

        View root = buildRoot();
        SystemBarInsets.apply(root);
        setContentView(root);
        showPage(PAGE_HOME, false);
        getContentResolver().registerContentObserver(
                HistoryProvider.RECORDS_URI, true, historyObserver);
    }

    @Override
    protected void onDestroy() {
        getContentResolver().unregisterContentObserver(historyObserver);
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
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
        onPrimary = Color.WHITE;
        success = dark ? Color.rgb(95, 211, 154) : Color.rgb(20, 127, 81);
        warning = dark ? Color.rgb(246, 190, 92) : Color.rgb(165, 103, 0);
        error = dark ? Color.rgb(255, 180, 171) : Color.rgb(186, 26, 26);

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                primary = getColor(android.R.color.system_accent1_600);
            } catch (Throwable ignored) {
                primary = Color.rgb(42, 122, 143);
            }
        } else {
            primary = Color.rgb(42, 122, 143);
        }
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        pageHost = new FrameLayout(this);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(pageHost, hostParams);

        navigation = buildNavigation();
        root.addView(navigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(10), dp(20), dp(8));

        titleView = text("系统下载加速", 25, textPrimary, true);
        subtitleView = text("下载服务 · API 102", 13, textSecondary, false);
        subtitleView.setPadding(0, dp(2), 0, 0);
        bar.addView(titleView);
        bar.addView(subtitleView);
        return bar;
    }

    private LinearLayout buildNavigation() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(12), dp(7), dp(12), dp(7));
        bar.setBackgroundColor(surface);

        addNavItem(bar, PAGE_HOME, android.R.drawable.ic_menu_view, "首页");
        addNavItem(bar, PAGE_CONFIG, android.R.drawable.ic_menu_manage, "配置");
        addNavItem(bar, PAGE_HISTORY, android.R.drawable.ic_menu_recent_history, "记录");
        addNavItem(bar, PAGE_SETTINGS, android.R.drawable.ic_menu_preferences, "设置");
        return bar;
    }

    private void addNavItem(LinearLayout parent, int page, int iconRes, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(8), dp(5), dp(8), dp(4));
        item.setTag(page);
        item.setOnClickListener(v -> showPage((Integer) v.getTag(), true));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(textSecondary);
        item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView text = text(label, 12, textSecondary, false);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(3), 0, 0);
        item.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(item, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void showPage(int page, boolean animate) {
        selectedPage = page;
        updateNavigation();
        updateTitle(page);

        View content;
        if (page == PAGE_HOME) {
            content = buildHomePage();
        } else if (page == PAGE_CONFIG) {
            content = buildConfigPage();
        } else if (page == PAGE_HISTORY) {
            content = buildHistoryPage();
        } else {
            content = buildSettingsPage();
        }

        pageHost.removeAllViews();
        pageHost.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (animate) {
            content.setAlpha(0f);
            content.setTranslationY(dp(10));
            content.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void updateTitle(int page) {
        String[] titles = {"系统下载加速", "下载配置", "下载记录", "模块设置"};
        String[] subtitles = {
                "下载服务 · API 102",
                "安全回退 · 动态分段",
                "来自系统下载服务的真实记录",
                "兼容性、诊断与外观"
        };
        titleView.setText(titles[page]);
        subtitleView.setText(subtitles[page]);
    }

    private void updateNavigation() {
        for (int index = 0; index < navigation.getChildCount(); index++) {
            LinearLayout item = (LinearLayout) navigation.getChildAt(index);
            boolean selected = ((Integer) item.getTag()) == selectedPage;
            item.setBackground(selected ? roundRect(surfaceHigh, 22) : null);
            ImageView icon = (ImageView) item.getChildAt(0);
            TextView label = (TextView) item.getChildAt(1);
            icon.setColorFilter(selected ? primary : textSecondary);
            label.setTextColor(selected ? primary : textSecondary);
            label.setTypeface(Typeface.DEFAULT,
                    selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private View buildHomePage() {
        LinearLayout content = pageColumn();
        List<HistoryItem> items = loadHistory();
        int active = 0;
        int completed = 0;
        int failed = 0;
        for (HistoryItem item : items) {
            if (isSuccess(item.status)) completed++;
            else if (isFailure(item.status)) failed++;
            else active++;
        }

        LinearLayout hero = card(primary, 26);
        TextView state = text("模块已启用", 21, onPrimary, true);
        TextView detail = text("系统下载服务统一拦截 · 异常自动回退", 14,
                withAlpha(onPrimary, 210), false);
        detail.setPadding(0, dp(6), 0, dp(16));
        hero.addView(state);
        hero.addView(detail);
        hero.addView(metricRow(
                metric("进行中", String.valueOf(active), onPrimary),
                metric("已完成", String.valueOf(completed), onPrimary),
                metric("失败", String.valueOf(failed), onPrimary)
        ));
        content.addView(hero);

        content.addView(sectionLabel("下载服务"));
        LinearLayout service = card(surface, 22);
        service.addView(infoRow("核心作用域", "com.android.providers.downloads"));
        service.addView(divider());
        service.addView(infoRow("Modern Xposed API", "102"));
        service.addView(divider());
        service.addView(infoRow("最高线程配置", String.valueOf(
                preferences.getInt("max_threads", 1024))));
        content.addView(service);

        content.addView(sectionLabel("快速操作"));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button history = button("查看记录", true);
        history.setOnClickListener(v -> showPage(PAGE_HISTORY, true));
        Button folder = button("打开下载目录", false);
        folder.setOnClickListener(v -> openDirectory(null));
        actions.addView(history, new LinearLayout.LayoutParams(0, dp(52), 1f));
        actions.addView(spaceHorizontal(10));
        actions.addView(folder, new LinearLayout.LayoutParams(0, dp(52), 1f));
        content.addView(actions);

        return scroll(content);
    }

    private View buildConfigPage() {
        LinearLayout content = pageColumn();

        content.addView(sectionLabel("下载确认"));
        LinearLayout confirmation = card(surface, 22);
        confirmation.addView(switchRow("下载前显示确认窗口",
                "所有进入系统 DownloadManager 的任务",
                "confirmation_enabled", true));
        confirmation.addView(divider());
        confirmation.addView(switchRow("显示完整下载链接",
                "界面显示，日志仍自动脱敏",
                "show_full_url", true));
        content.addView(confirmation);

        content.addView(sectionLabel("多线程"));
        LinearLayout threads = card(surface, 22);
        threads.addView(switchRow("启用多线程 Range 下载",
                "不支持 Range 时使用系统原始传输",
                "enabled", true));
        threads.addView(divider());
        threads.addView(numberRow("最高线程数", "允许范围 1–1024",
                "max_threads", 1024));
        threads.addView(divider());
        threads.addView(numberRow("初始线程数", "自动调度会继续调整",
                "initial_threads", 4));
        threads.addView(divider());
        threads.addView(numberRow("启用阈值（MiB）", "小文件保持系统单线程",
                "min_size_mb", 32));
        threads.addView(divider());
        threads.addView(switchRow("严格校验 Range",
                "验证 206、Content-Range 与 ETag",
                "strict_range", true));
        threads.addView(divider());
        threads.addView(switchRow("失败时自动回退",
                "避免下载服务崩溃或文件损坏",
                "auto_fallback", true));
        content.addView(threads);

        content.addView(sectionLabel("保存位置"));
        LinearLayout path = card(surface, 22);
        path.addView(infoRow("默认目录", "Download"));
        path.addView(divider());
        TextView note = text("应用明确指定目录时默认尊重应用设置。任意目录选择将在后续版本通过 SAF 持久授权实现。",
                13, textSecondary, false);
        note.setPadding(0, dp(12), 0, 0);
        path.addView(note);
        content.addView(path);
        return scroll(content);
    }

    private View buildHistoryPage() {
        LinearLayout content = pageColumn();

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("刷新", false);
        refresh.setOnClickListener(v -> showPage(PAGE_HISTORY, false));
        Button directory = button("打开默认目录", false);
        directory.setOnClickListener(v -> openDirectory(null));
        toolbar.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1f));
        toolbar.addView(spaceHorizontal(10));
        toolbar.addView(directory, new LinearLayout.LayoutParams(0, dp(48), 1f));
        content.addView(toolbar);

        List<HistoryItem> history = loadHistory();
        if (history.isEmpty()) {
            LinearLayout empty = card(surface, 24);
            empty.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageResource(android.R.drawable.ic_menu_recent_history);
            icon.setColorFilter(textSecondary);
            empty.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView title = text("暂无下载记录", 18, textPrimary, true);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(14), 0, dp(6));
            empty.addView(title);
            TextView body = text("这里仅显示系统下载服务真实同步的数据，不再放置演示记录。",
                    14, textSecondary, false);
            body.setGravity(Gravity.CENTER);
            empty.addView(body);
            content.addView(empty);
            return scroll(content);
        }

        for (HistoryItem item : history) {
            content.addView(historyCard(item));
        }
        return scroll(content);
    }

    private View buildSettingsPage() {
        LinearLayout content = pageColumn();

        content.addView(sectionLabel("外观"));
        LinearLayout appearance = card(surface, 22);
        appearance.addView(infoRow("界面风格", "Material 3 Expressive 结构"));
        appearance.addView(divider());
        appearance.addView(infoRow("配色", Build.VERSION.SDK_INT >= 31
                ? "跟随系统动态色" : "青蓝固定主题"));
        content.addView(appearance);

        content.addView(sectionLabel("诊断"));
        LinearLayout diagnostics = card(surface, 22);
        diagnostics.addView(infoRow("模块入口", "Real engine + system confirmation"));
        diagnostics.addView(divider());
        diagnostics.addView(infoRow("历史同步", "DownloadProvider → HistoryProvider"));
        diagnostics.addView(divider());
        diagnostics.addView(infoRow("日志标签", "SysDownloadAccel / SysDownloadConfirm"));
        content.addView(diagnostics);

        Button clear = button("清除历史记录", false);
        clear.setTextColor(error);
        clear.setOnClickListener(v -> {
            int count = getContentResolver().delete(HistoryProvider.RECORDS_URI,
                    null, null);
            Toast.makeText(this, "已清除 " + count + " 条记录", Toast.LENGTH_SHORT).show();
        });
        content.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return scroll(content);
    }

    private View historyCard(HistoryItem item) {
        LinearLayout card = card(surface, 22);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconForMime(item.mimeType));
        icon.setColorFilter(primary);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconParams.rightMargin = dp(12);
        header.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(empty(item.title) ? "未命名下载" : item.title,
                16, textPrimary, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(name);
        TextView meta = text(statusText(item.status) + " · "
                        + formatSize(item.totalBytes) + " · " + formatDate(item.lastModified),
                13, statusColor(item.status), false);
        meta.setPadding(0, dp(4), 0, 0);
        labels.addView(meta);
        header.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(header);

        if (!isTerminal(item.status) && item.totalBytes > 0) {
            ProgressBar progress = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgress((int) Math.min(1000,
                    item.currentBytes * 1000L / Math.max(1L, item.totalBytes)));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
            progressParams.topMargin = dp(14);
            card.addView(progress, progressParams);
        }

        if (!empty(item.errorText) && isFailure(item.status)) {
            TextView reason = text(item.errorText, 13, error, false);
            reason.setPadding(0, dp(10), 0, 0);
            reason.setMaxLines(2);
            card.addView(reason);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(14), 0, 0);
        Button open = button("打开文件", true);
        open.setEnabled(isSuccess(item.status));
        open.setAlpha(open.isEnabled() ? 1f : 0.45f);
        open.setOnClickListener(v -> openFile(item));
        Button folder = button("所在目录", false);
        folder.setOnClickListener(v -> openDirectory(item));
        actions.addView(open, new LinearLayout.LayoutParams(0, dp(46), 1f));
        actions.addView(spaceHorizontal(10));
        actions.addView(folder, new LinearLayout.LayoutParams(0, dp(46), 1f));
        card.addView(actions);
        return card;
    }

    private List<HistoryItem> loadHistory() {
        List<HistoryItem> items = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(HistoryProvider.RECORDS_URI,
                    null, null, null, "last_modified DESC, download_id DESC");
            if (cursor == null) {
                return items;
            }
            int limit = 200;
            while (cursor.moveToNext() && items.size() < limit) {
                HistoryItem item = new HistoryItem();
                item.id = longColumn(cursor, "download_id", -1);
                item.title = stringColumn(cursor, "title");
                item.sourceUrl = stringColumn(cursor, "source_url");
                item.sourcePackage = stringColumn(cursor, "source_package");
                item.mimeType = stringColumn(cursor, "mime_type");
                item.status = (int) longColumn(cursor, "status", 190);
                item.totalBytes = longColumn(cursor, "total_bytes", -1);
                item.currentBytes = longColumn(cursor, "current_bytes", 0);
                item.lastModified = longColumn(cursor, "last_modified", 0);
                item.localUri = stringColumn(cursor, "local_uri");
                item.localPath = stringColumn(cursor, "local_path");
                item.destinationHint = stringColumn(cursor, "destination_hint");
                item.errorText = stringColumn(cursor, "error_text");
                items.add(item);
            }
        } catch (Throwable error) {
            Toast.makeText(this, "读取下载历史失败：" + error.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        } finally {
            if (cursor != null) cursor.close();
        }
        return items;
    }

    private void openFile(HistoryItem item) {
        Uri uri = parseUri(item.localUri);
        if (uri == null && item.id >= 0) {
            try {
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                uri = manager.getUriForDownloadedFile(item.id);
            } catch (Throwable ignored) {
            }
        }
        if (uri == null && item.id >= 0) {
            uri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"), item.id);
        }
        if (uri == null) {
            Toast.makeText(this, "没有可授权的文件 URI", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, empty(item.mimeType) ? "*/*" : item.mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(intent, "打开文件"));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有应用可以打开该文件", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDirectory(HistoryItem item) {
        String documentId = "primary:Download";
        if (item != null) {
            String path = !empty(item.localPath) ? item.localPath : item.destinationHint;
            String derived = deriveDocumentId(path);
            if (!empty(derived)) {
                documentId = derived;
            }
        }
        Uri tree = DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", documentId);

        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(tree, DocumentsContract.Document.MIME_TYPE_DIR);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(view, "选择文件管理器"));
            return;
        } catch (Throwable ignored) {
        }

        Intent treePicker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        treePicker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26) {
            treePicker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, tree);
        }
        try {
            startActivity(treePicker);
        } catch (Throwable error) {
            Toast.makeText(this, "无法打开目录", Toast.LENGTH_SHORT).show();
        }
    }

    private String deriveDocumentId(String path) {
        if (empty(path)) return null;
        String normalized = Uri.decode(path);
        int download = normalized.indexOf("/Download");
        if (download < 0) download = normalized.indexOf("/download");
        if (download < 0) return null;
        String relative = normalized.substring(download + 1);
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash > 0 && relative.substring(lastSlash + 1).contains(".")) {
            relative = relative.substring(0, lastSlash);
        }
        return "primary:" + relative;
    }

    private LinearLayout pageColumn() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(28));
        return content;
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        FrameLayout center = new FrameLayout(this);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(760));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        center.addView(content, params);
        scroll.addView(center, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout card(int color, int radius) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setBackground(roundRect(color, radius));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        card.setElevation(dp(1));
        return card;
    }

    private TextView sectionLabel(String value) {
        TextView label = text(value, 14, textSecondary, true);
        label.setPadding(dp(4), dp(10), dp(4), dp(8));
        return label;
    }

    private View infoRow(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = text(key, 15, textPrimary, false);
        TextView right = text(value, 14, textSecondary, false);
        right.setGravity(Gravity.END);
        right.setMaxLines(2);
        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.48f));
        row.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.52f));
        row.setPadding(0, dp(5), 0, dp(5));
        return row;
    }

    private View switchRow(String title, String description, String key, boolean defaultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, textPrimary, false));
        TextView sub = text(description, 12, textSecondary, false);
        sub.setPadding(0, dp(3), dp(10), 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(key, checked).apply());
        row.addView(toggle);
        return row;
    }

    private View numberRow(String title, String description, String key, int defaultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, textPrimary, false));
        TextView sub = text(description, 12, textSecondary, false);
        sub.setPadding(0, dp(3), dp(10), 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText value = new EditText(this);
        value.setInputType(InputType.TYPE_CLASS_NUMBER);
        value.setSingleLine(true);
        value.setText(String.valueOf(preferences.getInt(key, defaultValue)));
        value.setTextColor(textPrimary);
        value.setGravity(Gravity.CENTER);
        value.setBackground(roundRect(surfaceHigh, 14));
        value.setPadding(dp(8), 0, dp(8), 0);
        value.setOnFocusChangeListener((view, focused) -> {
            if (!focused) saveNumber((EditText) view, key, defaultValue);
        });
        row.addView(value, new LinearLayout.LayoutParams(dp(82), dp(46)));
        return row;
    }

    private void saveNumber(EditText input, String key, int fallback) {
        int value = fallback;
        try {
            value = Integer.parseInt(input.getText().toString());
        } catch (Throwable ignored) {
        }
        value = Math.max(1, Math.min(1024, value));
        input.setText(String.valueOf(value));
        preferences.edit().putInt(key, value).apply();
    }

    private View metricRow(View... metrics) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (View metric : metrics) {
            row.addView(metric, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private View metric(String label, String value, int color) {
        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.VERTICAL);
        metric.setGravity(Gravity.CENTER);
        TextView number = text(value, 23, color, true);
        number.setGravity(Gravity.CENTER);
        TextView caption = text(label, 12, withAlpha(color, 210), false);
        caption.setGravity(Gravity.CENTER);
        metric.addView(number);
        metric.addView(caption);
        return metric;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(withAlpha(textSecondary, 32));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(8);
        params.bottomMargin = dp(8);
        divider.setLayoutParams(params);
        return divider;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button button(String label, boolean filled) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(filled ? onPrimary : textPrimary);
        button.setBackground(roundRect(filled ? primary : surfaceHigh, 18));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private Space spaceHorizontal(int widthDp) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        return space;
    }

    private int iconForMime(String mime) {
        if (mime == null) return android.R.drawable.ic_menu_save;
        if (mime.startsWith("image/")) return android.R.drawable.ic_menu_gallery;
        if (mime.startsWith("video/") || mime.startsWith("audio/"))
            return android.R.drawable.ic_media_play;
        if (mime.contains("android.package-archive"))
            return android.R.drawable.sym_def_app_icon;
        return android.R.drawable.ic_menu_save;
    }

    private int statusColor(int status) {
        if (isSuccess(status)) return success;
        if (isFailure(status)) return error;
        if (status == DownloadManager.STATUS_PAUSED || status == 193
                || status == 194 || status == 195 || status == 196) return warning;
        return primary;
    }

    private String statusText(int status) {
        if (status == DownloadManager.STATUS_SUCCESSFUL || status == 200) return "已完成";
        if (status == DownloadManager.STATUS_FAILED || status >= 400) return "失败";
        if (status == DownloadManager.STATUS_RUNNING || status == 192) return "下载中";
        if (status == DownloadManager.STATUS_PAUSED || status == 193
                || status == 194 || status == 195 || status == 196) return "已暂停";
        return "等待中";
    }

    private boolean isSuccess(int status) {
        return status == DownloadManager.STATUS_SUCCESSFUL || status == 200;
    }

    private boolean isFailure(int status) {
        return status == DownloadManager.STATUS_FAILED || status >= 400;
    }

    private boolean isTerminal(int status) {
        return isSuccess(status) || isFailure(status);
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "大小未知";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0
                ? String.format(Locale.ROOT, "%.0f %s", value, units[unit])
                : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String formatDate(long time) {
        if (time <= 0) return "时间未知";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(time));
    }

    private static long longColumn(Cursor cursor, String name, long fallback) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }

    private static String stringColumn(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static Uri parseUri(String value) {
        if (empty(value)) return null;
        try {
            return Uri.parse(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class HistoryItem {
        long id;
        String title;
        String sourceUrl;
        String sourcePackage;
        String mimeType;
        int status;
        long totalBytes;
        long currentBytes;
        long lastModified;
        String localUri;
        String localPath;
        String destinationHint;
        String errorText;
    }
}
