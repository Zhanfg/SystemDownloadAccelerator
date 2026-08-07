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
    // Conservative client-side strength threshold for Last-Modified. RFC 9110 allows a client
    // to treat a date validator as strong when the response Date is sufficiently later; keeping
    // the historical 60-second guard also protects against coarse timestamps and clock skew.
    private static final long STRONG_LAST_MODIFIED_GAP_MS = 60_000L;

    private RangeProtocol() { }

    static BaseWindow resolveBaseWindow(int responseCode,
                                        boolean requestRangeKnown,
                                        String requestRangeHeader,
                                        String contentRangeHeader,
                                        long contentLength) {
        if (!requestRangeKnown) {
            return BaseWindow.reject("original Range request state unavailable");
        }

        String requestRangeHeaderValue = trim(requestRangeHeader);
        if (responseCode == 206) {
            if (requestRangeHeaderValue == null) {
                return BaseWindow.reject("HTTP 206 without an original Range request");
            }
            RequestRange requestRange = parseSingleRange(requestRangeHeaderValue);
            if (requestRange == null) {
                return BaseWindow.reject("unsupported original Range request: "
                        + requestRangeHeaderValue);
            }

            ContentRange responseRange = parseContentRange(contentRangeHeader);
            if (responseRange == null) {
                return BaseWindow.reject("invalid base Content-Range");
            }
            if (responseRange.start != requestRange.start) {
                return BaseWindow.reject("resume offset mismatch: requested "
                        + requestRange.start + ", response " + responseRange.start);
            }
            if (!requestRange.openEnded
                    && (requestRange.end == null || responseRange.end != requestRange.end)) {
                return BaseWindow.reject("bounded original Range response mismatch");
            }
            if (responseRange.end != responseRange.total - 1L) {
                return BaseWindow.reject(requestRange.openEnded
                        ? "base Content-Range does not reach resource end"
                        : "bounded original Range does not reach resource end");
            }
            long expectedLength = responseRange.length();
            if (contentLength > 0L && contentLength != expectedLength) {
                return BaseWindow.reject("base response length mismatch: "
                        + contentLength + "/" + expectedLength);
            }
            return BaseWindow.accept(responseRange.start, responseRange.total);
        }

        if (responseCode == 200) {
            if (requestRangeHeaderValue != null) {
                return BaseWindow.reject("resume Range request downgraded to HTTP 200");
            }
            if (contentLength <= 0L) {
                return BaseWindow.reject("unknown content length");
            }
            return BaseWindow.accept(0L, contentLength);
        }

        return BaseWindow.reject("HTTP " + responseCode);
    }

    static String validateDestinationOffset(long expected, Long actual) {
        if (expected < 0L) return "invalid expected destination offset";
        if (actual == null || actual < 0L) return "destination offset unavailable";
        if (actual != expected) {
            return "destination offset mismatch: expected " + expected + ", actual " + actual;
        }
        return null;
    }

    static boolean isStrongLastModified(long lastModifiedMillis, long responseDateMillis) {
        if (lastModifiedMillis <= 0L || responseDateMillis <= 0L) return false;
        if (responseDateMillis < lastModifiedMillis) return false;
        return responseDateMillis - lastModifiedMillis >= STRONG_LAST_MODIFIED_GAP_MS;
    }

    static Long parseSingleRangeStart(String header) {
        RequestRange range = parseSingleRange(header);
        return range == null ? null : range.start;
    }

    static RequestRange parseSingleRange(String header) {
        String value = trim(header);
        if (value == null || value.indexOf(',') >= 0) return null;
        Matcher matcher = REQUEST_RANGE.matcher(value);
        if (!matcher.matches()) return null;
        try {
            long start = Long.parseLong(matcher.group(1));
            String endText = matcher.group(2);
            if (start < 0L) return null;
            if (endText == null || endText.isEmpty()) {
                return new RequestRange(start, null, true);
            }
            long end = Long.parseLong(endText);
            if (end < start) return null;
            return new RequestRange(start, end, false);
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
        long chunks;
        try {
            chunks = Math.max(1L, Math.addExact(remaining, chunkSize - 1L) / chunkSize);
        } catch (ArithmeticException overflow) {
            chunks = Long.MAX_VALUE;
        }
        return (int) Math.max(1L, Math.min(Math.min(maxThreads, requested), chunks));
    }

    static SpoolPlan planSpool(int chunkCount,
                               int plannedWorkers,
                               long chunkSize,
                               long maxWindowBytes) {
        if (chunkCount <= 0 || plannedWorkers <= 0 || chunkSize <= 0L || maxWindowBytes <= 0L) {
            return SpoolPlan.reject("invalid spool geometry");
        }

        long budgetSlotsLong = maxWindowBytes / chunkSize;
        if (budgetSlotsLong < 2L) {
            return SpoolPlan.reject("spool budget cannot hold two chunks");
        }
        int budgetSlots = (int) Math.min(Integer.MAX_VALUE, budgetSlotsLong);
        int workers = Math.min(Math.min(plannedWorkers, chunkCount), budgetSlots);
        if (workers < 2) {
            return SpoolPlan.reject("spool budget reduced worker count below two");
        }

        long targetWindow = Math.max((long) workers + 1L, (long) workers * 2L);
        int window = (int) Math.min(
                Math.min((long) chunkCount, targetWindow),
                (long) budgetSlots);
        if (window < workers) {
            return SpoolPlan.reject("spool window smaller than worker count");
        }

        long windowBytes;
        try {
            windowBytes = Math.multiplyExact((long) window, chunkSize);
        } catch (ArithmeticException overflow) {
            return SpoolPlan.reject("spool byte budget overflow");
        }
        if (windowBytes > maxWindowBytes) {
            return SpoolPlan.reject("spool window exceeds hard byte budget");
        }
        return SpoolPlan.accept(workers, window, windowBytes);
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

    static final class RequestRange {
        final long start;
        final Long end;
        final boolean openEnded;

        RequestRange(long start, Long end, boolean openEnded) {
            this.start = start;
            this.end = end;
            this.openEnded = openEnded;
        }
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

        long length() {
            return end - start + 1L;
        }
    }

    static final class SpoolPlan {
        final boolean accepted;
        final String reason;
        final int workers;
        final int windowChunks;
        final long windowBytes;

        private SpoolPlan(boolean accepted, String reason,
                          int workers, int windowChunks, long windowBytes) {
            this.accepted = accepted;
            this.reason = reason;
            this.workers = workers;
            this.windowChunks = windowChunks;
            this.windowBytes = windowBytes;
        }

        static SpoolPlan accept(int workers, int windowChunks, long windowBytes) {
            return new SpoolPlan(true, null, workers, windowChunks, windowBytes);
        }

        static SpoolPlan reject(String reason) {
            return new SpoolPlan(false, reason, 0, 0, 0L);
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
