package dev.axymorrsen.systemdownloadaccelerator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class MicroPartPlannerTest {
    @Test
    public void sixteenMiBWithTwoWorkersCreatesEightBalancedParts() {
        long size = 16L * 1024L * 1024L;
        List<RangePart> parts = MicroPartPlanner.plan(0L, size, 2);
        assertEquals(8, parts.size());
        assertEquals(0L, parts.get(0).from);
        assertEquals(size - 1L, parts.get(parts.size() - 1).to);
    }

    @Test
    public void resumedPlanStartsAtResumeOffset() {
        long total = 100L * 1024L * 1024L;
        long start = 13L * 1024L * 1024L;
        List<RangePart> parts = MicroPartPlanner.plan(start, total, 4);
        assertEquals(start, parts.get(0).from);
        assertEquals(total - 1L, parts.get(parts.size() - 1).to);
    }

    @Test
    public void rangesAreGapFreeAndNonOverlapping() {
        List<RangePart> parts = MicroPartPlanner.plan(17L, 9999999L, 8);
        long cursor = 17L;
        for (RangePart part : parts) {
            assertEquals(cursor, part.from);
            assertTrue(part.length() > 0L);
            cursor = part.to + 1L;
        }
        assertEquals(9999999L, cursor);
    }
}
