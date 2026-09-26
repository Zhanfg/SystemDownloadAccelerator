package com.v4atune.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Acoustics {
    val eq31 = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0,
        125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0,
        4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0,
    )

    fun grid24(): DoubleArray {
        val out = mutableListOf<Double>()
        var f = 20.0
        val ratio = 2.0.pow(1.0 / 24.0)
        while (f <= 20000.0 * 1.001) {
            out += f
            f *= ratio
        }
        return out.toDoubleArray()
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val a = values.sorted()
        val m = a.size / 2
        return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2.0
    }

    fun rmsDb(values: ShortArray, start: Int, count: Int): Double {
        if (count <= 0) return -120.0
        var sum = 0.0
        val end = min(values.size, start + count)
        for (i in max(0, start) until end) {
            val x = values[i] / 32768.0
            sum += x * x
        }
        val n = max(1, end - max(0, start))
        return 20.0 * log10(max(1e-9, sqrt(sum / n)))
    }

    fun goertzel(
        values: ShortArray,
        sampleRate: Int,
        frequency: Double,
        start: Int,
        count: Int,
    ): Double {
        val begin = max(0, start)
        val end = min(values.size, begin + count)
        val n = end - begin
        if (n < 32) return 0.0
        val omega = 2.0 * PI * frequency / sampleRate
        val coeff = 2.0 * cos(omega)
        var s0: Double
        var s1 = 0.0
        var s2 = 0.0
        for (i in begin until end) {
            val x = values[i] / 32768.0
            s0 = x + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return 2.0 * sqrt(max(0.0, power)) / n
    }

    fun thd(
        values: ShortArray,
        sampleRate: Int,
        frequency: Double,
        start: Int,
        count: Int,
    ): Double {
        val fundamental = goertzel(values, sampleRate, frequency, start, count)
        if (fundamental <= 1e-8) return 1.0
        var h2 = 0.0
        for (h in 2..5) {
            val hf = frequency * h
            if (hf >= sampleRate * 0.48) break
            val a = goertzel(values, sampleRate, hf, start, count)
            h2 += a * a
        }
        return sqrt(h2) / fundamental
    }

    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * if (inverse) 1.0 else -1.0
            val wlR = cos(angle)
            val wlI = sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val u = i + k
                    val v = u + len / 2
                    val vr = re[v] * wr - im[v] * wi
                    val vi = re[v] * wi + im[v] * wr
                    re[v] = re[u] - vr
                    im[v] = im[u] - vi
                    re[u] += vr
                    im[u] += vi
                    val nextWr = wr * wlR - wi * wlI
                    wi = wr * wlI + wi * wlR
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    fun nextPow2(value: Int): Int {
        var n = 1
        while (n < value) n = n shl 1
        return n
    }
}

class SweepCalibrator(private val context: Context) {
    companion object {
        private const val defaultRate = 48000
        private const val f0 = 20.0
        private const val f1 = 20000.0
        private const val markerSeconds = 0.08
        private const val gapSeconds = 0.10
        private const val preRecordSeconds = 0.18
    }

    suspend fun measure(
        mode: TestMode,
        verification: Boolean = false,
        progress: suspend (String) -> Unit = {},
    ): Measurement {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }

        val status = ViperControl.status()
        val sampleRate = status?.sampleRate?.takeIf { it in 32000..192000 } ?: defaultRate
        val seconds = if (verification) mode.verifySeconds else mode.sweepSeconds

        progress(if (verification) "验证扫频" else "20 Hz–20 kHz 指数扫频")
        val sweep = createSweep(sampleRate, seconds, -20.0)
        val playback = withMarker(sampleRate, sweep, -13.0)
        val recorded = playRecord(sampleRate, playback)

        val noiseCount = (sampleRate * preRecordSeconds * 0.75).roundToInt()
        val noiseDb = Acoustics.rmsDb(recorded, 0, noiseCount)
        val sweepStart = detectSweepStart(recorded, sampleRate, sweep.size)
        val transfer = transferResponse(sweep, recorded, sweepStart, sampleRate)

        val denseFreqs = Acoustics.grid24()
        val dense = denseFreqs.map { f ->
            val level = transfer.levelAt(f)
            ResponsePoint(f, level, level - noiseDb)
        }
        val eq31 = Acoustics.eq31.map { f ->
            val level = transfer.levelAt(f)
            ResponsePoint(f, level, level - noiseDb)
        }

