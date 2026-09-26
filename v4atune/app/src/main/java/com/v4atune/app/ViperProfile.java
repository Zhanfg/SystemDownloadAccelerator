package com.v4atune.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

final class ViperProfile {
    static final class Plan {
        final JSONObject json;
        final EnumMap<TuneConfig.Effect, Boolean> enabled;
        final EnumMap<TuneConfig.Effect, String> reason;
        final double firShare;
        final boolean convolverEnabled;

        Plan(JSONObject json,
             EnumMap<TuneConfig.Effect, Boolean> enabled,
             EnumMap<TuneConfig.Effect, String> reason,
             double firShare,
             boolean convolverEnabled) {
            this.json = json;
            this.enabled = enabled;
            this.reason = reason;
            this.firShare = firShare;
            this.convolverEnabled = convolverEnabled;
        }
    }

    private ViperProfile() {}


    static JSONObject baseline() throws Exception {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 2.1);
        root.put("name", "V4ATune Measurement Baseline");
        root.put("createdAt", System.currentTimeMillis());
        root.put("masterLimiter", obj("threshold", 1.0, "outputVolume", 1.0, "channelPan", 0.0));
        root.put("playbackGainControl", obj("enable", false, "strength", 1.0, "maxGain", 1.0, "outputThreshold", 1.0));
        root.put("lufs", obj("enable", false, "target", -16.0, "maxGain", 3.0, "speed", 0));
        root.put("fetCompressor", obj(
                "enable", false, "threshold", compDb(-18.0), "ratio", compRatio(1.0),
                "kneeAuto", true, "knee", 0.0, "kneeMulti", 0.0,
                "gainAuto", true, "gain", 0.0, "attackAuto", true,
                "attack", 0.020, "maxAttack", 0.080, "releaseAuto", true,
                "release", 0.050, "maxRelease", 0.100, "crest", 0.100,
                "adapt", Math.pow(4.0, .5), "noClip", true));
        root.put("multibandCompressor", obj(
                "enable", false, "bandEnables", arr(true,true,true,true,true),
                "crossovers", arr(120,500,4000,8000),
                "thresholds", arr(compDb(-18),compDb(-18),compDb(-18),compDb(-18),compDb(-18)),
                "ratios", arr(compRatio(.5),compRatio(.5),compRatio(.5),compRatio(.5),compRatio(.5)),
                "gains", arr(0.0,0.0,0.0,0.0,0.0),
                "knees", arr(0.0,0.0,0.0,0.0,0.0),
                "kneeMultis", arr(0.0,0.0,0.0,0.0,0.0),
                "attacks", arr(.001,.001,.001,.001,.001),
                "maxAttacks", arr(.044,.044,.044,.044,.044),
                "releases", arr(.100,.100,.100,.100,.100),
                "maxReleases", arr(.200,.200,.200,.200,.200),
                "crests", arr(.100,.100,.100,.100,.100),
                "adapts", arr(Math.pow(4,.5),Math.pow(4,.5),Math.pow(4,.5),Math.pow(4,.5),Math.pow(4,.5)),
                "kneeAutos", arr(true,true,true,true,true),
                "gainAutos", arr(true,true,true,true,true),
                "attackAutos", arr(true,true,true,true,true),
                "releaseAutos", arr(true,true,true,true,true),
                "noClips", arr(true,true,true,true,true)));
        root.put("ddc", obj("enable", false, "device", ""));
        root.put("spectrumExtension", obj("enable", false, "strength", 7600, "exciter", 0.0));
        root.put("equalizer", obj("enable", false, "bandCount", 10,
                "bands", arr(0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0), "presetId", JSONObject.NULL));
        root.put("dynamicEq", obj("enable", false, "bandCount", 3,
                "freqs", arr(60,150,400), "qs", arr(1.0,1.0,1.5), "gains", arr(0.0,0.0,0.0),
                "thresholds", arr(-20.0,-20.0,-20.0), "attacks", arr(10.0,10.0,10.0),
                "releases", arr(100.0,100.0,100.0), "filterTypes", arr(0,0,0)));
        root.put("convolver", obj("enable", false, "kernelFile", "", "crossChannel", 0.0));
        root.put("fieldSurround", obj("enable", false, "widening", 0.0, "midImage", 1.5, "depth", 200));
        root.put("diffSurround", obj("enable", false, "delay", 5.0, "reverse", false, "wetDryMix", 1.0, "lpCutoff", 0));
        root.put("stereoImager", obj("enable", false, "lowWidth", 1.0, "midWidth", 1.0, "highWidth", 1.0, "lowCrossover", 200, "highCrossover", 4000));
        root.put("headphoneSurround", obj("enable", false, "quality", 0));
        root.put("reverb", obj("enable", false, "roomSize", 0.0, "width", 0.0, "damp", .5, "wet", 0.0, "dry", 1.0));
        root.put("dynamicSystem", obj("enable", false, "presetId", JSONObject.NULL, "device", 0, "strength", 1.0,
                "xLow", 100, "xHigh", 5600, "yLow", 40, "yHigh", 80, "sideGainLow", .5, "sideGainHigh", .5));
        root.put("psychoacousticBass", obj("enable", false, "cutoff", 80, "intensity", .5, "harmonicOrder", 3, "originalLevel", 1.0));
        root.put("bass", obj("enable", false, "mode", 0, "frequency", 60, "gain", .5, "antiPop", false));
        root.put("bassMono", obj("enable", false, "mode", 0, "frequency", 60, "gain", .5, "antiPop", false));
        root.put("clarity", obj("enable", false, "mode", 0, "gain", .5));
        root.put("cure", obj("enable", false, "crossfeedPreset", 0));
        root.put("tubeSimulator", obj("enable", false));
        root.put("analogX", obj("enable", false, "mode", 0));
        root.put("speakerCorrection", obj("enable", false));
        return root;
    }

    static Plan build(Dsp.Analysis a,
                      AudioCalibrator.Result measurement,
                      TuneConfig config,
                      String kernelFile) throws Exception {
        EnumMap<TuneConfig.Effect, Boolean> en = new EnumMap<>(TuneConfig.Effect.class);
        EnumMap<TuneConfig.Effect, String> why = new EnumMap<>(TuneConfig.Effect.class);

        boolean convolver = decide(config, TuneConfig.Effect.CONVOLVER,
                a.reliable() && a.roughnessDb >= 1.4, en, why,
                "频响起伏较大，使用细粒度 FIR 校正");
        boolean eq = decide(config, TuneConfig.Effect.EQ,
                true, en, why, "用于宽带音色与剩余频响校正");
        boolean speakerCorrection = decide(config, TuneConfig.Effect.SPEAKER_CORRECTION,
                true, en, why, "当前输出是内置扬声器");
        boolean psychoBass = decide(config, TuneConfig.Effect.PSYCHO_BASS,
                a.lowDeficitDb >= 4.0 || config.target == TuneConfig.Target.BASS,
                en, why, "低频物理下潜不足时以谐波补偿代替危险的大幅低频增益");
        boolean bass = decide(config, TuneConfig.Effect.BASS,
                config.target == TuneConfig.Target.BASS && a.lowDeficitDb < 8.0 && a.medianThd < 0.12,
                en, why, "低频仍有物理余量时使用轻量直接增强");
        boolean bassMono = decide(config, TuneConfig.Effect.BASS_MONO,
                false, en, why, "内置双扬声器默认保留低频声道信息");
        boolean clarity = decide(config, TuneConfig.Effect.CLARITY,
                config.target == TuneConfig.Target.VOCAL && a.highDeficitDb > 1.5,
                en, why, "人声模式且高频偏暗时使用透明清晰度");
        boolean spectrum = decide(config, TuneConfig.Effect.SPECTRUM,
                (config.target == TuneConfig.Target.BALANCED || config.target == TuneConfig.Target.VOCAL)
                        && a.highDeficitDb >= 3.0 && a.highDeficitDb <= 10.0,
                en, why, "高频衰减明显时小幅补充高频谐波");

        boolean dynamicEq = decide(config, TuneConfig.Effect.DYNAMIC_EQ,
                strongestPeak(a) >= 3.0,
                en, why, "检测到会随内容暴露的窄带峰值");
        boolean stereoImager = decide(config, TuneConfig.Effect.STEREO_IMAGER,
                config.target == TuneConfig.Target.SPATIAL,
                en, why, "空间模式使用轻量分频段立体声宽度");
        boolean field = decide(config, TuneConfig.Effect.FIELD_SURROUND,
                config.target == TuneConfig.Target.SPATIAL,
                en, why, "空间模式增加轻量声场宽度");
        boolean diff = decide(config, TuneConfig.Effect.DIFF_SURROUND,
                false, en, why, "默认避免时延型染色；强制开启时采用低混合比");
        boolean reverb = decide(config, TuneConfig.Effect.REVERB,
                false, en, why, "参考音质默认不加入额外房间染色");
        boolean headphoneSurround = decide(config, TuneConfig.Effect.HEADPHONE_SURROUND,
                false, en, why, "内置扬声器不需要耳机 HRTF");

        boolean lufs = decide(config, TuneConfig.Effect.LUFS,
                config.target == TuneConfig.Target.LOUDNESS,
                en, why, "高响度模式启用感知响度归一化");
        boolean playbackGain = decide(config, TuneConfig.Effect.PLAYBACK_GAIN,
                false, en, why, "参考模式保留曲目间动态差异");
        boolean multiband = decide(config, TuneConfig.Effect.MULTIBAND_COMP,
                config.target == TuneConfig.Target.LOUDNESS,
                en, why, "高响度模式分频段控制峰值，避免低频触发全频泵动");
        boolean fet = decide(config, TuneConfig.Effect.FET_COMP,
                false, en, why, "默认保留瞬态；强制开启时仅轻压缩");

        boolean ddc = decide(config, TuneConfig.Effect.DDC,
                false, en, why, "DDC 更适合耳机/设备 VDC，扬声器校准优先 FIR");
        boolean dynamicSystem = decide(config, TuneConfig.Effect.DYNAMIC_SYSTEM,
                false, en, why, "该模块主要针对耳机的低频体感补偿");
        boolean cure = decide(config, TuneConfig.Effect.CURE,
                false, en, why, "交叉馈送是耳机用途，扬声器默认关闭");
        boolean tube = decide(config, TuneConfig.Effect.TUBE,
                false, en, why, "谐波染色不是参考目标");
        boolean analogX = decide(config, TuneConfig.Effect.ANALOGX,
                false, en, why, "模拟染色不是参考目标");

        double firShare = convolver && eq ? 0.62 : (convolver ? 1.0 : 0.0);
        double eqShare = convolver && eq ? 0.38 : (eq ? 1.0 : 0.0);

        JSONObject root = new JSONObject();
        root.put("schemaVersion", 2.1);
        root.put("name", "V4ATune " + config.target.label);
        root.put("createdAt", System.currentTimeMillis());

        double headroomDb = 1.0;
        if (psychoBass || bass || spectrum || clarity) headroomDb += 1.0;
        if (field || diff || reverb) headroomDb += 0.5;
        if (config.target == TuneConfig.Target.LOUDNESS) headroomDb = 1.5;
        root.put("masterLimiter", obj(
                "threshold", Math.pow(10.0, -headroomDb / 20.0),
                "outputVolume", 1.0,
                "channelPan", 0.0
        ));

        root.put("playbackGainControl", obj(
                "enable", playbackGain,
                "strength", config.target == TuneConfig.Target.LOUDNESS ? 1.2 : 0.75,
                "maxGain", 1.8,
                "outputThreshold", 0.82
        ));

        root.put("lufs", obj(
                "enable", lufs,
                "target", config.target == TuneConfig.Target.LOUDNESS ? -14.0 : -16.0,
                "maxGain", 3.0,
                "speed", 0
        ));

        root.put("fetCompressor", obj(
                "enable", fet,
                "threshold", compDb(-13.0),
                "ratio", compRatio(1.35),
                "kneeAuto", true,
                "knee", 0.0,
                "kneeMulti", 0.0,
                "gainAuto", true,
                "gain", 0.0,
                "attackAuto", false,
                "attack", 0.018,
                "maxAttack", 0.060,
                "releaseAuto", false,
                "release", 0.120,
                "maxRelease", 0.250,
                "crest", 0.100,
                "adapt", Math.pow(4.0, 0.5),
                "noClip", true
        ));

        root.put("multibandCompressor", obj(
                "enable", multiband,
                "bandEnables", arr(true, true, true, true, true),
                "crossovers", arr(160, 630, 2500, 8000),
                "thresholds", arr(compDb(-18), compDb(-16), compDb(-15), compDb(-15), compDb(-16)),
                "ratios", arr(compRatio(1.25), compRatio(1.25), compRatio(1.22), compRatio(1.20), compRatio(1.18)),
                "gains", arr(0.0, 0.0, 0.0, 0.0, 0.0),
                "knees", arr(0.0, 0.0, 0.0, 0.0, 0.0),
                "kneeMultis", arr(0.0, 0.0, 0.0, 0.0, 0.0),
                "attacks", arr(0.012, 0.010, 0.008, 0.006, 0.006),
                "maxAttacks", arr(0.060, 0.055, 0.050, 0.045, 0.045),
                "releases", arr(0.160, 0.140, 0.120, 0.100, 0.090),
                "maxReleases", arr(0.300, 0.280, 0.240, 0.220, 0.200),
                "crests", arr(0.100, 0.100, 0.100, 0.100, 0.100),
                "adapts", arr(Math.pow(4, .5), Math.pow(4, .5), Math.pow(4, .5), Math.pow(4, .5), Math.pow(4, .5)),
                "kneeAutos", arr(true, true, true, true, true),
                "gainAutos", arr(true, true, true, true, true),
                "attackAutos", arr(false, false, false, false, false),
                "releaseAutos", arr(false, false, false, false, false),
                "noClips", arr(true, true, true, true, true)
        ));

        root.put("ddc", obj("enable", ddc, "device", ""));
        root.put("spectrumExtension", obj(
                "enable", spectrum,
                "strength", a.highDeficitDb > 6 ? 6500 : 7600,
                "exciter", a.highDeficitDb > 6 ? 0.55 : 0.30
        ));

        double[] eqBands = new double[a.eq10.length];
        for (int i = 0; i < eqBands.length; i++) eqBands[i] = Math.round(a.eq10[i] * eqShare * 10.0) / 10.0;
        root.put("equalizer", obj(
                "enable", eq,
                "bandCount", 10,
                "bands", arr(eqBands),
                "presetId", JSONObject.NULL
        ));

        int[] peakFreqs = peakFrequencies(a, 3);
        double[] peakCuts = peakCuts(a, peakFreqs);
        root.put("dynamicEq", obj(
                "enable", dynamicEq,
                "bandCount", 3,
                "freqs", arr(peakFreqs[0], peakFreqs[1], peakFreqs[2]),
                "qs", arr(1.4, 1.6, 1.8),
                "gains", arr(peakCuts[0], peakCuts[1], peakCuts[2]),
                "thresholds", arr(-20.0, -20.0, -20.0),
                "attacks", arr(8.0, 8.0, 6.0),
                "releases", arr(120.0, 120.0, 100.0),
                "filterTypes", arr(0, 0, 0)
        ));

        root.put("convolver", obj(
                "enable", convolver && kernelFile != null && !kernelFile.isBlank(),
                "kernelFile", kernelFile == null ? "" : kernelFile,
                "crossChannel", 0.0
        ));

        root.put("fieldSurround", obj(
                "enable", field,
                "widening", config.target == TuneConfig.Target.SPATIAL ? 1.25 : 0.55,
                "midImage", 1.25,
                "depth", 360
        ));
        root.put("diffSurround", obj(
                "enable", diff,
                "delay", 2.5,
                "reverse", false,
                "wetDryMix", 0.16,
                "lpCutoff", 7000
        ));
        root.put("stereoImager", obj(
                "enable", stereoImager,
                "lowWidth", 0.92,
                "midWidth", 1.10,
                "highWidth", 1.16,
                "lowCrossover", 220,
                "highCrossover", 4500
        ));
        root.put("headphoneSurround", obj("enable", headphoneSurround, "quality", 2));
        root.put("reverb", obj(
                "enable", reverb,
                "roomSize", 0.12,
                "width", 0.68,
                "damp", 0.60,
                "wet", 0.055,
                "dry", 1.0
        ));

        root.put("dynamicSystem", obj(
                "enable", dynamicSystem,
                "presetId", JSONObject.NULL,
                "device", 0,
                "strength", 1.15,
                "xLow", 90,
                "xHigh", 4200,
                "yLow", 45,
                "yHigh", 95,
                "sideGainLow", 0.45,
                "sideGainHigh", 0.50
        ));

        int psychoCutoff = a.lowDeficitDb > 10 ? 120 : (a.lowDeficitDb > 7 ? 105 : 90);
        double psychoIntensity = clamp(0.18 + Math.max(0, a.lowDeficitDb - 4.0) * 0.025, 0.18, 0.44);
        if (config.target == TuneConfig.Target.BASS) psychoIntensity = Math.min(0.50, psychoIntensity + 0.07);
        root.put("psychoacousticBass", obj(
                "enable", psychoBass,
                "cutoff", psychoCutoff,
                "intensity", psychoIntensity,
                "harmonicOrder", 3,
                "originalLevel", 0.90
        ));

        root.put("bass", obj(
                "enable", bass,
                "mode", 0,
                "frequency", config.target == TuneConfig.Target.BASS ? 95 : 80,
                "gain", config.target == TuneConfig.Target.BASS ? 0.85 : 0.60,
                "antiPop", true
        ));
        root.put("bassMono", obj(
                "enable", bassMono,
                "mode", 0,
                "frequency", 90,
                "gain", 0.60,
                "antiPop", true
        ));
        root.put("clarity", obj(
                "enable", clarity,
                "mode", 0,
                "gain", config.target == TuneConfig.Target.VOCAL ? 0.85 : 0.55
        ));
        root.put("cure", obj("enable", cure, "crossfeedPreset", 0));
        root.put("tubeSimulator", obj("enable", tube));
        root.put("analogX", obj("enable", analogX, "mode", 0));
        root.put("speakerCorrection", obj("enable", speakerCorrection));

        return new Plan(root, en, why, firShare, convolver);
    }

    private static boolean decide(TuneConfig cfg,
                                  TuneConfig.Effect effect,
                                  boolean auto,
                                  Map<TuneConfig.Effect, Boolean> enabled,
                                  Map<TuneConfig.Effect, String> reason,
                                  String autoReason) {
        TuneConfig.Policy p = cfg.policy(effect);
        boolean on;
        String why;
        if (p == TuneConfig.Policy.ON) {
            on = true;
            why = "用户指定开启并优化";
        } else if (p == TuneConfig.Policy.OFF) {
            on = false;
            why = "用户指定关闭";
        } else {
            on = auto;
            why = auto ? autoReason : "自动判断当前不需要";
        }
        enabled.put(effect, on);
        reason.put(effect, why);
        return on;
    }

    private static double strongestPeak(Dsp.Analysis a) {
        double best = 0;
        for (Dsp.Band b : a.bands) {
            if (b.snr() < 12 || b.freq < 125 || b.freq > 10000) continue;
            best = Math.max(best, b.levelDb - a.referenceDb);
        }
        return best;
    }

    private static int[] peakFrequencies(Dsp.Analysis a, int count) {
        int[] out = {180, 1200, 6000};
        boolean[] used = new boolean[a.bands.size()];
        for (int k = 0; k < count; k++) {
            int best = -1;
            double bestVal = 1.0;
            for (int i = 0; i < a.bands.size(); i++) {
                if (used[i]) continue;
                Dsp.Band b = a.bands.get(i);
                if (b.snr() < 12 || b.freq < 100 || b.freq > 12000) continue;
                double v = b.levelDb - a.referenceDb;
                if (v > bestVal) {
                    bestVal = v;
                    best = i;
                }
            }
            if (best >= 0) {
                out[k] = (int) Math.round(a.bands.get(best).freq);
                used[best] = true;
            }
        }
        return out;
    }

    private static double[] peakCuts(Dsp.Analysis a, int[] freqs) {
        double[] out = {-0.8, -0.8, -0.8};
        for (int k = 0; k < out.length; k++) {
            double nearest = 999;
            double peak = 0;
            for (Dsp.Band b : a.bands) {
                double d = Math.abs(Math.log(b.freq / freqs[k]));
                if (d < nearest) {
                    nearest = d;
                    peak = b.levelDb - a.referenceDb;
                }
            }
            out[k] = -clamp(Math.max(0.8, peak - 1.0), 0.8, 3.5);
        }
        return out;
    }

    private static double compDb(double db) {
        return db * Math.log(10.0) / 20.0;
    }

    private static double compRatio(double ratio) {
        return -ratio;
    }

    private static JSONObject obj(Object... kv) throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
        return o;
    }

    private static JSONArray arr(Object... values) {
        JSONArray a = new JSONArray();
        for (Object value : values) a.put(value);
        return a;
    }

    private static JSONArray arr(double[] values) {
        JSONArray a = new JSONArray();
        for (double value : values) a.put(value);
        return a;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
