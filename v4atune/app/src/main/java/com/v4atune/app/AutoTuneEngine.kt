package com.v4atune.app

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class TuneResult(
    val before: TuneMetrics,
    val after: TuneMetrics,
    val plan: Plan,
    val driverBefore: DriverStatus,
    val driverAfter: DriverStatus,
    val outputDir: File,
    val persistenceOk: Boolean,
    val distortionBefore: List<DistortionProbe>,
    val distortionAfter: List<DistortionProbe>,
    val safetyAdjusted: Boolean,
)

class AutoTuneEngine(private val context: Context) {
    suspend fun run(
        options: TuneOptions,
        progress: suspend (String, Float) -> Unit,
    ): TuneResult = withContext(Dispatchers.Default) {
        progress("检查 Root 与 ViPER AIDL", 0.02f)
        check(RootShell.available()) { "需要 Root 权限" }
        check(ViperControl.available()) { "viper.control 不可用" }

        val driverBefore = ViperControl.status() ?: error("无法读取 ViPER 驱动状态")
        check(driverBefore.sampleRate > 0) { "ViPER 驱动采样率无效" }

        val audio = context.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val testVolume = maxOf(1, (maxVolume * 0.35f).roundToInt())
        val stressVolume = maxOf(testVolume, (maxVolume * 0.55f).roundToInt())

        val out = File(context.getExternalFilesDir(null), "runs/" + System.currentTimeMillis())
        out.mkdirs()

        val persistence = ViperPersistence(context)
        var rawBypassActive = false
        var staged = false

        try {
            progress("RAW 旁路 · 释放 ViPER AudioEffect", 0.06f)
            persistence.beginRawBypass()
            rawBypassActive = true

            progress("原始链路测量 · 不经过 ViPER", 0.08f)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, testVolume, 0)
            delay(250)

            val rawFramesBefore = ViperControl.status()?.processedFrames

            progress("第一次快速全频测量", 0.14f)
            val calibrator = SweepCalibrator(context)
            val first = calibrator.measure(options.mode, false) {
                progress(it, 0.14f)
            }
            val beforeMetrics = TuningPlanner.metrics(first, options)

            progress("建立中高音量失真基线", 0.43f)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, stressVolume, 0)
            delay(150)
            val baselineStress = calibrator.distortionStress(first.sampleRate)

            val rawFramesAfter = ViperControl.status()?.processedFrames
            if (rawFramesBefore != null && rawFramesAfter != null) {
                check(rawFramesAfter == rawFramesBefore) {
                    "RAW 旁路失败：基线测量期间 ViPER processedFrames 仍在增加；停止校准，避免用被 ViPER 污染的频响反推 ViPER。"
                }
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, testVolume, 0)
            delay(150)

            progress("计算 31 段 IIR / FIR / 动态参数", 0.48f)
            var plan = TuningPlanner.build(first, options)

            progress("写入第一轮 DSP · 保留原有路由模式", 0.56f)
            persistence.sync(plan)
            staged = true
            rawBypassActive = false
            delay(500)
            applyPlan(plan)
            delay(250)

            val framesBeforeVerify = ViperControl.status()?.processedFrames ?: 0L

            progress("第二轮短扫频验证 · 按现有 ViPER 路由", 0.70f)
            val verify = calibrator.measure(options.mode, true) {
                progress(it, 0.70f)
            }

            val framesAfterVerify = ViperControl.status()?.processedFrames ?: framesBeforeVerify
            check(framesAfterVerify > framesBeforeVerify) {
                "验证音频没有经过 ViPER。可能是主开关关闭、V4ATune 在排除列表，或当前 Per-App 路由未捕获该会话；" +
                    "V4ATune 不会为此强制开启 Global Mode。"
            }

            progress("根据残差精修", 0.84f)
            plan = TuningPlanner.refine(plan, verify, options)
            persistence.stage(plan)
            delay(350)
            applyPlan(plan)
            delay(200)

