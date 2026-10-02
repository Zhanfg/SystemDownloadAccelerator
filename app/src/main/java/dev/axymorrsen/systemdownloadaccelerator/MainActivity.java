package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.HotReloadResult;
import io.github.libxposed.service.XposedService;

/**
 * Runtime diagnostics UI backed by XposedService API 102.
 *
 * getRunningTargets() + loadedVersionCode + target state are authoritative for
 * live processes. DownloadProvider and Downloads UI are allowed to be dormant.
 */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 250);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(28, 31, 38);
    private static final int MUTED = Color.rgb(108, 114, 128);
    private static final int GREEN = Color.rgb(24, 140, 76);
    private static final int AMBER = Color.rgb(184, 112, 0);
    private static final int RED = Color.rgb(190, 45, 45);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable serviceListener = () -> runOnUiThread(this::refresh);

    private TextView overall;
    private TextView framework;
    private TextView version;
    private StatusCard providerCard;
    private StatusCard downloadsUiCard;
    private StatusCard systemUiCard;
    private Button selfTestButton;
    private TextView selfTestStatus;
    private TextView engineDiag;
    private EditText benchmarkUrl;
    private TextView benchmarkStatus;
    private TextView benchmarkEngine;
    private CheckBox benchmarkVpnMeteredOverride;
    private Button benchmarkButton;
    private RealDownloadBenchmark.Session benchmarkSession;
    private Button diagnosticButton;
    private TextView diagnosticStatus;
    private Button refreshButton;
    private boolean engineReceiverRegistered;
    private final AtomicBoolean providerReloadInFlight =
            new AtomicBoolean(false);
    private final ArrayDeque<String> engineEvents = new ArrayDeque<>();

    private final BroadcastReceiver engineReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !EngineTelemetry.ACTION.equals(intent.getAction())
                    || engineDiag == null) {
                return;
            }

            String phase = intent.getStringExtra(EngineTelemetry.EXTRA_PHASE);
            String detail = intent.getStringExtra(EngineTelemetry.EXTRA_DETAIL);
            if (phase == null) phase = "";
            if (detail == null) detail = "";

            String event = phase + (detail.isBlank() ? "" : " · " + detail);
            engineEvents.addLast(event);
            while (engineEvents.size() > 8) {
                engineEvents.removeFirst();
            }

            StringBuilder history = new StringBuilder("引擎事件：");
            for (String item : engineEvents) {
                history.append("\n").append(item);
            }
            engineDiag.setText(history.toString());

            if (benchmarkEngine != null
                    && (phase.startsWith("ADAPT_")
                    || phase.startsWith("BENCH_")
                    || "PARTS".equals(phase)
                    || "COMPLETE".equals(phase))) {
                benchmarkEngine.setText(
                        phase + (detail.isBlank()
                                ? ""
                                : " · " + detail));
                benchmarkEngine.setTextColor(
                        "ERROR".equals(phase)
                                || "BENCH_FAILED".equals(phase)
                                || "BENCH_ERROR".equals(phase)
                                ? RED
                                : AMBER);
            }

            if ("SUCCESS".equals(phase) || "RANGE_OK".equals(phase)) {
                engineDiag.setTextColor(GREEN);
            } else if ("ERROR".equals(phase)
                    || "FALLBACK".equals(phase)
                    || "NO_CONNECTION_ARG".equals(phase)) {
                engineDiag.setTextColor(RED);
            } else {
                engineDiag.setTextColor(AMBER);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        ModuleApp.addListener(serviceListener);
        registerEngineReceiver();
        loadPersistedEvents();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPersistedEvents();
        refresh();
    }

    @Override
    protected void onDestroy() {
        ModuleApp.removeListener(serviceListener);
        if (engineReceiverRegistered) {
            try {
                unregisterReceiver(engineReceiver);
            } catch (Throwable ignored) {
            }
            engineReceiverRegistered = false;
        }
        if (benchmarkSession != null) {
            benchmarkSession.cancel();
            benchmarkSession = null;
        }
        super.onDestroy();
    }

    private void registerEngineReceiver() {
        if (engineReceiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter(EngineTelemetry.ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(engineReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(engineReceiver, filter);
        }
        engineReceiverRegistered = true;
    }

    private void loadPersistedEvents() {
        if (engineDiag == null) {
            return;
        }

        java.util.List<String> persisted =
                TelemetryProvider.readEvents(getApplicationContext());

        engineEvents.clear();
        int start = Math.max(0, persisted.size() - 8);
        for (int i = start; i < persisted.size(); i++) {
            engineEvents.addLast(persisted.get(i));
        }

        if (engineEvents.isEmpty()) {
            return;
        }

        StringBuilder history = new StringBuilder("引擎事件：");
        for (String item : engineEvents) {
            history.append("\n").append(item);
        }
        engineDiag.setText(history.toString());
        engineDiag.setTextColor(AMBER);
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

        root.addView(text("SDA", 27, TEXT, true));

        TextView subtitle = text("System Download Accelerator · LSPosed API 102", 14, MUTED, false);
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
        version = text(
                "模块版本：" + BuildConfig.VERSION_NAME
                        + " (" + BuildConfig.VERSION_CODE + ")"
                        + " · build " + BuildConfig.BUILD_ID,
                13,
                MUTED,
                false);

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
                "真实下载传输与状态源 · 按需运行",
                true,
                "当前没有活动下载；运行自检可立即唤醒");
        downloadsUiCard = new StatusCard(
                "Downloads UI",
                "com.android.providers.downloads.ui",
                "下载列表与控制界面 · 按需加载",
                true,
                "界面进程未启动，不影响下载核心");
        systemUiCard = new StatusCard(
                "SystemUI",
                "com.android.systemui",
                "通知与系统界面",
                false,
                "");

        root.addView(providerCard.root, cardLp());
        root.addView(downloadsUiCard.root, cardLp());
        root.addView(systemUiCard.root, cardLp());

        TextView testSection = text("下载链路自检", 16, TEXT, true);
        LinearLayout.LayoutParams testSectionLp = wrap();
        testSectionLp.topMargin = dp(18);
        testSectionLp.bottomMargin = dp(10);
        root.addView(testSection, testSectionLp);

        LinearLayout testBox = cardContainer();
        TextView testExplain = text(
                "在本机 localhost 启动临时 HTTP/Range 服务器，再由 Android 原生 "
                        + "DownloadManager 下载 16 MiB。若 Range 请求数达到 3+，说明分段引擎已真实接管。"
                        + "不会使用公网流量。",
                13, MUTED, false);
        testExplain.setLineSpacing(0f, 1.15f);
        testBox.addView(testExplain);

        selfTestStatus = text(
                getSharedPreferences("ui_state", MODE_PRIVATE)
                        .getString("last_self_test", "尚未运行"),
                13,
                MUTED,
                false);
        LinearLayout.LayoutParams selfStatusLp = wrap();
        selfStatusLp.topMargin = dp(10);
        testBox.addView(selfTestStatus, selfStatusLp);

        engineDiag = text("引擎：等待测试", 12, MUTED, false);
        engineDiag.setLineSpacing(0f, 1.12f);
        LinearLayout.LayoutParams engineLp = wrap();
        engineLp.topMargin = dp(8);
        testBox.addView(engineDiag, engineLp);

        selfTestButton = new Button(this);
        selfTestButton.setText("运行下载链路自检");
        selfTestButton.setTextSize(15);
        selfTestButton.setAllCaps(false);
        selfTestButton.setOnClickListener(v -> runSelfTest());
        LinearLayout.LayoutParams selfButtonLp = matchWrap();
        selfButtonLp.topMargin = dp(12);
        testBox.addView(selfTestButton, selfButtonLp);

        root.addView(testBox, matchWrap());

        TextView benchmarkSection = text("大文件性能测试", 16, TEXT, true);
        LinearLayout.LayoutParams benchmarkSectionLp = wrap();
        benchmarkSectionLp.topMargin = dp(18);
        benchmarkSectionLp.bottomMargin = dp(10);
        root.addView(benchmarkSection, benchmarkSectionLp);

        LinearLayout benchmarkBox = cardContainer();
        TextView benchmarkExplain = text(
                "输入真实 HTTP/HTTPS 大文件直链，由系统 DownloadManager 下载并测试动态并发。"
                        + "会消耗真实网络流量；Android 标记为计费网络时默认拒绝启动。"
                        + "测试完成或取消后自动清理文件。",
                13, MUTED, false);
        benchmarkExplain.setLineSpacing(0f, 1.15f);
        benchmarkBox.addView(benchmarkExplain);

        benchmarkUrl = new EditText(this);
        benchmarkUrl.setHint("https://example.com/large-file.bin");
        benchmarkUrl.setSingleLine(true);
        benchmarkUrl.setTextSize(14);
        benchmarkUrl.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams benchmarkUrlLp = matchWrap();
        benchmarkUrlLp.topMargin = dp(10);
        benchmarkBox.addView(benchmarkUrl, benchmarkUrlLp);

        benchmarkStatus = text("尚未运行真实性能测试", 13, MUTED, false);
        LinearLayout.LayoutParams benchmarkStatusLp = wrap();
        benchmarkStatusLp.topMargin = dp(10);
        benchmarkBox.addView(benchmarkStatus, benchmarkStatusLp);

        benchmarkEngine = text("调度器：等待测试", 12, MUTED, false);
        benchmarkEngine.setLineSpacing(0f, 1.12f);
        LinearLayout.LayoutParams benchmarkEngineLp = wrap();
        benchmarkEngineLp.topMargin = dp(6);
        benchmarkBox.addView(benchmarkEngine, benchmarkEngineLp);

        benchmarkVpnMeteredOverride = new CheckBox(this);
        benchmarkVpnMeteredOverride.setText(
                "忽略 VPN 的计费标记（仅在你确认底层网络不限流量时使用）");
        benchmarkVpnMeteredOverride.setTextSize(13);
        benchmarkVpnMeteredOverride.setTextColor(TEXT);
        LinearLayout.LayoutParams vpnOverrideLp = wrap();
        vpnOverrideLp.topMargin = dp(8);
        benchmarkBox.addView(
                benchmarkVpnMeteredOverride,
                vpnOverrideLp);

        benchmarkButton = new Button(this);
        benchmarkButton.setText("开始大文件性能测试");
        benchmarkButton.setTextSize(15);
        benchmarkButton.setAllCaps(false);
        benchmarkButton.setOnClickListener(v -> toggleBenchmark());
        LinearLayout.LayoutParams benchmarkButtonLp = matchWrap();
        benchmarkButtonLp.topMargin = dp(12);
        benchmarkBox.addView(benchmarkButton, benchmarkButtonLp);

        root.addView(benchmarkBox, matchWrap());

        TextView diagSection = text("诊断日志", 16, TEXT, true);
        LinearLayout.LayoutParams diagSectionLp = wrap();
        diagSectionLp.topMargin = dp(18);
        diagSectionLp.bottomMargin = dp(10);
        root.addView(diagSection, diagSectionLp);

        LinearLayout diagBox = cardContainer();
        TextView diagExplain = text(
                "一键收集模块/LSPosed 状态、runningTargets、自检事件、相关 logcat，"
                        + "并在 root 可用时附加 LSPosed 模块日志与进程快照。日志保存到公共 Download 目录。",
                13, MUTED, false);
        diagExplain.setLineSpacing(0f, 1.15f);
        diagBox.addView(diagExplain);

        diagnosticStatus = text("尚未生成", 12, MUTED, false);
        LinearLayout.LayoutParams diagStatusLp = wrap();
        diagStatusLp.topMargin = dp(10);
        diagBox.addView(diagnosticStatus, diagStatusLp);

        diagnosticButton = new Button(this);
        diagnosticButton.setText("生成诊断日志");
        diagnosticButton.setTextSize(15);
        diagnosticButton.setAllCaps(false);
        diagnosticButton.setOnClickListener(v -> generateDiagnosticLog());
        LinearLayout.LayoutParams diagButtonLp = matchWrap();
        diagButtonLp.topMargin = dp(12);
        diagBox.addView(diagnosticButton, diagButtonLp);

        root.addView(diagBox, matchWrap());

        refreshButton = new Button(this);
        refreshButton.setText("刷新状态");
        refreshButton.setTextSize(15);
        refreshButton.setAllCaps(false);
        refreshButton.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams buttonLp = matchWrap();
        buttonLp.topMargin = dp(14);
        root.addView(refreshButton, buttonLp);

        TextView note = text(
                "状态以 LSPosed API 102 的 runningTargets 为准。DownloadProvider 和 "
                        + "Downloads UI 都可能在空闲时退出，因此“不在运行”不等于模块失效；"
                        + "自检会验证连接创建、响应流替换、Range 探测与并行微分片，而不再依赖 DownloadProvider 私有 transferData 方法。",
                12, MUTED, false);
        note.setLineSpacing(0f, 1.18f);
        LinearLayout.LayoutParams noteLp = wrap();
        noteLp.topMargin = dp(14);
        root.addView(note, noteLp);

        return scroll;
    }

    private void runSelfTest() {
        selfTestButton.setEnabled(false);
        selfTestButton.setText("正在确认 Provider 版本…");
        selfTestStatus.setText("正在确认 DownloadProvider 是否运行当前 Hook generation");
        selfTestStatus.setTextColor(AMBER);

        requireCurrentProviderGeneration(
                "下载链路自检",
                () -> startSelfTest(),
                message -> {
                    selfTestButton.setEnabled(true);
                    selfTestButton.setText("重新运行下载链路自检");
                    selfTestStatus.setText(message);
                    selfTestStatus.setTextColor(RED);
                    refresh();
                });
    }

    private void startSelfTest() {
        selfTestButton.setEnabled(false);
        selfTestButton.setText("自检运行中…");
        selfTestStatus.setText("正在准备本地 DownloadManager 测试");
        selfTestStatus.setTextColor(AMBER);
        engineEvents.clear();
        engineDiag.setText("引擎事件：\n等待连接流接管");
        EngineTelemetry.emit(
                getApplicationContext(),
                "SELFTEST_BEGIN",
                "version=" + BuildConfig.VERSION_CODE
                        + " build=" + BuildConfig.BUILD_ID
                        + " at=" + android.os.SystemClock.elapsedRealtime());
        engineDiag.setTextColor(MUTED);

        DownloadSelfTest.run(this, (message, terminal, success) ->
                runOnUiThread(() -> {
                    selfTestStatus.setText(message);
                    selfTestStatus.setTextColor(
                            terminal ? (success ? GREEN : RED) : AMBER);

                    if (terminal) {
                        getSharedPreferences("ui_state", MODE_PRIVATE)
                                .edit()
                                .putString("last_self_test", message)
                                .apply();
                        selfTestButton.setEnabled(true);
                        selfTestButton.setText("重新运行下载链路自检");
                        loadPersistedEvents();
                        refresh();
                    } else {
                        mainHandler.postDelayed(this::refresh, 250L);
                    }
                }));
    }

    private void toggleBenchmark() {
        if (benchmarkSession != null
                && !benchmarkSession.isCancelled()) {
            benchmarkSession.cancel();
            benchmarkSession = null;
            benchmarkButton.setText("开始大文件性能测试");
            benchmarkStatus.setText("正在取消并清理测试文件…");
            benchmarkStatus.setTextColor(AMBER);
            return;
        }

        benchmarkButton.setEnabled(false);
        benchmarkButton.setText("正在确认 Provider 版本…");
        benchmarkStatus.setText("正在确认 DownloadProvider 是否运行当前 Hook generation");
        benchmarkStatus.setTextColor(AMBER);

        requireCurrentProviderGeneration(
                "大文件性能测试",
                () -> startBenchmark(),
                message -> {
                    benchmarkButton.setEnabled(true);
                    benchmarkButton.setText("开始大文件性能测试");
                    benchmarkStatus.setText(message);
                    benchmarkStatus.setTextColor(RED);
                    refresh();
                });
    }

    private void startBenchmark() {
        String url = benchmarkUrl == null
                ? ""
                : benchmarkUrl.getText().toString().trim();

        benchmarkButton.setEnabled(true);
        benchmarkButton.setText("取消性能测试");
        benchmarkStatus.setText("正在提交真实 DownloadManager 大文件测试…");
        benchmarkStatus.setTextColor(AMBER);
        benchmarkEngine.setText("调度器：等待 ADAPT_INIT");
        benchmarkEngine.setTextColor(MUTED);

        EngineTelemetry.emit(
                getApplicationContext(),
                "BENCH_UI_BEGIN",
                "version=" + BuildConfig.VERSION_CODE
                        + " build=" + BuildConfig.BUILD_ID);

        boolean allowVpnMeteredOverride =
                benchmarkVpnMeteredOverride != null
                        && benchmarkVpnMeteredOverride.isChecked();

        benchmarkSession = RealDownloadBenchmark.run(
                this,
                url,
                allowVpnMeteredOverride,
                (message, terminal, success) ->
                        runOnUiThread(() -> {
                            benchmarkStatus.setText(message);
                            benchmarkStatus.setTextColor(
                                    terminal
                                            ? (success ? GREEN : RED)
                                            : AMBER);

                            String latest = latestAdaptiveEvent();
                            if (latest != null) {
                                benchmarkEngine.setText(
                                        "调度器：" + latest);
                                benchmarkEngine.setTextColor(AMBER);
                            }

                            if (terminal) {
                                benchmarkSession = null;
                                benchmarkButton.setText(
                                        "重新运行大文件性能测试");
                                loadPersistedEvents();
                                refresh();
                            }
                        }));
    }

    private interface ProviderGenerationFailure {
        void onFailure(String message);
    }

    private void requireCurrentProviderGeneration(
            String action,
            Runnable onReady,
            ProviderGenerationFailure onFailure) {
        new Thread(() -> {
            StatusReport report = queryStatus();
            String failure = null;

            if (!report.serviceConnected) {
                failure = "LSPosed 服务未连接，无法开始" + action;
            } else if (report.error != null) {
                failure = "无法确认 DownloadProvider 状态：" + report.error;
            } else if (!report.provider.inScope) {
                failure = "DownloadProvider 不在 LSPosed Scope 中";
            } else if (report.provider.running) {
                String receipt = latestProviderBuildReceipt();
                boolean versionReady = report.provider.currentGeneration;
                boolean buildReady = receiptMatchesCurrentBuild(receipt);

                if (!versionReady || !buildReady) {
                    failure = recoverProviderGeneration(
                            action,
                            report.provider,
                            receipt);
                }
            }

            final String result = failure;
            runOnUiThread(() -> {
                if (result == null) {
                    onReady.run();
                } else {
                    onFailure.onFailure(result);
                }
            });
        }, "sysdl-provider-preflight").start();
    }

    private String recoverProviderGeneration(
            String action,
            ScopeStatus provider,
            String previousReceipt) {
        XposedService service = ModuleApp.service();
        if (service == null) {
            return "LSPosed 服务已断开，无法自动刷新 DownloadProvider";
        }

        try {
            if (service.getApiVersion() < XposedService.API_102) {
                return "当前框架不支持 API 102 显式热重载；"
                        + "请重启 DownloadProvider 后再运行"
                        + action;
            }
        } catch (Throwable t) {
            return "读取 Hot Reload 能力失败："
                    + t.getClass().getSimpleName()
                    + ": "
                    + String.valueOf(t.getMessage());
        }

        if (!providerReloadInFlight.compareAndSet(false, true)) {
            return "DownloadProvider 正在自动热重载，请稍候再运行" + action;
        }

        try {
            HookedTarget target = null;
            try {
                target = findTarget(
                        service.getRunningTargets(),
                        "com.android.providers.downloads",
                        provider.uid);
            } catch (Throwable t) {
                return "无法重新取得 DownloadProvider target："
                        + t.getClass().getSimpleName()
                        + ": "
                        + String.valueOf(t.getMessage());
            }

            if (target == null) {
                // Provider became dormant between preflight and recovery.
                // The next DownloadManager request will start a fresh process.
                EngineTelemetry.emit(
                        getApplicationContext(),
                        "PROVIDER_RELOAD_SKIP",
                        "target became dormant; fresh launch will load current build");
                return null;
            }

            EngineTelemetry.emit(
                    getApplicationContext(),
                    "PROVIDER_RELOAD_REQUEST",
                    "action=" + action
                            + " pid=" + target.getPid()
                            + " state=" + target.getState()
                            + " loadedVersion=" + target.getLoadedVersionCode()
                            + " currentVersion=" + BuildConfig.VERSION_CODE
                            + " currentBuild=" + BuildConfig.BUILD_ID
                            + " previousReceipt="
                            + String.valueOf(previousReceipt));

            CountDownLatch callbackLatch = new CountDownLatch(1);
            AtomicReference<HotReloadResult.Status> callbackStatus =
                    new AtomicReference<>();
            AtomicReference<String> callbackMessage =
                    new AtomicReference<>();

            try {
                service.hotReloadModule(
                        target,
                        null,
                        (hookedTarget, result) -> {
                            if (result != null) {
                                callbackStatus.set(result.status());
                                callbackMessage.set(result.message());
                            }
                            callbackLatch.countDown();
                        });
            } catch (UnsupportedOperationException e) {
                return "框架不支持显式 Hot Reload："
                        + String.valueOf(e.getMessage());
            } catch (SecurityException e) {
                return "Hot Reload target 已失效："
                        + String.valueOf(e.getMessage());
            } catch (Throwable t) {
                return "提交 DownloadProvider Hot Reload 失败："
                        + t.getClass().getSimpleName()
                        + ": "
                        + String.valueOf(t.getMessage());
            }

            boolean callbackArrived;
            try {
                callbackArrived = callbackLatch.await(
                        10L,
                        TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "等待 DownloadProvider Hot Reload 时被中断";
            }

            if (!callbackArrived) {
                EngineTelemetry.emit(
                        getApplicationContext(),
                        "PROVIDER_RELOAD_TIMEOUT",
                        "callback not received within 10s");
                return "DownloadProvider Hot Reload 回调超时；"
                        + "为避免混用旧 Hook，已阻止"
                        + action;
            }

            HotReloadResult.Status status = callbackStatus.get();
            String message = callbackMessage.get();

            EngineTelemetry.emit(
                    getApplicationContext(),
                    "PROVIDER_RELOAD_RESULT",
                    "status=" + String.valueOf(status)
                            + " message=" + String.valueOf(message));

            if (status == HotReloadResult.Status.PROCESS_DIED) {
                // A dead provider is safe: the pending action will wake a fresh
                // process which loads the installed generation from scratch.
                return null;
            }

            if (status != HotReloadResult.Status.SUCCEEDED
                    && status != HotReloadResult.Status.IN_PROGRESS) {
                return "DownloadProvider Hot Reload 未成功："
                        + String.valueOf(status)
                        + (message == null || message.isBlank()
                                ? ""
                                : " · " + message);
            }

            long deadline =
                    android.os.SystemClock.elapsedRealtime() + 6000L;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                String receipt = latestProviderBuildReceipt();
                if (receiptMatchesCurrentBuild(receipt)) {
                    StatusReport refreshed = queryStatus();
                    if (!refreshed.provider.running
                            || refreshed.provider.currentGeneration) {
                        EngineTelemetry.emit(
                                getApplicationContext(),
                                "PROVIDER_RELOAD_VERIFIED",
                                "version=" + BuildConfig.VERSION_CODE
                                        + " build=" + BuildConfig.BUILD_ID
                                        + " receipt=" + receipt);
                        return null;
                    }
                }

                try {
                    Thread.sleep(120L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "等待当前 Provider build 回执时被中断";
                }
            }

            String receipt = latestProviderBuildReceipt();
            return "框架报告 Hot Reload="
                    + status
                    + "，但 6 秒内没有收到当前 Provider build 回执。"
                    + "当前 build=" + BuildConfig.BUILD_ID
                    + "，最新回执="
                    + (receipt == null ? "(none)" : receipt)
                    + "。已阻止"
                    + action
                    + "，避免误测旧 Hook。";
        } finally {
            providerReloadInFlight.set(false);
        }
    }

    private String latestProviderBuildReceipt() {
        java.util.List<String> events =
                TelemetryProvider.readEvents(
                        getApplicationContext());
        for (int i = events.size() - 1; i >= 0; i--) {
            String event = events.get(i);
            if (event.startsWith("ADAPTER_INSTALL")) {
                return event;
            }
        }
        return null;
    }

    private boolean receiptMatchesCurrentBuild(String receipt) {
        if (receipt == null) {
            return false;
        }
        return receipt.contains(
                        "version=" + BuildConfig.VERSION_CODE)
                && receipt.contains(
                        "build=" + BuildConfig.BUILD_ID);
    }

    private String latestAdaptiveEvent() {
        java.util.List<String> events =
                TelemetryProvider.readEvents(
                        getApplicationContext());
        for (int i = events.size() - 1; i >= 0; i--) {
            String event = events.get(i);
            if (event.startsWith("ADAPT_")
                    || event.startsWith("PARTS")
                    || event.startsWith("BENCH_")) {
                return event;
            }
        }
        return null;
    }

    private void generateDiagnosticLog() {
        if (diagnosticButton == null || diagnosticStatus == null) {
            return;
        }

        diagnosticButton.setEnabled(false);
        diagnosticButton.setText("正在生成…");
        diagnosticStatus.setText("正在收集 LSPosed、进程与日志；root 授权弹窗出现时请允许");
        diagnosticStatus.setTextColor(AMBER);

        java.util.ArrayList<String> events =
                new java.util.ArrayList<>(engineEvents);
        String selfTest = selfTestStatus == null
                ? "(self-test UI unavailable)"
                : String.valueOf(selfTestStatus.getText());
        XposedService service = ModuleApp.service();

        new Thread(() -> {
            DiagnosticReport.Result result =
                    DiagnosticReport.generate(
                            getApplicationContext(),
                            service,
                            events,
                            selfTest);

            runOnUiThread(() -> {
                diagnosticButton.setEnabled(true);
                diagnosticButton.setText("重新生成诊断日志");

                if (result.success) {
                    diagnosticStatus.setText(
                            "已生成："
                                    + result.displayPath
                                    + "\n把这个 TXT 直接发给我即可。");
                    diagnosticStatus.setTextColor(GREEN);
                } else {
                    diagnosticStatus.setText(
                            "生成失败："
                                    + (result.error == null
                                            ? "unknown"
                                            : result.error));
                    diagnosticStatus.setTextColor(RED);
                }
            });
        }, "sysdl-diagnostic-report").start();
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
            Set<String> scopes = new HashSet<>(service.getScope());

            List<HookedTarget> targets = api >= XposedService.API_102
                    ? service.getRunningTargets()
                    : Collections.emptyList();

            ScopeStatus provider = scopeStatus(
                    "com.android.providers.downloads", scopes, targets);
            ScopeStatus downloadsUi = scopeStatus(
                    "com.android.providers.downloads.ui", scopes, targets);
            ScopeStatus systemUi = scopeStatus(
                    "com.android.systemui", scopes, targets);

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
            List<HookedTarget> targets) {
        boolean inScope = scopes.contains(packageName);
        int uid = resolvePackageUid(packageName);
        HookedTarget target = findTarget(targets, packageName, uid);

        boolean running = target != null;
        String process = running && target.getProcessName() != null
                ? target.getProcessName()
                : "";
        String state = running ? target.getState().name() : "";
        long loadedVersion = running ? target.getLoadedVersionCode() : 0L;
        int pid = running ? target.getPid() : 0;

        boolean currentGeneration = running
                && loadedVersion == BuildConfig.VERSION_CODE
                && target.getState() == HookedTarget.State.UP_TO_DATE;

        return new ScopeStatus(
                packageName,
                inScope,
                uid,
                process,
                running,
                state,
                pid,
                loadedVersion,
                currentGeneration,
                false);
    }

    private HookedTarget findTarget(
            List<HookedTarget> targets, String packageName, int packageUid) {
        if (targets == null) {
            return null;
        }

        for (HookedTarget target : targets) {
            if (packageName.equals(target.getProcessName())) {
                return target;
            }
        }

        if (packageUid >= 0) {
            for (HookedTarget target : targets) {
                if (target.getUid() == packageUid) {
                    return target;
                }
            }
        }

        return null;
    }

    private int resolvePackageUid(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
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
            version.setText(
                    "模块版本：" + BuildConfig.VERSION_NAME
                            + " (" + BuildConfig.VERSION_CODE + ")"
                            + " · build " + BuildConfig.BUILD_ID
                            + " · 热重载 "
                            + (report.apiVersion >= XposedService.API_102
                                    ? "可用"
                                    : "不可用"));

            boolean providerReady = report.provider.currentGeneration;
            boolean providerDormant = report.provider.inScope && !report.provider.running;
            boolean systemUiReady = report.systemUi.currentGeneration;
            boolean downloadsUiReady = report.downloadsUi.currentGeneration;

            if (systemUiReady && (providerReady || providerDormant)) {
                if (providerReady && downloadsUiReady) {
                    overall.setText("已激活 · 当前三个目标均已加载");
                } else if (providerReady) {
                    overall.setText("核心已激活 · Provider 正在运行");
                } else {
                    overall.setText("模块已就绪 · Provider 待下载唤醒");
                }
                overall.setTextColor(GREEN);
            } else {
                int core = (providerReady ? 1 : 0) + (systemUiReady ? 1 : 0);
                if (core > 0) {
                    overall.setText("部分激活 · " + core + "/2 核心运行目标已加载");
                } else {
                    overall.setText("框架已连接 · 等待核心目标加载");
                }
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
        final boolean dormantOk;
        final String dormantDetail;

        StatusCard(
                String title,
                String pkg,
                String description,
                boolean dormantOk,
                String dormantDetail) {
            this.dormantOk = dormantOk;
            this.dormantDetail = dormantDetail;
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
                state.setText(dormantOk
                        ? "Scope 已启用 · 当前休眠"
                        : "Scope 已启用 · 等待进程");
                state.setTextColor(dormantOk ? MUTED : AMBER);
                detail.setText(dormantOk
                        ? dormantDetail
                        : "目标进程当前没有出现在 runningTargets");
                return;
            }

            boolean stale = "STALE".equals(s.targetState)
                    || (s.loadedVersion > 0 && s.loadedVersion != BuildConfig.VERSION_CODE);

            if ("FAILED".equals(s.targetState)) {
                state.setText("注入失败");
                state.setTextColor(RED);
            } else if (stale) {
                state.setText("已加载旧版本 · 待热重载");
                state.setTextColor(AMBER);
            } else if ("RELOADING".equals(s.targetState)) {
                state.setText("正在热重载");
                state.setTextColor(AMBER);
            } else if (s.currentGeneration) {
                state.setText("已加载");
                state.setTextColor(GREEN);
            } else {
                state.setText("已注入 · 状态待确认");
                state.setTextColor(AMBER);
            }

            StringBuilder info = new StringBuilder();
            info.append("进程：").append(s.process.isBlank() ? "未知" : s.process)
                    .append(" · PID ").append(s.pid)
                    .append("\nUID：").append(s.uid)
                    .append(" · 框架状态：")
                    .append(s.targetState.isBlank() ? "UNKNOWN" : s.targetState)
                    .append("\n加载版本：").append(s.loadedVersion)
                    .append(" · 当前版本：").append(BuildConfig.VERSION_CODE);
            detail.setText(info.toString());
        }
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
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
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
        final int uid;
        final String process;
        final boolean running;
        final String targetState;
        final int pid;
        final long loadedVersion;
        final boolean currentGeneration;
        final boolean error;

        ScopeStatus(
                String packageName,
                boolean inScope,
                int uid,
                String process,
                boolean running,
                String targetState,
                int pid,
                long loadedVersion,
                boolean currentGeneration,
                boolean error) {
            this.packageName = packageName;
            this.inScope = inScope;
            this.uid = uid;
            this.process = process == null ? "" : process;
            this.running = running;
            this.targetState = targetState == null ? "" : targetState;
            this.pid = pid;
            this.loadedVersion = loadedVersion;
            this.currentGeneration = currentGeneration;
            this.error = error;
        }

        static ScopeStatus disconnected(String packageName) {
            return new ScopeStatus(
                    packageName, false, -1, "", false, "",
                    0, 0L, false, false);
        }

        static ScopeStatus error(String packageName) {
            return new ScopeStatus(
                    packageName, false, -1, "", false, "",
                    0, 0L, false, true);
        }
    }
}
