package com.v4atune.app;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Dsp {
    static final int SAMPLE_RATE = 48000;
    static final double[] MEASURE_FREQS = {
            63, 80, 100, 125, 160, 200, 250, 315, 400, 500,
            630, 800, 1000, 1250, 1600, 2000, 2500, 3150,
            4000, 5000, 6300, 8000, 10000, 12500, 16000
    };
    static final double[] EQ_FREQS = {31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    static final class Band {
        final double freq;
        final double levelDb;
        final double noiseDb;
        final double thd;
        final double peak;

        Band(double freq, double levelDb, double noiseDb, double thd, double peak) {
            this.freq = freq;
            this.levelDb = levelDb;
            this.noiseDb = noiseDb;
            this.thd = thd;
            this.peak = peak;
        }

        double snr() {
            return levelDb - noiseDb;
        }
    }

    static final class Analysis {
        final List<Band> bands;
        final double referenceDb;
        final double roughnessDb;
        final double lowDeficitDb;
        final double highDeficitDb;
        final double medianThd;
        final double[] eq10;
        final double[] correctionAtBands;
        final int reliableBands;

        Analysis(List<Band> bands,
                 double referenceDb,
                 double roughnessDb,
                 double lowDeficitDb,
                 double highDeficitDb,
                 double medianThd,
                 double[] eq10,
                 double[] correctionAtBands,
                 int reliableBands) {
            this.bands = bands;
            this.referenceDb = referenceDb;
            this.roughnessDb = roughnessDb;
            this.lowDeficitDb = lowDeficitDb;
            this.highDeficitDb = highDeficitDb;
            this.medianThd = medianThd;
            this.eq10 = eq10;
            this.correctionAtBands = correctionAtBands;
            this.reliableBands = reliableBands;
        }

        boolean reliable() {
            return reliableBands >= 14;
        }
    }

    private Dsp() {}

    static short[] makeStereoTone(double freq, double seconds, double dbfs, boolean left, boolean right) {
        int frames = Math.max(1, (int) Math.round(seconds * SAMPLE_RATE));
        short[] pcm = new short[frames * 2];
        double amp = 32767.0 * Math.pow(10.0, dbfs / 20.0);
        int ramp = Math.max(1, (int) (SAMPLE_RATE * 0.025));
        for (int i = 0; i < frames; i++) {
            double env = 1.0;
            if (i < ramp) env = i / (double) ramp;
            else if (i >= frames - ramp) env = Math.max(0.0, (frames - 1 - i) / (double) ramp);
            short v = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(amp * env * Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE))));
            pcm[i * 2] = left ? v : 0;
            pcm[i * 2 + 1] = right ? v : 0;
        }
        return pcm;
    }

    static Band measure(short[] mono, int sampleRate, double freq, int startSample, int count) {
        if (mono == null || mono.length == 0) return new Band(freq, -120, -120, 1, 0);
        int start = Math.max(0, Math.min(startSample, mono.length - 1));
        int n = Math.max(256, Math.min(count, mono.length - start));
        double mean = 0.0;
        double peak = 0.0;
        for (int i = 0; i < n; i++) {
            double x = mono[start + i] / 32768.0;
            mean += x;
            peak = Math.max(peak, Math.abs(x));
        }
        mean /= n;

        double fundamental = projection(mono, start, n, sampleRate, freq, mean);
        double h2 = freq * 2 < sampleRate * 0.48 ? projection(mono, start, n, sampleRate, freq * 2, mean) : 0.0;
        double h3 = freq * 3 < sampleRate * 0.48 ? projection(mono, start, n, sampleRate, freq * 3, mean) : 0.0;
        double h4 = freq * 4 < sampleRate * 0.48 ? projection(mono, start, n, sampleRate, freq * 4, mean) : 0.0;
        double thd = Math.sqrt(h2 * h2 + h3 * h3 + h4 * h4) / Math.max(1e-9, fundamental);

        int noiseN = Math.min(start, Math.max(512, sampleRate / 8));
        double noise = noiseN >= 256
                ? projection(mono, Math.max(0, start - noiseN), noiseN, sampleRate, freq, 0.0)
                : 1e-9;

        return new Band(freq, ampDb(fundamental), ampDb(noise), thd, peak);
    }

    private static double projection(short[] pcm, int start, int n, int sr, double freq, double mean) {
        double cs = 0.0;
        double sn = 0.0;
        for (int i = 0; i < n; i++) {
            double x = pcm[start + i] / 32768.0 - mean;
            double ph = 2.0 * Math.PI * freq * i / sr;
            cs += x * Math.cos(ph);
            sn += x * Math.sin(ph);
        }
        return 2.0 * Math.hypot(cs, sn) / Math.max(1, n);
    }

    private static double ampDb(double amp) {
        return 20.0 * Math.log10(Math.max(1e-9, amp));
    }

    static Analysis analyze(List<Band> bands, TuneConfig.Target target) {
        if (bands == null || bands.isEmpty()) throw new IllegalArgumentException("No measurement bands");

        List<Double> mids = new ArrayList<>();
        List<Double> thds = new ArrayList<>();
        int reliable = 0;
        for (Band b : bands) {
            if (b.snr() >= 12.0 && b.freq >= 250 && b.freq <= 4000) mids.add(b.levelDb);
            if (b.snr() >= 12.0) {
                reliable++;
                if (b.thd < 2.0) thds.add(b.thd);
            }
        }
        if (mids.size() < 3) throw new IllegalStateException("Measurement SNR is too low");
        double ref = median(mids);

        double[] corr = new double[bands.size()];
        double sumSq = 0.0;
        int roughN = 0;
        for (int i = 0; i < bands.size(); i++) {
            Band b = bands.get(i);
            double rel = b.levelDb - ref;
            if (b.snr() >= 12.0 && b.freq >= 125 && b.freq <= 10000) {
                sumSq += rel * rel;
                roughN++;
            }

            double desired = targetOffset(target, b.freq);
            double c = desired - rel;

            // Do not use a phone's own microphone to chase extreme LF/HF nulls.
            double maxBoost;
            if (b.freq < 125) maxBoost = 0.0;
            else if (b.freq > 10000) maxBoost = 0.0;
            else if (b.freq >= 8000) maxBoost = 0.8;
            else maxBoost = 2.0;

            // High measured distortion means prefer cuts/psychoacoustic bass over more excursion.
            if (b.thd > 0.12) maxBoost = Math.min(maxBoost, 0.5);
            if (b.thd > 0.25) maxBoost = 0.0;
            if (b.snr() < 12.0) c = 0.0;
            corr[i] = clamp(c, -6.0, maxBoost);
        }
        corr = smooth(corr);

        // Headroom normalization: preserve shape but remove net positive gain.
        double max = 0.0;
        for (double v : corr) max = Math.max(max, v);
        if (max > 0.0) {
            for (int i = 0; i < corr.length; i++) corr[i] -= max;
        }
        for (int i = 0; i < corr.length; i++) corr[i] = clamp(corr[i], -6.0, 0.0);

        double[] eq = new double[EQ_FREQS.length];
        for (int i = 0; i < eq.length; i++) {
            if (EQ_FREQS[i] < MEASURE_FREQS[0]) eq[i] = 0.0;
            else eq[i] = round1(interpolateLogFreq(bands, corr, EQ_FREQS[i]));
            eq[i] = clamp(eq[i], -6.0, 0.0);
        }

        double low = deficit(bands, ref, 125, 315);
        double high = deficit(bands, ref, 6300, 12500);
        double roughness = Math.sqrt(sumSq / Math.max(1, roughN));
        double medThd = thds.isEmpty() ? 0.0 : median(thds);
        return new Analysis(bands, ref, roughness, low, high, medThd, eq, corr, reliable);
    }

    static Analysis refine(Analysis first, Analysis verification, TuneConfig.Target target) {
        double[] next = Arrays.copyOf(first.correctionAtBands, first.correctionAtBands.length);
        for (int i = 0; i < Math.min(next.length, verification.bands.size()); i++) {
            Band b = verification.bands.get(i);
            if (b.snr() < 12) continue;
            double rel = b.levelDb - verification.referenceDb;
            double err = targetOffset(target, b.freq) - rel;
            next[i] = clamp(next[i] + clamp(err, -1.5, 1.0), -7.0, 0.0);
        }
        List<Band> same = first.bands;
        double[] eq = new double[EQ_FREQS.length];
        for (int i = 0; i < eq.length; i++) {
            if (EQ_FREQS[i] < MEASURE_FREQS[0]) eq[i] = 0.0;
            else eq[i] = round1(interpolateLogFreq(same, next, EQ_FREQS[i]));
        }
        return new Analysis(first.bands, first.referenceDb, verification.roughnessDb,
                first.lowDeficitDb, first.highDeficitDb, first.medianThd,
                eq, next, first.reliableBands);
    }

    private static double targetOffset(TuneConfig.Target target, double f) {
        switch (target) {
            case BALANCED:
                if (f <= 250) return 1.0;
                if (f >= 8000) return 0.5;
                return 0.0;
            case VOCAL:
                if (f < 250) return -0.8;
                if (f >= 1000 && f <= 3500) return 1.2;
                if (f >= 8000) return -0.3;
                return 0.0;
            case BASS:
                if (f <= 315) return 1.8;
                if (f >= 8000) return 0.2;
                return 0.0;
            case SPATIAL:
                if (f < 160) return 0.4;
                if (f >= 4000 && f <= 10000) return 0.6;
                return 0.0;
            case LOUDNESS:
                if (f <= 250) return 0.7;
                if (f >= 2500 && f <= 8000) return 0.6;
                return 0.0;
            case REFERENCE:
            default:
                return 0.0;
        }
    }

    private static double deficit(List<Band> bands, double ref, double lo, double hi) {
        List<Double> vals = new ArrayList<>();
        for (Band b : bands) {
            if (b.freq >= lo && b.freq <= hi && b.snr() >= 12.0) vals.add(b.levelDb);
        }
        if (vals.isEmpty()) return 0.0;
        return Math.max(0.0, ref - median(vals));
    }

    private static double interpolateLogFreq(List<Band> bands, double[] values, double f) {
        if (f <= bands.get(0).freq) return values[0];
        int last = bands.size() - 1;
        if (f >= bands.get(last).freq) return values[last];
        double lf = Math.log(f);
        for (int i = 0; i < last; i++) {
            double f0 = bands.get(i).freq;
            double f1 = bands.get(i + 1).freq;
            if (f >= f0 && f <= f1) {
                double t = (lf - Math.log(f0)) / (Math.log(f1) - Math.log(f0));
                return values[i] * (1.0 - t) + values[i + 1] * t;
            }
        }
        return 0.0;
    }

    static File writeCorrectionFir(File out, Analysis a) throws Exception {
        final int n = 1024;
        double[] re = new double[n];
        double[] im = new double[n];

        for (int k = 0; k <= n / 2; k++) {
            double f = k * SAMPLE_RATE / (double) n;
            double gainDb;
            if (f < MEASURE_FREQS[0]) gainDb = 0.0;
            else if (f > MEASURE_FREQS[MEASURE_FREQS.length - 1]) gainDb = 0.0;
            else gainDb = interpolateLogFreq(a.bands, a.correctionAtBands, f);
            double amp = Math.pow(10.0, gainDb / 20.0);
            re[k] = amp;
            if (k > 0 && k < n / 2) re[n - k] = amp;
        }

        fft(re, im, true);

        double[] h = new double[n];
        for (int i = 0; i < n; i++) {
            int src = (i + n / 2) % n;
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
            h[i] = re[src] * window;
        }

        double peak = 0.0;
        for (double v : h) peak = Math.max(peak, Math.abs(v));
        if (peak > 0.95) {
            double scale = 0.95 / peak;
            for (int i = 0; i < h.length; i++) h[i] *= scale;
        }
        writePcm16MonoWav(out, h, SAMPLE_RATE);
        return out;
    }

    private static void writePcm16MonoWav(File out, double[] samples, int sr) throws Exception {
        try (DataOutputStream d = new DataOutputStream(new FileOutputStream(out))) {
            int dataSize = samples.length * 2;
            writeAscii(d, "RIFF");
            writeLeInt(d, 36 + dataSize);
            writeAscii(d, "WAVE");
            writeAscii(d, "fmt ");
            writeLeInt(d, 16);
            writeLeShort(d, 1);
            writeLeShort(d, 1);
            writeLeInt(d, sr);
            writeLeInt(d, sr * 2);
            writeLeShort(d, 2);
            writeLeShort(d, 16);
            writeAscii(d, "data");
            writeLeInt(d, dataSize);
            for (double s : samples) {
                int v = (int) Math.round(clamp(s, -1.0, 1.0) * 32767.0);
                writeLeShort(d, v);
            }
        }
    }

    private static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wlenR = Math.cos(ang);
            double wlenI = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double wr = 1, wi = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = i + j + len / 2;
                    double vr = re[v] * wr - im[v] * wi;
                    double vi = re[v] * wi + im[v] * wr;
                    re[v] = re[u] - vr; im[v] = im[u] - vi;
                    re[u] += vr; im[u] += vi;
                    double nwr = wr * wlenR - wi * wlenI;
                    wi = wr * wlenI + wi * wlenR;
                    wr = nwr;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    private static double[] smooth(double[] x) {
        if (x.length < 3) return x.clone();
        double[] y = new double[x.length];
        y[0] = .75 * x[0] + .25 * x[1];
        for (int i = 1; i < x.length - 1; i++) y[i] = .25 * x[i - 1] + .5 * x[i] + .25 * x[i + 1];
        y[x.length - 1] = .25 * x[x.length - 2] + .75 * x[x.length - 1];
        return y;
    }

    private static double median(List<Double> in) {
        double[] a = new double[in.size()];
        for (int i = 0; i < a.length; i++) a[i] = in.get(i);
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) * 0.5;
    }

    private static double round1(double x) {
        return Math.round(x * 10.0) / 10.0;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static void writeAscii(DataOutputStream d, String s) throws Exception {
        d.writeBytes(s);
    }

    private static void writeLeInt(DataOutputStream d, int v) throws Exception {
        d.writeByte(v & 0xff);
        d.writeByte((v >>> 8) & 0xff);
        d.writeByte((v >>> 16) & 0xff);
        d.writeByte((v >>> 24) & 0xff);
    }

    private static void writeLeShort(DataOutputStream d, int v) throws Exception {
        d.writeByte(v & 0xff);
        d.writeByte((v >>> 8) & 0xff);
    }
}