            progress("破音保护 · THD / 峰值压力测试", 0.88f)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, stressVolume, 0)
            delay(150)
            var postStress = calibrator.distortionStress(first.sampleRate)
            var safePlan = TuningPlanner.applyDistortionSafety(
                plan = plan,
                baseline = baselineStress,
                after = postStress,
                options = options,
            )
            var safetyAdjusted = safePlan.profile.toString() != plan.profile.toString() ||
                !safePlan.eqLevels.contentEquals(plan.eqLevels)

            if (safetyAdjusted) {
                plan = safePlan
                persistence.stage(plan)
                delay(300)
                applyPlan(plan)
                delay(200)
                postStress = calibrator.distortionStress(first.sampleRate)

                // A second bounded pass handles cases where acoustic speaker breakup
                // remains after the first digital headroom rollback.
                safePlan = TuningPlanner.applyDistortionSafety(
                    plan = plan,
                    baseline = baselineStress,
                    after = postStress,
                    options = options,
                )
                if (safePlan.profile.toString() != plan.profile.toString() ||
                    !safePlan.eqLevels.contentEquals(plan.eqLevels)
                ) {
                    plan = safePlan
                    persistence.stage(plan)
                    delay(300)
                    applyPlan(plan)
                    delay(200)
                    postStress = calibrator.distortionStress(first.sampleRate)
                }
            }

            audio.setStreamVolume(AudioManager.STREAM_MUSIC, testVolume, 0)
            delay(150)

            var driverAfter = ViperControl.status() ?: error("调整后无法读取驱动状态")
            check(driverAfter.processedFrames >= driverBefore.processedFrames) {
                "ViPER processedFrames 未前进"
            }

            val finalVerify = if (options.mode == TestMode.Deep) {
                progress("深度模式最终复核", 0.90f)
                calibrator.measure(TestMode.Quick, true) { progress(it, 0.90f) }
            } else {
                verify
            }
            val afterMetrics = TuningPlanner.metrics(finalVerify, options)

            progress("保存配置与测试报告", 0.96f)
            saveArtifacts(
                out,
                plan,
                beforeMetrics,
                afterMetrics,
                driverBefore,
                driverAfter,
                baselineStress,
                postStress,
                safetyAdjusted,
            )
            val persistenceOk = try {
                persistence.stage(plan)
                delay(250)
                applyPlan(plan)
                driverAfter = ViperControl.status() ?: driverAfter
                true
            } catch (_: Throwable) {
                false
            }

            progress("完成", 1.0f)
            TuneResult(
                before = beforeMetrics,
                after = afterMetrics,
                plan = plan,
                driverBefore = driverBefore,
                driverAfter = driverAfter,
                outputDir = out,
                persistenceOk = persistenceOk,
                distortionBefore = baselineStress,
                distortionAfter = postStress,
                safetyAdjusted = safetyAdjusted,
            )
        } catch (t: Throwable) {
            if (staged) {
                runCatching { persistence.restoreLast() }
            } else if (rawBypassActive) {
                runCatching { persistence.resumeManager() }
            }
            throw t
        } finally {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) }
        }
    }

    private fun applyPlan(plan: Plan) {
        ViperControl.apply(plan.commands)
        if (plan.ddc44 != null && plan.ddc48 != null) {
            ViperControl.streamDdc(plan.ddc44, plan.ddc48)
        }
        plan.kernel?.let { kernel ->
            ViperControl.streamKernel(
                samples = kernel,
                channels = 1,
                kernelId = "V4ATune_Speaker.wav".hashCode(),
            )
        }
    }

    private fun saveArtifacts(
        dir: File,
        plan: Plan,
        before: TuneMetrics,
        after: TuneMetrics,
        driverBefore: DriverStatus,
        driverAfter: DriverStatus,
        distortionBefore: List<DistortionProbe>,
        distortionAfter: List<DistortionProbe>,
        safetyAdjusted: Boolean,
    ) {
        File(dir, "profile.json").writeText(plan.profile.toString(2))
        File(dir, "report.json").writeText(
            JSONObject().apply {
                put("beforeRmsDb", before.responseRmsDb)
                put("afterRmsDb", after.responseRmsDb)
                put("beforeMaxDeviationDb", before.maxDeviationDb)
                put("afterMaxDeviationDb", after.maxDeviationDb)
                put("lowDeficitDb", before.lowDeficitDb)
                put("highDeficitDb", before.highDeficitDb)
                put("medianThd", before.medianThd)
                put("channelDeltaDb", before.channelDeltaDb)
                put("compressionDb", before.compressionDb)
                put("sampleRate", driverAfter.sampleRate)
                put("driverVersion", driverAfter.versionName)
                put("processedFramesBefore", driverBefore.processedFrames)
                put("processedFramesAfter", driverAfter.processedFrames)
                put("distortionSafetyAdjusted", safetyAdjusted)
                put(
                    "distortionBefore",
                    org.json.JSONArray().apply {
                        distortionBefore.forEach { p ->
                            put(JSONObject().apply {
                                put("frequency", p.frequency)
                                put("thd", p.thd)
                                put("peak", p.peak)
                            })
                        }
                    },
                )
                put(
                    "distortionAfter",
                    org.json.JSONArray().apply {
                        distortionAfter.forEach { p ->
                            put(JSONObject().apply {
                                put("frequency", p.frequency)
                                put("thd", p.thd)
                                put("peak", p.peak)
                            })
                        }
                    },
                )
            }.toString(2),
        )
        plan.kernel?.let { WavFiles.writeMonoFloat(File(dir, "V4ATune_Speaker.wav"), it, driverAfter.sampleRate) }
        if (plan.ddc44 != null && plan.ddc48 != null) {
            VdcFiles.write(File(dir, "V4ATune_Speaker.vdc"), plan.ddc44, plan.ddc48)
        }
    }
}

object WavFiles {
    fun writeMonoFloat(file: File, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 4
        file.outputStream().buffered().use { out ->
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun le16(v: Int) {
                out.write(v and 0xff)
                out.write((v ushr 8) and 0xff)
            }
            fun le32(v: Int) {
                out.write(v and 0xff)
                out.write((v ushr 8) and 0xff)
                out.write((v ushr 16) and 0xff)
                out.write((v ushr 24) and 0xff)
            }
            ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
            ascii("fmt "); le32(16); le16(3); le16(1)
            le32(sampleRate); le32(sampleRate * 4); le16(4); le16(32)
            ascii("data"); le32(dataSize)
            val bytes = java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            samples.forEach {
                bytes.clear()
                bytes.putFloat(it)
                out.write(bytes.array())
            }
        }
    }
}

object VdcFiles {
    fun write(file: File, coeffs44: FloatArray, coeffs48: FloatArray) {
        fun line(name: String, values: FloatArray): String =
            name + values.joinToString(",") { java.lang.String.format(java.util.Locale.US, "%.9g", it) }
        file.writeText(
            line("SR_44100:", coeffs44) + "\n" +
                line("SR_48000:", coeffs48) + "\n",
        )
    }
}
