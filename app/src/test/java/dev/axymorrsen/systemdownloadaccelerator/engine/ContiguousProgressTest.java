package dev.axymorrsen.systemdownloadaccelerator.engine;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public final class ContiguousProgressTest {
    private final SegmentPlanner planner = new SegmentPlanner();

    @Test
    public void laterCompletedSegmentsDoNotCrossAHole() {
        List<Segment> segments = planner.plan(100L, 4);
        assertEquals(10L, ContiguousProgress.prefix(
                segments,
                new long[] {10L, 25L, 25L, 25L}));
    }

    @Test
    public void prefixAdvancesIntoSecondSegmentAfterFirstCompletes() {
        List<Segment> segments = planner.plan(100L, 4);
        assertEquals(32L, ContiguousProgress.prefix(
                segments,
                new long[] {25L, 7L, 25L, 25L}));
    }

    @Test
    public void fullyCompletedTransferPublishesTotalLength() {
        List<Segment> segments = planner.plan(101L, 4);
        assertEquals(101L, ContiguousProgress.prefix(
                segments,
                new long[] {
                        segments.get(0).length(),
                        segments.get(1).length(),
                        segments.get(2).length(),
                        segments.get(3).length()
                }));
    }

    @Test
    public void excessiveWorkerCountersAreClampedToSegmentLength() {
        List<Segment> segments = planner.plan(64L, 2);
        assertEquals(64L, ContiguousProgress.prefix(
                segments,
                new long[] {999L, 999L}));
    }
}
