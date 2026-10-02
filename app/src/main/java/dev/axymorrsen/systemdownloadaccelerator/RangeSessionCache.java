package dev.axymorrsen.systemdownloadaccelerator;

import dev.axymorrsen.systemdownloadaccelerator.core.RangePart;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Persistent reorder-cache checkpoint store.
 *
 * Session names contain only a SHA-256 fingerprint. URL query strings,
 * credentials and request headers are never written in plaintext.
 */
final class RangeSessionCache {
    private static final String ROOT_NAME = "resume_v1";
    private static final long MAX_AGE_MS = 72L * 60L * 60L * 1000L;
    private static final long MAX_TOTAL_BYTES = 768L * 1024L * 1024L;

    static final class PartBacking {
        final File file;
        final long reusableBytes;

        PartBacking(File file, long reusableBytes) {
            this.file = file;
            this.reusableBytes = reusableBytes;
        }
    }

    static final class Session {
        private final File dir;
        private final boolean persistent;
        private final String key;
        private final RandomAccessFile leaseFile;
        private final FileChannel leaseChannel;
        private final FileLock leaseLock;
        private boolean released;

        Session(
                File dir,
                boolean persistent,
                String key,
                RandomAccessFile leaseFile,
                FileChannel leaseChannel,
                FileLock leaseLock) {
            this.dir = dir;
            this.persistent = persistent;
            this.key = key;
            this.leaseFile = leaseFile;
            this.leaseChannel = leaseChannel;
            this.leaseLock = leaseLock;
        }

        String key() {
            return key;
        }

        boolean persistent() {
            return persistent;
        }

        synchronized PartBacking backingFor(RangePart part)
                throws IOException {
            touch();

            File exact = partFile(dir, part.from, part.to);
            normalizeLength(exact, part.length());
            if (exact.exists()) {
                return new PartBacking(
                        exact,
                        Math.min(part.length(), exact.length()));
            }

            File candidate = findPrefixCandidate(part);
            if (candidate != null) {
                long[] range = parsePartFile(candidate.getName());
                long skip = part.from - range[0];
                long available = Math.max(
                        0L,
                        candidate.length() - skip);
                long reusable = Math.min(
                        part.length(),
                        available);
                if (reusable > 0L) {
                    copySuffix(candidate, exact, skip, reusable);
                    // The exact resumed head supersedes the older prefix file.
                    //noinspection ResultOfMethodCallIgnored
                    candidate.delete();
                    return new PartBacking(exact, reusable);
                }
            }

            return new PartBacking(exact, 0L);
        }

        synchronized void close(boolean discard) {
            if (released) return;
            released = true;

            try {
                leaseLock.release();
            } catch (Throwable ignored) {
            }
            try {
                leaseChannel.close();
            } catch (Throwable ignored) {
            }
            try {
                leaseFile.close();
            } catch (Throwable ignored) {
            }

            if (discard || !persistent) {
                deleteTree(dir);
            } else {
                touch();
            }
        }

        private File findPrefixCandidate(RangePart part) {
            File[] files = dir.listFiles();
            if (files == null) return null;

            File best = null;
            long bestFrom = Long.MIN_VALUE;
            for (File file : files) {
                long[] range = parsePartFile(file.getName());
                if (range == null) continue;
                long from = range[0];
                long to = range[1];

                if (from < part.from
                        && to == part.to
                        && from > bestFrom
                        && file.length() > part.from - from) {
                    best = file;
                    bestFrom = from;
                }
            }
            return best;
        }

        private void touch() {
            //noinspection ResultOfMethodCallIgnored
            dir.setLastModified(System.currentTimeMillis());
        }
    }

    private RangeSessionCache() {}

    static Session open(
            File cacheRoot,
            URL url,
            String validator,
            long totalLength,
            int requestingUid,
            Map<String, List<String>> headers) throws IOException {
        if (cacheRoot == null) {
            throw new IOException("cacheRoot == null");
        }

        File root = new File(cacheRoot, ROOT_NAME);
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("unable to create persistent range root");
        }

        prune(root);

