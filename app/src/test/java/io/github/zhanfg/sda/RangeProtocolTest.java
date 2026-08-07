package io.github.zhanfg.sda;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RangeProtocolTest {
    @Test
    public void freshHttp200IsAcceptedOnlyWhenNoRangeWasRequested() {
        RangeProtocol.BaseWindow fresh = RangeProtocol.resolveBaseWindow(
                200, true, null, null, 1000L);
        assertTrue(fresh.accepted);
        assertEquals(0L, fresh.current);
        assertEquals(1000L, fresh.total);

        RangeProtocol.BaseWindow downgraded = RangeProtocol.resolveBaseWindow(
                200, true, "bytes=500-", null, 1000L);
        assertFalse(downgraded.accepted);
        assertTrue(downgraded.reason.contains("downgraded"));
    }

    @Test
    public void unknownOriginalRangeStateFailsClosed() {
        RangeProtocol.BaseWindow window = RangeProtocol.resolveBaseWindow(
                200, false, null, null, 1000L);
        assertFalse(window.accepted);
        assertTrue(window.reason.contains("unavailable"));
    }

    @Test
    public void resumed206MustMatchOriginalRequestStart() {
        RangeProtocol.BaseWindow ok = RangeProtocol.resolveBaseWindow(
                206, true, "bytes=500-", "bytes 500-999/1000", 500L);
        assertTrue(ok.accepted);
        assertEquals(500L, ok.current);
        assertEquals(1000L, ok.total);

        RangeProtocol.BaseWindow mismatch = RangeProtocol.resolveBaseWindow(
                206, true, "bytes=400-", "bytes 500-999/1000", 500L);
        assertFalse(mismatch.accepted);
        assertTrue(mismatch.reason.contains("offset mismatch"));
    }

    @Test
    public void unsolicitedOrMalformed206FailsClosed() {
        assertFalse(RangeProtocol.resolveBaseWindow(
                206, true, null, "bytes 0-99/100", 100L).accepted);
        assertFalse(RangeProtocol.resolveBaseWindow(
                206, true, "bytes=0-", "garbage", 100L).accepted);
        assertFalse(RangeProtocol.resolveBaseWindow(
                206, true, "bytes=0-10,20-30", "bytes 0-10/100", 11L).accepted);
    }

    @Test
    public void rangeHeaderParserRejectsAmbiguousRanges() {
        assertEquals(Long.valueOf(42L), RangeProtocol.parseSingleRangeStart("bytes=42-"));
        assertEquals(Long.valueOf(42L), RangeProtocol.parseSingleRangeStart("bytes = 42-99"));
        assertNull(RangeProtocol.parseSingleRangeStart("bytes=99-42"));
        assertNull(RangeProtocol.parseSingleRangeStart("bytes=0-1,4-5"));
    }

    @Test
    public void contentRangeParserRequiresExactValidGeometry() {
        RangeProtocol.ContentRange range = RangeProtocol.parseContentRange("bytes 10-19/100");
        assertNotNull(range);
        assertEquals(10L, range.start);
        assertEquals(19L, range.end);
        assertEquals(100L, range.total);

        assertNull(RangeProtocol.parseContentRange("bytes 10-100/100"));
        assertNull(RangeProtocol.parseContentRange("bytes */100"));
    }

    @Test
    public void schedulerNeverExceedsChunksOrHardLimit() {
        long mib = 1024L * 1024L;
        assertEquals(4, RangeProtocol.chooseThreads(64L * mib, 4, 16, 16L * mib));
        assertEquals(4, RangeProtocol.chooseThreads(512L * mib, 4, 16, 16L * mib));
        assertEquals(2, RangeProtocol.chooseThreads(20L * mib, 8, 16, 10L * mib));
        assertEquals(16, RangeProtocol.chooseThreads(16L * 1024L * mib, 16, 16, 64L * mib));
    }

    @Test
    public void chunkBoundsAreContiguousAndClamped() {
        RangeProtocol.ChunkBounds first = RangeProtocol.chunkBounds(100L, 350L, 100L, 0);
        RangeProtocol.ChunkBounds second = RangeProtocol.chunkBounds(100L, 350L, 100L, 1);
        RangeProtocol.ChunkBounds last = RangeProtocol.chunkBounds(100L, 350L, 100L, 2);

        assertEquals(100L, first.start);
        assertEquals(199L, first.end);
        assertEquals(200L, second.start);
        assertEquals(299L, second.end);
        assertEquals(300L, last.start);
        assertEquals(349L, last.end);
        assertEquals(50L, last.length());
    }
}
