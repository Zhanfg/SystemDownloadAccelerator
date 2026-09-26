package com.v4atune.app

import android.os.IBinder
import android.os.Parcel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

sealed interface WireValue {
    val index: Int
    data class Bool(val value: Boolean, override val index: Int = -1) : WireValue
    data class IntValue(val value: Int, override val index: Int = -1) : WireValue
    data class FloatValue(val value: Float, override val index: Int = -1) : WireValue
    data class Floats(val values: FloatArray, override val index: Int = -1) : WireValue
    data class Ints(val values: IntArray, override val index: Int = -1) : WireValue
    data class Bytes(val values: ByteArray, override val index: Int = -1) : WireValue
}

data class ViperCommand(val param: Int, val value: WireValue)

object ViperParams {
    const val MASTER_LIMITER_THRESHOLD = 0x10110
    const val MASTER_LIMITER_OUTPUT_VOLUME = 0x10111
    const val MASTER_LIMITER_CHANNEL_PAN = 0x10112
    const val PLAYBACK_GAIN_ENABLE = 0x10120
    const val PLAYBACK_GAIN_STRENGTH = 0x10121
    const val PLAYBACK_GAIN_MAX = 0x10122
    const val PLAYBACK_GAIN_THRESHOLD = 0x10123
    const val LUFS_ENABLE = 0x10130
    const val LUFS_TARGET = 0x10131
    const val LUFS_MAX_GAIN = 0x10132
    const val LUFS_SPEED = 0x10133
    const val FET_ENABLE = 0x10140
    const val FET_THRESHOLD = 0x10141
    const val FET_RATIO = 0x10142
    const val FET_KNEE = 0x10143
    const val FET_KNEE_AUTO = 0x10144
    const val FET_GAIN = 0x10145
    const val FET_GAIN_AUTO = 0x10146
    const val FET_ATTACK = 0x10147
    const val FET_ATTACK_AUTO = 0x10148
    const val FET_RELEASE = 0x10149
    const val FET_RELEASE_AUTO = 0x1014A
    const val FET_KNEE_MULTI = 0x1014B
    const val FET_MAX_ATTACK = 0x1014C
    const val FET_MAX_RELEASE = 0x1014D
    const val FET_CREST = 0x1014E
    const val FET_ADAPT = 0x1014F
    const val FET_NO_CLIP = 0x10150
    const val BASS_ENABLE = 0x10160
    const val BASS_MODE = 0x10161
    const val BASS_FREQUENCY = 0x10162
    const val BASS_GAIN = 0x10163
    const val BASS_ANTI_POP = 0x10164
    const val BASS_MONO_ENABLE = 0x10170
    const val BASS_MONO_MODE = 0x10171
    const val BASS_MONO_FREQUENCY = 0x10172
    const val BASS_MONO_GAIN = 0x10173
    const val BASS_MONO_ANTI_POP = 0x10174
    const val PSYCHO_BASS_ENABLE = 0x10180
    const val PSYCHO_BASS_CUTOFF = 0x10181
    const val PSYCHO_BASS_INTENSITY = 0x10182
    const val PSYCHO_BASS_HARMONIC_ORDER = 0x10183
    const val PSYCHO_BASS_ORIGINAL_LEVEL = 0x10184
    const val SPECTRUM_ENABLE = 0x10190
    const val SPECTRUM_STRENGTH = 0x10191
    const val SPECTRUM_EXCITER = 0x10192
    const val EQ_ENABLE = 0x101A0
    const val EQ_BAND_LEVEL = 0x101A1
    const val EQ_BAND_COUNT = 0x101A2
    const val EQ_BAND_LEVELS = 0x101A3
    const val CONVOLVER_ENABLE = 0x101B0
    const val CONVOLVER_PREPARE = 0x101B2
    const val CONVOLVER_BUFFER = 0x101B3
    const val CONVOLVER_COMMIT = 0x101B4
    const val CONVOLVER_CROSS = 0x101B5
    const val DDC_ENABLE = 0x101C0
    const val DDC_COEFFS = 0x101C1
    const val FIELD_ENABLE = 0x101D0
    const val FIELD_WIDENING = 0x101D1
    const val FIELD_MID_IMAGE = 0x101D2
    const val FIELD_DEPTH = 0x101D3
    const val DIFF_ENABLE = 0x101E0
    const val DIFF_DELAY = 0x101E1
    const val DIFF_REVERSE = 0x101E2
    const val DIFF_MIX = 0x101E3
    const val DIFF_LP = 0x101E4
    const val IMAGER_ENABLE = 0x101F0
    const val IMAGER_LOW = 0x101F1
    const val IMAGER_MID = 0x101F2
    const val IMAGER_HIGH = 0x101F3
    const val IMAGER_LOW_X = 0x101F4
    const val IMAGER_HIGH_X = 0x101F5
    const val HEADPHONE_SURROUND_ENABLE = 0x10200
    const val HEADPHONE_SURROUND_QUALITY = 0x10201
    const val REVERB_ENABLE = 0x10210
    const val REVERB_ROOM = 0x10211
    const val REVERB_WIDTH = 0x10212
    const val REVERB_DAMP = 0x10213
    const val REVERB_WET = 0x10214
    const val REVERB_DRY = 0x10215
    const val DYNAMIC_SYSTEM_ENABLE = 0x10220
    const val DYNAMIC_SYSTEM_X_LOW = 0x10221
    const val DYNAMIC_SYSTEM_X_HIGH = 0x10222
    const val DYNAMIC_SYSTEM_Y_LOW = 0x10223
    const val DYNAMIC_SYSTEM_Y_HIGH = 0x10224
    const val DYNAMIC_SYSTEM_SIDE_LOW = 0x10225
    const val DYNAMIC_SYSTEM_SIDE_HIGH = 0x10226
    const val DYNAMIC_SYSTEM_STRENGTH = 0x10227
    const val CLARITY_ENABLE = 0x10230
    const val CLARITY_MODE = 0x10231
    const val CLARITY_GAIN = 0x10232
    const val CURE_ENABLE = 0x10240
    const val CURE_PRESET = 0x10241
    const val TUBE_ENABLE = 0x10250
    const val ANALOG_ENABLE = 0x10260
    const val ANALOG_MODE = 0x10261
    const val SPEAKER_CORRECTION_ENABLE = 0x10270
    const val MBC_ENABLE = 0x10280
    const val MBC_BAND_COUNT = 0x10281
    const val MBC_CROSSOVER = 0x10282
    const val MBC_THRESHOLD = 0x10283
    const val MBC_RATIO = 0x10284
    const val MBC_KNEE = 0x10285
    const val MBC_KNEE_AUTO = 0x10286
    const val MBC_GAIN = 0x10287
    const val MBC_GAIN_AUTO = 0x10288
    const val MBC_ATTACK = 0x10289
    const val MBC_ATTACK_AUTO = 0x1028A
    const val MBC_RELEASE = 0x1028B
    const val MBC_RELEASE_AUTO = 0x1028C
    const val MBC_KNEE_MULTI = 0x1028D
    const val MBC_MAX_ATTACK = 0x1028E
    const val MBC_MAX_RELEASE = 0x1028F
    const val MBC_CREST = 0x10290
    const val MBC_ADAPT = 0x10291
    const val MBC_NO_CLIP = 0x10292
    const val MBC_BAND_ENABLE = 0x10293
    const val DYN_EQ_ENABLE = 0x102A0
    const val DYN_EQ_BAND_COUNT = 0x102A1
    const val DYN_EQ_FREQUENCY = 0x102A2
    const val DYN_EQ_Q = 0x102A3
    const val DYN_EQ_GAIN = 0x102A4
    const val DYN_EQ_THRESHOLD = 0x102A5
    const val DYN_EQ_ATTACK = 0x102A6
    const val DYN_EQ_RELEASE = 0x102A7
    const val DYN_EQ_FILTER_TYPE = 0x102A8
}

