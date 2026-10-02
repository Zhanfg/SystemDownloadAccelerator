package dev.axymorrsen.systemdownloadaccelerator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class MicroPartPlannerTest {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    @Test
    public void sixteenMiBUsesEightStableTwoMiBParts() {
        long size = 16L * MIB;
        List<RangePart> parts =
                MicroPartPlanner.plan(0L, size, 2);
        assertEquals(8, parts.size());
        assertEquals(2L * MIB, parts.get(0).length());
        assertEquals(0L, parts.get(0).from);
        assertEquals(size - 1L, parts.get(7).to);
    }

    @Test
    public void onePointFiveGiBUsesStableTwoMiBParts() {
        long size = 1536L * MIB;
        List<RangePart> parts =
                MicroPartPlanner.plan(0L, size, 16);
        assertEquals(768, parts.size());
        assertEquals(2L * MIB, parts.get(0).length());
        assertEquals(size - 1L, parts.get(parts.size() - 1).to);
    }

    @Test
    public void sixteenGiBHasEnoughChunksForSixtyFourWorkers() {
        long size = 16L * GIB;
        List<RangePart> parts =
                MicroPartPlanner.plan(0L, size, 64);
        assertEquals(8192, parts.size());
        assertEquals(2L * MIB, parts.get(0).length());
        assertTrue(parts.size() <= MicroPartPlanner.ABSOLUTE_MAX_PARTS);
    }

    @Test
    public void resumedHeadEndsAtOriginalStableBoundary() {
        long total = 100L * MIB;
        long start = 13L * MIB;
        List<RangePart> resumed =
                MicroPartPlanner.plan(start, total, 4);
        List<RangePart> original =
                MicroPartPlanner.plan(0L, total, 4);

        long chunk = MicroPartPlanner.stableChunkBytes(total);
        int containingBucket = (int) (start / chunk);

        assertEquals(start, resumed.get(0).from);
        assertEquals(
                original.get(containingBucket).to,
                resumed.get(0).to);
        assertEquals(
                original.get(containingBucket + 1).from,
                resumed.get(1).from);
        assertEquals(
                total - 1L,
                resumed.get(resumed.size() - 1).to);
    }

    @Test
    public void workerCountDoesNotMoveBoundaries() {
        long total = 3L * GIB;
        List<RangePart> a =
                MicroPartPlanner.plan(0L, total, 4);
        List<RangePart> b =
                MicroPartPlanner.plan(0L, total, 32);

        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).from, b.get(i).from);
            assertEquals(a.get(i).to, b.get(i).to);
        }
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
