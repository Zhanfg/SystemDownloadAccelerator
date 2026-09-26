package dev.axymorrsen.systemdownloadaccelerator;

import dev.axymorrsen.systemdownloadaccelerator.core.RangePart;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequential InputStream backed by parallel immutable Range workers.
 *
 * Each micro-part is written into a small temporary file. The reader consumes
 * parts in order while later parts are prefetched in parallel. Scheduling is
 * windowed, so temporary storage is bounded instead of duplicating the entire
 * download.
 */
final class ParallelRangeInputStream extends InputStream {
    static final class RangeConnection {
        final HttpURLConnection connection;
        final InputStream body;
        final long responseEnd;

        RangeConnection(
                HttpURLConnection connection,
                InputStream body,
                long responseEnd) {
            this.connection = connection;
            this.body = body;
            this.responseEnd = responseEnd;
        }

        void close() {
            try {
                body.close();
            } catch (Throwable ignored) {
            }
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_PART_RETRIES = 3;

    private static final class PartState {
        final RangePart part;
        final File file;
        final Object lock = new Object();
        final AtomicLong downloaded = new AtomicLong(0L);

        volatile boolean done;
        volatile Throwable failure;

        PartState(RangePart part, File file) {
            this.part = part;
            this.file = file;
        }
    }

    private final URL url;
    private final ConnectionRegistry.Metadata metadata;
    private final Map<String, List<String>> headers;
    private final HttpURLConnection original;
    private final ParallelRangeEngine.EntityValidator validator;
    private final long totalLength;
    private final List<PartState> states;
    private final int workers;
    private final int maxAheadParts;
    private final File sessionDir;

    private final ExecutorService pool;
    private final AtomicInteger nextToClaim = new AtomicInteger(0);
    private final Object schedulerLock = new Object();

    private volatile boolean cancelled;
    private volatile boolean closed;
    private volatile Throwable sessionFailure;
    private volatile int consumedPartIndex;

    private RandomAccessFile currentReader;
    private long currentPartRead;

    ParallelRangeInputStream(
            URL url,
            ConnectionRegistry.Metadata metadata,
            Map<String, List<String>> headers,
            HttpURLConnection original,
            ParallelRangeEngine.EntityValidator validator,
            long totalLength,
            List<RangePart> parts,
            int workers,
            File cacheRoot) throws IOException {
        this.url = url;
        this.metadata = metadata;
        this.headers = headers;
        this.original = original;
        this.validator = validator;
        this.totalLength = totalLength;
        this.workers = workers;
        this.maxAheadParts = Math.max(workers * 2, workers + 1);

        sessionDir = new File(
                cacheRoot,
                "job_" + UUID.randomUUID().toString().replace("-", ""));
        if (!sessionDir.mkdirs()) {
            throw new IOException("unable to create range session directory");
        }

        states = new ArrayList<>(parts.size());
        for (RangePart part : parts) {
            states.add(new PartState(
                    part,
                    new File(sessionDir, "p" + part.index + ".tmp")));
        }

        pool = Executors.newFixedThreadPool(
                workers,
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "SysDlRangeWorker");
                    thread.setDaemon(true);
                    return thread;
                });

        for (int i = 0; i < workers; i++) {
            pool.execute(this::workerLoop);
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length)
            throws IOException {
        if (buffer == null) throw new NullPointerException("buffer");
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;
        if (closed) throw new IOException("stream closed");

        while (true) {
            throwIfInterruptedOrFailed();

            if (consumedPartIndex >= states.size()) {
                finishSuccess();
                return -1;
            }

            PartState state = states.get(consumedPartIndex);
            ensureReader(state);

            long available = state.downloaded.get() - currentPartRead;
            if (available > 0L) {
                int wanted = (int) Math.min((long) length, available);
                currentReader.seek(currentPartRead);
                int count = currentReader.read(buffer, offset, wanted);
                if (count > 0) {
                    currentPartRead += count;
                    if (currentPartRead == state.part.length()) {
                        advancePart(state);
                    }
                    return count;
                }
            }

            if (state.failure != null) {
                throw asIo("range part failed", state.failure);
            }

            if (state.done
                    && currentPartRead >= state.part.length()) {
                advancePart(state);
                continue;
            }

            synchronized (state.lock) {
                try {
                    state.lock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("range read interrupted");
                }
            }
        }
    }

