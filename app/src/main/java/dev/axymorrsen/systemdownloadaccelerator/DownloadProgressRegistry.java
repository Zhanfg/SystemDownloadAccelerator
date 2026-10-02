package dev.axymorrsen.systemdownloadaccelerator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local exact progress learned from the accelerated native stream. */
final class DownloadProgressRegistry {
    static final class Snapshot {
        final long id;
        final String sourcePackage;
        final String title;
        final long currentBytes;
        final long totalBytes;
        final long updatedNs;

        Snapshot(
                long id,
                String sourcePackage,
                String title,
                long currentBytes,
                long totalBytes,
                long updatedNs) {
            this.id = id;
            this.sourcePackage = sourcePackage;
            this.title = title;
            this.currentBytes = currentBytes;
            this.totalBytes = totalBytes;
            this.updatedNs = updatedNs;
        }
    }

    private static final long STALE_NS =
            30L * 60L * 1_000_000_000L;
    private static final ConcurrentHashMap<Long, Snapshot> ENTRIES =
            new ConcurrentHashMap<>();

    private DownloadProgressRegistry() {}

    static void update(
            long id,
            String sourcePackage,
            String title,
            long currentBytes,
            long totalBytes) {
        if (id < 0L) return;
        long now = System.nanoTime();
        ENTRIES.put(
                id,
                new Snapshot(
                        id,
                        normalize(sourcePackage),
                        clean(title),
                        Math.max(0L, currentBytes),
                        Math.max(-1L, totalBytes),
                        now));
        prune(now);
    }

    static Snapshot get(long id) {
        Snapshot snapshot = ENTRIES.get(id);
        if (snapshot == null) return null;
        long now = System.nanoTime();
        if (now - snapshot.updatedNs > STALE_NS) {
            ENTRIES.remove(id, snapshot);
            return null;
        }
        return snapshot;
    }

    static List<Snapshot> forSource(String sourcePackage) {
        String normalized = normalize(sourcePackage);
        long now = System.nanoTime();
        ArrayList<Snapshot> out = new ArrayList<>();
        for (Snapshot snapshot : ENTRIES.values()) {
            if (now - snapshot.updatedNs > STALE_NS) {
                ENTRIES.remove(snapshot.id, snapshot);
                continue;
            }
            if (same(normalized, snapshot.sourcePackage)) {
                out.add(snapshot);
            }
        }
        return out;
    }

    static void remove(long id) {
        if (id >= 0L) {
            ENTRIES.remove(id);
        }
    }

    private static void prune(long now) {
        if (ENTRIES.size() < 32) return;
        for (Snapshot snapshot : ENTRIES.values()) {
            if (now - snapshot.updatedNs > STALE_NS) {
                ENTRIES.remove(snapshot.id, snapshot);
            }
        }
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String normalize(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(java.util.Locale.ROOT);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
