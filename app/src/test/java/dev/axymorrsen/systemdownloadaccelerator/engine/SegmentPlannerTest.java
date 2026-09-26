package dev.axymorrsen.systemdownloadaccelerator.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class SegmentPlannerTest {
    @Test
    public void planCoversWholeFileWithoutGaps() {
        long size = 2L * 1024L * 1024L * 1024L + 17L;
        List<Segment> segments = new SegmentPlanner().plan(size, 8);

        assertEquals(8, segments.size());
        assertEquals(0L, segments.get(0).startInclusive);
        assertEquals(size - 1L, segments.get(segments.size() - 1).endInclusive);

        long covered = 0L;
        long next = 0L;
        for (Segment segment : segments) {
            assertEquals(next, segment.startInclusive);
            assertTrue(segment.endInclusive >= segment.startInclusive);
            covered += segment.length();
            next = segment.endInclusive + 1L;
        }
        assertEquals(size, covered);
    }

    @Test
    public void workersNeverCreateEmptySegments() {
        List<Segment> segments = new SegmentPlanner().plan(3L, 8);
        assertEquals(3, segments.size());
        for (Segment segment : segments) {
            assertEquals(1L, segment.length());
        }
    }
}
