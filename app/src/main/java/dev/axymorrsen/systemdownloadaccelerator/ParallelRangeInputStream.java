package dev.axymorrsen.systemdownloadaccelerator;

import dev.axymorrsen.systemdownloadaccelerator.core.RangePart;
import dev.axymorrsen.systemdownloadaccelerator.engine.AdaptiveConcurrencyController;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequential InputStream backed by parallel immutable Range workers.
 *
 * Concurrency starts conservatively and can ramp at runtime when measured
 * throughput proves that additional sockets are still useful.
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
    private static final int MAX_PART_RETRIES = 5;
    private static final long TUNE_INTERVAL_MS = 2500L;
    private static final long CACHE_WINDOW_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_BUFFERED_BYTES = 128L * 1024L * 1024L;
    private static final long MIN_HEAD_RESERVE_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_HEAD_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static final long MIN_CONTROLLER_DRAIN_BYTES = 16L * 1024L * 1024L;
    private static final double MIN_CONTROLLER_DRAIN_BPS = 512.0 * 1024.0;
    private static final int MIN_CONTROLLER_READY_SAMPLES = 2;
    private static final long CONSUMER_STALL_TIMEOUT_MS = 60_000L;

    private static final class PartState {
        final RangePart part;
        final File file;
        final Object lock = new Object();
        final AtomicLong downloaded;

        volatile boolean done;
        volatile Throwable failure;

        PartState(
                RangePart part,
                File file,
                long reusableBytes) {
            this.part = part;
            this.file = file;
            long initial = Math.max(
                    0L,
                    Math.min(part.length(), reusableBytes));
            this.downloaded = new AtomicLong(initial);
            this.done = initial == part.length();
        }
    }

    private final URL url;
    private final ConnectionRegistry.Metadata metadata;
    private final Map<String, List<String>> headers;
    private final HttpURLConnection original;
    private final ParallelRangeEngine.EntityValidator validator;
    private final long totalLength;
    private final long sessionLength;
    private final long averagePartBytes;
    private final List<PartState> states;
    private final int initialWorkers;
    private final int maxWorkers;
    private final RangeSessionCache.Session rangeSession;
    private final GlobalTransferGovernor.Lease globalLease;

    private final ExecutorService pool;
    private final AdaptiveConcurrencyController controller;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicInteger peakWorkers = new AtomicInteger(0);
    private final AtomicInteger nextToClaim = new AtomicInteger(0);
    private final AtomicLong totalDownloaded = new AtomicLong(0L);
    private final AtomicLong totalConsumed = new AtomicLong(0L);
    private final Object schedulerLock = new Object();

    private final long startedNs = System.nanoTime();
    private final Thread tunerThread;

    private volatile boolean cancelled;
    private volatile boolean closed;
    private volatile boolean completedSuccessfully;
    private volatile Throwable sessionFailure;
    private volatile int consumedPartIndex;

    private RandomAccessFile currentReader;
    private volatile long currentPartRead;

    ParallelRangeInputStream(
            URL url,
            ConnectionRegistry.Metadata metadata,
            Map<String, List<String>> headers,
            HttpURLConnection original,
            ParallelRangeEngine.EntityValidator validator,
            long totalLength,
            List<RangePart> parts,
            int initialWorkers,
            int maxWorkers,
            File cacheRoot) throws IOException {
        this.url = url;
        this.metadata = metadata;
        this.headers = headers;
        this.original = original;
        this.validator = validator;
        this.totalLength = totalLength;
        this.initialWorkers = initialWorkers;
        this.maxWorkers = maxWorkers;

        if (initialWorkers < 1 || maxWorkers < initialWorkers) {
            throw new IllegalArgumentException(
                    "bad worker range "
                            + initialWorkers
                            + ".."
                            + maxWorkers);
        }

        long bytes = 0L;
        for (RangePart part : parts) {
            bytes += part.length();
        }
        sessionLength = bytes;
        averagePartBytes = Math.max(
                1L,
                (sessionLength + Math.max(1, parts.size()) - 1L)
                        / Math.max(1, parts.size()));

        rangeSession = RangeSessionCache.open(
                cacheRoot,
                url,
                validator.headerValue,
                totalLength,
                metadata.requestingUid,
                headers);
        globalLease = GlobalTransferGovernor.register(
                url == null ? "" : url.getHost(),
                maxWorkers);

        states = new ArrayList<>(parts.size());
        long restoredBytes = 0L;
        for (RangePart part : parts) {
            RangeSessionCache.PartBacking backing =
                    rangeSession.backingFor(part);
            PartState state = new PartState(
                    part,
                    backing.file,
                    backing.reusableBytes);
            states.add(state);
            restoredBytes += state.downloaded.get();
        }
        totalDownloaded.set(restoredBytes);

        controller =
                new AdaptiveConcurrencyController(
                        initialWorkers,
                        maxWorkers);

        if (restoredBytes > 0L) {
            EngineTelemetry.emit(
                    metadata.context,
                    "RESUME_CACHE",
                    "restoredMiB=" + formatMiB(restoredBytes)
                            + " session="
                            + rangeSession.key().substring(0, 12)
                            + " persistent="
                            + rangeSession.persistent());
        }

        pool = Executors.newFixedThreadPool(
                maxWorkers,
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "SysDlRangeWorker");
                    thread.setDaemon(true);
                    return thread;
                });

        startWorkers(initialWorkers);

        tunerThread = new Thread(
                this::tuneLoop,
                "SysDlRangeTune");
        tunerThread.setDaemon(true);
        tunerThread.start();

        EngineTelemetry.emit(
                metadata.context,
                "ADAPT_INIT",
                "initial=" + initialWorkers
                        + " max=" + maxWorkers
                        + " parts=" + parts.size()
                        + " avgPartMiB="
                        + formatMiB(averagePartBytes));
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
                // Reader position already advances sequentially. Re-seeking on
                // every native 8 KiB DownloadManager read creates thousands of
                // extra lseek syscalls per second at high throughput.
                int count = currentReader.read(buffer, offset, wanted);
                if (count > 0) {
                    currentPartRead += count;
                    totalConsumed.addAndGet(count);
                    if (totalDownloaded.get() - totalConsumed.get()
                            < MAX_BUFFERED_BYTES) {
                        synchronized (schedulerLock) {
                            schedulerLock.notifyAll();
                        }
                    }
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
                    throw new InterruptedIOException(
                            "range read interrupted");
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

        try {
            tunerThread.interrupt();
        } catch (Throwable ignored) {
        }

        pool.shutdownNow();
        try {
            pool.awaitTermination(1500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        releaseSharedResources(
                completedSuccessfully
                        || RetryPolicy.isIntegrityFailure(sessionFailure));
    }

    private void startWorkers(int target) {
        int capped = Math.max(
                1,
                Math.min(
                        maxWorkers,
                        globalLease.allowed(target)));

        while (!cancelled) {
            int current = activeWorkers.get();
            if (current >= capped) {
                return;
            }
            if (!activeWorkers.compareAndSet(
                    current,
                    current + 1)) {
                continue;
            }

            peakWorkers.accumulateAndGet(
                    current + 1,
                    Math::max);

            try {
                pool.execute(this::workerLoop);
            } catch (RejectedExecutionException rejected) {
                activeWorkers.decrementAndGet();
                return;
            }
        }
    }

    private void tuneLoop() {
        long previousBytes = totalDownloaded.get();
        long previousConsumed = totalConsumed.get();
        long previousNs = System.nanoTime();
        long lastDrainProgressNs = previousNs;
        int sampleIndex = 0;
        int readyDrainSamples = 0;
        boolean controllerReady =
                initialWorkers >= maxWorkers;
        boolean finalHoldReported = false;
        boolean warmupReported = false;

        while (!cancelled && !closed) {
            try {
                Thread.sleep(TUNE_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }

            if (cancelled || closed) {
                return;
            }

            long nowNs = System.nanoTime();
            long bytes = totalDownloaded.get();
            long consumed = totalConsumed.get();
            long deltaBytes = Math.max(0L, bytes - previousBytes);
            long deltaConsumed = Math.max(0L, consumed - previousConsumed);
            long deltaNs = Math.max(1L, nowNs - previousNs);

            double bytesPerSecond =
                    deltaBytes * 1_000_000_000.0 / deltaNs;
            double drainBytesPerSecond =
                    deltaConsumed * 1_000_000_000.0 / deltaNs;
            long remaining =
                    Math.max(0L, sessionLength - bytes);
            long bufferedBytes =
                    Math.max(0L, bytes - consumed);

            if (deltaConsumed > 0L) {
                lastDrainProgressNs = nowNs;
            } else if (bufferedBytes >= MIN_HEAD_RESERVE_BYTES
                    && TimeUnit.NANOSECONDS.toMillis(
                            nowNs - lastDrainProgressNs)
                    >= CONSUMER_STALL_TIMEOUT_MS) {
                EngineTelemetry.emit(
                        metadata.context,
                        "STALL_ABORT",
                        "bufferedMiB=" + formatMiB(bufferedBytes)
                                + " noDrainMs="
                                + TimeUnit.NANOSECONDS.toMillis(
                                        nowNs - lastDrainProgressNs)
                                + " workers="
                                + activeWorkers.get()
                                + " target="
                                + controller.workers()
                                + "/" + maxWorkers);
                abortStalledConsumer();
                return;
            }

            double deliveredBps =
                    drainBytesPerSecond > 0.0
                            ? Math.min(bytesPerSecond, drainBytesPerSecond)
                            : 0.0;

            if (!controllerReady) {
                long readyBytes = Math.min(
                        MIN_CONTROLLER_DRAIN_BYTES,
                        Math.max(
                                4L * 1024L * 1024L,
                                averagePartBytes * 2L));

                if (consumed >= readyBytes
                        && drainBytesPerSecond
                        >= MIN_CONTROLLER_DRAIN_BPS) {
                    readyDrainSamples++;
                } else {
                    readyDrainSamples = 0;
                }

                if (readyDrainSamples
                        >= MIN_CONTROLLER_READY_SAMPLES) {
                    controllerReady = true;
                    warmupReported = false;
                    EngineTelemetry.emit(
                            metadata.context,
                            "ADAPT_READY",
                            "consumedMiB=" + formatMiB(consumed)
                                    + " drainMiBs="
                                    + formatMiBPerSecond(
                                            drainBytesPerSecond)
                                    + " workers="
                                    + controller.workers()
                                    + "/" + maxWorkers);
                } else if (!warmupReported
                        && sampleIndex >= 1) {
                    warmupReported = true;
                    EngineTelemetry.emit(
                            metadata.context,
                            "ADAPT_WARMUP",
                            "waitingForNativeDrain consumedMiB="
                                    + formatMiB(consumed)
                                    + " drainMiBs="
                                    + formatMiBPerSecond(
                                            drainBytesPerSecond)
                                    + " requiredSamples="
                                    + MIN_CONTROLLER_READY_SAMPLES);
                }
            }

            AdaptiveConcurrencyController.Decision decision =
                    controllerReady
                            ? controller.sample(
                                    deliveredBps,
                                    remaining)
                            : null;

            // Re-evaluate the process-wide fair share every sample. This both
            // retires workers when another transfer arrives and expands again
            // after a competing transfer finishes.
            startWorkers(controller.workers());

            sampleIndex++;
            if (sampleIndex % 4 == 0) {
                EngineTelemetry.emit(
                        metadata.context,
                        "PIPELINE",
                        "netMiBs="
                                + formatMiBPerSecond(bytesPerSecond)
                                + " drainMiBs="
                                + formatMiBPerSecond(drainBytesPerSecond)
                                + " bufferedMiB="
                                + formatMiB(bufferedBytes)
                                + " headMiB="
                                + formatMiB(currentHeadUnreadBytes())
                                + " futureMiB="
                                + formatMiB(currentFutureBufferedBytes())
                                + " workers="
                                + activeWorkers.get()
                                + "->" + globalLease.allowed(
                                        controller.workers())
                                + "/" + maxWorkers
                                + " sessions="
                                + GlobalTransferGovernor.activeSessions()
                                + " controllerReady="
                                + controllerReady);
            }

            if (decision != null
                    && decision.action
                    == AdaptiveConcurrencyController.Action.RAMP) {
                startWorkers(decision.workers);
                finalHoldReported = false;
                EngineTelemetry.emit(
                        metadata.context,
                        "ADAPT_RAMP",
                        "workers=" + decision.workers
                                + "/" + maxWorkers
                                + " deliveredMiBs="
                                + formatMiBPerSecond(deliveredBps)
                                + " netMiBs="
                                + formatMiBPerSecond(bytesPerSecond)
                                + " drainMiBs="
                                + formatMiBPerSecond(drainBytesPerSecond)
                                + " remainingMiB="
                                + formatMiB(remaining));
            } else if (decision != null
                    && decision.action
                    == AdaptiveConcurrencyController.Action.ROLLBACK) {
                finalHoldReported = true;
                synchronized (schedulerLock) {
                    schedulerLock.notifyAll();
                }
                EngineTelemetry.emit(
                        metadata.context,
                        "ADAPT_ROLLBACK",
                        "workers=" + decision.workers
                                + "/" + maxWorkers
                                + " deliveredMiBs="
                                + formatMiBPerSecond(deliveredBps)
                                + " gain="
                                + formatRatio(decision.gainRatio)
                                + " reason="
                                + decision.reason);
            } else if (decision != null
                    && decision.action
                    == AdaptiveConcurrencyController.Action.HOLD) {
                if (!finalHoldReported
                        && (decision.reason.contains("gain")
                        || decision.reason.contains("remaining")
                        || decision.reason.contains("per-worker")
                        || decision.reason.contains("final"))) {
                    finalHoldReported = true;
                    EngineTelemetry.emit(
                            metadata.context,
                            "ADAPT_HOLD",
                            "workers=" + decision.workers
                                    + "/" + maxWorkers
                                    + " deliveredMiBs="
                                    + formatMiBPerSecond(
                                            deliveredBps)
                                    + " netMiBs="
                                    + formatMiBPerSecond(
                                            bytesPerSecond)
                                    + " drainMiBs="
                                    + formatMiBPerSecond(
                                            drainBytesPerSecond)
                                    + " gain="
                                    + formatRatio(
                                            decision.gainRatio)
                                    + " reason="
                                    + decision.reason);
                }
            } else if (decision != null
                    && decision.action
                    == AdaptiveConcurrencyController.Action.CEILING) {
                if (!finalHoldReported) {
                    finalHoldReported = true;
                    EngineTelemetry.emit(
                            metadata.context,
                            "ADAPT_MAX",
                            "workers=" + decision.workers
                                    + " deliveredMiBs="
                                    + formatMiBPerSecond(
                                            deliveredBps)
                                    + " netMiBs="
                                    + formatMiBPerSecond(
                                            bytesPerSecond)
                                    + " drainMiBs="
                                    + formatMiBPerSecond(
                                            drainBytesPerSecond));
                }
            }

            previousBytes = bytes;
            previousConsumed = consumed;
            previousNs = nowNs;
        }
    }

    private void abortStalledConsumer() {
        cancelled = true;

        synchronized (schedulerLock) {
            schedulerLock.notifyAll();
        }
        for (PartState state : states) {
            synchronized (state.lock) {
                state.lock.notifyAll();
            }
        }

        pool.shutdownNow();
        try {
            pool.awaitTermination(1500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        closeCurrentReader();
        releaseSharedResources(false);
    }

    private void workerLoop() {
        boolean ownsActiveCount = true;
        try {
            while (!cancelled) {
                if (retireIfExcess()) {
                    ownsActiveCount = false;
                    return;
                }

                PartState state = claimPart();
                if (state == null) {
                    return;
                }

                downloadPart(state);

                if (retireIfExcess()) {
                    ownsActiveCount = false;
                    return;
                }
            }
        } catch (Throwable t) {
            failSession(t);
        } finally {
            if (ownsActiveCount) {
                activeWorkers.decrementAndGet();
            }
        }
    }

    /**
     * Atomically retires at most the exact number of workers above the
     * controller target. A plain activeWorkers > target check lets every
     * worker observe the same stale count and all exit during rollback.
     */
    private boolean retireIfExcess() {
        while (!cancelled) {
            int active = activeWorkers.get();
            int target = Math.max(
                    1,
                    globalLease.allowed(controller.workers()));
            if (active <= target) {
                return false;
            }
            if (activeWorkers.compareAndSet(active, active - 1)) {
                EngineTelemetry.emit(
                        metadata.context,
                        "WORKER_RETIRE",
                        "active=" + (active - 1)
                                + " target=" + target);
                return true;
            }
        }
        return false;
    }

    private PartState claimPart() throws InterruptedException {
        synchronized (schedulerLock) {
            while (!cancelled) {
                int next = nextToClaim.get();
                if (next >= states.size()) {
                    return null;
                }

                int workers = Math.max(
                        1,
                        controller.workers());
                long byBytes = Math.max(
                        workers,
                        CACHE_WINDOW_BYTES
                                / Math.max(1L, averagePartBytes));
                int ahead = (int) Math.max(
                        workers,
                        Math.min(
                                (long) workers * 2L,
                                byBytes));

                int limit = consumedPartIndex + ahead;
                if (next < limit) {
                    if (nextToClaim.compareAndSet(
                            next,
                            next + 1)) {
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

                    long before = state.downloaded.get();
                    copyRangeBody(
                            state,
                            range.body,
                            maxThisResponse);
                    if (state.downloaded.get() <= before) {
                        throw new IOException(
                                "range body made no progress");
                    }
                    attempts = 0;
                } catch (Throwable t) {
                    if (!RetryPolicy.shouldRetry(
                            t,
                            attempts,
                            MAX_PART_RETRIES)) {
                        throw t;
                    }
                    long delayMs = RetryPolicy.delayMillis(
                            attempts,
                            t,
                            state.part.from);
                    EngineTelemetry.emit(
                            metadata.context,
                            "PART_RETRY",
                            "part=" + state.part.index
                                    + " attempt=" + attempts
                                    + "/" + MAX_PART_RETRIES
                                    + " delayMs=" + delayMs
                                    + " error="
                                    + t.getClass().getSimpleName());
                    sleepRetry(delayMs);
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
                waitForBufferBudget(state);

                int wanted = (int) Math.min(
                        (long) buffer.length,
                        remaining);
                int count = source.read(buffer, 0, wanted);
                if (count < 0) {
                    break;
                }

                out.write(buffer, 0, count);
                state.downloaded.addAndGet(count);
                totalDownloaded.addAndGet(count);
                remaining -= count;

                synchronized (state.lock) {
                    state.lock.notifyAll();
                }
            }
            out.getFD().sync();
        }
    }

    private void waitForBufferBudget(PartState state)
            throws IOException {
        while (!cancelled) {
            throwIfInterruptedOrFailed();

            int headIndex = consumedPartIndex;
            long headReserve = headReserveBytes();
            long totalBuffered = Math.max(
                    0L,
                    totalDownloaded.get() - totalConsumed.get());
            long headUnread = currentHeadUnreadBytes();
            long futureBuffered = Math.max(
                    0L,
                    totalBuffered - headUnread);

            if (state.part.index <= headIndex) {
                /*
                 * The native DownloadManager can only advance through the head
                 * part. Always reserve enough budget for it so future parts can
                 * never fill the cache and starve the byte range the reader is
                 * currently waiting on.
                 */
                if (headUnread < headReserve) {
                    return;
                }
            } else {
                long futureLimit = Math.max(
                        MIN_HEAD_RESERVE_BYTES,
                        MAX_BUFFERED_BYTES - headReserve);
                if (futureBuffered < futureLimit
                        && totalBuffered < MAX_BUFFERED_BYTES) {
                    return;
                }
            }

            synchronized (schedulerLock) {
                try {
                    schedulerLock.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException(
                            "range prefetch backpressure interrupted");
                }
            }
        }
    }

    private long headReserveBytes() {
        return Math.min(
                MAX_HEAD_RESERVE_BYTES,
                Math.max(
                        MIN_HEAD_RESERVE_BYTES,
                        Math.min(
                                averagePartBytes,
                                MAX_BUFFERED_BYTES / 2L)));
    }

    private long currentHeadUnreadBytes() {
        int index = consumedPartIndex;
        if (index < 0 || index >= states.size()) {
            return 0L;
        }
        PartState head = states.get(index);
        return Math.max(
                0L,
                head.downloaded.get() - currentPartRead);
    }

    private long currentFutureBufferedBytes() {
        long totalBuffered = Math.max(
                0L,
                totalDownloaded.get() - totalConsumed.get());
        return Math.max(
                0L,
                totalBuffered - currentHeadUnreadBytes());
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
        double elapsedSeconds =
                Math.max(
                        0.001,
                        (System.nanoTime() - startedNs)
                                / 1_000_000_000.0);
        double averageBps =
                sessionLength / elapsedSeconds;

        EngineTelemetry.emit(
                metadata.context,
                "COMPLETE",
                "bytes=" + sessionLength
                        + " peakWorkers="
                        + peakWorkers.get()
                        + "/" + maxWorkers
                        + " avgMiBs="
                        + formatMiBPerSecond(averageBps));

        HostProfileStore.recordSuccess(
                metadata.context,
                url == null ? null : url.getHost(),
                Math.max(
                        1,
                        Math.min(
                                controller.workers(),
                                Math.max(1, peakWorkers.get()))),
                averageBps);
        completedSuccessfully = true;
        close();
    }

    private void failSession(Throwable t) {
        if (sessionFailure == null) {
            sessionFailure = t;
            HostProfileStore.recordFailure(
                    metadata.context,
                    url == null ? null : url.getHost());
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
                    "native download thread interrupted");
        }
        if (sessionFailure != null) {
            throw asIo(
                    "parallel range session failed",
                    sessionFailure);
        }
        if (cancelled && !closed) {
            throw new IOException(
                    "parallel range session cancelled");
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

    private void releaseSharedResources(boolean discardCache) {
        try {
            globalLease.close();
        } catch (Throwable ignored) {
        }
        try {
            rangeSession.close(discardCache);
        } catch (Throwable ignored) {
        }
    }

    private static IOException asIo(
            String message,
            Throwable cause) {
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(message, cause);
    }

    private static void sleepRetry(long delayMs)
            throws InterruptedException {
        Thread.sleep(Math.max(0L, delayMs));
    }

    private static String formatMiB(long bytes) {
        return String.format(
                java.util.Locale.US,
                "%.1f",
                bytes / (1024.0 * 1024.0));
    }

    private static String formatMiBPerSecond(double bps) {
        return String.format(
                java.util.Locale.US,
                "%.2f",
                bps / (1024.0 * 1024.0));
    }

    private static String formatRatio(double ratio) {
        return String.format(
                java.util.Locale.US,
                "%.2f",
                ratio);
    }
}
