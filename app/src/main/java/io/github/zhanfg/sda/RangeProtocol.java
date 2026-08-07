package io.github.zhanfg.sda;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java HTTP Range invariants shared by the runtime and JVM tests. */
final class RangeProtocol {
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REQUEST_RANGE = Pattern.compile(
            "bytes\\s*=\\s*(\\d+)-(\\d*)",
            Pattern.CASE_INSENSITIVE
    );

    private RangeProtocol() { }

    static BaseWindow resolveBaseWindow(int responseCode,
                                        boolean requestRangeKnown,
                                        String requestRangeHeader,
                                        String contentRangeHeader,
                                        long contentLength) {
        if (!requestRangeKnown) {
            return BaseWindow.reject("original Range request state unavailable");
        }

        String requestRange = trim(requestRangeHeader);
        if (responseCode == 206) {
            if (requestRange == null) {
                return BaseWindow.reject("HTTP 206 without an original Range request");
            }
            Long requestedStart = parseSingleRangeStart(requestRange);
            if (requestedStart == null) {
                return BaseWindow.reject("unsupported original Range request: " + requestRange);
            }
            ContentRange responseRange = parseContentRange(contentRangeHeader);
            if (responseRange == null) {
                return BaseWindow.reject("invalid base Content-Range");
            }
            if (responseRange.start != requestedStart) {
                return BaseWindow.reject("resume offset mismatch: requested "
                        + requestedStart + ", response " + responseRange.start);
            }
            return BaseWindow.accept(responseRange.start, responseRange.total);
        }

        if (responseCode == 200) {
            if (requestRange != null) {
                return BaseWindow.reject("resume Range request downgraded to HTTP 200");
            }
            if (contentLength <= 0L) {
                return BaseWindow.reject("unknown content length");
            }
            return BaseWindow.accept(0L, contentLength);
        }

        return BaseWindow.reject("HTTP " + responseCode);
    }

    static Long parseSingleRangeStart(String header) {
        String value = trim(header);
        if (value == null || value.indexOf(',') >= 0) return null;
        Matcher matcher = REQUEST_RANGE.matcher(value);
        if (!matcher.matches()) return null;
        try {
            long start = Long.parseLong(matcher.group(1));
            String endText = matcher.group(2);
            if (start < 0L) return null;
            if (endText != null && !endText.isEmpty()) {
                long end = Long.parseLong(endText);
                if (end < start) return null;
            }
            return start;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static ContentRange parseContentRange(String header) {
        String value = trim(header);
        if (value == null) return null;
        Matcher matcher = CONTENT_RANGE.matcher(value);
        if (!matcher.matches() || "*".equals(matcher.group(3))) return null;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));
            if (start < 0L || end < start || total <= end) return null;
            return new ContentRange(start, end, total);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static int chooseThreads(long remaining, int initialThreads, int maxThreads, long chunkSize) {
        if (remaining <= 0L || chunkSize <= 0L) return 1;
        int automatic;
        if (remaining < 256L * 1024L * 1024L) automatic = 2;
        else if (remaining < 1024L * 1024L * 1024L) automatic = 4;
        else if (remaining < 4L * 1024L * 1024L * 1024L) automatic = 6;
        else automatic = 8;

        int requested = Math.max(initialThreads, automatic);
        long chunks = Math.max(1L, (remaining + chunkSize - 1L) / chunkSize);
        return (int) Math.max(1L, Math.min(Math.min(maxThreads, requested), chunks));
    }

    static ChunkBounds chunkBounds(long current, long total, long chunkSize, int index) {
        if (current < 0L || total <= current || chunkSize <= 0L || index < 0) {
            throw new IllegalArgumentException("invalid chunk geometry");
        }
        long relativeStart;
        try {
            relativeStart = Math.multiplyExact((long) index, chunkSize);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("chunk offset overflow", overflow);
        }
        long start;
        try {
            start = Math.addExact(current, relativeStart);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("absolute chunk offset overflow", overflow);
        }
        if (start >= total) throw new IllegalArgumentException("chunk starts beyond resource");
        long end = Math.min(total - 1L, start + Math.min(chunkSize - 1L, total - 1L - start));
        return new ChunkBounds(start, end);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static final class BaseWindow {
        final boolean accepted;
        final String reason;
        final long current;
        final long total;

        private BaseWindow(boolean accepted, String reason, long current, long total) {
            this.accepted = accepted;
            this.reason = reason;
            this.current = current;
            this.total = total;
        }

        static BaseWindow accept(long current, long total) {
            if (current < 0L || total <= current) return reject("invalid response byte window");
            return new BaseWindow(true, null, current, total);
        }

        static BaseWindow reject(String reason) {
            return new BaseWindow(false, reason, 0L, 0L);
        }
    }

    static final class ContentRange {
        final long start;
        final long end;
        final long total;

        ContentRange(long start, long end, long total) {
            this.start = start;
            this.end = end;
            this.total = total;
        }
    }

    static final class ChunkBounds {
        final long start;
        final long end;

        ChunkBounds(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start + 1L;
        }
    }
}