object ViperControl {
    private const val serviceName = "viper.control"
    private const val descriptor = "viper.fx.IViperControl"
    private const val txDispatch = IBinder.FIRST_CALL_TRANSACTION
    private const val txStatus = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val typeBool = 1
    private const val typeInt = 2
    private const val typeFloat = 3
    private const val typeFloatArray = 4
    private const val typeBytes = 5
    private const val typeIntArray = 6

    @Suppress("PrivateApi")
    private fun service(): IBinder? {
        val clazz = Class.forName("android.os.ServiceManager")
        return clazz.getMethod("getService", String::class.java)
            .invoke(null, serviceName) as? IBinder
    }

    fun available(): Boolean = runCatching { service() != null }.getOrDefault(false)

    fun status(): DriverStatus? {
        val binder = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            check(binder.transact(txStatus, data, reply, 0)) { "viper.control status transact failed" }
            reply.readException()
            if (reply.readInt() == 0) return null
            reply.readInt()
            DriverStatus(
                enabled = reply.readInt() != 0,
                sampleRate = reply.readInt(),
                processedFrames = reply.readLong(),
                kernelId = reply.readInt(),
                versionCode = reply.readInt(),
                versionName = reply.readString().orEmpty(),
                arch = reply.readString().orEmpty(),
            )
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun apply(commands: List<ViperCommand>) = commands.forEach { dispatch(it.param, it.value) }

    fun dispatch(param: Int, value: WireValue) {
        val binder = service() ?: error("viper.control is unavailable")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(param)
            data.writeByteArray(encode(value))
            check(binder.transact(txDispatch, data, reply, 0)) {
                "viper.control dispatch failed: 0x" + param.toString(16)
            }
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun streamKernel(samples: FloatArray, channels: Int, kernelId: Int) {
        require(channels in 1..2)
        require(samples.size >= 16)
        val raw = ByteBuffer.allocate(samples.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach(::putFloat) }
            .array()
        val crc = CRC32().apply { update(raw) }.value.toInt()

        dispatch(ViperParams.CONVOLVER_PREPARE, WireValue.Ints(intArrayOf(samples.size, channels, 0)))
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 2046, samples.size)
            dispatch(ViperParams.CONVOLVER_BUFFER, WireValue.Floats(samples.copyOfRange(offset, end)))
            offset = end
        }
        dispatch(ViperParams.CONVOLVER_COMMIT, WireValue.Ints(intArrayOf(samples.size, crc, kernelId)))
    }

