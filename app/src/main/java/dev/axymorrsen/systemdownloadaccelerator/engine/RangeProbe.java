package dev.axymorrsen.systemdownloadaccelerator.engine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser for a one-byte capability request using "Range: bytes=0-0".
 *
 * A valid 206 response is stronger evidence than an Accept-Ranges header, so the
 * probe deliberately trusts observed protocol behavior instead of server claims.
 */
public final class RangeProbe {
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$",
            Pattern.CASE_INSENSITIVE);

    public enum Reason {
        ELIGIBLE,
        NOT_PARTIAL,
        BAD_CONTENT_RANGE,
        UNKNOWN_TOTAL,
        BAD_PROBE_LENGTH,
        NO_STABLE_VALIDATOR,
        CONTENT_ENCODED
    }

    public enum ValidatorKind {
        STRONG_ETAG,
        LAST_MODIFIED,
        NONE
    }

    public static final class Result {
        public final boolean eligible;
        public final Reason reason;
        public final long totalBytes;
        public final ValidatorKind validatorKind;
        public final String validator;

        private Result(
                boolean eligible,
                Reason reason,
                long totalBytes,
                ValidatorKind validatorKind,
                String validator) {
            this.eligible = eligible;
            this.reason = reason;
            this.totalBytes = totalBytes;
            this.validatorKind = validatorKind;
            this.validator = validator;
        }

        static Result reject(Reason reason) {
            return new Result(false, reason, -1L, ValidatorKind.NONE, null);
        }

        static Result accept(long totalBytes, ValidatorKind kind, String validator) {
            return new Result(true, Reason.ELIGIBLE, totalBytes, kind, validator);
        }
    }

    private RangeProbe() {}

    public static Result parse(
            int statusCode,
            String contentRange,
            long contentLength,
            String etag,
            String lastModified,
            String contentEncoding) {
        if (statusCode != 206) {
            return Result.reject(Reason.NOT_PARTIAL);
        }

        if (contentRange == null) {
            return Result.reject(Reason.BAD_CONTENT_RANGE);
        }

        Matcher matcher = CONTENT_RANGE.matcher(contentRange.trim());
        if (!matcher.matches()) {
            return Result.reject(Reason.BAD_CONTENT_RANGE);
        }

        long start;
        long end;
        long total;
        try {
            start = Long.parseLong(matcher.group(1));
            end = Long.parseLong(matcher.group(2));
            if ("*".equals(matcher.group(3))) {
                return Result.reject(Reason.UNKNOWN_TOTAL);
            }
            total = Long.parseLong(matcher.group(3));
        } catch (NumberFormatException e) {
            return Result.reject(Reason.BAD_CONTENT_RANGE);
        }

        if (start != 0L || end != 0L || total <= 0L) {
            return Result.reject(Reason.BAD_CONTENT_RANGE);
        }

        // For bytes=0-0, a declared response body length must be exactly one byte.
        if (contentLength >= 0L && contentLength != 1L) {
            return Result.reject(Reason.BAD_PROBE_LENGTH);
        }

        if (!isIdentityEncoding(contentEncoding)) {
            return Result.reject(Reason.CONTENT_ENCODED);
        }

        String cleanEtag = trimToNull(etag);
        if (cleanEtag != null && !cleanEtag.regionMatches(true, 0, "W/", 0, 2)) {
            return Result.accept(total, ValidatorKind.STRONG_ETAG, cleanEtag);
        }

        String cleanLastModified = trimToNull(lastModified);
        if (cleanLastModified != null) {
            return Result.accept(total, ValidatorKind.LAST_MODIFIED, cleanLastModified);
        }

        return Result.reject(Reason.NO_STABLE_VALIDATOR);
    }

    private static boolean isIdentityEncoding(String contentEncoding) {
        String value = trimToNull(contentEncoding);
        return value == null || "identity".equals(value.toLowerCase(Locale.ROOT));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
