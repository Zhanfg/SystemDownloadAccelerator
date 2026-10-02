package dev.axymorrsen.systemdownloadaccelerator;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public final class GlobalTransferGovernorTest {
    @After
    public void cleanup() {
        GlobalTransferGovernor.resetForTests();
    }

    @Test
    public void oneSessionCanUseItsRequestedBudget() {
        GlobalTransferGovernor.Lease lease =
                GlobalTransferGovernor.register("example.com", 64);
        assertEquals(48, lease.allowed(64));
        assertEquals(32, lease.allowed(32));
        lease.close();
    }

    @Test
    public void twoHostsShareGlobalBudget() {
        GlobalTransferGovernor.Lease a =
                GlobalTransferGovernor.register("a.example", 64);
        GlobalTransferGovernor.Lease b =
                GlobalTransferGovernor.register("b.example", 64);

        assertEquals(32, a.allowed(64));
        assertEquals(32, b.allowed(64));
    }

    @Test
    public void sameHostAlsoSharesHostBudget() {
        GlobalTransferGovernor.Lease a =
                GlobalTransferGovernor.register("same.example", 64);
        GlobalTransferGovernor.Lease b =
                GlobalTransferGovernor.register("same.example", 64);

        assertEquals(24, a.allowed(64));
        assertEquals(24, b.allowed(64));
    }
}
