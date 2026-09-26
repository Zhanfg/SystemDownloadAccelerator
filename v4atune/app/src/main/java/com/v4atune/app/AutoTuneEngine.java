package com.v4atune.app;

import android.content.Context;
import android.media.AudioManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class AutoTuneEngine {
    interface Listener {
        void onStatus(String text, int done, int total);
    }

    static final class Result {
        final Dsp.Analysis raw;
        final Dsp.Analysis verification;
        final ViperProfile.Plan plan;
        final File backup;
        final int calibrationVolume;

        Result(Dsp.Analysis raw,
               Dsp.Analysis verification,
               ViperProfile.Plan plan,
               File backup,
               int calibrationVolume) {
            this.raw = raw;
            this.verification = verification;
            this.plan = plan;
            this.backup = backup;
            this.calibrationVolume = calibrationVolume;
        }
    }

    private final Context context;
    private final ViperBridge bridge;
    private final AudioCalibrator calibrator;

    AutoTuneEngine(Context context) {
        this.context = context.getApplicationContext();
        this.bridge = new ViperBridge(context);
        this.calibrator = new AudioCalibrator(context);
    }

    Result run(TuneConfig config, Listener listener) throws Exception {
        status(listener, "检查 root", 0, 7);
        if (!RootShell.available()) throw new IllegalStateException("没有获得 root 权限");
        if (!bridge.driverReady()) throw new IllegalStateException("viper.control 不可用，请先修复 ViPER 驱动");

        ViperBridge.Paths paths = bridge.locate();

        status(listener, "备份当前 ViPER 配置", 1, 7);
        File backup = bridge.backup(paths);

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int calibrationVolume = Math.max(1, Math.round(maxVolume * 0.35f));

        try {
            status(listener, "写入无染色测量基线", 2, 7);
            bridge.apply(paths, ViperProfile.baseline(), null);
            bringTuneToFront();
            am.setStreamVolume(AudioManager.STREAM_MUSIC, calibrationVolume, 0);

            status(listener, "第一次声学测量", 3, 7);
            AudioCalibrator.Result rawMeasurement = calibrator.run((text, done, total) ->
                    status(listener, text, 3, 7));
            Dsp.Analysis raw = Dsp.analyze(rawMeasurement.bands, config.target);

            // If the self-microphone was near clipping, repeat at a safer level.
            double maxPeak = maxPeak(rawMeasurement);
            if (maxPeak > 0.94 && calibrationVolume > 1) {
                calibrationVolume = Math.max(1, Math.round(maxVolume * 0.25f));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, calibrationVolume, 0);
                status(listener, "检测到接近削波，降低音量重测", 3, 7);
                rawMeasurement = calibrator.run((text, done, total) ->
                        status(listener, text, 3, 7));
                raw = Dsp.analyze(rawMeasurement.bands, config.target);
            }

            String kernelName = "V4ATune_Speaker.wav";
            ViperProfile.Plan firstPlan = ViperProfile.build(raw, rawMeasurement, config, kernelName);
            File kernel = null;
            if (firstPlan.convolverEnabled) {
                status(listener, "生成这台手机专属 FIR", 4, 7);
                kernel = new File(context.getCacheDir(), kernelName);
                Dsp.writeCorrectionFir(kernel, raw, firstPlan.firShare);
            }

            status(listener, "应用第一轮完整 ViPER 配置", 4, 7);
            bridge.apply(paths, firstPlan.json, kernel);
            bringTuneToFront();
            Thread.sleep(1200);

            Dsp.Analysis verification = null;
            ViperProfile.Plan finalPlan = firstPlan;

            if (config.twoPass) {
                status(listener, "第二轮闭环验证", 5, 7);
                AudioCalibrator.Result verifyMeasurement = calibrator.run((text, done, total) ->
                        status(listener, "验证：" + text, 5, 7));
                verification = Dsp.analyze(verifyMeasurement.bands, config.target);

                Dsp.Analysis refined = Dsp.refine(raw, verification, config.target);
                finalPlan = ViperProfile.build(refined, verifyMeasurement, config, kernelName);

                if (finalPlan.convolverEnabled) {
                    kernel = new File(context.getCacheDir(), kernelName);
                    Dsp.writeCorrectionFir(kernel, refined, finalPlan.firShare);
                } else {
                    kernel = null;
                }

                status(listener, "根据残差写入最终配置", 6, 7);
                bridge.apply(paths, finalPlan.json, kernel);
                bringTuneToFront();
            }

            status(listener, "完成", 7, 7);
            return new Result(raw, verification, finalPlan, backup, calibrationVolume);
        } catch (Exception e) {
            // Preserve the backup for manual/one-tap recovery. Do not auto-restore a
            // partially successful tuning unless the user requests it.
            throw e;
        } finally {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
            } catch (Exception ignored) {}
        }
    }

    private void bringTuneToFront() {
        RootShell.exec("am start -n " + context.getPackageName() + "/.MainActivity >/dev/null 2>&1");
        try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static double maxPeak(AudioCalibrator.Result r) {
        double p = 0.0;
        for (Dsp.Band b : r.bands) p = Math.max(p, b.peak);
        return p;
    }

    private static void status(Listener l, String text, int done, int total) {
        if (l != null) l.onStatus(text, done, total);
    }

    static List<String> summarize(ViperProfile.Plan plan) {
        List<String> lines = new ArrayList<>();
        for (TuneConfig.Effect e : TuneConfig.Effect.values()) {
            Boolean on = plan.enabled.get(e);
            if (on == null) continue;
            lines.add((on ? "✓ " : "— ") + e.label + " · " + plan.reason.get(e));
        }
        return lines;
    }
}
