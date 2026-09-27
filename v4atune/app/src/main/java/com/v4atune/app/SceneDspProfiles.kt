package com.v4atune.app

data class FirStrategy(
    val defaultTaps: Int,
    val autoEnable: Boolean,
    val minResponseRmsDb: Double,
    val eqShare: Double,
    val maxCutDb: Double,
    val maxBoostDb: Double,
    val refineEqShare: Double,
    val refineFirShare: Double,
)

data class DynamicEqStrategy(
    val autoEnable: Boolean,
    val maxBands: Int,
    val minExcessDb: Double,
    val minProminenceDb: Double,
    val qMin: Double,
    val qMax: Double,
    val maxCutDb: Double,
    val thresholdDb: Double,
    val attackMs: Double,
    val releaseMs: Double,
)

data class SpatialStrategy(
    val fieldWidening: Double,
    val fieldMidImage: Double,
    val fieldDepth: Int,
    val diffDelayMs: Double,
    val diffMix: Double,
    val diffLowPassHz: Int,
    val imagerLowWidth: Double,
    val imagerMidWidth: Double,
    val imagerHighWidth: Double,
    val imagerLowCrossoverHz: Int,
    val imagerHighCrossoverHz: Int,
    val reverbRoomSize: Double,
    val reverbWidth: Double,
    val reverbDamp: Double,
    val reverbWet: Double,
)

data class LoudnessStrategy(
    val baseHeadroomDb: Double,
    val playbackStrength: Double,
    val playbackMaxGain: Double,
    val playbackOutputThreshold: Double,
    val lufsTarget: Double,
    val lufsMaxGain: Double,
    val lufsSpeed: Int,
    val fetThresholdDb: Double,
    val fetRatio: Double,
    val fetAttackSec: Double,
    val fetReleaseSec: Double,
    val mbcCrossovers: List<Int>,
    val mbcThresholdsDb: List<Double>,
    val mbcRatios: List<Double>,
    val mbcAttacksSec: List<Double>,
    val mbcReleasesSec: List<Double>,
)

data class SafetyStrategy(
    val preHeadroomDb: Double,
    val limiterCeilingDb: Double,
    val maxSpectrumExciter: Double,
    val maxPsychoBassIntensity: Double,
    val maxBassGain: Double,
    val maxClarityGain: Double,
)

data class SceneDspSpec(
    val scene: Scene,
    val target: Target,
    val testMode: TestMode,
    val eqTarget31Db: DoubleArray,
    val fir: FirStrategy,
    val dynamicEq: DynamicEqStrategy,
    val spatial: SpatialStrategy,
    val loudness: LoudnessStrategy,
    val safety: SafetyStrategy,
    val policies: Map<Component, Policy>,
)

object SceneDspProfiles {
    private fun policies(
        on: Set<Component> = emptySet(),
        auto: Set<Component> = emptySet(),
    ): Map<Component, Policy> = Component.entries.associateWith { component ->
        when (component) {
            in on -> Policy.On
            in auto -> Policy.Auto
            else -> Policy.Off
        }
    }

    private fun curve(vararg values: Double): DoubleArray {
        require(values.size == 31) { "Scene EQ target must contain exactly 31 bands" }
        return values
    }

    private val neutralSpatial = SpatialStrategy(
        fieldWidening = 0.0,
        fieldMidImage = 1.0,
        fieldDepth = 0,
        diffDelayMs = 0.0,
        diffMix = 0.0,
        diffLowPassHz = 7000,
        imagerLowWidth = 1.0,
        imagerMidWidth = 1.0,
        imagerHighWidth = 1.0,
        imagerLowCrossoverHz = 220,
        imagerHighCrossoverHz = 4500,
        reverbRoomSize = 0.0,
        reverbWidth = 0.0,
        reverbDamp = 0.6,
        reverbWet = 0.0,
    )

