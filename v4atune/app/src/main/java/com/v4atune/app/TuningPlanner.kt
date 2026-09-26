package com.v4atune.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object TuningPlanner {
    private val eq10 = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private val eq15 = doubleArrayOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0)
    private val eq25 = doubleArrayOf(20.0, 31.5, 40.0, 50.0, 80.0, 100.0, 125.0, 160.0, 250.0, 315.0, 400.0, 500.0, 800.0, 1000.0, 1250.0, 1600.0, 2500.0, 3150.0, 4000.0, 5000.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0)

    fun metrics(m: Measurement, options: TuneOptions): TuneMetrics {
        val ref = reference(m)
        val useful = m.dense.filter { it.frequency in 125.0..12000.0 && it.snrDb >= 12.0 }
        val deviations = useful.map { it.levelDb - ref - sceneTargetOffset(options, it.frequency) }
        val rms = sqrt(deviations.map { it * it }.average().takeIf { !it.isNaN() } ?: 0.0)
        val maxDev = deviations.maxOfOrNull { abs(it) } ?: 0.0
        val low = deficit(m, ref, 80.0, 315.0, options)
        val high = deficit(m, ref, 6300.0, 16000.0, options)
        val thd = Acoustics.median(m.distortion.map { it.thd })
        return TuneMetrics(
            responseRmsDb = rms,
            maxDeviationDb = maxDev,
            lowDeficitDb = low,
            highDeficitDb = high,
            medianThd = thd,
            channelDeltaDb = m.left1kDb - m.right1kDb,
            compressionDb = m.compressionDb,
        )
    }

    fun build(m: Measurement, options: TuneOptions): Plan {
        val spec = SceneDspProfiles.forScene(options.effectiveScene)
        val metrics = metrics(m, options)
        val decisions = linkedMapOf<Component, Pair<Boolean, String>>()

        fun decide(component: Component, auto: Boolean, reason: String): Boolean {
            val policy = options.policies[component] ?: Policy.Auto
            val enabled = when (policy) {
                Policy.On -> true
                Policy.Off -> false
                Policy.Auto -> auto
            }
            decisions[component] = enabled to when (policy) {
                Policy.On -> "用户指定开启；参数按实测优化"
                Policy.Off -> "用户指定关闭"
                Policy.Auto -> if (enabled) reason else "自动判断当前收益不足"
            }
            return enabled
        }

        val speakerCorrection = decide(Component.SpeakerCorrection, true, "内置扬声器专用校正")
        val ddcOn = decide(Component.Ddc, false, "扬声器默认由 IIR/FIR 承担；DDC 仅在显式要求时加入")
        val convolverOn = decide(
            Component.Convolver,
            spec.fir.autoEnable && metrics.responseRmsDb >= spec.fir.minResponseRmsDb && reliable(m),
            if (!spec.fir.autoEnable) "当前场景的延迟预算禁止自动 FIR"
            else "稠密频响残差超过场景阈值，使用最小相位 FIR",
        )
        val eqOn = decide(Component.Equalizer, true, "使用驱动原生 31 段 IIR 做宽带校正")
        val dynamicEqOn = decide(
            Component.DynamicEq,
            spec.dynamicEq.autoEnable && peakCandidates(m, options).isNotEmpty(),
            "检测到超过当前场景阈值的窄带共振峰",
        )
        val psychoOn = decide(
            Component.PsychoBass,
            metrics.lowDeficitDb >= 4.0 || options.target == Target.Bass,
            "低频下潜不足，优先用心理声学谐波而非危险低频暴力增益",
        )
        val bassOn = decide(
            Component.Bass,
            options.target == Target.Bass && metrics.medianThd < 0.12 && metrics.lowDeficitDb < 10.0,
            "低频失真余量允许轻度直接增强",
        )
        val bassMonoOn = decide(Component.BassMono, false, "内置双扬声器默认保留声道低频信息")
        val spectrumOn = decide(
            Component.Spectrum,
            metrics.highDeficitDb in 2.5..8.0 && metrics.medianThd < 0.15,
            "高频衰减明显但仍有失真余量",
        )
        val clarityOn = decide(
            Component.Clarity,
            options.target == Target.Vocal && metrics.highDeficitDb >= 1.5,
            "人声目标且高频偏暗",
        )
        val imagerOn = decide(
            Component.StereoImager,
            options.effectiveScene in setOf(Scene.Music, Scene.Movie, Scene.Game),
            "当前场景允许分频段声像宽度优化",
        )
        val fieldOn = decide(
            Component.FieldSurround,
            options.effectiveScene == Scene.Movie,
            "电影场景允许轻量场环绕",
        )
        val diffOn = decide(Component.DiffSurround, false, "默认避免额外延迟和梳状干涉")
        val headphoneOn = decide(Component.HeadphoneSurround, false, "当前输出是手机扬声器而非耳机")
        val reverbOn = decide(Component.Reverb, false, "参考音质默认不添加房间染色")
        val dynamicSystemOn = decide(Component.DynamicSystem, false, "主要为耳机虚拟化设计")
        val cureOn = decide(Component.Cure, false, "交叉馈送主要用于耳机")
        val tubeOn = decide(Component.Tube, false, "谐波染色不是参考目标")
        val analogOn = decide(Component.AnalogX, false, "模拟染色不是参考目标")
        val lufsOn = decide(
            Component.Lufs,
            options.effectiveScene in setOf(Scene.Voice, Scene.Outdoor, Scene.Night),
            "当前场景需要稳定感知响度",
        )
        val playbackOn = decide(
            Component.PlaybackGain,
            options.effectiveScene == Scene.Outdoor,
            "户外场景允许受控回放增益",
        )
        val mbcOn = decide(
            Component.MultibandCompressor,
            options.effectiveScene == Scene.Outdoor,
            "户外场景使用分段压缩提升可听度",
        )
        val fetOn = decide(Component.FetCompressor, false, "默认保留瞬态；高响度优先使用多段压缩")

        val ref = reference(m)
        val denseFreqs = m.dense.map { it.frequency }.toDoubleArray()
        val desired = DoubleArray(m.dense.size) { i ->
            val p = m.dense[i]
            val relative = p.levelDb - ref
            var correction = sceneTargetOffset(options, p.frequency) - relative
            val maxBoost = when {
                p.frequency < 80.0 -> 0.0
                p.frequency > 14000.0 -> 0.0
                p.frequency > 8000.0 -> 0.7
                else -> 1.8
            }
            if (p.snrDb < 12.0) correction = 0.0
            correction.coerceIn(-7.0, maxBoost)
        }.smooth3()

        val ddc = if (ddcOn) designDdc(m, options) else null
        val ddcDb = if (ddc != null) {
            DoubleArray(denseFreqs.size) { i -> cascadeDb(ddc.second, denseFreqs[i], 48000.0) }
        } else {
            DoubleArray(denseFreqs.size)
        }
        val afterDdc = DoubleArray(desired.size) { i -> desired[i] - ddcDb[i] }

        val eqFreqs = when (options.eqBands) {
            10 -> eq10
            15 -> eq15
            25 -> eq25
            else -> Acoustics.eq31
        }
        val eqShare = when {
            !eqOn -> 0.0
            convolverOn -> spec.fir.eqShare
            else -> 1.0
        }
        val eqLevels = DoubleArray(eqFreqs.size) { i ->
            (interpolate(denseFreqs, afterDdc, eqFreqs[i]) * eqShare)
                .coerceIn(-7.0, 1.5)
        }.smooth3()

        val eqApprox = DoubleArray(denseFreqs.size) { i ->
            if (eqOn) interpolate(eqFreqs, eqLevels, denseFreqs[i]) else 0.0
        }
        val firDb = DoubleArray(denseFreqs.size) { i ->
            if (convolverOn) {
                (afterDdc[i] - eqApprox[i]).coerceIn(spec.fir.maxCutDb, spec.fir.maxBoostDb)
            } else 0.0
        }.smooth3()

        val kernel = if (convolverOn) {
            minimumPhaseFir(
                frequencies = denseFreqs,
                correctionDb = firDb,
                sampleRate = m.sampleRate,
                taps = options.firTaps.coerceIn(256, 8192),
            )
        } else null

        val dynPeaks = if (dynamicEqOn) {
            peakCandidates(m, options).take(spec.dynamicEq.maxBands)
        } else emptyList()
        val pan = channelPan(metrics.channelDeltaDb)
        val headroomDb = (
            spec.loudness.baseHeadroomDb +
                if (psychoOn || bassOn || spectrumOn || clarityOn) 0.6 else 0.0 +
                if (fieldOn || diffOn || reverbOn) 0.3 else 0.0
            ).coerceIn(0.8, 3.5)

        val profile = JSONObject().apply {
            put("schemaVersion", 2.1)
            put("name", "V4ATune " + options.scene.label + " · " + options.target.label)
            put("createdAt", System.currentTimeMillis())
            put("masterLimiter", obj(
                "threshold", 10.0.pow(-1.0 / 20.0),
                "outputVolume", 10.0.pow(-headroomDb / 20.0),
                "channelPan", pan,
            ))
            put("playbackGainControl", obj(
                "enable", playbackOn,
                "strength", spec.loudness.playbackStrength,
                "maxGain", spec.loudness.playbackMaxGain,
                "outputThreshold", spec.loudness.playbackOutputThreshold,
            ))
            put("lufs", obj(
                "enable", lufsOn,
                "target", spec.loudness.lufsTarget,
                "maxGain", spec.loudness.lufsMaxGain,
                "speed", spec.loudness.lufsSpeed,
            ))
            put("fetCompressor", fetJson(fetOn, spec.loudness))
            put("multibandCompressor", multibandJson(mbcOn, spec.loudness))
            put("ddc", obj("enable", ddcOn, "device", if (ddcOn) "V4ATune_Speaker" else ""))
            put("spectrumExtension", obj(
                "enable", spectrumOn,
                "strength", if (metrics.highDeficitDb > 5.0) 6200 else 7600,
                "exciter", if (metrics.highDeficitDb > 5.0) 0.55 else 0.28,
            ))
            put("equalizer", obj(
                "enable", eqOn,
                "bandCount", eqFreqs.size,
                "bands", arr(eqLevels),
                "presetId", JSONObject.NULL,
            ))
            put("dynamicEq", dynamicEqJson(dynamicEqOn, dynPeaks, spec.dynamicEq))
            put("convolver", obj(
                "enable", convolverOn,
                "kernelFile", if (convolverOn) "V4ATune_Speaker.wav" else "",
                "crossChannel", 0.0,
            ))
            put("fieldSurround", obj(
                "enable", fieldOn,
                "widening", spec.spatial.fieldWidening,
                "midImage", spec.spatial.fieldMidImage,
                "depth", spec.spatial.fieldDepth,
            ))
            put("diffSurround", obj(
                "enable", diffOn,
                "delay", spec.spatial.diffDelayMs,
                "reverse", false,
                "wetDryMix", spec.spatial.diffMix,
                "lpCutoff", spec.spatial.diffLowPassHz,
            ))
            put("stereoImager", obj(
                "enable", imagerOn,
                "lowWidth", spec.spatial.imagerLowWidth,
                "midWidth", spec.spatial.imagerMidWidth,
                "highWidth", spec.spatial.imagerHighWidth,
                "lowCrossover", spec.spatial.imagerLowCrossoverHz,
                "highCrossover", spec.spatial.imagerHighCrossoverHz,
            ))
            put("headphoneSurround", obj("enable", headphoneOn, "quality", 2))
            put("reverb", obj(
                "enable", reverbOn,
                "roomSize", spec.spatial.reverbRoomSize,
                "width", spec.spatial.reverbWidth,
                "damp", spec.spatial.reverbDamp,
                "wet", spec.spatial.reverbWet,
                "dry", 1.0,
            ))
            put("dynamicSystem", obj(
                "enable", dynamicSystemOn,
                "presetId", JSONObject.NULL,
                "device", 0,
                "strength", 1.12,
                "xLow", 90,
                "xHigh", 4200,
                "yLow", 45,
                "yHigh", 95,
                "sideGainLow", 0.45,
                "sideGainHigh", 0.50,
            ))
            put("psychoacousticBass", obj(
                "enable", psychoOn,
                "cutoff", when {
                    metrics.lowDeficitDb > 10.0 -> 120
                    metrics.lowDeficitDb > 7.0 -> 105
                    else -> 90
                },
                "intensity", (
                    0.18 + max(0.0, metrics.lowDeficitDb - 4.0) * 0.025 +
                        if (options.effectiveScene == Scene.Outdoor) 0.05 else 0.0
                    ).coerceIn(
                        0.18,
                        when {
                            options.effectiveScene == Scene.Outdoor -> 0.46
                            options.target == Target.Bass -> 0.50
                            else -> 0.42
                        },
                    ),
                "harmonicOrder", 3,
                "originalLevel", 0.90,
            ))
            put("bass", obj(
                "enable", bassOn,
                "mode", 0,
                "frequency", if (options.target == Target.Bass) 95 else 80,
                "gain", if (options.target == Target.Bass) 0.80 else 0.55,
                "antiPop", true,
            ))
            put("bassMono", obj(
                "enable", bassMonoOn,
                "mode", 0,
                "frequency", 90,
                "gain", 0.55,
                "antiPop", true,
            ))
            put("clarity", obj(
                "enable", clarityOn,
                "mode", 0,
                "gain", when (options.scene) {
                    Scene.Voice -> 0.84
                    Scene.Outdoor -> 0.72
                    Scene.Night -> 0.68
                    Scene.Game -> 0.62
                    else -> if (options.target == Target.Vocal) 0.80 else 0.50
                },
            ))
            put("cure", obj("enable", cureOn, "crossfeedPreset", 0))
            put("tubeSimulator", obj("enable", tubeOn))
            put("analogX", obj("enable", analogOn, "mode", 0))
            put("speakerCorrection", obj("enable", speakerCorrection))
        }

        val commands = ProfileCodec.commands(profile)
        return Plan(
            profile = profile,
            eqFrequencies = eqFreqs,
            eqLevels = eqLevels,
            firDb = firDb,
            firFrequencies = denseFreqs,
            kernel = kernel,
            commands = commands,
            decisions = decisions,
            ddc44 = ddc?.first,
            ddc48 = ddc?.second,
        )
    }

    fun refine(
        first: Plan,
        verification: Measurement,
        options: TuneOptions,
    ): Plan {
        val spec = SceneDspProfiles.forScene(options.effectiveScene)
        val verifyMetrics = metrics(verification, options)
        if (verifyMetrics.responseRmsDb < 1.0) return first

        val ref = reference(verification)
        val vf = verification.dense.map { it.frequency }.toDoubleArray()
        val residual = DoubleArray(vf.size) { i ->
            val p = verification.dense[i]
            if (p.snrDb < 12.0) 0.0
            else (sceneTargetOffset(options, p.frequency) - (p.levelDb - ref)).coerceIn(-1.5, 1.0)
        }.smooth3()

        val newEq = DoubleArray(first.eqLevels.size) { i ->
            (
                first.eqLevels[i] +
                    interpolate(vf, residual, first.eqFrequencies[i]) * spec.fir.refineEqShare
                ).coerceIn(-8.0, 1.5)
        }.smooth3()

        val profile = JSONObject(first.profile.toString())
        profile.getJSONObject("equalizer").put("bands", arr(newEq))

        val residualFir = DoubleArray(first.firDb.size) { i ->
            val correction = interpolate(vf, residual, first.firFrequencies[i]) * spec.fir.refineFirShare
            (first.firDb[i] + correction).coerceIn(spec.fir.maxCutDb, spec.fir.maxBoostDb)
        }.smooth3()

        val kernel = if (profile.getJSONObject("convolver").getBoolean("enable")) {
            minimumPhaseFir(
                first.firFrequencies,
                residualFir,
                verification.sampleRate,
                options.firTaps,
            )
        } else null

        return first.copy(
            profile = profile,
            eqLevels = newEq,
            firDb = residualFir,
            kernel = kernel,
            commands = ProfileCodec.commands(profile),
        )
    }

    private fun reference(m: Measurement): Double =
        Acoustics.median(
            m.dense.filter { it.frequency in 315.0..3150.0 && it.snrDb >= 12.0 }
                .map { it.levelDb },
        )

    private fun reliable(m: Measurement): Boolean =
        m.dense.count { it.frequency in 80.0..16000.0 && it.snrDb >= 12.0 } >=
            (m.dense.count { it.frequency in 80.0..16000.0 } * 0.7).toInt()

    private fun deficit(
        m: Measurement,
        ref: Double,
        lo: Double,
        hi: Double,
        options: TuneOptions,
    ): Double {
        val vals = m.dense
            .filter { it.frequency in lo..hi && it.snrDb >= 12.0 }
            .map { it.levelDb - sceneTargetOffset(options, it.frequency) }
        return if (vals.isEmpty()) 0.0 else max(0.0, ref - Acoustics.median(vals))
    }

    private fun sceneTargetOffset(options: TuneOptions, frequency: Double): Double {
        val spec = SceneDspProfiles.forScene(options.effectiveScene)
        val base = interpolate(Acoustics.eq31, spec.eqTarget31Db, frequency)

        if (options.scene != Scene.Custom || options.target == spec.target) return base

        // Custom mode is an overlay: retain the originating scene curve and add
        // only the delta introduced by the manually selected generic target.
        return base +
            genericTargetOffset(options.target, frequency) -
            genericTargetOffset(spec.target, frequency)
    }

    private fun genericTargetOffset(target: Target, f: Double): Double = when (target) {
        Target.Reference -> 0.0
        Target.Balanced -> when {
            f <= 250 -> 0.8
            f >= 8000 -> 0.4
            else -> 0.0
        }
        Target.Vocal -> when {
            f < 250 -> -0.7
            f in 1000.0..3500.0 -> 1.0
            f >= 10000 -> -0.4
            else -> 0.0
        }
        Target.Bass -> if (f <= 315) 1.5 else 0.0
        Target.Spatial -> if (f in 4000.0..10000.0) 0.4 else 0.0
        Target.Loudness -> when {
            f <= 250 -> 0.6
            f in 2500.0..8000.0 -> 0.5
            else -> 0.0
        }
    }

    private data class Peak(val f: Double, val gain: Double, val q: Double)

    private fun peakCandidates(m: Measurement, options: TuneOptions): List<Peak> {
        val ref = reference(m)
        val strategy = SceneDspProfiles.forScene(options.effectiveScene).dynamicEq
        val candidates = mutableListOf<Peak>()
        val a = m.dense

        for (i in 2 until a.size - 2) {
            val p = a[i]
            if (p.frequency !in 100.0..14000.0 || p.snrDb < 12.0) continue

            val excess = p.levelDb - ref - sceneTargetOffset(options, p.frequency)
            val neighbors = listOf(i - 2, i - 1, i + 1, i + 2)
            val localExcess = neighbors
                .map { n -> a[n].levelDb - ref - sceneTargetOffset(options, a[n].frequency) }
                .average()
            val prominence = excess - localExcess

            if (excess > strategy.minExcessDb && prominence > strategy.minProminenceDb) {
                val q = (1.0 + prominence / 2.0).coerceIn(strategy.qMin, strategy.qMax)
                val cut = min(strategy.maxCutDb, excess - strategy.minExcessDb * 0.35)
                candidates += Peak(p.frequency, -cut, q)
            }
        }

        return candidates
            .sortedBy { it.gain }
            .fold(mutableListOf()) { acc, peak ->
                if (acc.none { abs(ln(it.f / peak.f) / ln(2.0)) < 0.25 }) acc += peak
                acc
            }
    }

    private fun designDdc(m: Measurement, options: TuneOptions): Pair<FloatArray, FloatArray>? {
        val strategy = SceneDspProfiles.forScene(options.effectiveScene).dynamicEq
        val peaks = peakCandidates(m, options).take(min(4, strategy.maxBands))
        if (peaks.isEmpty()) return null

        fun at(rate: Double): FloatArray = peaks.flatMap { p ->
            rbjPeak(p.f, p.q, p.gain, rate).toList()
        }.toFloatArray()

        return at(44100.0) to at(48000.0)
    }

    private fun rbjPeak(f: Double, q: Double, gainDb: Double, sampleRate: Double): FloatArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * c
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * c
        val a2 = 1.0 - alpha / a
        return floatArrayOf(
            (b0 / a0).toFloat(),
            (b1 / a0).toFloat(),
            (b2 / a0).toFloat(),
            (-a1 / a0).toFloat(),
            (-a2 / a0).toFloat(),
        )
    }

    private fun cascadeDb(coeffs: FloatArray, f: Double, sr: Double): Double {
        if (coeffs.isEmpty()) return 0.0
        val w = 2.0 * PI * f / sr
        val z1r = cos(w)
        val z1i = -sin(w)
        val z2r = cos(2.0 * w)
        val z2i = -sin(2.0 * w)
        var total = 0.0
        for (i in coeffs.indices step 5) {
            val b0 = coeffs[i].toDouble()
            val b1 = coeffs[i + 1].toDouble()
            val b2 = coeffs[i + 2].toDouble()
            val a1 = coeffs[i + 3].toDouble()
            val a2 = coeffs[i + 4].toDouble()
            val nr = b0 + b1 * z1r + b2 * z2r
            val ni = b1 * z1i + b2 * z2i
            val dr = 1.0 - a1 * z1r - a2 * z2r
            val di = -a1 * z1i - a2 * z2i
            total += 20.0 * log10(max(1e-12, hypot(nr, ni) / hypot(dr, di)))
        }
        return total
    }

    private fun channelPan(deltaDb: Double): Double {
        if (abs(deltaDb) < 0.35) return 0.0
        val attenuation = 10.0.pow(-abs(deltaDb).coerceAtMost(2.5) / 20.0)
        val amount = (1.0 - attenuation).coerceIn(0.0, 0.25)
        return if (deltaDb > 0) amount else -amount
    }

    private fun dynamicEqJson(
        enabled: Boolean,
        peaks: List<Peak>,
        strategy: DynamicEqStrategy,
    ): JSONObject {
        val p = if (peaks.isEmpty()) listOf(Peak(1000.0, -1.0, 1.4)) else peaks
        return obj(
            "enable", enabled,
            "bandCount", if (enabled) p.size else 1,
            "freqs", arr(p.map { it.f.toInt() }),
            "qs", arr(p.map { it.q }),
            "gains", arr(p.map { it.gain }),
            "thresholds", arr(p.map { strategy.thresholdDb }),
            "attacks", arr(p.map { strategy.attackMs }),
            "releases", arr(p.map { strategy.releaseMs }),
            "filterTypes", arr(p.map { 0 }),
        )
    }

    private fun fetJson(enabled: Boolean, strategy: LoudnessStrategy) = obj(
        "enable", enabled,
        "threshold", dbToRaw(strategy.fetThresholdDb),
        "ratio", strategy.fetRatio,
        "kneeAuto", true,
        "knee", 0.0,
        "kneeMulti", 0.0,
        "gainAuto", true,
        "gain", 0.0,
        "attackAuto", false,
        "attack", strategy.fetAttackSec,
        "maxAttack", strategy.fetAttackSec * 3.2,
        "releaseAuto", false,
        "release", strategy.fetReleaseSec,
        "maxRelease", strategy.fetReleaseSec * 1.8,
        "crest", 0.100,
        "adapt", 2.0,
        "noClip", true,
    )

    private fun multibandJson(enabled: Boolean, strategy: LoudnessStrategy) = obj(
        "enable", enabled,
        "bandEnables", arr(List(5) { true }),
        "crossovers", arr(strategy.mbcCrossovers),
        "thresholds", arr(strategy.mbcThresholdsDb.map(::dbToRaw)),
        "ratios", arr(strategy.mbcRatios),
        "gains", arr(List(5) { 0.0 }),
        "knees", arr(List(5) { 0.0 }),
        "kneeMultis", arr(List(5) { 0.0 }),
        "attacks", arr(strategy.mbcAttacksSec),
        "maxAttacks", arr(strategy.mbcAttacksSec.map { it * 5.0 }),
        "releases", arr(strategy.mbcReleasesSec),
        "maxReleases", arr(strategy.mbcReleasesSec.map { it * 1.8 }),
        "crests", arr(List(5) { 0.100 }),
        "adapts", arr(List(5) { 2.0 }),
        "kneeAutos", arr(List(5) { true }),
        "gainAutos", arr(List(5) { true }),
        "attackAutos", arr(List(5) { false }),
        "releaseAutos", arr(List(5) { false }),
        "noClips", arr(List(5) { true }),
    )

    private fun minimumPhaseFir(
        frequencies: DoubleArray,
        correctionDb: DoubleArray,
        sampleRate: Int,
        taps: Int,
    ): FloatArray {
        val n = Acoustics.nextPow2(max(8192, taps * 2))
        val logMag = DoubleArray(n)
        val imag = DoubleArray(n)
        for (k in 0..n / 2) {
            val f = k * sampleRate.toDouble() / n
            val db = when {
                f < frequencies.first() -> 0.0
                f > frequencies.last() -> 0.0
                else -> interpolate(frequencies, correctionDb, f)
            }.coerceIn(-4.0, 2.0)
            logMag[k] = db * ln(10.0) / 20.0
            if (k in 1 until n / 2) logMag[n - k] = logMag[k]
        }

        Acoustics.fft(logMag, imag, true)
        for (i in 1 until n / 2) {
            logMag[i] *= 2.0
            imag[i] *= 2.0
        }
        for (i in n / 2 + 1 until n) {
            logMag[i] = 0.0
            imag[i] = 0.0
        }
        Acoustics.fft(logMag, imag, false)

        val hr = DoubleArray(n)
        val hi = DoubleArray(n)
        for (i in 0 until n) {
            val e = exp(logMag[i])
            hr[i] = e * cos(imag[i])
            hi[i] = e * sin(imag[i])
        }
        Acoustics.fft(hr, hi, true)

        val count = min(taps, n)
        val out = FloatArray(count)
        var peak = 0.0
        for (i in 0 until count) {
            val tailStart = (count * 0.80).toInt()
            val fade = if (i < tailStart) 1.0 else {
                val x = (i - tailStart).toDouble() / max(1, count - tailStart - 1)
                0.5 + 0.5 * cos(PI * x)
            }
            val v = hr[i] * fade
            peak = max(peak, abs(v))
            out[i] = v.toFloat()
        }
        if (peak > 0.95) {
            val s = 0.95 / peak
            for (i in out.indices) out[i] = (out[i] * s).toFloat()
        }
        return out
    }

    private fun interpolate(x: DoubleArray, y: DoubleArray, q: Double): Double {
        if (q <= x.first()) return y.first()
        if (q >= x.last()) return y.last()
        val lq = ln(q)
        for (i in 0 until x.size - 1) {
            if (q in x[i]..x[i + 1]) {
                val t = (lq - ln(x[i])) / (ln(x[i + 1]) - ln(x[i]))
                return y[i] * (1.0 - t) + y[i + 1] * t
            }
        }
        return 0.0
    }

    private fun DoubleArray.smooth3(): DoubleArray {
        if (size < 3) return copyOf()
        val out = DoubleArray(size)
        out[0] = 0.75 * this[0] + 0.25 * this[1]
        for (i in 1 until lastIndex) out[i] = 0.25 * this[i - 1] + 0.5 * this[i] + 0.25 * this[i + 1]
        out[lastIndex] = 0.25 * this[lastIndex - 1] + 0.75 * this[lastIndex]
        return out
    }

    private fun dbToRaw(db: Double) = db * ln(10.0) / 20.0

    private fun obj(vararg kv: Any?): JSONObject = JSONObject().apply {
        var i = 0
        while (i < kv.size) {
            put(kv[i].toString(), kv[i + 1])
            i += 2
        }
    }

    private fun arr(values: DoubleArray) = JSONArray().apply { values.forEach { put(it) } }
    private fun arr(values: List<*>) = JSONArray().apply { values.forEach { put(it) } }
}
