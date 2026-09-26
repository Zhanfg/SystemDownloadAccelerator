package dev.axymorrsen.systemdownloadaccelerator.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdaptivePolicyTest {
    private final AdaptivePolicy policy = new AdaptivePolicy();

    @Test
    public void largeWifiDownloadCanUseEightWorkers() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.WIFI, false, false, 0);
        assertEquals(8, policy.workers(1024L * 1024L * 1024L, conditions));
    }

    @Test
    public void meteredNetworkIsCappedAtTwoWorkers() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.CELLULAR, true, false, 0);
        assertEquals(2, policy.workers(1024L * 1024L * 1024L, conditions));
    }

    @Test
    public void highThermalStateIsCappedAtTwoWorkers() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.WIFI, false, false, 4);
        assertEquals(2, policy.workers(1024L * 1024L * 1024L, conditions));
    }

    @Test
    public void smallFilesStaySingleConnection() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.WIFI, false, false, 0);
        assertEquals(1, policy.workers(4L * 1024L * 1024L, conditions));
    }
}
