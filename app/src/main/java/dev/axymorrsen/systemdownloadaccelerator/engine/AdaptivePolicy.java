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

        // Start conservatively. Android's downstreamKbps is only a hint and
        // is especially unreliable for VPN/tunnel stacks. Real delivered
        // throughput decides whether we ramp later.
        int initial;
        switch (c.networkKind) {
            case VPN:
            case CELLULAR:
                initial = 2;
                break;
            case WIFI:
            case ETHERNET:
                initial = contentLength >= 256L * MIB ? 4 : 2;
                break;
            case UNKNOWN:
            default:
                initial = 2;
                break;
        }

        if (c.metered || c.powerSave || c.thermalStatus >= 3) {
            initial = Math.min(initial, 2);
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
            case VPN:
                // VPN itself is not a reason to hard-cap a fast unmetered
                // underlay. Runtime throughput/thermal guards decide whether
                // higher concurrency is useful.
                networkCap = 64;
                break;
            case CELLULAR:
                networkCap = 32;
                break;
            case UNKNOWN:
            default:
                networkCap = 16;
                break;
        }

        /*
         * Android's downstreamKbps is an estimate, not a measured application
         * throughput. Treating it as a hard ceiling can suppress a link that
         * is actually much faster (especially VPNs and vendor stacks).
         * Runtime throughput is the authoritative ramp signal.
         */
        int cap = Math.min(sizeCap, networkCap);

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