        if (verification) {
            return Measurement(
                sampleRate = sampleRate,
                dense = dense,
                eq31 = eq31,
                distortion = emptyList(),
                left1kDb = 0.0,
                right1kDb = 0.0,
                compressionDb = 0.0,
                noiseDb = noiseDb,
                clipped = recorded.maxOf { abs(it.toInt()) } >= 32700,
            )
        }

        progress("失真探针 · 100 Hz / 1 kHz / 8 kHz")
        val distortion = listOf(100.0, 1000.0, 8000.0).map { f ->
            val probe = toneProbe(sampleRate, f, -13.0)
            DistortionProbe(f, probe.thd, probe.peak)
        }

        progress("左右声道平衡")
        val left = toneProbe(sampleRate, 1000.0, -18.0, left = true, right = false)
        val right = toneProbe(sampleRate, 1000.0, -18.0, left = false, right = true)

        progress("动态压缩探针")
        val medium = toneProbe(sampleRate, 1000.0, -25.0)
        val loud = toneProbe(sampleRate, 1000.0, -13.0)
        val expectedDelta = 12.0
        val actualDelta = loud.levelDb - medium.levelDb
        val compressionDb = max(0.0, expectedDelta - actualDelta)

        return Measurement(
            sampleRate = sampleRate,
            dense = dense,
            eq31 = eq31,
            distortion = distortion,
            left1kDb = left.levelDb,
            right1kDb = right.levelDb,
            compressionDb = compressionDb,
            noiseDb = noiseDb,
            clipped = recorded.maxOf { abs(it.toInt()) } >= 32700 || distortion.any { it.peak >= 0.995 },
        )
    }

    private data class Probe(val levelDb: Double, val thd: Double, val peak: Double)

    private fun toneProbe(
        sampleRate: Int,
        frequency: Double,
        dbfs: Double,
        left: Boolean = true,
        right: Boolean = true,
    ): Probe {
        val seconds = 0.42
        val mono = FloatArray((sampleRate * seconds).roundToInt()) { i ->
            val fade = min(1.0, min(i / (sampleRate * 0.02), (monoLength(sampleRate, seconds) - 1 - i) / (sampleRate * 0.02)))
            (10.0.pow(dbfs / 20.0) * fade.coerceIn(0.0, 1.0) * sin(2.0 * PI * frequency * i / sampleRate)).toFloat()
        }
        val stereo = stereo(mono, left, right)
        val recorded = playRecord(sampleRate, stereo, 0.12)
        val start = (sampleRate * 0.16).roundToInt()
        val count = min(recorded.size - start, (sampleRate * 0.28).roundToInt())
        val amp = Acoustics.goertzel(recorded, sampleRate, frequency, start, count)
        val level = 20.0 * log10(max(1e-9, amp))
        val thd = Acoustics.thd(recorded, sampleRate, frequency, start, count)
        val peak = recorded.maxOf { abs(it.toInt()) } / 32768.0
        return Probe(level, thd, peak)
    }

    private fun monoLength(sampleRate: Int, seconds: Double) = (sampleRate * seconds).roundToInt()

    private fun createSweep(sampleRate: Int, seconds: Double, dbfs: Double): FloatArray {
        val n = (sampleRate * seconds).roundToInt()
        val amplitude = 10.0.pow(dbfs / 20.0)
        val ratio = f1 / f0
        val k = seconds / ln(ratio)
        return FloatArray(n) { i ->
            val t = i / sampleRate.toDouble()
            val phase = 2.0 * PI * f0 * k * (exp(t / k) - 1.0)
            val fadeSamples = max(1, (sampleRate * 0.025).roundToInt())
            val fade = when {
                i < fadeSamples -> i / fadeSamples.toDouble()
                i >= n - fadeSamples -> (n - 1 - i) / fadeSamples.toDouble()
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            (amplitude * fade * sin(phase)).toFloat()
        }
    }

    private fun withMarker(sampleRate: Int, sweep: FloatArray, markerDbfs: Double): ShortArray {
        val markerN = (sampleRate * markerSeconds).roundToInt()
        val gapN = (sampleRate * gapSeconds).roundToInt()
        val mono = FloatArray(markerN + gapN + sweep.size)
        val amp = 10.0.pow(markerDbfs / 20.0)
        for (i in 0 until markerN) {
            val fade = min(1.0, min(i / (sampleRate * 0.01), (markerN - 1 - i) / (sampleRate * 0.01))).coerceIn(0.0, 1.0)
            mono[i] = (amp * fade * sin(2.0 * PI * 1000.0 * i / sampleRate)).toFloat()
        }
        sweep.copyInto(mono, markerN + gapN)
        return stereo(mono)
    }

    private fun stereo(mono: FloatArray, left: Boolean = true, right: Boolean = true): ShortArray {
        val out = ShortArray(mono.size * 2)
        mono.forEachIndexed { i, x ->
            val v = (x.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            out[i * 2] = if (left) v else 0
            out[i * 2 + 1] = if (right) v else 0
        }
        return out
    }

    private fun playRecord(sampleRate: Int, stereoPcm: ShortArray, preroll: Double = preRecordSeconds): ShortArray {
        val source = preferredSource()
        val inFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val totalSeconds = preroll + stereoPcm.size / 2.0 / sampleRate + 0.25
        val samples = (totalSeconds * sampleRate).roundToInt()
        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(inFormat)
            .setBufferSizeInBytes(max(minIn, sampleRate) * 2)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

        val agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId) else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null
        runCatching { agc?.enabled = false }
        runCatching { ns?.enabled = false }
        runCatching { aec?.enabled = false }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val outFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(outFormat)
            .setBufferSizeInBytes(max(minOut, sampleRate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val captured = ShortArray(samples)
        record.startRecording()
        val reader = Thread {
            var offset = 0
            while (offset < captured.size) {
                val n = record.read(captured, offset, captured.size - offset, AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                offset += n
            }
        }
        reader.start()

        Thread.sleep((preroll * 1000).roundToInt().toLong())
        track.play()
        var offset = 0
        while (offset < stereoPcm.size) {
            val n = track.write(stereoPcm, offset, stereoPcm.size - offset, AudioTrack.WRITE_BLOCKING)
            check(n >= 0) { "AudioTrack write failed: $n" }
            offset += n
        }
        track.stop()
        reader.join(3000)

        runCatching { record.stop() }
        track.release()
        record.release()
        agc?.release()
        ns?.release()
        aec?.release()
        return captured
    }

    private fun preferredSource(): Int {
        val manager = context.getSystemService(AudioManager::class.java)
        val unprocessed = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed.equals("true", true)) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
    }

    private fun detectSweepStart(recorded: ShortArray, sampleRate: Int, sweepSamples: Int): Int {
        val window = max(64, sampleRate / 100)
        val baseline = 10.0.pow(Acoustics.rmsDb(recorded, 0, min(recorded.size, sampleRate / 8)) / 20.0)
        val threshold = max(0.006, baseline * 5.0)
        var marker = (sampleRate * preRecordSeconds * 0.5).roundToInt()
        val limit = min(recorded.size - window, sampleRate)
        var i = 0
        while (i < limit) {
            var sum = 0.0
            for (j in 0 until window) {
                val x = recorded[i + j] / 32768.0
                sum += x * x
            }
            if (sqrt(sum / window) > threshold) {
                marker = i
                break
            }
            i += window / 2
        }
        val start = marker +
            (sampleRate * markerSeconds).roundToInt() +
            (sampleRate * gapSeconds).roundToInt()
        return start.coerceIn(0, max(0, recorded.size - sweepSamples))
    }

    private data class Transfer(
        val sampleRate: Int,
        val n: Int,
        val re: DoubleArray,
        val im: DoubleArray,
    ) {
        fun levelAt(frequency: Double): Double {
            val bin = (frequency * n / sampleRate).roundToInt().coerceIn(1, n / 2 - 1)
            return 20.0 * log10(max(1e-12, hypot(re[bin], im[bin])))
        }
    }

    private fun transferResponse(
        sweep: FloatArray,
        recorded: ShortArray,
        start: Int,
        sampleRate: Int,
    ): Transfer {
        val n = Acoustics.nextPow2(sweep.size)
        val xr = DoubleArray(n)
        val xi = DoubleArray(n)
        val yr = DoubleArray(n)
        val yi = DoubleArray(n)

        val available = min(sweep.size, recorded.size - start)
        for (i in 0 until available) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / max(1, sweep.size - 1))
            xr[i] = sweep[i] * w
            yr[i] = recorded[start + i] / 32768.0 * w
        }
        Acoustics.fft(xr, xi, false)
        Acoustics.fft(yr, yi, false)

        val hr = DoubleArray(n)
        val hi = DoubleArray(n)
        for (k in 0..n / 2) {
            val den = xr[k] * xr[k] + xi[k] * xi[k] + 1e-18
            hr[k] = (yr[k] * xr[k] + yi[k] * xi[k]) / den
            hi[k] = (yi[k] * xr[k] - yr[k] * xi[k]) / den
        }
        return Transfer(sampleRate, n, hr, hi)
    }
}
