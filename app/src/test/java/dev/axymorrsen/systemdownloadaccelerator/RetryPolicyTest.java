package dev.axymorrsen.systemdownloadaccelerator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public final class RetryPolicyTest {
    @Test
    public void retriesTransientHttpErrors() {
        assertTrue(RetryPolicy.shouldRetry(
                new RangeHttpException(503, -1L),
                1,
                5));
        assertTrue(RetryPolicy.shouldRetry(
                new RangeHttpException(429, 2_000L),
                2,
                5));
    }

    @Test
    public void doesNotRetryPermanentHttpErrors() {
        assertFalse(RetryPolicy.shouldRetry(
                new RangeHttpException(404, -1L),
                1,
                5));
        assertFalse(RetryPolicy.shouldRetry(
                new RangeHttpException(416, -1L),
                1,
                5));
    }

    @Test
    public void retriesOrdinaryIoFailuresWithinBudget() {
        assertTrue(RetryPolicy.shouldRetry(
                new IOException("reset"),
                1,
                5));
        assertFalse(RetryPolicy.shouldRetry(
                new IOException("reset"),
                5,
                5));
    }

    @Test
    public void retryAfterCanExtendLocalBackoff() {
        long delay = RetryPolicy.delayMillis(
                1,
                new RangeHttpException(503, 5_000L),
                123L);
        assertTrue(delay >= 5_000L);
        assertTrue(delay <= 30_000L);
    }
}
