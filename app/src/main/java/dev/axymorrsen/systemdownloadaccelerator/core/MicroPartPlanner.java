package dev.axymorrsen.systemdownloadaccelerator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic aligned micro-part planner.
 *
 * Boundaries depend on the entity length, not the current worker count. Stable
 * boundaries let a restarted DownloadProvider reuse prefetched chunks even when
 * the native resume offset or network policy changed.
 */
public final class MicroPartPlanner {
    public static final long DEFAULT_MIN_PART_BYTES = 1024L * 1024L;
    public static final long MIB = 1024L * 1024L;
    public static final long GIB = 1024L * MIB;
    public static final int DEFAULT_PARTS_PER_WORKER = 2;
    public static final int ABSOLUTE_MAX_PARTS = 8192;

    private MicroPartPlanner() {}

    public static List<RangePart> plan(
            long startOffset,
            long totalLength,
            int workers) {
        return plan(
                startOffset,
                totalLength,
                workers,
                DEFAULT_MIN_PART_BYTES,
                DEFAULT_PARTS_PER_WORKER);
    }

    public static List<RangePart> plan(
            long startOffset,
            long totalLength,
            int workers,
            long minPartBytes,
            int partsPerWorker) {
        if (startOffset < 0L || totalLength <= startOffset) {
            return Collections.emptyList();
        }
        if (workers < 1) throw new IllegalArgumentException("workers < 1");
        if (minPartBytes < 1L) throw new IllegalArgumentException("minPartBytes < 1");
        if (partsPerWorker < 1) throw new IllegalArgumentException("partsPerWorker < 1");

        long chunkBytes = Math.max(
                stableChunkBytes(totalLength),
                minPartBytes);
        chunkBytes = Math.max(
                chunkBytes,
                ceilDiv(totalLength, ABSOLUTE_MAX_PARTS));

        List<RangePart> parts = new ArrayList<>();
        long cursor = startOffset;
        int index = 0;

        while (cursor < totalLength) {
            long bucket = cursor / chunkBytes;
            long boundary;
            if (bucket >= Long.MAX_VALUE / chunkBytes - 1L) {
                boundary = totalLength;
            } else {
                boundary = (bucket + 1L) * chunkBytes;
            }

            long endExclusive = Math.min(totalLength, boundary);
            if (endExclusive <= cursor) {
                endExclusive = totalLength;
            }

            parts.add(new RangePart(
                    index++,
                    cursor,
                    endExclusive - 1L));
            cursor = endExclusive;

            if (parts.size() > ABSOLUTE_MAX_PARTS) {
                throw new IllegalStateException("part cap exceeded");
            }
        }

        return parts;
    }

    public static long stableChunkBytes(long totalLength) {
        if (totalLength < 64L * MIB) {
            return 4L * MIB;
        }
        if (totalLength < 256L * MIB) {
            return 8L * MIB;
        }
        if (totalLength < 1L * GIB) {
            return 16L * MIB;
        }
        if (totalLength < 4L * GIB) {
            return 32L * MIB;
        }
        if (totalLength < 16L * GIB) {
            return 64L * MIB;
        }
        return 128L * MIB;
    }

    private static long ceilDiv(long value, long divisor) {
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }
}
