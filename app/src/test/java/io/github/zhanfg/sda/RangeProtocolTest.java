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
    public void resumed206MustMatchOpenEndedOriginalRequest() {
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
    public void boundedResumeRequestIsAcceptedOnlyWhenItReachesEof() {
        RangeProtocol.BaseWindow fullTail = RangeProtocol.resolveBaseWindow(
                206, true, "bytes=500-999", "bytes 500-999/1000", 500L);
        assertTrue(fullTail.accepted);
        assertEquals(500L, fullTail.current);
        assertEquals(1000L, fullTail.total);

        RangeProtocol.BaseWindow middleWindow = RangeProtocol.resolveBaseWindow(
                206, true, "bytes=500-749", "bytes 500-749/1000", 250L);
        assertFalse(middleWindow.accepted);
        assertTrue(middleWindow.reason.contains("resource end"));

        RangeProtocol.BaseWindow responseMismatch = RangeProtocol.resolveBaseWindow(
                206, true, "bytes=500-999", "bytes 500-899/1000", 400L);
        assertFalse(responseMismatch.accepted);
        assertTrue(responseMismatch.reason.contains("response mismatch"));
    }

    @Test
    public void resumed206MustReachResourceEnd() {
        RangeProtocol.BaseWindow partial = RangeProtocol.resolveBaseWindow(
                206, true, "bytes=500-", "bytes 500-749/1000", 250L);
        assertFalse(partial.accepted);
        assertTrue(partial.reason.contains("resource end"));
    }

    @Test
    public void resumed206ContentLengthMustMatchContentRange() {
        RangeProtocol.BaseWindow mismatch = RangeProtocol.resolveBaseWindow(
                206, true, "bytes=500-", "bytes 500-999/1000", 499L);
        assertFalse(mismatch.accepted);
        assertTrue(mismatch.reason.contains("length mismatch"));

        RangeProtocol.BaseWindow unknownLength = RangeProtocol.resolveBaseWindow(
                206, true, "bytes=500-", "bytes 500-999/1000", -1L);
        assertTrue(unknownLength.accepted);
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

        RangeProtocol.RequestRange open = RangeProtocol.parseSingleRange("bytes=42-");
        assertNotNull(open);
        assertTrue(open.openEnded);
        assertNull(open.end);

        RangeProtocol.RequestRange bounded = RangeProtocol.parseSingleRange("bytes=42-99");
        assertNotNull(bounded);
        assertFalse(bounded.openEnded);
        assertEquals(Long.valueOf(99L), bounded.end);
    }

    @Test
    public void destinationOffsetMustMatchResolvedResumeWindow() {
        assertNull(RangeProtocol.validateDestinationOffset(500L, 500L));
        assertTrue(RangeProtocol.validateDestinationOffset(500L, 400L)
                .contains("mismatch"));
        assertTrue(RangeProtocol.validateDestinationOffset(500L, null)
                .contains("unavailable"));
    }

    @Test
    public void lastModifiedMustBeStrongEnoughForIfRange() {
        long modified = 1_700_000_000_000L;
        assertFalse(RangeProtocol.isStrongLastModified(modified, modified));
        assertFalse(RangeProtocol.isStrongLastModified(modified, modified + 59_999L));
        assertTrue(RangeProtocol.isStrongLastModified(modified, modified + 60_000L));
        assertFalse(RangeProtocol.isStrongLastModified(modified, modified - 1L));
        assertFalse(RangeProtocol.isStrongLastModified(-1L, modified + 60_000L));
        assertFalse(RangeProtocol.isStrongLastModified(modified, -1L));
    }

    @Test
    public void contentRangeParserRequiresExactValidGeometry() {
        RangeProtocol.ContentRange range = RangeProtocol.parseContentRange("bytes 10-19/100");
        assertNotNull(range);
        assertEquals(10L, range.start);
        assertEquals(19L, range.end);
        assertEquals(100L, range.total);
        assertEquals(10L, range.length());

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
        assertEquals(16, RangeProtocol.chooseThreads(Long.MAX_VALUE, 16, 16, 64L * mib));
    }

    @Test
    public void spoolPlanCapsWindowAtIndependentByteBudget() {
        long mib = 1024L * 1024L;
        long budget = 512L * mib;

        RangeProtocol.SpoolPlan extreme = RangeProtocol.planSpool(
                256, 16, 64L * mib, budget);
        assertTrue(extreme.accepted);
        assertEquals(8, extreme.workers);
        assertEquals(8, extreme.windowChunks);
        assertEquals(512L * mib, extreme.windowBytes);

        RangeProtocol.SpoolPlan normal = RangeProtocol.planSpool(
                64, 4, 16L * mib, budget);
        assertTrue(normal.accepted);
        assertEquals(4, normal.workers);
        assertEquals(8, normal.windowChunks);
        assertEquals(128L * mib, normal.windowBytes);
    }

    @Test
    public void spoolPlanFailsWhenBudgetCannotHoldParallelism() {
        long mib = 1024L * 1024L;
        RangeProtocol.SpoolPlan plan = RangeProtocol.planSpool(
                8, 8, 64L * mib, 64L * mib);
        assertFalse(plan.accepted);
        assertTrue(plan.reason.contains("two chunks"));
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