    fun streamDdc(coeffs44: FloatArray, coeffs48: FloatArray) {
        require(coeffs44.isNotEmpty() && coeffs44.size == coeffs48.size)
        require(coeffs44.size % 5 == 0)
        dispatch(ViperParams.DDC_COEFFS, WireValue.Floats(coeffs44 + coeffs48))
    }

    private fun encode(value: WireValue): ByteArray = when (value) {
        is WireValue.Bool -> ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(typeBool).putInt(value.index).putInt(1)
            .put(if (value.value) 1.toByte() else 0.toByte()).array()
        is WireValue.IntValue -> ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(typeInt).putInt(value.index).putInt(1).putInt(value.value).array()
        is WireValue.FloatValue -> ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(typeFloat).putInt(value.index).putInt(1).putFloat(value.value).array()
        is WireValue.Floats -> ByteBuffer.allocate(12 + value.values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(typeFloatArray).putInt(value.index).putInt(value.values.size)
            .also { b -> value.values.forEach(b::putFloat) }.array()
        is WireValue.Ints -> ByteBuffer.allocate(12 + value.values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(typeIntArray).putInt(value.index).putInt(value.values.size)
            .also { b -> value.values.forEach(b::putInt) }.array()
        is WireValue.Bytes -> ByteBuffer.allocate(12 + value.values.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(typeBytes).putInt(value.index).putInt(value.values.size)
            .put(value.values).array()
    }
}
