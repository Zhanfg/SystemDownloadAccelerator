package com.v4atune.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final EnumMap<TuneConfig.Effect, Spinner> effectSpinners =
            new EnumMap<>(TuneConfig.Effect.class);

    private Spinner targetSpinner;
    private TextView status;
    private TextView result;
    private ProgressBar progress;
    private Button start;
    private Button restore;
    private Button openViper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("V4ATune");
        buildUi();
        refreshPreflight();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("V4ATune", 28, true);
        root.addView(title);

        TextView subtitle = text(
                "OnePlus / ViPER4Android 一键声学校准\n" +
                        "自动 = 只有实测收益大于副作用时才启用；开启并优化 = 强制启用并为该部件计算参数。",
                14, false);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        status = text("正在检查环境…", 14, false);
        root.addView(status);

        TextView targetLabel = text("优化目标", 18, true);
        targetLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(targetLabel);

        targetSpinner = new Spinner(this);
        targetSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                TuneConfig.Target.values()
        ));
        root.addView(targetSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        TextView principle = text(
                "默认“参考 / 高保真”优先：频响平滑、低失真、足够动态余量和稳定声像。\n" +
                        "低频、空间、响度与染色类算法不会为了“多开功能”而硬开。",
                13, false);
        principle.setPadding(0, dp(8), 0, dp(12));
        root.addView(principle);

        TextView effectsTitle = text("部件策略", 18, true);
        effectsTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(effectsTitle);

        for (TuneConfig.Effect effect : TuneConfig.Effect.values()) {
            addEffectRow(root, effect);
        }

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        pp.setMargins(0, dp(18), 0, dp(10));
        root.addView(progress, pp);

        start = new Button(this);
        start.setText("一键测量并调到最佳");
        start.setOnClickListener(v -> startTune());
        root.addView(start, fullButtonParams());

        restore = new Button(this);
        restore.setText("恢复上次校准前配置");
        restore.setOnClickListener(v -> restoreLast());
        root.addView(restore, fullButtonParams());

        openViper = new Button(this);
        openViper.setText("打开 ViPER4Android");
        openViper.setOnClickListener(v -> executor.execute(() -> {
            new ViperBridge(this).launchViper();
            runOnUiThread(() -> Toast.makeText(this, "已打开 ViPER4Android", Toast.LENGTH_SHORT).show());
        }));
        root.addView(openViper, fullButtonParams());

        result = text("", 13, false);
        result.setPadding(0, dp(14), 0, 0);
        root.addView(result);

        TextView note = text(
                "测量说明：本机扬声器→本机麦克风属于相对自校准，会受到麦克风频响、机身近场耦合和房间反射影响。" +
                        "APK 会限制危险增益、优先削峰，并用第二轮残差校正防止过调。",
                12, false);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void addEffectRow(LinearLayout parent, TuneConfig.Effect effect) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView label = text(effect.label, 14, false);
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, lpLabel);

        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                TuneConfig.Policy.values()
        ));

        // DDC cannot be meaningfully self-generated for the built-in speaker in
        // this first speaker-only build; do not offer a fake "enabled" state.
        if (effect == TuneConfig.Effect.DDC) {
            spinner.setSelection(TuneConfig.Policy.OFF.ordinal());
            spinner.setEnabled(false);
            label.setText(effect.label + " · 耳机/VDC 模式");
        } else {
            spinner.setSelection(TuneConfig.Policy.AUTO.ordinal());
        }

        effectSpinners.put(effect, spinner);
        row.addView(spinner, new LinearLayout.LayoutParams(dp(150), dp(48)));
        parent.addView(row);
    }

    private void refreshPreflight() {
        executor.execute(() -> {
            boolean root = RootShell.available();
            ViperBridge bridge = new ViperBridge(this);
            boolean driver = root && bridge.driverReady();
            String text = "Root: " + (root ? "✓" : "✗") +
                    "    ViPER AIDL: " + (driver ? "✓" : "✗");
            runOnUiThread(() -> status.setText(text));
        });
    }

    private void startTune() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }

        TuneConfig cfg = collectConfig();
        setBusy(true);
        result.setText("");

        executor.execute(() -> {
            try {
                AutoTuneEngine engine = new AutoTuneEngine(this);
                AutoTuneEngine.Result r = engine.run(cfg, (text, done, total) ->
                        runOnUiThread(() -> {
                            status.setText(text);
                            progress.setProgress((int) Math.round(done * 100.0 / Math.max(1, total)));
                        }));

                List<String> decisions = AutoTuneEngine.summarize(r.plan);
                StringBuilder sb = new StringBuilder();
                sb.append("完成。\n");
                sb.append(String.format("原始频响起伏 RMS：%.2f dB\n", r.raw.roughnessDb));
                if (r.verification != null) {
                    sb.append(String.format("第一轮校正后 RMS：%.2f dB\n", r.verification.roughnessDb));
                }
                sb.append(String.format("低频缺口：%.2f dB\n", r.raw.lowDeficitDb));
                sb.append(String.format("可靠测量频段：%d/%d\n\n", r.raw.reliableBands, r.raw.bands.size()));
                sb.append("最终部件决策：\n");
                for (String line : decisions) sb.append(line).append('\n');
                sb.append("\n已直接写入 ViPER 的 Speaker 配置；Master / Global / Auto start 已开启。");
                String out = sb.toString();

                runOnUiThread(() -> {
                    status.setText("校准完成");
                    progress.setProgress(100);
                    result.setText(out);
                    setBusy(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("校准失败");
                    result.setText(e.getClass().getSimpleName() + ": " + e.getMessage() +
                            "\n\n原配置备份会保留；可点击“恢复上次校准前配置”。");
                    setBusy(false);
                });
            }
        });
    }

    private TuneConfig collectConfig() {
        TuneConfig cfg = new TuneConfig();
        cfg.target = (TuneConfig.Target) targetSpinner.getSelectedItem();
        cfg.twoPass = true;
        for (TuneConfig.Effect e : TuneConfig.Effect.values()) {
            Spinner s = effectSpinners.get(e);
            if (s != null && s.isEnabled()) {
                cfg.policies.put(e, (TuneConfig.Policy) s.getSelectedItem());
            } else {
                cfg.policies.put(e, TuneConfig.Policy.OFF);
            }
        }
        return cfg;
    }

    private void restoreLast() {
        setBusy(true);
        executor.execute(() -> {
            try {
                boolean ok = new ViperBridge(this).restoreLast();
                runOnUiThread(() -> {
                    result.setText(ok ? "已恢复上次校准前的 ViPER 配置。" : "没有找到可恢复的备份。");
                    status.setText(ok ? "恢复完成" : "无备份");
                    setBusy(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    result.setText("恢复失败：" + e.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        start.setEnabled(!busy);
        restore.setEnabled(!busy);
        targetSpinner.setEnabled(!busy);
        for (Spinner s : effectSpinners.values()) {
            if (s != null && s.isEnabled()) s.setEnabled(!busy);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTune();
        } else if (requestCode == REQ_MIC) {
            Toast.makeText(this, "需要麦克风权限才能进行闭环声学校准", Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams fullButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        p.setMargins(0, dp(6), 0, 0);
        return p;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) executor.shutdownNow();
    }
}
