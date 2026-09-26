package dev.axymorrsen.systemdownloadaccelerator.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RangeProbeTest {
    @Test
    public void validStrongEtagProbeIsEligible() {
        RangeProbe.Result result = RangeProbe.parse(
                206,
                "bytes 0-0/104857600",
                1L,
                "\"abc123\"",
                null,
                "identity");

        assertTrue(result.eligible);
        assertEquals(104857600L, result.totalBytes);
        assertEquals(RangeProbe.ValidatorKind.STRONG_ETAG, result.validatorKind);
    }

    @Test
    public void validLastModifiedFallbackIsEligible() {
        RangeProbe.Result result = RangeProbe.parse(
                206,
                "bytes 0-0/4096",
                1L,
                null,
                "Sat, 26 Sep 2026 10:00:00 GMT",
                null);

        assertTrue(result.eligible);
        assertEquals(RangeProbe.ValidatorKind.LAST_MODIFIED, result.validatorKind);
    }

    @Test
    public void status200DoesNotProveRangeSupport() {
        RangeProbe.Result result = RangeProbe.parse(
                200, null, 100L, "\"abc\"", null, "identity");

        assertFalse(result.eligible);
        assertEquals(RangeProbe.Reason.NOT_PARTIAL, result.reason);
    }

    @Test
    public void weakEtagWithoutDateIsRejected() {
        RangeProbe.Result result = RangeProbe.parse(
                206,
                "bytes 0-0/4096",
                1L,
                "W/\"abc\"",
                null,
                "identity");

        assertFalse(result.eligible);
        assertEquals(RangeProbe.Reason.NO_STABLE_VALIDATOR, result.reason);
    }

    @Test
    public void encodedProbeIsRejected() {
        RangeProbe.Result result = RangeProbe.parse(
                206,
                "bytes 0-0/4096",
                1L,
                "\"abc\"",
                null,
                "gzip");

        assertFalse(result.eligible);
        assertEquals(RangeProbe.Reason.CONTENT_ENCODED, result.reason);
    }

    @Test
    public void wildcardTotalIsRejected() {
        RangeProbe.Result result = RangeProbe.parse(
                206,
                "bytes 0-0/*",
                1L,
                "\"abc\"",
                null,
                "identity");

        assertFalse(result.eligible);
        assertEquals(RangeProbe.Reason.UNKNOWN_TOTAL, result.reason);
    }
}
