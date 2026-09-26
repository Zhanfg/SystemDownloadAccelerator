package dev.axymorrsen.systemdownloadaccelerator.engine;

/**
 * Computes a safe initial worker count and a hard concurrency ceiling.
 *
 * The initial value is intentionally conservative. Runtime throughput decides
 * whether the stream is allowed to ramp toward the ceiling.
 */
public final class AdaptivePolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    /** Backward-compatible alias used by older callers/tests. */
    public int workers(long contentLength, Conditions conditions) {
        return initialWorkers(contentLength, conditions);
    }

    public int initialWorkers(long contentLength, Conditions conditions) {
        int ceiling = maxWorkers(contentLength, conditions);
        if (ceiling <= 1) {
            return 1;
        }
        if (ceiling == 2) {
            return 2;
        }

        Conditions c = normalize(conditions);

        // Small/medium transfers do not live long enough to justify a large
        // connection ramp. Large transfers start at four unless the link hint
        // strongly suggests a high-bandwidth path.
        int initial = contentLength >= 256L * MIB ? 4 : 2;

        if (contentLength >= 4L * GIB
                && c.downstreamKbps >= 500_000
                && !c.metered
                && !c.powerSave
                && c.thermalStatus < 3) {
            initial = 8;
        }

        return Math.min(initial, ceiling);
    }

    public int maxWorkers(long contentLength, Conditions conditions) {
        if (contentLength <= 0L || contentLength < 8L * MIB) {
            return 1;
        }

        Conditions c = normalize(conditions);

        int sizeCap;
        if (contentLength < 32L * MIB) {
            sizeCap = 2;
        } else if (contentLength < 256L * MIB) {
            sizeCap = 4;
        } else if (contentLength < 1L * GIB) {
            sizeCap = 8;
        } else if (contentLength < 4L * GIB) {
            sizeCap = 16;
        } else if (contentLength < 16L * GIB) {
            sizeCap = 32;
        } else {
            sizeCap = 64;
        }

        int networkCap;
        switch (c.networkKind) {
            case WIFI:
            case ETHERNET:
                networkCap = 64;
                break;
            case CELLULAR:
            case VPN:
                networkCap = 32;
                break;
            case UNKNOWN:
            default:
                networkCap = 16;
                break;
        }

        // LinkProperties/NetworkCapabilities bandwidth is only a hint, but it
        // is useful as an upper bound. Runtime throughput still controls ramp.
        int linkCap = 64;
        if (c.downstreamKbps > 0) {
            if (c.downstreamKbps < 10_000) {
                linkCap = 2;
            } else if (c.downstreamKbps < 25_000) {
                linkCap = 4;
            } else if (c.downstreamKbps < 50_000) {
                linkCap = 8;
            } else if (c.downstreamKbps < 100_000) {
                linkCap = 16;
            } else if (c.downstreamKbps < 250_000) {
                linkCap = 32;
            }
        }

        int cap = Math.min(sizeCap, Math.min(networkCap, linkCap));

        if (c.metered) {
            cap = Math.min(cap, 8);
        }
        if (c.powerSave) {
            cap = Math.min(cap, 4);
        }
        if (c.thermalStatus >= 4) {
            cap = Math.min(cap, 2);
        } else if (c.thermalStatus >= 3) {
            cap = Math.min(cap, 8);
        }

        return Math.max(1, cap);
    }

    private static Conditions normalize(Conditions conditions) {
        return conditions == null
                ? new Conditions(
                        Conditions.NetworkKind.UNKNOWN,
                        true,
                        false,
                        0,
                        0)
                : conditions;
    }
}
