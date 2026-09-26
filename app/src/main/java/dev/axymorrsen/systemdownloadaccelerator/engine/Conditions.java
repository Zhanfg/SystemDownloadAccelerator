package dev.axymorrsen.systemdownloadaccelerator.engine;

/** Snapshot consumed by the adaptive segment scheduler. */
public final class Conditions {
    public enum NetworkKind {
        WIFI,
        CELLULAR,
        ETHERNET,
        VPN,
        UNKNOWN
    }

    public final NetworkKind networkKind;
    public final boolean metered;
    public final boolean powerSave;
    public final int thermalStatus;
    /** Android link-bandwidth hint. Zero means unavailable/unknown. */
    public final int downstreamKbps;

    public Conditions(
            NetworkKind networkKind,
            boolean metered,
            boolean powerSave,
            int thermalStatus) {
        this(networkKind, metered, powerSave, thermalStatus, 0);
    }

    public Conditions(
            NetworkKind networkKind,
            boolean metered,
            boolean powerSave,
            int thermalStatus,
            int downstreamKbps) {
        this.networkKind = networkKind == null
                ? NetworkKind.UNKNOWN
                : networkKind;
        this.metered = metered;
        this.powerSave = powerSave;
        this.thermalStatus = Math.max(0, thermalStatus);
        this.downstreamKbps = Math.max(0, downstreamKbps);
    }
}
