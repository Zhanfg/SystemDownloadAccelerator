package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * Runtime diagnostics UI.
 *
 * It deliberately distinguishes:
 *  1. framework service connection,
 *  2. framework scope enablement,
 *  3. a currently running injected process,
 *  4. this APK generation actually reporting from that target.
 */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 250);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(28, 31, 38);
    private static final int MUTED = Color.rgb(108, 114, 128);
    private static final int GREEN = Color.rgb(24, 140, 76);
    private static final int AMBER = Color.rgb(184, 112, 0);
    private static final int RED = Color.rgb(190, 45, 45);
    private static final int BLUE = Color.rgb(51, 92, 255);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable serviceListener = () -> runOnUiThread(this::refresh);

    private TextView overall;
    private TextView framework;
    private TextView version;
    private StatusCard providerCard;
    private StatusCard downloadsUiCard;
    private StatusCard systemUiCard;
    private Button refreshButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        setContentView(buildUi());
        ModuleApp.addListener(serviceListener);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        ModuleApp.removeListener(serviceListener);
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("System Download Accelerator", 27, TEXT, true);
        root.addView(title);

        TextView subtitle = text("系统下载加速 · LSPosed API 102", 14, MUTED, false);
        LinearLayout.LayoutParams subtitleLp = wrap();
        subtitleLp.topMargin = dp(4);
        root.addView(subtitle, subtitleLp);

        overall = text("正在读取运行状态…", 20, TEXT, true);
        LinearLayout overallBox = cardContainer();
        overallBox.setPadding(dp(18), dp(18), dp(18), dp(18));
        overallBox.addView(overall);
        LinearLayout.LayoutParams overallLp = matchWrap();
        overallLp.topMargin = dp(22);
        root.addView(overallBox, overallLp);

        framework = text("框架：正在连接…", 15, TEXT, true);
        version = text("模块版本：" + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")", 13, MUTED, false);

        LinearLayout frameworkBox = cardContainer();
        frameworkBox.addView(framework);
        LinearLayout.LayoutParams versionLp = wrap();
        versionLp.topMargin = dp(6);
        frameworkBox.addView(version, versionLp);
        LinearLayout.LayoutParams frameworkLp = matchWrap();
        frameworkLp.topMargin = dp(12);
        root.addView(frameworkBox, frameworkLp);

        TextView section = text("目标进程", 16, TEXT, true);
        LinearLayout.LayoutParams sectionLp = wrap();
        sectionLp.topMargin = dp(24);
        sectionLp.bottomMargin = dp(10);
        root.addView(section, sectionLp);

        providerCard = new StatusCard(
                "DownloadProvider",
                "com.android.providers.downloads",
                "真实下载传输与状态源");
        downloadsUiCard = new StatusCard(
                "Downloads UI",
                "com.android.providers.downloads.ui",
                "下载列表与控制界面");
        systemUiCard = new StatusCard(
                "SystemUI",
                "com.android.systemui",
                "通知与系统界面");

        root.addView(providerCard.root, cardLp());
        root.addView(downloadsUiCard.root, cardLp());
        root.addView(systemUiCard.root, cardLp());

        refreshButton = new Button(this);
        refreshButton.setText("刷新状态");
        refreshButton.setTextSize(15);
        refreshButton.setAllCaps(false);
        refreshButton.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams buttonLp = matchWrap();
        buttonLp.topMargin = dp(18);
        root.addView(refreshButton, buttonLp);

        TextView note = text(
                "“已加载”要求同时看到 LSPosed 服务、Scope、正在运行的目标进程，"
                        + "以及当前 APK generation 的运行时回执。仅安装 APK 不会被判定为已激活。",
                12, MUTED, false);
        note.setLineSpacing(0f, 1.18f);
        LinearLayout.LayoutParams noteLp = wrap();
        noteLp.topMargin = dp(14);
        root.addView(note, noteLp);

        return scroll;
    }

    private void refresh() {
        if (refreshButton != null) {
            refreshButton.setEnabled(false);
            refreshButton.setText("正在刷新…");
        }

        new Thread(() -> {
            StatusReport report = queryStatus();
            mainHandler.post(() -> render(report));
        }, "sysdl-status").start();
    }

    private StatusReport queryStatus() {
        XposedService service = ModuleApp.service();
        if (service == null) {
            return StatusReport.disconnected();
        }

        try {
            int api = service.getApiVersion();
            String frameworkLabel = service.getFrameworkName()
                    + " " + service.getFrameworkVersion();
            long properties = service.getFrameworkProperties();
            Set<String> scopes = new HashSet<>(service.getScope());

            List<HookedTarget> targets = api >= XposedService.API_102
                    ? service.getRunningTargets()
                    : Collections.emptyList();

            SharedPreferences runtime = null;
            if ((properties & XposedService.PROP_CAP_REMOTE) != 0L) {
                runtime = service.getRemotePreferences("runtime");
            }

            ScopeStatus provider = scopeStatus(
                    "com.android.providers.downloads", scopes, targets, runtime);
            ScopeStatus downloadsUi = scopeStatus(
                    "com.android.providers.downloads.ui", scopes, targets, runtime);
            ScopeStatus systemUi = scopeStatus(
                    "com.android.systemui", scopes, targets, runtime);

            return new StatusReport(
                    true, api, frameworkLabel, provider, downloadsUi, systemUi, null);
        } catch (Throwable t) {
            String message = t.getMessage();
            if (message == null || message.isBlank()) {
                message = t.getClass().getSimpleName();
            }
            return new StatusReport(
                    true, 0, "Xposed service connected",
                    ScopeStatus.error("com.android.providers.downloads"),
                    ScopeStatus.error("com.android.providers.downloads.ui"),
                    ScopeStatus.error("com.android.systemui"),
                    message);
        }
    }

    private ScopeStatus scopeStatus(
            String packageName,
            Set<String> scopes,
            List<HookedTarget> targets,
            SharedPreferences runtime) {
        boolean inScope = scopes.contains(packageName);
        String prefix = "scope." + packageName + ".";

        String process = runtime == null
                ? ""
                : runtime.getString(prefix + "process", "");
        long reportedVersion = runtime == null
                ? 0L
                : runtime.getLong(prefix + "version", 0L);
        long loadedAt = runtime == null
                ? 0L
                : runtime.getLong(prefix + "loadedAt", 0L);
        long lastReload = runtime == null
                ? 0L
                : runtime.getLong(prefix + "lastReload", 0L);
        int evidenceCount = runtime == null
                ? 0
                : runtime.getInt(prefix + "evidenceCount", 0);

        HookedTarget target = findTarget(targets, process, packageName);
        boolean running = target != null;
        String state = running ? target.getState().name() : "";
        long loadedVersion = running ? target.getLoadedVersionCode() : 0L;
        int pid = running ? target.getPid() : 0;

        boolean currentGeneration = running
                && loadedVersion == BuildConfig.VERSION_CODE
                && reportedVersion == BuildConfig.VERSION_CODE
                && target.getState() == HookedTarget.State.UP_TO_DATE;

        return new ScopeStatus(
                packageName,
                inScope,
                process,
                running,
                state,
                pid,
                loadedVersion,
                reportedVersion,
                loadedAt,
                lastReload,
                evidenceCount,
                currentGeneration,
                false);
    }

    private HookedTarget findTarget(
            List<HookedTarget> targets, String process, String packageName) {
        if (targets == null) return null;

        if (process != null && !process.isBlank()) {
            for (HookedTarget target : targets) {
                if (process.equals(target.getProcessName())) {
                    return target;
                }
            }
        }

        for (HookedTarget target : targets) {
            if (packageName.equals(target.getProcessName())) {
                return target;
            }
        }
        return null;
    }

    private void render(StatusReport report) {
        refreshButton.setEnabled(true);
        refreshButton.setText("刷新状态");

        if (!report.serviceConnected) {
            overall.setText("未连接到 LSPosed");
            overall.setTextColor(RED);
            framework.setText("框架：未连接 / 模块未启用");
            framework.setTextColor(RED);
            providerCard.render(ScopeStatus.disconnected("com.android.providers.downloads"));
            downloadsUiCard.render(ScopeStatus.disconnected("com.android.providers.downloads.ui"));
            systemUiCard.render(ScopeStatus.disconnected("com.android.systemui"));
            return;
        }

        framework.setText("框架：" + report.frameworkLabel + " · API " + report.apiVersion);
        framework.setTextColor(TEXT);

        if (report.error != null) {
            overall.setText("状态读取异常");
            overall.setTextColor(RED);
            version.setText("错误：" + report.error);
        } else {
            version.setText("模块版本：" + BuildConfig.VERSION_NAME
                    + " (" + BuildConfig.VERSION_CODE + ") · 热重载 "
                    + (report.apiVersion >= XposedService.API_102 ? "可用" : "不可用"));

            int active = 0;
            if (report.provider.currentGeneration) active++;
            if (report.downloadsUi.currentGeneration) active++;
            if (report.systemUi.currentGeneration) active++;

            if (active == 3) {
                overall.setText("已激活 · 3/3 目标已加载");
                overall.setTextColor(GREEN);
            } else if (active > 0) {
                overall.setText("部分激活 · " + active + "/3 目标已加载");
                overall.setTextColor(AMBER);
            } else {
                overall.setText("框架已连接 · 等待目标加载");
                overall.setTextColor(AMBER);
            }
        }

        providerCard.render(report.provider);
        downloadsUiCard.render(report.downloadsUi);
        systemUiCard.render(report.systemUi);
    }

    private final class StatusCard {
        final LinearLayout root;
        final TextView name;
        final TextView packageName;
        final TextView state;
        final TextView detail;

        StatusCard(String title, String pkg, String description) {
            root = cardContainer();

            name = text(title, 17, TEXT, true);
            root.addView(name);

            packageName = text(pkg, 12, MUTED, false);
            LinearLayout.LayoutParams pkgLp = wrap();
            pkgLp.topMargin = dp(2);
            root.addView(packageName, pkgLp);

            TextView descriptionView = text(description, 13, MUTED, false);
            LinearLayout.LayoutParams descriptionLp = wrap();
            descriptionLp.topMargin = dp(8);
            root.addView(descriptionView, descriptionLp);

            state = text("正在读取…", 15, AMBER, true);
            LinearLayout.LayoutParams stateLp = wrap();
            stateLp.topMargin = dp(12);
            root.addView(state, stateLp);

            detail = text("", 12, MUTED, false);
            detail.setLineSpacing(0f, 1.15f);
            LinearLayout.LayoutParams detailLp = wrap();
            detailLp.topMargin = dp(5);
            root.addView(detail, detailLp);
        }

        void render(ScopeStatus s) {
            if (s.error) {
                state.setText("状态读取失败");
                state.setTextColor(RED);
                detail.setText("无法获取目标进程状态");
                return;
            }

            if (!s.inScope) {
                state.setText("未启用");
                state.setTextColor(RED);
                detail.setText("LSPosed Scope 中没有此目标");
                return;
            }

            if (!s.running) {
                state.setText(s.process.isBlank() ? "等待首次加载" : "目标进程未运行");
                state.setTextColor(AMBER);
                detail.setText(s.process.isBlank()
                        ? "Scope 已启用，但还没有收到运行时回执"
                        : "Scope 已启用 · 上次进程 " + s.process);
                return;
            }

            boolean stale = "STALE".equals(s.targetState)
                    || (s.loadedVersion > 0 && s.loadedVersion != BuildConfig.VERSION_CODE);

            if (stale) {
                state.setText("已加载旧版本 · 待热重载");
                state.setTextColor(AMBER);
            } else if (s.currentGeneration) {
                state.setText("已加载");
                state.setTextColor(GREEN);
            } else {
                state.setText("进程已注入 · 等待当前 generation 回执");
                state.setTextColor(AMBER);
            }

            StringBuilder info = new StringBuilder();
            info.append("进程：").append(s.process.isBlank() ? "未知" : s.process)
                    .append(" · PID ").append(s.pid)
                    .append("\n框架状态：").append(s.targetState.isBlank() ? "UNKNOWN" : s.targetState)
                    .append(" · 加载版本 ").append(s.loadedVersion)
                    .append("\n运行时回执版本：").append(s.reportedVersion)
                    .append(" · 证据数 ").append(s.evidenceCount);

            if (s.loadedAt > 0L) {
                info.append("\n本 generation：").append(formatTime(s.loadedAt));
            }
            if (s.lastReload > 0L) {
                info.append(" · 最近热重载 ").append(formatTime(s.lastReload));
            }
            detail.setText(info.toString());
        }
    }

    private String formatTime(long time) {
        return DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(time));
    }

    private LinearLayout cardContainer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(15), dp(16), dp(15));
        box.setElevation(dp(1));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        return box;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.START);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class StatusReport {
        final boolean serviceConnected;
        final int apiVersion;
        final String frameworkLabel;
        final ScopeStatus provider;
        final ScopeStatus downloadsUi;
        final ScopeStatus systemUi;
        final String error;

        StatusReport(
                boolean serviceConnected,
                int apiVersion,
                String frameworkLabel,
                ScopeStatus provider,
                ScopeStatus downloadsUi,
                ScopeStatus systemUi,
                String error) {
            this.serviceConnected = serviceConnected;
            this.apiVersion = apiVersion;
            this.frameworkLabel = frameworkLabel;
            this.provider = provider;
            this.downloadsUi = downloadsUi;
            this.systemUi = systemUi;
            this.error = error;
        }

        static StatusReport disconnected() {
            return new StatusReport(
                    false, 0, "",
                    ScopeStatus.disconnected("com.android.providers.downloads"),
                    ScopeStatus.disconnected("com.android.providers.downloads.ui"),
                    ScopeStatus.disconnected("com.android.systemui"),
                    null);
        }
    }

    private static final class ScopeStatus {
        final String packageName;
        final boolean inScope;
        final String process;
        final boolean running;
        final String targetState;
        final int pid;
        final long loadedVersion;
        final long reportedVersion;
        final long loadedAt;
        final long lastReload;
        final int evidenceCount;
        final boolean currentGeneration;
        final boolean error;

        ScopeStatus(
                String packageName,
                boolean inScope,
                String process,
                boolean running,
                String targetState,
                int pid,
                long loadedVersion,
                long reportedVersion,
                long loadedAt,
                long lastReload,
                int evidenceCount,
                boolean currentGeneration,
                boolean error) {
            this.packageName = packageName;
            this.inScope = inScope;
            this.process = process == null ? "" : process;
            this.running = running;
            this.targetState = targetState == null ? "" : targetState;
            this.pid = pid;
            this.loadedVersion = loadedVersion;
            this.reportedVersion = reportedVersion;
            this.loadedAt = loadedAt;
            this.lastReload = lastReload;
            this.evidenceCount = evidenceCount;
            this.currentGeneration = currentGeneration;
            this.error = error;
        }

        static ScopeStatus disconnected(String packageName) {
            return new ScopeStatus(
                    packageName, false, "", false, "", 0,
                    0L, 0L, 0L, 0L, 0, false, false);
        }

        static ScopeStatus error(String packageName) {
            return new ScopeStatus(
                    packageName, false, "", false, "", 0,
                    0L, 0L, 0L, 0L, 0, false, true);
        }
    }
}
