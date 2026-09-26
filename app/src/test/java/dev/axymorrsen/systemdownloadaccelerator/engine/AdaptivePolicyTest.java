package dev.axymorrsen.systemdownloadaccelerator.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdaptivePolicyTest {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private final AdaptivePolicy policy = new AdaptivePolicy();

    @Test
    public void sixteenGiBFastWifiCanReachSixtyFourWorkers() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.WIFI,
                false,
                false,
                0,
                1_000_000);
        assertEquals(
                64,
                policy.maxWorkers(16L * GIB, conditions));
        assertEquals(
                16,
                policy.initialWorkers(16L * GIB, conditions));
    }

    @Test
    public void largeUnmeteredVpnCanReachSixtyFourWorkers() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.VPN,
                false,
                false,
                0,
                1_000_000);
        assertEquals(
                64,
                policy.maxWorkers(32L * GIB, conditions));
    }

    @Test
    public void linkHintIsNotAHardCeiling() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.WIFI,
                false,
                false,
                0,
                50_000);
        assertEquals(
                64,
                policy.maxWorkers(32L * GIB, conditions));
        assertEquals(
                4,
                policy.initialWorkers(32L * GIB, conditions));
    }

    @Test
    public void meteredNetworkIsCappedAtEightWorkers() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.CELLULAR,
                true,
                false,
                0,
                500_000);
        assertEquals(
                8,
                policy.maxWorkers(32L * GIB, conditions));
    }

    @Test
    public void severeThermalStateIsCappedAtTwoWorkers() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.WIFI,
                false,
                false,
                4,
                1_000_000);
        assertEquals(
                2,
                policy.maxWorkers(32L * GIB, conditions));
    }

    @Test
    public void smallFilesStaySingleConnection() {
        Conditions conditions = new Conditions(
                Conditions.NetworkKind.WIFI,
                false,
                false,
                0,
                1_000_000);
        assertEquals(
                1,
                policy.maxWorkers(4L * MIB, conditions));
    }
}
