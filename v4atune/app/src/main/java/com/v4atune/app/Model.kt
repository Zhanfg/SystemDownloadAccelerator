package com.v4atune.app

enum class Target(val label: String) {
    Reference("参考 / 高保真"),
    Balanced("均衡悦耳"),
    Vocal("人声清晰"),
    Bass("低频冲击"),
    Spatial("空间感"),
    Loudness("高响度"),
}

enum class Policy(val label: String) {
    Auto("自动"),
    On("开启并优化"),
    Off("关闭"),
}

enum class TestMode(val label: String, val sweepSeconds: Double, val verifySeconds: Double, val repeat: Int) {
    Quick("快速 · ~8秒", 2.8, 2.2, 1),
    Standard("标准 · ~14秒", 3.8, 2.8, 1),
    Deep("深度 · ~25秒", 4.8, 3.8, 2),
}

enum class Component(val label: String) {
    PlaybackGain("回放增益控制"),
    Lufs("响度均衡 / LUFS"),
    FetCompressor("FET 压缩器"),
    MultibandCompressor("多段压缩器"),
    Ddc("ViPER DDC"),
    Spectrum("频谱扩展"),
    Equalizer("IIR 均衡器 · 10/15/25/31"),
    DynamicEq("动态均衡器 · 最多10段"),
    Convolver("卷积器 / FIR"),
    FieldSurround("场环绕"),
    DiffSurround("差分环绕"),
    StereoImager("立体声成像"),
    HeadphoneSurround("耳机环绕"),
    Reverb("混响"),
    DynamicSystem("动态系统"),
    PsychoBass("心理声学低频"),
    Bass("ViPER 低音"),
    BassMono("单声道低音"),
    Clarity("ViPER 清晰度"),
    Cure("Cure / 交叉馈送"),
    Tube("电子管模拟"),
    AnalogX("AnalogX"),
    SpeakerCorrection("扬声器优化"),
}

data class TuneOptions(
    val target: Target = Target.Reference,
    val mode: TestMode = TestMode.Standard,
    val eqBands: Int = 31,
    val firTaps: Int = 4096,
    val policies: Map<Component, Policy> = Component.entries.associateWith { Policy.Auto },
)

data class DriverStatus(
    val enabled: Boolean,
    val sampleRate: Int,
    val processedFrames: Long,
    val kernelId: Int,
    val versionCode: Int,
    val versionName: String,
    val arch: String,
)

data class ResponsePoint(
    val frequency: Double,
    val levelDb: Double,
    val snrDb: Double,
)

data class DistortionProbe(
    val frequency: Double,
    val thd: Double,
    val peak: Double,
)

data class Measurement(
    val sampleRate: Int,
    val dense: List<ResponsePoint>,
    val eq31: List<ResponsePoint>,
    val distortion: List<DistortionProbe>,
    val left1kDb: Double,
    val right1kDb: Double,
    val compressionDb: Double,
    val noiseDb: Double,
    val clipped: Boolean,
)

data class TuneMetrics(
    val responseRmsDb: Double,
    val maxDeviationDb: Double,
    val lowDeficitDb: Double,
    val highDeficitDb: Double,
    val medianThd: Double,
    val channelDeltaDb: Double,
    val compressionDb: Double,
)

data class Plan(
    val profile: org.json.JSONObject,
    val eqFrequencies: DoubleArray,
    val eqLevels: DoubleArray,
    val firDb: DoubleArray,
    val firFrequencies: DoubleArray,
    val decisions: Map<Component, Pair<Boolean, String>>,
    val ddc44: FloatArray? = null,
    val ddc48: FloatArray? = null,
)