        String key = fingerprint(
                url,
                validator,
                totalLength,
                requestingUid,
                headers);
        File dir = new File(root, "s_" + key.substring(0, 32));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("unable to create range session");
        }

        Session persistent = tryOpenLease(dir, true, key);
        if (persistent != null) {
            return persistent;
        }

        // Identical entities can be downloaded twice concurrently. The second
        // transfer must not share checkpoint files with the first one.
        File volatileDir = new File(
                root,
                "v_" + UUID.randomUUID().toString().replace("-", ""));
        if (!volatileDir.mkdirs()) {
            throw new IOException("unable to create volatile range session");
        }
        Session isolated = tryOpenLease(volatileDir, false, key);
        if (isolated == null) {
            deleteTree(volatileDir);
            throw new IOException("unable to lease volatile range session");
        }
        return isolated;
    }

    private static Session tryOpenLease(
            File dir,
            boolean persistent,
            String key) throws IOException {
        RandomAccessFile raf =
                new RandomAccessFile(new File(dir, ".lease"), "rw");
        FileChannel channel = raf.getChannel();
        FileLock lock = null;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException ignored) {
        } catch (Throwable t) {
            try {
                channel.close();
            } catch (Throwable ignored) {
            }
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            return null;
        }

        if (lock == null) {
            try {
                channel.close();
            } catch (Throwable ignored) {
            }
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
            return null;
        }

        //noinspection ResultOfMethodCallIgnored
        dir.setLastModified(System.currentTimeMillis());
        return new Session(
                dir,
                persistent,
                key,
                raf,
                channel,
                lock);
    }

    private static String fingerprint(
            URL url,
            String validator,
            long totalLength,
            int requestingUid,
            Map<String, List<String>> headers) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "range-session-v1");
            update(digest, url == null ? "" : url.toExternalForm());
            update(digest, validator == null ? "" : validator);
            update(digest, Long.toString(totalLength));
            update(digest, Integer.toString(requestingUid));

            TreeMap<String, List<String>> sorted =
                    new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (headers != null) {
                sorted.putAll(headers);
            }
            for (Map.Entry<String, List<String>> entry : sorted.entrySet()) {
                String name = entry.getKey() == null
                        ? ""
                        : entry.getKey().trim().toLowerCase(Locale.ROOT);
                if (isTransientHeader(name)) {
                    continue;
                }
                update(digest, name);
                List<String> values = entry.getValue();
                if (values != null) {
                    for (String value : values) {
                        update(digest, value == null ? "" : value);
                    }
                }
            }

            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format(
                        Locale.ROOT,
                        "%02x",
                        b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IOException(
                    "unable to fingerprint range session",
                    e);
        }
    }

    private static boolean isTransientHeader(String name) {
        return "range".equals(name)
                || "if-range".equals(name)
                || "content-length".equals(name)
                || "connection".equals(name)
                || "proxy-connection".equals(name);
    }

    private static void update(
            MessageDigest digest,
            String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) ((bytes.length >>> 24) & 0xff));
        digest.update((byte) ((bytes.length >>> 16) & 0xff));
        digest.update((byte) ((bytes.length >>> 8) & 0xff));
        digest.update((byte) (bytes.length & 0xff));
        digest.update(bytes);
    }

    private static File partFile(
            File dir,
            long from,
            long to) {
        return new File(
                dir,
                "r_" + from + "_" + to + ".bin");
    }

    private static long[] parsePartFile(String name) {
        if (name == null
                || !name.startsWith("r_")
                || !name.endsWith(".bin")) {
            return null;
        }
        String body = name.substring(2, name.length() - 4);
        int split = body.indexOf('_');
        if (split <= 0 || split >= body.length() - 1) {
            return null;
        }
        try {
            long from = Long.parseLong(body.substring(0, split));
            long to = Long.parseLong(body.substring(split + 1));
            if (from < 0L || to < from) {
                return null;
            }
            return new long[]{from, to};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void normalizeLength(
            File file,
            long maximum) throws IOException {
        if (!file.exists() || file.length() <= maximum) {
            return;
        }
        try (RandomAccessFile raf =
                     new RandomAccessFile(file, "rw")) {
            raf.setLength(maximum);
        }
    }

    private static void copySuffix(
            File source,
            File target,
            long skip,
            long length) throws IOException {
        try (RandomAccessFile in =
                     new RandomAccessFile(source, "r");
             RandomAccessFile out =
                     new RandomAccessFile(target, "rw")) {
            out.setLength(0L);
            in.seek(skip);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0L) {
                int count = in.read(
                        buffer,
                        0,
                        (int) Math.min(
                                (long) buffer.length,
                                remaining));
                if (count < 0) break;
                out.write(buffer, 0, count);
                remaining -= count;
            }
            out.getFD().sync();
        }
    }

    private static void prune(File root) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        List<File> survivors = new ArrayList<>();
        for (File dir : dirs) {
            if (isLeased(dir)) {
                survivors.add(dir);
                continue;
            }
            long age = Math.max(
                    0L,
                    now - dir.lastModified());
            if (age > MAX_AGE_MS) {
                deleteTree(dir);
            } else {
                survivors.add(dir);
            }
        }

        long total = 0L;
        for (File dir : survivors) {
            total += treeSize(dir);
        }
        if (total <= MAX_TOTAL_BYTES) {
            return;
        }

        survivors.sort(
                Comparator.comparingLong(File::lastModified));
        for (File dir : survivors) {
            if (total <= MAX_TOTAL_BYTES) break;
            if (isLeased(dir)) continue;
            long size = treeSize(dir);
            deleteTree(dir);
            total = Math.max(0L, total - size);
        }
    }

    private static boolean isLeased(File dir) {
        File lease = new File(dir, ".lease");
        try (RandomAccessFile raf =
                     new RandomAccessFile(lease, "rw");
             FileChannel channel = raf.getChannel()) {
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    return true;
                }
                lock.release();
                return false;
            } catch (OverlappingFileLockException e) {
                return true;
            }
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static long treeSize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += treeSize(child);
            }
        }
        return total;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
