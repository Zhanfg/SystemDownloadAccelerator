package dev.axymorrsen.systemdownloadaccelerator.engine;

import java.util.List;

/**
 * Computes the largest byte prefix that is known to be fully present from offset zero.
 *
 * Bytes written in later segments are intentionally ignored until every byte before
 * them is complete. This makes the result safe to publish as DownloadManager's
 * resumable currentBytes offset.
 */
public final class ContiguousProgress {
    private ContiguousProgress() {}

    public static long prefix(List<Segment> segments, long[] writtenPerSegment) {
        if (segments == null || writtenPerSegment == null
                || segments.size() != writtenPerSegment.length) {
            throw new IllegalArgumentException("segment/progress size mismatch");
        }

        long prefix = 0L;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment.startInclusive != prefix) {
                break;
            }

            long bytes = Math.max(
                    0L,
                    Math.min(segment.length(), writtenPerSegment[i]));
            prefix += bytes;

            if (bytes < segment.length()) {
                break;
            }
        }
        return prefix;
    }
}
