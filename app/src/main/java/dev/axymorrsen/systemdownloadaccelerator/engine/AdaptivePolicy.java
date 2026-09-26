package dev.axymorrsen.systemdownloadaccelerator.engine;

/**
 * Conservative first-pass worker policy.
 *
 * The result is only a ceiling. A future throughput controller may reduce the
 * worker count when one connection already saturates the path.
 */
public final class AdaptivePolicy {
    private static final long MIB = 1024L * 1024L;

    public int workers(long contentLength, Conditions conditions) {
        if (contentLength <= 0L || contentLength < 8L * MIB) {
            return 1;
        }

        Conditions c = conditions == null
                ? new Conditions(Conditions.NetworkKind.UNKNOWN, true, false, 0)
                : conditions;

        if (c.powerSave || c.thermalStatus >= 4 || c.metered) {
            return 2;
        }

        int networkCap;
        switch (c.networkKind) {
            case WIFI:
            case ETHERNET:
                networkCap = 8;
                break;
            case CELLULAR:
                networkCap = 6;
                break;
            case VPN:
                networkCap = 4;
                break;
            case UNKNOWN:
            default:
                networkCap = 4;
                break;
        }

        if (c.thermalStatus >= 3) {
            networkCap = Math.min(networkCap, 4);
        }

        if (contentLength < 32L * MIB) {
            return Math.min(networkCap, 2);
        }
        if (contentLength < 256L * MIB) {
            return Math.min(networkCap, 4);
        }
        return networkCap;
    }
}
