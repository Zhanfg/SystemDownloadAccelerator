package dev.axymorrsen.systemdownloadaccelerator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AB-inspired load balancing without mutating in-flight ranges.
 *
 * Workers consume immutable micro-parts from a shared queue. Large files create
 * many more parts than workers so concurrency can ramp at runtime.
 */
public final class MicroPartPlanner {
    public static final long DEFAULT_MIN_PART_BYTES = 1024L * 1024L;
    public static final long TARGET_PART_BYTES = 8L * 1024L * 1024L;
    public static final int DEFAULT_PARTS_PER_WORKER = 4;
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

        long size = totalLength - startOffset;

        long byWorker =
                Math.max(1L, (long) workers * partsPerWorker);
        long byTargetSize =
                Math.max(1L, (size + TARGET_PART_BYTES - 1L) / TARGET_PART_BYTES);
        long byMinimumSize =
                Math.max(1L, (size + minPartBytes - 1L) / minPartBytes);

        long desired = Math.max(byWorker, byTargetSize);
        int partCount = (int) Math.max(
                1L,
                Math.min(
                        (long) ABSOLUTE_MAX_PARTS,
                        Math.min(byMinimumSize, desired)));

        long baseSize = size / partCount;
        long remainder = size % partCount;

        List<RangePart> parts = new ArrayList<>(partCount);
        long cursor = startOffset;
        for (int i = 0; i < partCount; i++) {
            long length = baseSize + (i < remainder ? 1L : 0L);
            long end = cursor + length - 1L;
            parts.add(new RangePart(i, cursor, end));
            cursor = end + 1L;
        }

        if (cursor != totalLength) {
            throw new IllegalStateException(
                    "planner coverage mismatch: " + cursor + " != " + totalLength);
        }
        return parts;
    }
}