    private val neutralLoudness = LoudnessStrategy(
        baseHeadroomDb = 1.0,
        playbackStrength = 0.85,
        playbackMaxGain = 1.35,
        playbackOutputThreshold = 0.82,
        lufsTarget = -18.0,
        lufsMaxGain = 2.0,
        lufsSpeed = 0,
        fetThresholdDb = -14.0,
        fetRatio = -1.30,
        fetAttackSec = 0.018,
        fetReleaseSec = 0.120,
        mbcCrossovers = listOf(160, 630, 2500, 8000),
        mbcThresholdsDb = listOf(-18.0, -16.0, -15.0, -15.0, -16.0),
        mbcRatios = listOf(-1.25, -1.25, -1.22, -1.20, -1.18),
        mbcAttacksSec = listOf(0.012, 0.010, 0.008, 0.006, 0.006),
        mbcReleasesSec = listOf(0.160, 0.140, 0.120, 0.100, 0.090),
    )

    val reference = SceneDspSpec(
        scene = Scene.Reference,
        target = Target.Reference,
        testMode = TestMode.Standard,
        eqTarget31Db = curve(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        ),
        fir = FirStrategy(4096, true, 1.10, 0.74, -3.5, 1.5, 0.60, 0.40),
        dynamicEq = DynamicEqStrategy(true, 6, 2.4, 0.9, 0.8, 3.2, 3.5, -24.0, 12.0, 160.0),
        spatial = neutralSpatial,
        loudness = neutralLoudness,
        safety = SafetyStrategy(1.5, -1.5, 0.18, 0.22, 0.25, 0.35),
        policies = policies(
            on = setOf(Component.Equalizer),
            auto = setOf(Component.Convolver, Component.DynamicEq),
        ),
    )

    val music = SceneDspSpec(
        scene = Scene.Music,
        target = Target.Balanced,
        testMode = TestMode.Standard,
        eqTarget31Db = curve(
            -1.5, -1.2, -0.8, -0.3, 0.2, 0.6, 0.9, 1.1,
            1.1, 0.9, 0.6, 0.3, 0.0, -0.1, -0.1, 0.0,
            0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.5, 0.4,
            0.3, 0.3, 0.4, 0.4, 0.3, 0.1, -0.3,
        ),
        fir = FirStrategy(4096, true, 1.20, 0.70, -3.0, 1.3, 0.58, 0.42),
        dynamicEq = DynamicEqStrategy(true, 6, 2.3, 0.8, 0.8, 3.5, 3.5, -22.0, 10.0, 140.0),
        spatial = neutralSpatial.copy(
            imagerLowWidth = 0.96,
            imagerMidWidth = 1.03,
            imagerHighWidth = 1.06,
        ),
        loudness = neutralLoudness.copy(
            baseHeadroomDb = 1.2,
            lufsTarget = -17.0,
        ),
        safety = SafetyStrategy(1.8, -1.5, 0.20, 0.26, 0.30, 0.40),
        policies = policies(
            on = setOf(Component.Equalizer),
            auto = setOf(
                Component.Convolver,
                Component.DynamicEq,
                Component.StereoImager,
            ),
        ),
    )

    val movie = SceneDspSpec(
        scene = Scene.Movie,
        target = Target.Spatial,
        testMode = TestMode.Standard,
        eqTarget31Db = curve(
            -2.0, -1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.4,
            1.4, 1.2, 0.8, 0.3, -0.2, -0.4, -0.5, -0.4,
            -0.2, 0.2, 0.5, 0.8, 1.0, 1.2, 1.2, 1.0,
            0.8, 0.6, 0.6, 0.5, 0.3, 0.0, -0.5,
        ),
        fir = FirStrategy(4096, true, 1.30, 0.68, -3.0, 1.3, 0.56, 0.44),
        dynamicEq = DynamicEqStrategy(true, 8, 2.0, 0.7, 0.8, 4.0, 4.0, -24.0, 8.0, 160.0),
        spatial = SpatialStrategy(
            fieldWidening = 1.22,
            fieldMidImage = 1.28,
            fieldDepth = 380,
            diffDelayMs = 2.4,
            diffMix = 0.10,
            diffLowPassHz = 7200,
            imagerLowWidth = 0.92,
            imagerMidWidth = 1.13,
            imagerHighWidth = 1.20,
            imagerLowCrossoverHz = 220,
            imagerHighCrossoverHz = 4500,
            reverbRoomSize = 0.10,
            reverbWidth = 0.70,
            reverbDamp = 0.64,
            reverbWet = 0.035,
        ),
        loudness = neutralLoudness.copy(
            baseHeadroomDb = 1.5,
            lufsTarget = -16.0,
            lufsMaxGain = 2.5,
        ),
        safety = SafetyStrategy(2.2, -1.8, 0.18, 0.28, 0.30, 0.45),
        policies = policies(
            on = setOf(
                Component.Equalizer,
                Component.FieldSurround,
                Component.StereoImager,
            ),
            auto = setOf(
                Component.Convolver,
                Component.DynamicEq,
            ),
        ),
    )

