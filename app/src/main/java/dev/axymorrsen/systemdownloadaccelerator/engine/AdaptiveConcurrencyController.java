package dev.axymorrsen.systemdownloadaccelerator.engine;

/**
 * Throughput-driven concurrency ramp.
 *
 * It never forces a ramp to the ceiling. Each level must prove useful before
 * the next level is allowed. The first experiment can at most double the
 * initial worker count; if marginal throughput gain is poor, ramping stops.
 */
public final class AdaptiveConcurrencyController {
    public enum Action {
        WARMUP,
        HOLD,
        RAMP,
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

    private static final double MIN_THROUGHPUT_BPS = 256.0 * 1024.0;
    private static final double MIN_PER_WORKER_BPS = 96.0 * 1024.0;
    private static final double MIN_GAIN_TO_CONTINUE = 1.12;
    private static final long MIN_BYTES_PER_NEXT_WORKER = 2L * 1024L * 1024L;

    private final int ceiling;
    private int currentWorkers;

    private double stableBaselineBps;
    private double preRampBaselineBps;
    private int samplesAtLevel;
    private boolean evaluatingRamp;
    private boolean stopped;

    public AdaptiveConcurrencyController(int initialWorkers, int ceiling) {
        if (initialWorkers < 1) {
            throw new IllegalArgumentException("initialWorkers < 1");
        }
        if (ceiling < initialWorkers) {
            throw new IllegalArgumentException("ceiling < initialWorkers");
        }
        this.currentWorkers = initialWorkers;
        this.ceiling = ceiling;
    }

    public int workers() {
        return currentWorkers;
    }

    public int ceiling() {
        return ceiling;
    }

    public Decision sample(double bytesPerSecond, long remainingBytes) {
        double bps = Math.max(0.0, bytesPerSecond);

        if (currentWorkers >= ceiling) {
            return new Decision(
                    Action.CEILING,
                    currentWorkers,
                    bps,
                    1.0,
                    "ceiling reached");
        }
        if (stopped) {
            return new Decision(
                    Action.HOLD,
                    currentWorkers,
                    bps,
                    1.0,
                    "marginal gain insufficient");
        }
        if (bps < MIN_THROUGHPUT_BPS) {
            samplesAtLevel++;
            stableBaselineBps = smooth(stableBaselineBps, bps);
            return new Decision(
                    Action.HOLD,
                    currentWorkers,
                    bps,
                    1.0,
                    "path throughput too low to justify more sockets");
        }
        if (bps / Math.max(1, currentWorkers) < MIN_PER_WORKER_BPS) {
            stopped = true;
            return new Decision(
                    Action.HOLD,
                    currentWorkers,
                    bps,
                    1.0,
                    "per-worker throughput too low");
        }

        samplesAtLevel++;
        stableBaselineBps = smooth(stableBaselineBps, bps);

        if (evaluatingRamp) {
            if (samplesAtLevel < 2) {
                return new Decision(
                        Action.WARMUP,
                        currentWorkers,
                        bps,
                        ratio(bps, preRampBaselineBps),
                        "collecting post-ramp sample");
            }

            double gain = ratio(stableBaselineBps, preRampBaselineBps);
            evaluatingRamp = false;

            if (gain < MIN_GAIN_TO_CONTINUE) {
                stopped = true;
                return new Decision(
                        Action.HOLD,
                        currentWorkers,
                        bps,
                        gain,
                        "marginal throughput gain below 12%");
            }
        } else if (samplesAtLevel < 2) {
            return new Decision(
                    Action.WARMUP,
                    currentWorkers,
                    bps,
                    1.0,
                    "collecting baseline");
        }

        int next = Math.min(ceiling, currentWorkers * 2);
        if (next <= currentWorkers) {
            return new Decision(
                    Action.CEILING,
                    currentWorkers,
                    bps,
                    1.0,
                    "ceiling reached");
        }

        if (remainingBytes
                < (long) next * MIN_BYTES_PER_NEXT_WORKER) {
            stopped = true;
            return new Decision(
                    Action.HOLD,
                    currentWorkers,
                    bps,
                    1.0,
                    "not enough remaining data to amortize more sockets");
        }

        preRampBaselineBps = Math.max(1.0, stableBaselineBps);
        currentWorkers = next;
        samplesAtLevel = 0;
        stableBaselineBps = 0.0;
        evaluatingRamp = true;

        return new Decision(
                Action.RAMP,
                currentWorkers,
                bps,
                1.0,
                "throughput still benefits from more concurrency");
    }

    private static double smooth(double previous, double current) {
        if (previous <= 0.0) {
            return current;
        }
        return previous * 0.35 + current * 0.65;
    }

    private static double ratio(double value, double baseline) {
        if (baseline <= 0.0) return 1.0;
        return value / baseline;
    }
}
