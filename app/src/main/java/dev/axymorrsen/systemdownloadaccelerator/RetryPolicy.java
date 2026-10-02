package dev.axymorrsen.systemdownloadaccelerator;

import java.io.IOException;
import java.io.InterruptedIOException;

/** Part-level retry classification with bounded exponential backoff + jitter. */
final class RetryPolicy {
    private static final long BASE_DELAY_MS = 300L;
    private static final long MAX_BACKOFF_MS = 8_000L;
    private static final long MAX_SERVER_DELAY_MS = 30_000L;
    private static final int JITTER_WINDOW_MS = 251;

    private RetryPolicy() {}

    static boolean shouldRetry(
            Throwable error,
            int attempt,
            int maxAttempts) {
        if (error == null || attempt >= maxAttempts) {
            return false;
        }
        if (error instanceof InterruptedException
                || error instanceof InterruptedIOException) {
            return false;
        }

        RangeHttpException http = findHttp(error);
        if (http != null) {
            switch (http.statusCode) {
                case 408:
                case 425:
                case 429:
                case 500:
                case 502:
                case 503:
                case 504:
                    return true;
                default:
                    return false;
            }
        }

        return findIo(error) != null;
    }

    static long delayMillis(
            int attempt,
            Throwable error,
            long salt) {
        int shift = Math.max(0, Math.min(5, attempt - 1));
        long backoff = Math.min(
                MAX_BACKOFF_MS,
                BASE_DELAY_MS << shift);

        long mixed = salt
                ^ ((long) attempt * 0x9E3779B97F4A7C15L)
                ^ (error == null
                ? 0L
                : error.getClass().getName().hashCode());
        long jitter = Math.floorMod(mixed, (long) JITTER_WINDOW_MS);

        long local = Math.min(
                MAX_BACKOFF_MS,
                backoff + jitter);

        RangeHttpException http = findHttp(error);
        if (http != null && http.retryAfterMs >= 0L) {
            return Math.min(
                    MAX_SERVER_DELAY_MS,
                    Math.max(local, http.retryAfterMs));
        }
        return local;
    }

    static boolean isIntegrityFailure(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null
                    && (message.contains("ETag changed")
                    || message.contains("Last-Modified changed")
                    || message.contains("bad Content-Range")
                    || message.contains("checkpoint base"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static RangeHttpException findHttp(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof RangeHttpException) {
                return (RangeHttpException) cursor;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private static IOException findIo(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof IOException) {
                return (IOException) cursor;
            }
            cursor = cursor.getCause();
        }
        return null;
    }
}
