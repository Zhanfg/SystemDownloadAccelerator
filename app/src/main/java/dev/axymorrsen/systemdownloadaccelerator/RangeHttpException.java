package dev.axymorrsen.systemdownloadaccelerator;

import java.io.IOException;

/** HTTP error returned by a byte-range request. */
final class RangeHttpException extends IOException {
    final int statusCode;
    final long retryAfterMs;

    RangeHttpException(int statusCode, long retryAfterMs) {
        super("Range HTTP " + statusCode);
        this.statusCode = statusCode;
        this.retryAfterMs = Math.max(-1L, retryAfterMs);
    }
}
