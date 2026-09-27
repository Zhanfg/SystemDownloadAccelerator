package com.v4atune.app

import android.media.audiofx.AudioEffect
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Transient ViPER effect bound to exactly one AudioTrack session.
 *
 * This is intentionally independent from ViPER4Android's Global/Per-App routing.
 * It exists only while V4ATune verifies a calibration, then is released.
 */
class ViperSessionEffect private constructor(
    private val effect: AudioEffect,
) : AutoCloseable {
    companion object {
        private val AIDL_TYPE_UUID = UUID.fromString("7261676f-6d75-7369-6364-28e2fd3ac39e")
        private val LEGACY_TYPE_UUID = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")
        private val EFFECT_UUID = UUID.fromString("90380da3-8536-4744-a6a3-5731970e640f")

        private val ctor: Constructor<AudioEffect> by lazy {
            AudioEffect::class.java.getConstructor(
                UUID::class.java,
                UUID::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }

        private val setParameter: Method by lazy {
            AudioEffect::class.java.getMethod(
                "setParameter",
                ByteArray::class.java,
                ByteArray::class.java,
            )
        }

        private val getParameter: Method by lazy {
            AudioEffect::class.java.getMethod(
                "getParameter",
                ByteArray::class.java,
                ByteArray::class.java,
            )
        }

        fun create(sessionId: Int): ViperSessionEffect {
            require(sessionId > 0) { "Invalid AudioTrack sessionId=$sessionId" }

            val errors = mutableListOf<Throwable>()
            for (type in listOf(AIDL_TYPE_UUID, LEGACY_TYPE_UUID)) {
                try {
                    val fx = ctor.newInstance(type, EFFECT_UUID, 0, sessionId)
                    return ViperSessionEffect(fx)
                } catch (t: Throwable) {
                    errors += (t.cause ?: t)
                }
            }

            val detail = errors.joinToString(" | ") {
                it.javaClass.simpleName + ": " + (it.message ?: "")
            }
            error("无法为检测 AudioTrack 创建 ViPER session effect: $detail")
        }
    }

    fun apply(plan: Plan) {
        // Bulk payloads belong to the exact transient effect as well.
        plan.ddc44?.let { a ->
            val b = plan.ddc48 ?: error("DDC 48 kHz coefficients missing")
            set(ViperParams.DDC_COEFFS, WireValue.Floats(a + b))
        }

        plan.kernel?.let { streamKernel(it, 1, it.contentHashCode()) }

        plan.commands.forEach { command ->
            set(command.param, command.value)
        }

        effect.enabled = true
        check(effect.enabled) { "ViPER session effect could not be enabled" }
    }

    fun processedFrames(): Long = getLong(3)

    fun sampleRate(): Int = getInt(4)

    private fun set(param: Int, value: WireValue) {
        val status = setParameter.invoke(
            effect,
            intBytes(param),
            encode(value),
        ) as Int
        check(status == AudioEffect.SUCCESS) {
            "ViPER session setParameter failed: param=0x" +
                param.toString(16) + " status=" + status
        }
    }

    private fun streamKernel(samples: FloatArray, channels: Int, kernelId: Int) {
        require(samples.size >= 16)
        val raw = ByteBuffer.allocate(samples.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach(::putFloat) }
            .array()
        val crc = CRC32().apply { update(raw) }.value.toInt()

        set(
            ViperParams.CONVOLVER_PREPARE,
            WireValue.Ints(intArrayOf(samples.size, channels, 0)),
        )

        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 2046, samples.size)
            set(
                ViperParams.CONVOLVER_BUFFER,
                WireValue.Floats(samples.copyOfRange(offset, end)),
            )
            offset = end
        }

        set(
            ViperParams.CONVOLVER_COMMIT,
            WireValue.Ints(intArrayOf(samples.size, crc, kernelId)),
        )
    }

    private fun getInt(param: Int): Int {
        val out = ByteArray(Int.SIZE_BYTES)
        val status = getParameter.invoke(effect, intBytes(param), out) as Int
        if (status < 0) return -1
        return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun getLong(param: Int): Long {
        val out = ByteArray(Long.SIZE_BYTES)
        val status = getParameter.invoke(effect, intBytes(param), out) as Int
        if (status < 0) return 0L
        return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private fun encode(value: WireValue): ByteArray = when (value) {
        is WireValue.Bool -> ByteBuffer.allocate(13)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putInt(value.index).putInt(1)
            .put(if (value.value) 1.toByte() else 0.toByte())
            .array()

        is WireValue.IntValue -> ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(2).putInt(value.index).putInt(1)
            .putInt(value.value)
            .array()

        is WireValue.FloatValue -> ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(3).putInt(value.index).putInt(1)
            .putFloat(value.value)
            .array()

        is WireValue.Floats -> ByteBuffer.allocate(12 + value.values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4).putInt(value.index).putInt(value.values.size)
            .also { b -> value.values.forEach(b::putFloat) }
            .array()

        is WireValue.Bytes -> ByteBuffer.allocate(12 + value.values.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(5).putInt(value.index).putInt(value.values.size)
            .put(value.values)
            .array()

        is WireValue.Ints -> ByteBuffer.allocate(12 + value.values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(6).putInt(value.index).putInt(value.values.size)
            .also { b -> value.values.forEach(b::putInt) }
            .array()
    }

    override fun close() {
        runCatching { effect.enabled = false }
        effect.release()
    }
}
