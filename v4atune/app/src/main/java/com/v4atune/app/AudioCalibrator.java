package com.v4atune.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.util.ArrayList;
import java.util.List;

final class AudioCalibrator {
    interface Progress {
        void onProgress(String text, int done, int total);
    }

    static final class Result {
        final List<Dsp.Band> bands;
        final double left1kDb;
        final double right1kDb;
        final int audioSource;
        final boolean unprocessed;

        Result(List<Dsp.Band> bands, double left1kDb, double right1kDb, int audioSource, boolean unprocessed) {
            this.bands = bands;
            this.left1kDb = left1kDb;
            this.right1kDb = right1kDb;
            this.audioSource = audioSource;
            this.unprocessed = unprocessed;
        }

        double channelBalanceDb() {
            return left1kDb - right1kDb;
        }
    }

    private static final int SR = Dsp.SAMPLE_RATE;
    private static final double TONE_SECONDS = 0.90;
    private static final double RECORD_SECONDS = 1.45;
    private static final int PRE_MS = 250;
    private static final double DBFS = -20.0;

    private final Context context;

    AudioCalibrator(Context context) {
        this.context = context.getApplicationContext();
    }

    Result run(Progress cb) throws Exception {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Microphone permission is required");
        }

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_NORMAL);

        boolean unprocessed = "true".equalsIgnoreCase(
                am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int source = unprocessed ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.MIC;

        List<Dsp.Band> bands = new ArrayList<>();
        int total = Dsp.MEASURE_FREQS.length + 2;
        int done = 0;

        for (double f : Dsp.MEASURE_FREQS) {
            cb.onProgress(String.format("测量 %.0f Hz", f), done, total);
            short[] rec = playAndRecord(f, true, true, source);
            int start = (int) (SR * 0.36);
            int count = (int) (SR * 0.62);
            bands.add(Dsp.measure(rec, SR, f, start, count));
            done++;
        }

        cb.onProgress("测量左声道", done, total);
        short[] left = playAndRecord(1000, true, false, source);
        Dsp.Band lb = Dsp.measure(left, SR, 1000, (int) (SR * 0.36), (int) (SR * 0.62));
        done++;

        cb.onProgress("测量右声道", done, total);
        short[] right = playAndRecord(1000, false, true, source);
        Dsp.Band rb = Dsp.measure(right, SR, 1000, (int) (SR * 0.36), (int) (SR * 0.62));
        done++;

        cb.onProgress("测量完成", done, total);
        return new Result(bands, lb.levelDb, rb.levelDb, source, unprocessed);
    }

    private short[] playAndRecord(double freq, boolean left, boolean right, int source) throws Exception {
        int channelIn = AudioFormat.CHANNEL_IN_MONO;
        int enc = AudioFormat.ENCODING_PCM_16BIT;
        int minRec = AudioRecord.getMinBufferSize(SR, channelIn, enc);
        int recordSamples = (int) Math.round(SR * RECORD_SECONDS);
        int recBuffer = Math.max(minRec, SR / 2);

        AudioRecord recorder = new AudioRecord(
                source,
                SR,
                channelIn,
                enc,
                recBuffer
        );
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            if (source != MediaRecorder.AudioSource.MIC) {
                return playAndRecord(freq, left, right, MediaRecorder.AudioSource.MIC);
            }
            throw new IllegalStateException("AudioRecord initialization failed");
        }

        AutomaticGainControl agc = null;
        NoiseSuppressor ns = null;
        AcousticEchoCanceler aec = null;
        try {
            int session = recorder.getAudioSessionId();
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(session);
                if (agc != null) agc.setEnabled(false);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(false);
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(false);
            }

            AudioFormat outFormat = new AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(enc)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            int minTrack = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, enc);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(outFormat)
                    .setBufferSizeInBytes(Math.max(minTrack, SR * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            short[] captured = new short[recordSamples];
            final Throwable[] readError = new Throwable[1];
            recorder.startRecording();
            Thread reader = new Thread(() -> {
                int off = 0;
                try {
                    while (off < captured.length) {
                        int n = recorder.read(captured, off, captured.length - off, AudioRecord.READ_BLOCKING);
                        if (n < 0) throw new IllegalStateException("AudioRecord read error " + n);
                        if (n == 0) continue;
                        off += n;
                    }
                } catch (Throwable t) {
                    readError[0] = t;
                }
            }, "V4ATune-Recorder");
            reader.start();

            Thread.sleep(PRE_MS);
            short[] tone = Dsp.makeStereoTone(freq, TONE_SECONDS, DBFS, left, right);
            track.play();
            int off = 0;
            while (off < tone.length) {
                int n = track.write(tone, off, tone.length - off, AudioTrack.WRITE_BLOCKING);
                if (n < 0) throw new IllegalStateException("AudioTrack write error " + n);
                off += n;
            }
            track.stop();

            reader.join(3000);
            if (reader.isAlive()) {
                recorder.stop();
                reader.interrupt();
                reader.join(500);
            } else {
                recorder.stop();
            }
            track.release();

            if (readError[0] != null) throw new Exception(readError[0]);
            return captured;
        } finally {
            try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
            if (agc != null) agc.release();
            if (ns != null) ns.release();
            if (aec != null) aec.release();
        }
    }
}