    val game = SceneDspSpec(
        scene = Scene.Game,
        target = Target.Spatial,
        testMode = TestMode.Quick,
        eqTarget31Db = curve(
            -3.0, -3.0, -2.5, -2.0, -1.5, -1.0, -0.6, -0.3,
            0.0, -0.1, -0.2, -0.3, -0.4, -0.4, -0.3, -0.1,
            0.2, 0.5, 0.8, 1.1, 1.3, 1.4, 1.3, 1.1,
            0.9, 0.8, 0.7, 0.5, 0.2, -0.2, -0.8,
        ),
        fir = FirStrategy(1024, false, 99.0, 1.0, 0.0, 0.0, 0.70, 0.30),
        dynamicEq = DynamicEqStrategy(true, 6, 1.8, 0.6, 1.0, 4.5, 3.0, -18.0, 4.0, 70.0),
        spatial = neutralSpatial.copy(
            imagerLowWidth = 0.88,
            imagerMidWidth = 1.08,
            imagerHighWidth = 1.12,
            imagerLowCrossoverHz = 260,
            imagerHighCrossoverHz = 3800,
        ),
        loudness = neutralLoudness.copy(
            baseHeadroomDb = 1.0,
            lufsTarget = -18.0,
        ),
        safety = SafetyStrategy(2.0, -1.5, 0.16, 0.18, 0.20, 0.38),
        policies = policies(
            on = setOf(
                Component.Equalizer,
                Component.DynamicEq,
                Component.StereoImager,
            ),
        ),
    )

    val voice = SceneDspSpec(
        scene = Scene.Voice,
        target = Target.Vocal,
        testMode = TestMode.Quick,
        eqTarget31Db = curve(
            -4.0, -4.0, -3.5, -3.0, -2.5, -2.0, -1.5, -1.2,
            -1.0, -0.8, -0.6, -0.4, -0.2, 0.0, 0.2, 0.4,
            0.6, 0.8, 1.0, 1.2, 1.3, 1.3, 1.2, 1.0,
            0.8, 0.6, 0.3, 0.0, -0.3, -0.7, -1.2,
        ),
        fir = FirStrategy(2048, true, 1.40, 0.80, -2.8, 1.0, 0.65, 0.35),
        dynamicEq = DynamicEqStrategy(true, 8, 1.7, 0.6, 0.9, 4.0, 3.5, -26.0, 6.0, 110.0),
        spatial = neutralSpatial.copy(
            imagerLowWidth = 0.98,
            imagerMidWidth = 1.0,
            imagerHighWidth = 1.0,
        ),
        loudness = neutralLoudness.copy(
            baseHeadroomDb = 1.2,
            lufsTarget = -18.0,
            lufsMaxGain = 1.5,
        ),
        safety = SafetyStrategy(2.0, -1.8, 0.14, 0.12, 0.15, 0.42),
        policies = policies(
            on = setOf(
                Component.Equalizer,
                Component.DynamicEq,
            ),
            auto = setOf(Component.Convolver, Component.Lufs),
        ),
    )

