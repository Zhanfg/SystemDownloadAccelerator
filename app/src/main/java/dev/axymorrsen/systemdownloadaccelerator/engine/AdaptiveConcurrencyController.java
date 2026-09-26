package dev.axymorrsen.systemdownloadaccelerator.engine;

/**
 * Throughput-driven concurrency controller with rollback.
 *
 * Startup/drain transients are deliberately non-terminal: a slow or zero
 * delivered sample cannot permanently disable ramping. Each worker level needs
 * a stable delivered-throughput baseline before it can ramp or roll back.
 */
public final class AdaptiveConcurrencyController {
    public enum Action {
        WARMUP,
        HOLD,
        RAMP,
        ROLLBACK,
        CEILING
    }

    public static final class Decision {
        public final Action action;
        public final int workers;
        public final double bytesPerSecond;
        public final double gainRatio;
        public final String reason;

        Decision(
                Action action,
                int workers,
                double bytesPerSecond,
                double gainRatio,
                String reason) {
            this.action = action;
            this.workers = workers;
            this.bytesPerSecond = bytesPerSecond;
            this.gainRatio = gainRatio;
            this.reason = reason;
        }
    }

    private static final double MIN_THROUGHPUT_BPS =
            256.0 * 1024.0;
    private static final double MIN_PER_WORKER_BPS =
            128.0 * 1024.0;
    private static final double MIN_GAIN_TO_KEEP_RAMP = 1.10;
    private static final long MIN_BYTES_PER_NEXT_WORKER =
            4L * 1024L * 1024L;

    /** Require a real steady baseline, not the first drain sample. */
    private static final int BASELINE_SAMPLES = 3;
    /** Give a freshly enlarged worker pool time to fill the ordered pipeline. */
    private static final int POST_RAMP_SAMPLES = 3;
    /** Do not treat one transient low per-worker sample as terminal. */
    private static final int LOW_PER_WORKER_SAMPLES = 3;

    private final int ceiling;
    private int currentWorkers;
    private int previousWorkers;

    private double stableBaselineBps;
    private double preRampBaselineBps;
    private int samplesAtLevel;
    private int lowPerWorkerSamples;
    private boolean evaluatingRamp;
    private boolean stopped;

    public AdaptiveConcurrencyController(
            int initialWorkers,
            int ceiling) {
        if (initialWorkers < 1) {
            throw new IllegalArgumentException(
                    "initialWorkers < 1");
        }
        if (ceiling < initialWorkers) {
            throw new IllegalArgumentException(
                    "ceiling < initialWorkers");
        }
        this.currentWorkers = initialWorkers;
        this.previousWorkers = initialWorkers;
        this.ceiling = ceiling;
    }

    public int workers() {
        return currentWorkers;
    }

    public int ceiling() {
        return ceiling;
    }

    public Decision sample(
            double deliveredBytesPerSecond,
            long remainingBytes) {
        double bps = Math.max(
                0.0,
                deliveredBytesPerSecond);

        if (stopped) {
            return new Decision(
                    Action.HOLD,
                    currentWorkers,
                    bps,
                    1.0,
                    "ramp disabled after rollback/final hold");
        }

        if (currentWorkers >= ceiling
                && !evaluatingRamp) {
            return new Decision(
                    Action.CEILING,
                    currentWorkers,
                    bps,
                    1.0,
                    "ceiling reached");
        }

        /*
         * A zero/very-low delivered sample is common while the native consumer
         * has not reached the first prefetched range yet. Do not count it as a
         * baseline and, crucially, do not make it terminal.
         */
        if (bps < MIN_THROUGHPUT_BPS) {
            return new Decision(
                    Action.WARMUP,
                    currentWorkers,
                    bps,
                    1.0,
                    "waiting for delivered pipeline");
        }

        if (bps / Math.max(1, currentWorkers)
                < MIN_PER_WORKER_BPS) {
            lowPerWorkerSamples++;
            if (lowPerWorkerSamples < LOW_PER_WORKER_SAMPLES) {
                return new Decision(
                        Action.WARMUP,
                        currentWorkers,
                        bps,
                        1.0,
                        "transient low per-worker delivered throughput");
            }

            /*
             * Keep sampling at the same level instead of permanently disabling
             * adaptation. A VPN/server path can recover later in a long file.
             */
            return new Decision(
                    Action.HOLD,
                    currentWorkers,
                    bps,
                    1.0,
                    "sustained per-worker delivered throughput too low");
        }

        lowPerWorkerSamples = 0;
        samplesAtLevel++;
        stableBaselineBps =
                smooth(stableBaselineBps, bps);

        if (evaluatingRamp) {
            if (samplesAtLevel < POST_RAMP_SAMPLES) {
                return new Decision(
                        Action.WARMUP,
                        currentWorkers,
                        bps,
                        ratio(
                                stableBaselineBps,
                                preRampBaselineBps),
                        "settling post-ramp delivered pipeline");
            }

            double gain = ratio(
                    stableBaselineBps,
                    preRampBaselineBps);
            evaluatingRamp = false;

            if (gain < MIN_GAIN_TO_KEEP_RAMP) {
                currentWorkers =
                        Math.max(1, previousWorkers);
                stopped = true;
                stableBaselineBps =
                        preRampBaselineBps;
                samplesAtLevel = 0;
                return new Decision(
                        Action.ROLLBACK,
                        currentWorkers,
                        bps,
                        gain,
                        "post-ramp delivered gain below 10%");
            }
        } else if (samplesAtLevel < BASELINE_SAMPLES) {
            return new Decision(
                    Action.WARMUP,
                    currentWorkers,
                    bps,
                    1.0,
                    "collecting stable delivered baseline");
        }

        int next = Math.min(
                ceiling,
                currentWorkers * 2);
        if (next <= currentWorkers) {
            return new Decision(
                    Action.CEILING,
                    currentWorkers,
                    bps,
                    1.0,
                    "ceiling reached");
        }

        if (remainingBytes
                < (long) next
                * MIN_BYTES_PER_NEXT_WORKER) {
            stopped = true;
            return new Decision(
                    Action.HOLD,
                    currentWorkers,
                    bps,
                    1.0,
                    "not enough remaining data to amortize more sockets");
        }

        previousWorkers = currentWorkers;
        preRampBaselineBps =
                Math.max(1.0, stableBaselineBps);
        currentWorkers = next;
        samplesAtLevel = 0;
        lowPerWorkerSamples = 0;
        stableBaselineBps = 0.0;
        evaluatingRamp = true;

        return new Decision(
                Action.RAMP,
                currentWorkers,
                bps,
                1.0,
                "stable delivered throughput allows concurrency probe");
    }

    private static double smooth(
            double previous,
            double current) {
        if (previous <= 0.0) {
            return current;
        }
        return previous * 0.35
                + current * 0.65;
    }

    private static double ratio(
            double value,
            double baseline) {
        if (baseline <= 0.0) return 1.0;
        return value / baseline;
    }
}
