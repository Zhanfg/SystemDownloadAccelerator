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

    public Conditions(
            NetworkKind networkKind,
            boolean metered,
            boolean powerSave,
            int thermalStatus) {
        this.networkKind = networkKind == null ? NetworkKind.UNKNOWN : networkKind;
        this.metered = metered;
        this.powerSave = powerSave;
        this.thermalStatus = Math.max(0, thermalStatus);
    }
}
