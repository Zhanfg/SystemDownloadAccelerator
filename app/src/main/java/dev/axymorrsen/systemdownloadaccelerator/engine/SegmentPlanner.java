package dev.axymorrsen.systemdownloadaccelerator.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure planning logic; no network or file I/O lives here. */
public final class SegmentPlanner {
    public List<Segment> plan(long contentLength, int requestedWorkers) {
        if (contentLength <= 0L) return Collections.emptyList();

        int workers = Math.max(1, requestedWorkers);
        workers = (int) Math.min((long) workers, contentLength);

        long base = contentLength / workers;
        long remainder = contentLength % workers;
        long cursor = 0L;

        List<Segment> result = new ArrayList<>(workers);
        for (int i = 0; i < workers; i++) {
            long length = base + (i < remainder ? 1L : 0L);
            long end = cursor + length - 1L;
            result.add(new Segment(cursor, end));
            cursor = end + 1L;
        }
        return Collections.unmodifiableList(result);
    }
}
