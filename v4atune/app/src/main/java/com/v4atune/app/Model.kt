package com.v4atune.app

enum class Target(val label: String) {
    Reference("参考 / 高保真"),
    Balanced("均衡悦耳"),
    Vocal("人声清晰"),
    Bass("低频冲击"),
    Spatial("空间感"),
    Loudness("高响度"),
}

enum class Scene(val label: String, val subtitle: String) {
    Reference("参考", "低染色 · 平滑频响 · 保留动态"),
    Music("音乐", "均衡耐听 · 低频体感 · 声像稳定"),
    Movie("电影", "对白清晰 · 宽声场 · 低频氛围"),
    Game("游戏", "低延迟 · 定位清晰 · 瞬态优先"),
    Voice("人声", "对白 / 播客 · 中频可懂度优先"),
    Outdoor("户外", "抗环境噪声 · 高响度 · 动态受控"),
    Night("夜间", "低音量细节 · 克制低频 · 轻压缩"),
    Custom("自定义", "手动覆盖场景默认策略"),
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
    val scene: Scene = Scene.Reference,
    val baseScene: Scene = Scene.Reference,
    val target: Target = Target.Reference,
    val mode: TestMode = TestMode.Standard,
    val eqBands: Int = 31,
    val firTaps: Int = 4096,
    val policies: Map<Component, Policy> = Component.entries.associateWith { Policy.Auto },
)

object ScenePresets {
    fun apply(scene: Scene, current: TuneOptions = TuneOptions()): TuneOptions {
        if (scene == Scene.Custom) return current.copy(scene = Scene.Custom)

        val p = Component.entries.associateWith { Policy.Auto }.toMutableMap()

        fun off(vararg components: Component) = components.forEach { p[it] = Policy.Off }
        fun on(vararg components: Component) = components.forEach { p[it] = Policy.On }

        return when (scene) {
            Scene.Reference -> {
                off(
                    Component.PlaybackGain,
                    Component.Lufs,
                    Component.FetCompressor,
                    Component.MultibandCompressor,
                    Component.FieldSurround,
                    Component.DiffSurround,
                    Component.HeadphoneSurround,
                    Component.Reverb,
                    Component.DynamicSystem,
                    Component.Cure,
                    Component.Tube,
                    Component.AnalogX,
                )
                current.copy(
                    scene = scene,
                    baseScene = scene,
                    target = Target.Reference,
                    mode = TestMode.Standard,
                    eqBands = 31,
                    firTaps = 4096,
                    policies = p,
                )
            }

            Scene.Music -> {
                off(
                    Component.PlaybackGain,
                    Component.Lufs,
                    Component.FetCompressor,
                    Component.MultibandCompressor,
                    Component.DiffSurround,
                    Component.HeadphoneSurround,
                    Component.Reverb,
                    Component.DynamicSystem,
                    Component.Cure,
                    Component.Tube,
                    Component.AnalogX,
                )
                current.copy(
                    scene = scene,
                    baseScene = scene,
                    target = Target.Balanced,
                    mode = TestMode.Standard,
                    eqBands = 31,
                    firTaps = 4096,
                    policies = p,
                )
            }

            Scene.Movie -> {
                on(Component.FieldSurround, Component.StereoImager)
                off(
                    Component.PlaybackGain,
                    Component.FetCompressor,
                    Component.HeadphoneSurround,
                    Component.Cure,
                    Component.Tube,
                    Component.AnalogX,
                )
                current.copy(
                    scene = scene,
                    baseScene = scene,
                    target = Target.Spatial,
                    mode = TestMode.Standard,
                    eqBands = 31,
                    firTaps = 4096,
                    policies = p,
                )
            }

            Scene.Game -> {
                on(Component.StereoImager, Component.Clarity)
                off(
                    Component.PlaybackGain,
                    Component.Lufs,
                    Component.FetCompressor,
                    Component.MultibandCompressor,
                    Component.Convolver,
                    Component.DiffSurround,
                    Component.HeadphoneSurround,
                    Component.Reverb,
                    Component.DynamicSystem,
                    Component.Cure,
                    Component.Tube,
                    Component.AnalogX,
                )
                current.copy(
                    scene = scene,
                    baseScene = scene,
                    target = Target.Spatial,
                    mode = TestMode.Quick,
                    eqBands = 31,
                    firTaps = 1024,
                    policies = p,
                )
            }

            Scene.Voice -> {
                on(Component.Clarity)
                off(
                    Component.PlaybackGain,
                    Component.Lufs,
                    Component.FetCompressor,
                    Component.MultibandCompressor,
                    Component.FieldSurround,
                    Component.DiffSurround,
                    Component.StereoImager,
                    Component.HeadphoneSurround,
                    Component.Reverb,
                    Component.DynamicSystem,
                    Component.PsychoBass,
                    Component.Bass,
                    Component.BassMono,
                    Component.Cure,
                    Component.Tube,
                    Component.AnalogX,
                )
                current.copy(
                    scene = scene,
                    baseScene = scene,
                    target = Target.Vocal,
                    mode = TestMode.Quick,
                    eqBands = 31,
                    firTaps = 2048,
                    policies = p,
                )
            }

            Scene.Outdoor -> {
                on(
                    Component.PlaybackGain,
                    Component.Lufs,
                    Component.MultibandCompressor,
                    Component.PsychoBass,
                    Component.Clarity,
                )
                off(
                    Component.FetCompressor,
                    Component.FieldSurround,
                    Component.DiffSurround,
                    Component.HeadphoneSurround,
                    Component.Reverb,
                    Component.DynamicSystem,
                    Component.Cure,
                    Component.Tube,
                    Component.AnalogX,
                )
                current.copy(
                    scene = scene,
                    baseScene = scene,
                    target = Target.Loudness,
                    mode = TestMode.Quick,
                    eqBands = 31,
                    firTaps = 2048,
                    policies = p,
                )
            }

            Scene.Night -> {
                on(Component.Lufs, Component.FetCompressor, Component.Clarity)
                off(
                    Component.PlaybackGain,
                    Component.MultibandCompressor,
                    Component.FieldSurround,
                    Component.DiffSurround,
                    Component.StereoImager,
                    Component.HeadphoneSurround,
                    Component.Reverb,
                    Component.DynamicSystem,
                    Component.PsychoBass,
                    Component.Bass,
                    Component.BassMono,
                    Component.Cure,
                    Component.Tube,
                    Component.AnalogX,
                )
                current.copy(
                    scene = scene,
                    baseScene = scene,
                    target = Target.Vocal,
                    mode = TestMode.Quick,
                    eqBands = 31,
                    firTaps = 2048,
                    policies = p,
                )
            }

            Scene.Custom -> current.copy(scene = Scene.Custom)
        }
    }
}

val TuneOptions.effectiveScene: Scene
    get() = if (scene == Scene.Custom) baseScene else scene

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
    val kernel: FloatArray?,
    val commands: List<ViperCommand>,
    val decisions: Map<Component, Pair<Boolean, String>>,
    val ddc44: FloatArray? = null,
    val ddc48: FloatArray? = null,
)