    @Override
    public int available() throws IOException {
        if (closed || consumedPartIndex >= states.size()) {
            return 0;
        }
        PartState state = states.get(consumedPartIndex);
        long available = Math.max(
                0L,
                state.downloaded.get() - currentPartRead);
        return (int) Math.min(Integer.MAX_VALUE, available);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cancelled = true;

        synchronized (schedulerLock) {
            schedulerLock.notifyAll();
        }
        for (PartState state : states) {
            synchronized (state.lock) {
                state.lock.notifyAll();
            }
        }

        closeCurrentReader();

        pool.shutdownNow();
        try {
            pool.awaitTermination(1500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        cleanupFiles();
    }

    private void workerLoop() {
        try {
            while (!cancelled) {
                PartState state = claimPart();
                if (state == null) {
                    return;
                }
                downloadPart(state);
            }
        } catch (Throwable t) {
            failSession(t);
        }
    }

    private PartState claimPart() throws InterruptedException {
        synchronized (schedulerLock) {
            while (!cancelled) {
                int next = nextToClaim.get();
                if (next >= states.size()) {
                    return null;
                }

                int limit = consumedPartIndex + maxAheadParts;
                if (next < limit) {
                    if (nextToClaim.compareAndSet(next, next + 1)) {
                        return states.get(next);
                    }
                    continue;
                }

                schedulerLock.wait(100L);
            }
            return null;
        }
    }

    private void downloadPart(PartState state) {
        int attempts = 0;

        try {
            if (!state.file.exists() && !state.file.createNewFile()) {
                throw new IOException(
                        "unable to create " + state.file.getName());
            }

            while (!cancelled
                    && state.downloaded.get() < state.part.length()) {
                long already = state.downloaded.get();
                long requestStart = state.part.from + already;
                long requestEnd = state.part.to;

                attempts++;
                RangeConnection range = null;
                try {
                    range = ParallelRangeEngine.openRange(
                            url,
                            metadata,
                            headers,
                            original,
                            validator,
                            requestStart,
                            requestEnd,
                            totalLength);

                    long serverEnd = range.responseEnd;
                    long maxThisResponse =
                            Math.min(requestEnd, serverEnd)
                                    - requestStart + 1L;
                    if (maxThisResponse <= 0L) {
                        throw new IOException(
                                "server returned empty range");
                    }

                    copyRangeBody(
                            state,
                            range.body,
                            maxThisResponse);
                    attempts = 0;
                } catch (Throwable t) {
                    if (attempts >= MAX_PART_RETRIES) {
                        throw t;
                    }
                    sleepRetry(attempts);
                } finally {
                    if (range != null) {
                        range.close();
                    }
                }
            }

            if (!cancelled
                    && state.downloaded.get() == state.part.length()) {
                state.done = true;
                synchronized (state.lock) {
                    state.lock.notifyAll();
                }
            }
        } catch (Throwable t) {
            state.failure = t;
            synchronized (state.lock) {
                state.lock.notifyAll();
            }
            failSession(t);
        }
    }

    private void copyRangeBody(
            PartState state,
            InputStream source,
            long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = Math.min(
                maxBytes,
                state.part.length() - state.downloaded.get());

        try (FileOutputStream out =
                     new FileOutputStream(state.file, true)) {
            while (!cancelled && remaining > 0L) {
                int wanted = (int) Math.min(
                        (long) buffer.length,
                        remaining);
                int count = source.read(buffer, 0, wanted);
                if (count < 0) {
                    break;
                }

                out.write(buffer, 0, count);
                state.downloaded.addAndGet(count);
                remaining -= count;

                synchronized (state.lock) {
                    state.lock.notifyAll();
                }
            }
        }
    }

    private void ensureReader(PartState state) throws IOException {
        if (currentReader != null) return;

        while (!state.file.exists()) {
            throwIfInterruptedOrFailed();
            synchronized (state.lock) {
                try {
                    state.lock.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException(
                            "waiting for range file interrupted");
                }
            }
        }
        currentReader = new RandomAccessFile(state.file, "r");
        currentPartRead = 0L;
    }

    private void advancePart(PartState state) {
        closeCurrentReader();
        //noinspection ResultOfMethodCallIgnored
        state.file.delete();

        consumedPartIndex++;
        currentPartRead = 0L;

        synchronized (schedulerLock) {
            schedulerLock.notifyAll();
        }
    }

    private void finishSuccess() {
        EngineTelemetry.emit(
                metadata.context,
                "COMPLETE",
                "parallel stream completed");
        close();
    }

    private void failSession(Throwable t) {
        if (sessionFailure == null) {
            sessionFailure = t;
            EngineTelemetry.emit(
                    metadata.context,
                    "ERROR",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
        }
        cancelled = true;

        synchronized (schedulerLock) {
            schedulerLock.notifyAll();
        }
        for (PartState state : states) {
            synchronized (state.lock) {
                state.lock.notifyAll();
            }
        }
    }

    private void throwIfInterruptedOrFailed() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException(
                    "native DownloadThread interrupted");
        }
        if (sessionFailure != null) {
            throw asIo("parallel range session failed", sessionFailure);
        }
        if (cancelled && !closed) {
            throw new IOException("parallel range session cancelled");
        }
    }

    private void closeCurrentReader() {
        if (currentReader != null) {
            try {
                currentReader.close();
            } catch (Throwable ignored) {
            }
            currentReader = null;
        }
    }

    private void cleanupFiles() {
        for (PartState state : states) {
            //noinspection ResultOfMethodCallIgnored
            state.file.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        sessionDir.delete();
    }

    private static IOException asIo(
            String message,
            Throwable cause) {
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(message, cause);
    }

    private static void sleepRetry(int attempt)
            throws InterruptedException {
        Thread.sleep(Math.min(1500L, 350L * attempt));
    }
}
