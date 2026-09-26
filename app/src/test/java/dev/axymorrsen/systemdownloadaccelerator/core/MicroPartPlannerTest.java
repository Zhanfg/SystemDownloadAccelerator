package dev.axymorrsen.systemdownloadaccelerator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class MicroPartPlannerTest {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    @Test
    public void sixteenMiBWithTwoWorkersCreatesFourBalancedParts() {
        long size = 16L * MIB;
        List<RangePart> parts =
                MicroPartPlanner.plan(0L, size, 2);
        assertEquals(4, parts.size());
        assertEquals(0L, parts.get(0).from);
        assertEquals(
                size - 1L,
                parts.get(parts.size() - 1).to);
    }

    @Test
    public void onePointFiveGiBWithSixteenWorkersUsesPipelineSizedParts() {
        long size = 1536L * MIB;
        List<RangePart> parts =
                MicroPartPlanner.plan(0L, size, 16);

        assertEquals(192, parts.size());
        assertEquals(8L * MIB, parts.get(0).length());
        assertEquals(
                size - 1L,
                parts.get(parts.size() - 1).to);
    }

    @Test
    public void sixteenGiBWithSixtyFourWorkersHasEnoughTwoMiBUnits() {
        long size = 16L * GIB;
        List<RangePart> parts =
                MicroPartPlanner.plan(0L, size, 64);

        assertEquals(8192, parts.size());
        assertEquals(2L * MIB, parts.get(0).length());
        assertTrue(parts.size() <= MicroPartPlanner.ABSOLUTE_MAX_PARTS);
        assertEquals(0L, parts.get(0).from);
        assertEquals(
                size - 1L,
                parts.get(parts.size() - 1).to);
    }

    @Test
    public void resumedPlanStartsAtResumeOffset() {
        long total = 100L * MIB;
        long start = 13L * MIB;
        List<RangePart> parts =
                MicroPartPlanner.plan(start, total, 4);
        assertEquals(start, parts.get(0).from);
        assertEquals(
                total - 1L,
                parts.get(parts.size() - 1).to);
    }

    @Test
    public void rangesAreGapFreeAndNonOverlapping() {
        List<RangePart> parts =
                MicroPartPlanner.plan(17L, 9_999_999L, 8);
        long cursor = 17L;
        for (RangePart part : parts) {
            assertEquals(cursor, part.from);
            assertTrue(part.length() > 0L);
            cursor = part.to + 1L;
        }
        assertEquals(9_999_999L, cursor);
    }
}
