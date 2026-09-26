package dev.axymorrsen.systemdownloadaccelerator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AB-inspired load balancing without mutating in-flight ranges.
 *
 * We create more micro-parts than workers. A worker that finishes early claims
 * another part, which gives dynamic load balancing while every HTTP request
 * keeps immutable boundaries.
 */
public final class MicroPartPlanner {
    public static final long DEFAULT_MIN_PART_BYTES = 1024L * 1024L;
    public static final int DEFAULT_PARTS_PER_WORKER = 4;
    public static final int ABSOLUTE_MAX_PARTS = 64;

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
        long maxUsefulParts = (size + minPartBytes - 1L) / minPartBytes;
        long desiredParts = Math.min(
                (long) ABSOLUTE_MAX_PARTS,
                (long) workers * partsPerWorker);
        int partCount = (int) Math.max(
                1L,
                Math.min(maxUsefulParts, desiredParts));

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