    val outdoor = SceneDspSpec(
        scene = Scene.Outdoor,
        target = Target.Loudness,
        testMode = TestMode.Quick,
        eqTarget31Db = curve(
            -3.0, -2.5, -2.0, -1.5, -1.0, -0.4, 0.2, 0.7,
            1.0, 1.0, 0.8, 0.5, 0.2, 0.0, 0.1, 0.3,
            0.6, 0.9, 1.1, 1.3, 1.4, 1.4, 1.3, 1.2,
            1.0, 0.8, 0.7, 0.5, 0.2, -0.2, -0.8,
        ),
        fir = FirStrategy(2048, true, 1.60, 0.85, -2.5, 0.8, 0.70, 0.30),
        dynamicEq = DynamicEqStrategy(true, 6, 1.8, 0.7, 0.8, 3.5, 3.0, -18.0, 5.0, 90.0),
        spatial = neutralSpatial,
        loudness = LoudnessStrategy(
            baseHeadroomDb = 1.7,
            playbackStrength = 0.95,
            playbackMaxGain = 1.35,
            playbackOutputThreshold = 0.78,
            lufsTarget = -15.0,
            lufsMaxGain = 1.8,
            lufsSpeed = 0,
            fetThresholdDb = -14.0,
            fetRatio = -1.30,
            fetAttackSec = 0.018,
            fetReleaseSec = 0.120,
            mbcCrossovers = listOf(160, 630, 2500, 8000),
            mbcThresholdsDb = listOf(-22.0, -20.0, -18.0, -18.0, -19.0),
            mbcRatios = listOf(-1.42, -1.38, -1.32, -1.28, -1.24),
            mbcAttacksSec = listOf(0.010, 0.008, 0.006, 0.005, 0.005),
            mbcReleasesSec = listOf(0.140, 0.120, 0.100, 0.085, 0.080),
        ),
        safety = SafetyStrategy(3.0, -2.2, 0.16, 0.28, 0.28, 0.42),
        policies = policies(
            on = setOf(
                Component.Equalizer,
                Component.DynamicEq,
                Component.PlaybackGain,
                Component.Lufs,
                Component.MultibandCompressor,
            ),
            auto = setOf(Component.Convolver),
        ),
    )

    val night = SceneDspSpec(
        scene = Scene.Night,
        target = Target.Vocal,
        testMode = TestMode.Quick,
        eqTarget31Db = curve(
            -4.0, -4.0, -3.5, -3.0, -2.5, -2.0, -1.6, -1.2,
            -0.9, -0.6, -0.4, -0.2, 0.0, 0.2, 0.4, 0.6,
            0.7, 0.8, 0.9, 1.0, 1.0, 0.9, 0.7, 0.5,
            0.2, 0.0, -0.3, -0.6, -0.9, -1.2, -1.5,
        ),
        fir = FirStrategy(2048, true, 1.40, 0.82, -2.5, 0.8, 0.68, 0.32),
        dynamicEq = DynamicEqStrategy(true, 8, 1.8, 0.7, 0.8, 3.5, 2.5, -28.0, 15.0, 220.0),
        spatial = neutralSpatial.copy(
            imagerLowWidth = 0.95,
        ),
        loudness = neutralLoudness.copy(
            baseHeadroomDb = 1.3,
            playbackStrength = 0.75,
            playbackMaxGain = 1.20,
            lufsTarget = -19.0,
            lufsMaxGain = 1.5,
            lufsSpeed = 1,
            fetThresholdDb = -22.0,
            fetRatio = -1.55,
            fetAttackSec = 0.025,
            fetReleaseSec = 0.180,
        ),
        safety = SafetyStrategy(2.2, -2.0, 0.10, 0.10, 0.12, 0.36),
        policies = policies(
            on = setOf(
                Component.Equalizer,
                Component.DynamicEq,
                Component.Lufs,
                Component.FetCompressor,
            ),
            auto = setOf(Component.Convolver),
        ),
    )

    fun forScene(scene: Scene): SceneDspSpec = when (scene) {
        Scene.Reference -> reference
        Scene.Music -> music
        Scene.Movie -> movie
        Scene.Game -> game
        Scene.Voice -> voice
        Scene.Outdoor -> outdoor
        Scene.Night -> night
        Scene.Custom -> reference
    }

    fun optionsFor(scene: Scene, current: TuneOptions): TuneOptions {
        if (scene == Scene.Custom) return current.copy(scene = Scene.Custom)
        val spec = forScene(scene)
        return current.copy(
            scene = scene,
            baseScene = scene,
            target = spec.target,
            mode = spec.testMode,
            eqBands = 31,
            firTaps = spec.fir.defaultTaps,
            policies = spec.policies,
        )
    }
}
