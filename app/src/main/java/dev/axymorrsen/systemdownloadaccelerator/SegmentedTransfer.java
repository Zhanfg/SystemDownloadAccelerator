package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import dev.axymorrsen.systemdownloadaccelerator.engine.AdaptivePolicy;
import dev.axymorrsen.systemdownloadaccelerator.engine.Conditions;
import dev.axymorrsen.systemdownloadaccelerator.engine.ContiguousProgress;
import dev.axymorrsen.systemdownloadaccelerator.engine.RangeProbe;
import dev.axymorrsen.systemdownloadaccelerator.engine.Segment;
import dev.axymorrsen.systemdownloadaccelerator.engine.SegmentPlanner;

import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Guarded segmented transfer for Android DownloadProvider.
 *
 * This class intentionally talks to DownloadThread through reflection. OEM/API
 * layout drift therefore fails closed: any missing field/method simply returns
 * control to Android's original transferData(HttpURLConnection).
 */
final class SegmentedTransfer {
    private static final String TAG = "SysDlAccel";
    private static final long MIN_BYTES = 8L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Prevent several simultaneous DownloadManager jobs from multiplying into
     * dozens of sockets. We reduce the per-download worker count before falling
     * back to the native path.
     */
    private static final Semaphore GLOBAL_SOCKETS = new Semaphore(12, true);

    private static final AdaptivePolicy ADAPTIVE = new AdaptivePolicy();
    private static final SegmentPlanner PLANNER = new SegmentPlanner();

    private SegmentedTransfer() {}

    static boolean tryAccelerate(Object thread, HttpURLConnection original) {
        if (thread == null || original == null) {
            return false;
        }

        Runtime rt = null;
        int permits = 0;
        boolean destinationTouched = false;

        try {
            rt = Runtime.inspect(thread, original);
            if (!rt.eligible()) {
                log("passthrough: " + rt.rejectReason);
                return false;
            }

            RangeProbe.Result probe = probeRange(rt);
            if (!probe.eligible) {
                log("passthrough: range probe " + probe.reason);
                return false;
            }
            if (probe.totalBytes != rt.totalBytes) {
                log("passthrough: probe total changed "
                        + probe.totalBytes + " != " + rt.totalBytes);
                return false;
            }
            if (probe.validatorKind != RangeProbe.ValidatorKind.STRONG_ETAG
                    || !rt.etag.equals(probe.validator)) {
                log("passthrough: validator changed during probe");
                return false;
            }

            Conditions conditions = detectConditions(rt.context, rt.network);
            int workers = ADAPTIVE.workers(rt.totalBytes, conditions);
            workers = acquireBudget(workers);
            if (workers < 2) {
                log("passthrough: global connection budget busy");
                return false;
            }
            permits = workers;

            List<Segment> segments = PLANNER.plan(rt.totalBytes, workers);
            if (segments.size() < 2) {
                return false;
            }

            destinationTouched = prepareDestination(rt);
            if (!destinationTouched) {
                log("passthrough: destination is not safely seekable");
                return false;
            }

            log("accelerating bytes=" + rt.totalBytes
                    + " workers=" + workers
                    + " network=" + conditions.networkKind
                    + " metered=" + conditions.metered);

            boolean success = transferSegments(rt, segments);
            if (!success) {
                rollback(rt);
                log("segmented transfer failed; native path restored");
                return false;
            }

            finishProgress(rt);
            log("accelerated transfer complete bytes=" + rt.totalBytes
                    + " workers=" + workers);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "segmented path failed closed", unwrap(t));
            if (rt != null && destinationTouched) {
                try {
                    rollback(rt);
                } catch (Throwable rollbackError) {
                    Log.w(TAG, "rollback failed", unwrap(rollbackError));
                }
            }
            return false;
        } finally {
            if (permits > 0) {
                GLOBAL_SOCKETS.release(permits);
            }
        }
    }

    private static RangeProbe.Result probeRange(Runtime rt) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = open(rt, rt.url);
            applyOriginalHeaders(rt, conn);
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setRequestProperty("If-Match", rt.etag);
            conn.setRequestProperty("Accept-Encoding", "identity");

            int status = conn.getResponseCode();
            RangeProbe.Result result = RangeProbe.parse(
                    status,
                    conn.getHeaderField("Content-Range"),
                    conn.getHeaderFieldLong("Content-Length", -1L),
                    conn.getHeaderField("ETag"),
                    conn.getHeaderField("Last-Modified"),
                    conn.getHeaderField("Content-Encoding"));

            if (status == 206) {
                try (InputStream in = conn.getInputStream()) {
                    // Consume the one-byte probe body so HttpEngine/URLConnection
                    // can close cleanly without leaving a half-read response.
                    in.read();
                }
            }
            return result;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean prepareDestination(Runtime rt) {
        try (ParcelFileDescriptor pfd = rt.context.getContentResolver()
                .openFileDescriptor(rt.downloadUri, "rw")) {
            if (pfd == null) {
                return false;
            }

            FileDescriptor fd = pfd.getFileDescriptor();
            Os.lseek(fd, 0L, OsConstants.SEEK_SET);

            // Match AOSP's pre-flight allocation when StorageManager supports it.
            try {
                Object storage = getField(rt.thread, "mStorage");
                Method supported = findMethod(
                        storage.getClass(), "isAllocationSupported", FileDescriptor.class);
                Method allocate = findMethod(
                        storage.getClass(), "allocateBytes", FileDescriptor.class, long.class);
                if (Boolean.TRUE.equals(supported.invoke(storage, fd))) {
                    allocate.invoke(storage, fd, rt.totalBytes);
                }
            } catch (Throwable ignored) {
                // Allocation optimization is optional; writes still detect ENOSPC.
            }

            Os.ftruncate(fd, rt.totalBytes);
            fd.sync();
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "seek/preallocation rejected: " + unwrap(t));
            return false;
        }
    }

    private static boolean transferSegments(Runtime rt, List<Segment> segments)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(segments.size());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicLong written = new AtomicLong(0L);
        AtomicLongArray segmentWritten = new AtomicLongArray(segments.size());
        List<Future<Long>> futures = new ArrayList<>(segments.size());

        try (ParcelFileDescriptor progressPfd = rt.context.getContentResolver()
                .openFileDescriptor(rt.downloadUri, "rw")) {
            if (progressPfd == null) {
                return false;
            }
            FileDescriptor progressFd = progressPfd.getFileDescriptor();

            for (int i = 0; i < segments.size(); i++) {
                final int index = i;
                final Segment segment = segments.get(i);
                futures.add(pool.submit(() ->
                        transferOne(
                                rt,
                                segment,
                                index,
                                cancelled,
                                written,
                                segmentWritten)));
            }

            long lastLogBytes = 0L;
            long lastLogTime = android.os.SystemClock.elapsedRealtime();
            long lastControlCheck = lastLogTime;
            long publishedPrefix = 0L;

            boolean done;
            do {
                done = true;
                for (Future<Long> future : futures) {
                    if (!future.isDone()) {
                        done = false;
                        break;
                    }
                }

                long prefix = ContiguousProgress.prefix(
                        segments,
                        snapshotProgress(segmentWritten));
                if (prefix > publishedPrefix) {
                    setLong(rt.delta, "mCurrentBytes", prefix);
                    setBoolean(rt.thread, "mMadeProgress", true);
                    invokeUpdateProgress(rt, progressFd);
                    publishedPrefix = prefix;
                }

                if (!done) {
                    if (getBoolean(rt.thread, "mShutdownRequested")) {
                        cancelled.set(true);
                        return false;
                    }
                    if (getBoolean(rt.thread, "mPolicyDirty")) {
                        invokeNoArgs(rt.thread, "checkConnectivity");
                    }

                    long now = android.os.SystemClock.elapsedRealtime();

                    // Keep AOSP pause/cancel/deleted checks alive without ever
                    // publishing a non-contiguous resume offset.
                    if (now - lastControlCheck >= 750L) {
                        invokeNoArgs(rt.delta, "writeToDatabaseOrThrow");
                        lastControlCheck = now;
                    }

                    if (now - lastLogTime >= 500L) {
                        long bytes = written.get();
                        long delta = bytes - lastLogBytes;
                        long speed = delta <= 0L
                                ? 0L
                                : (delta * 1000L) / Math.max(1L, now - lastLogTime);
                        Log.d(TAG, "segmented progress aggregate=" + bytes
                                + "/" + rt.totalBytes
                                + " contiguous=" + prefix
                                + " speed=" + speed + " B/s");
                        lastLogBytes = bytes;
                        lastLogTime = now;
                    }

                    Thread.sleep(120L);
                }
            } while (!done);

            long sum = 0L;
            for (Future<Long> future : futures) {
                sum += future.get();
            }

            long prefix = ContiguousProgress.prefix(
                        segments,
                        snapshotProgress(segmentWritten));
            if (sum != rt.totalBytes
                    || written.get() != rt.totalBytes
                    || prefix != rt.totalBytes) {
                return false;
            }

            setLong(rt.delta, "mCurrentBytes", rt.totalBytes);
            setBoolean(rt.thread, "mMadeProgress", true);
            invokeUpdateProgress(rt, progressFd);
            progressFd.sync();
            return true;
        } catch (Throwable t) {
            cancelled.set(true);
            Log.w(TAG, "segment worker/coordinator failure", unwrap(t));
            return false;
        } finally {
            cancelled.set(true);
            for (Future<Long> future : futures) {
                future.cancel(true);
            }
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static long[] snapshotProgress(AtomicLongArray progress) {
        long[] snapshot = new long[progress.length()];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = progress.get(i);
        }
        return snapshot;
    }

    private static long transferOne(
            Runtime rt,
            Segment segment,
            int segmentIndex,
            AtomicBoolean cancelled,
            AtomicLong written,
            AtomicLongArray segmentWritten) throws Exception {
        tagTraffic(rt.requestingUid);
        HttpURLConnection conn = null;
        ParcelFileDescriptor pfd = null;
        OutputStream out = null;
        InputStream in = null;

        try {
            if (cancelled.get()) {
                throw new InterruptedException("cancelled before segment start");
            }

            conn = open(rt, rt.url);
            applyOriginalHeaders(rt, conn);
            conn.setRequestProperty(
                    "Range",
                    "bytes=" + segment.startInclusive + "-" + segment.endInclusive);
            conn.setRequestProperty("If-Match", rt.etag);
            conn.setRequestProperty("Accept-Encoding", "identity");

            int status = conn.getResponseCode();
            if (status != 206) {
                throw new IllegalStateException("segment HTTP " + status);
            }

            validateSegmentResponse(rt, segment, conn);

            pfd = rt.context.getContentResolver().openFileDescriptor(rt.downloadUri, "rw");
            if (pfd == null) {
                throw new IllegalStateException("destination PFD unavailable");
            }

            FileDescriptor fd = pfd.getFileDescriptor();
            Os.lseek(fd, segment.startInclusive, OsConstants.SEEK_SET);
            out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
            // Ownership transferred to AutoCloseOutputStream.
            pfd = null;

            in = conn.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = segment.length();
            long local = 0L;

            while (remaining > 0L) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("segment cancelled");
                }

                int wanted = (int) Math.min((long) buffer.length, remaining);
                int count = in.read(buffer, 0, wanted);
                if (count < 0) {
                    throw new IllegalStateException(
                            "early EOF at " + local + "/" + segment.length());
                }

                out.write(buffer, 0, count);
                remaining -= count;
                local += count;
                written.addAndGet(count);
                segmentWritten.addAndGet(segmentIndex, count);
            }

            if (in.read() != -1) {
                throw new IllegalStateException("segment body exceeds Content-Range");
            }

            out.flush();
            fd.sync();
            return local;
        } catch (Exception e) {
            cancelled.set(true);
            throw e;
        } catch (Error e) {
            cancelled.set(true);
            throw e;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(pfd);
            if (conn != null) {
                conn.disconnect();
            }
            clearTraffic();
        }
    }

    private static void validateSegmentResponse(
            Runtime rt, Segment segment, HttpURLConnection conn) {
        long length = conn.getHeaderFieldLong("Content-Length", -1L);
        if (length != segment.length()) {
            throw new IllegalStateException(
                    "bad segment length " + length + " != " + segment.length());
        }

        String expected = "bytes " + segment.startInclusive + "-"
                + segment.endInclusive + "/" + rt.totalBytes;
        String actual = conn.getHeaderField("Content-Range");
        if (actual == null || !expected.equalsIgnoreCase(actual.trim())) {
            throw new IllegalStateException(
                    "bad Content-Range " + actual + " expected " + expected);
        }

        String etag = trim(conn.getHeaderField("ETag"));
        if (!rt.etag.equals(etag)) {
            throw new IllegalStateException("ETag changed during segmented transfer");
        }

        String encoding = trim(conn.getHeaderField("Content-Encoding"));
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            throw new IllegalStateException("encoded segment response: " + encoding);
        }
    }

    private static HttpURLConnection open(Runtime rt, URL url) throws Exception {
        URLConnection opened;
        if (rt.httpEngine != null) {
            Method method = findMethod(rt.httpEngine.getClass(), "openConnection", URL.class);
            opened = (URLConnection) method.invoke(rt.httpEngine, url);
        } else {
            opened = rt.network.openConnection(url);
        }

        if (!(opened instanceof HttpURLConnection)) {
            throw new IllegalStateException("not HTTP: " + opened.getClass());
        }

        HttpURLConnection conn = (HttpURLConnection) opened;
        conn.setInstanceFollowRedirects(false);
        if (rt.httpEngine == null) {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        }
        conn.setReadTimeout(READ_TIMEOUT_MS);

        if (conn instanceof HttpsURLConnection && rt.sslSocketFactory != null) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(rt.sslSocketFactory);
        }
        return conn;
    }

    private static void applyOriginalHeaders(Runtime rt, HttpURLConnection conn)
            throws Exception {
        rt.addRequestHeaders.invoke(rt.thread, conn, false);
        // Our partial transfer owns these headers regardless of caller-supplied
        // Range/If-Match values.
        conn.setRequestProperty("Connection", "close");
    }

    private static void finishProgress(Runtime rt) throws Exception {
        setLong(rt.delta, "mCurrentBytes", rt.totalBytes);
        setBoolean(rt.thread, "mMadeProgress", true);
        invokeNoArgs(rt.delta, "writeToDatabaseOrThrow");
    }

    private static void rollback(Runtime rt) throws Exception {
        try (ParcelFileDescriptor pfd = rt.context.getContentResolver()
                .openFileDescriptor(rt.downloadUri, "rw")) {
            if (pfd != null) {
                Os.ftruncate(pfd.getFileDescriptor(), 0L);
                pfd.getFileDescriptor().sync();
            }
        }

        setLong(rt.delta, "mCurrentBytes", 0L);
        setBoolean(rt.thread, "mMadeProgress", false);
        setLongIfPresent(rt.thread, "mLastUpdateBytes", 0L);
        setLongIfPresent(rt.thread, "mLastUpdateTime", 0L);
        setLongIfPresent(rt.thread, "mSpeed", 0L);
        setLongIfPresent(rt.thread, "mSpeedSampleStart", 0L);
        setLongIfPresent(rt.thread, "mSpeedSampleBytes", 0L);
        invokeNoArgs(rt.delta, "writeToDatabaseOrThrow");
    }

    private static Conditions detectConditions(Context context, Network network) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(network);

            Conditions.NetworkKind kind = Conditions.NetworkKind.UNKNOWN;
            boolean metered = true;

            if (caps != null) {
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    kind = Conditions.NetworkKind.VPN;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    kind = Conditions.NetworkKind.WIFI;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    kind = Conditions.NetworkKind.ETHERNET;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    kind = Conditions.NetworkKind.CELLULAR;
                }
            }

            PowerManager pm = context.getSystemService(PowerManager.class);
            boolean powerSave = pm != null && pm.isPowerSaveMode();
            int thermal = pm == null ? 0 : pm.getCurrentThermalStatus();

            return new Conditions(kind, metered, powerSave, thermal);
        } catch (Throwable ignored) {
            return new Conditions(
                    Conditions.NetworkKind.UNKNOWN, true, false, 0);
        }
    }

    private static int acquireBudget(int requested) {
        int workers = Math.max(1, requested);
        while (workers >= 2) {
            if (GLOBAL_SOCKETS.tryAcquire(workers)) {
                return workers;
            }
            workers--;
        }
        return 0;
    }

    private static void invokeUpdateProgress(Runtime rt, FileDescriptor fd)
            throws Exception {
        try {
            rt.updateProgress.invoke(rt.thread, fd);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        try {
            return findMethod(target.getClass(), name).invoke(target);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        return field.get(target);
    }

    private static long getLong(Object target, String name) throws Exception {
        return findField(target.getClass(), name).getLong(target);
    }

    private static boolean getBoolean(Object target, String name) throws Exception {
        return findField(target.getClass(), name).getBoolean(target);
    }

    private static void setLong(Object target, String name, long value) throws Exception {
        findField(target.getClass(), name).setLong(target, value);
    }

    private static void setBoolean(Object target, String name, boolean value)
            throws Exception {
        findField(target.getClass(), name).setBoolean(target, value);
    }

    private static void setLongIfPresent(Object target, String name, long value) {
        try {
            setLong(target, name, value);
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params)
            throws Exception {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Method method = cursor.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void tagTraffic(int uid) {
        try {
            Method tag = android.net.TrafficStats.class
                    .getDeclaredMethod("setThreadStatsTagDownload");
            tag.setAccessible(true);
            tag.invoke(null);
        } catch (Throwable ignored) {
        }
        try {
            Method setUid = android.net.TrafficStats.class
                    .getDeclaredMethod("setThreadStatsUid", int.class);
            setUid.setAccessible(true);
            setUid.invoke(null, uid);
        } catch (Throwable ignored) {
        }
    }

    private static void clearTraffic() {
        try {
            android.net.TrafficStats.clearThreadStatsTag();
        } catch (Throwable ignored) {
        }
        try {
            Method clearUid = android.net.TrafficStats.class
                    .getDeclaredMethod("clearThreadStatsUid");
            clearUid.setAccessible(true);
            clearUid.invoke(null);
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException
                && ((InvocationTargetException) t).getCause() != null) {
            t = ((InvocationTargetException) t).getCause();
        }
        return t;
    }

    private static String trim(String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }

    private static void log(String message) {
        Log.i(TAG, message);
    }

    private static final class Runtime {
        final Object thread;
        final Object info;
        final Object delta;
        final Context context;
        final Network network;
        final Object httpEngine;
        final android.net.Uri downloadUri;
        final URL url;
        final long totalBytes;
        final long currentBytes;
        final String etag;
        final String mimeType;
        final int requestingUid;
        final Method addRequestHeaders;
        final Method updateProgress;
        final SSLSocketFactory sslSocketFactory;
        final String rejectReason;

        Runtime(
                Object thread,
                Object info,
                Object delta,
                Context context,
                Network network,
                Object httpEngine,
                android.net.Uri downloadUri,
                URL url,
                long totalBytes,
                long currentBytes,
                String etag,
                String mimeType,
                int requestingUid,
                Method addRequestHeaders,
                Method updateProgress,
                SSLSocketFactory sslSocketFactory,
                String rejectReason) {
            this.thread = thread;
            this.info = info;
            this.delta = delta;
            this.context = context;
            this.network = network;
            this.httpEngine = httpEngine;
            this.downloadUri = downloadUri;
            this.url = url;
            this.totalBytes = totalBytes;
            this.currentBytes = currentBytes;
            this.etag = etag;
            this.mimeType = mimeType;
            this.requestingUid = requestingUid;
            this.addRequestHeaders = addRequestHeaders;
            this.updateProgress = updateProgress;
            this.sslSocketFactory = sslSocketFactory;
            this.rejectReason = rejectReason;
        }

        boolean eligible() {
            return rejectReason == null;
        }

        static Runtime inspect(Object thread, HttpURLConnection original)
                throws Exception {
            Object info = getField(thread, "mInfo");
            Object delta = getField(thread, "mInfoDelta");
            Context context = (Context) getField(thread, "mContext");
            Network network = (Network) getField(thread, "mNetwork");
            Object httpEngine = null;
            try {
                httpEngine = getField(thread, "mHttpEngine");
            } catch (Throwable ignored) {
            }

            long total = getLong(delta, "mTotalBytes");
            long current = getLong(delta, "mCurrentBytes");
            String etag = trim((String) getField(delta, "mETag"));
            String mime = trim((String) getField(delta, "mMimeType"));
            int uid = findField(info.getClass(), "mUid").getInt(info);

            Method allUri = findMethod(info.getClass(), "getAllDownloadsUri");
            android.net.Uri uri = (android.net.Uri) allUri.invoke(info);

            Method addHeaders = findMethod(
                    thread.getClass(),
                    "addRequestHeaders",
                    HttpURLConnection.class,
                    boolean.class);
            Method updateProgress = findMethod(
                    thread.getClass(), "updateProgress", FileDescriptor.class);

            SSLSocketFactory ssl = null;
            if (original instanceof HttpsURLConnection) {
                try {
                    ssl = ((HttpsURLConnection) original).getSSLSocketFactory();
                } catch (Throwable ignored) {
                }
            }

            String reject = null;
            if (current != 0L) {
                reject = "resume in progress";
            } else if (total < MIN_BYTES) {
                reject = "file below 8 MiB";
            } else if (etag == null || etag.regionMatches(true, 0, "W/", 0, 2)) {
                reject = "no strong ETag";
            } else {
                String encoding = trim(original.getHeaderField("Content-Encoding"));
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    reject = "initial response encoded=" + encoding;
                }
            }

            if (reject == null && isDrm(thread, mime)) {
                reject = "DRM conversion required";
            }

            return new Runtime(
                    thread,
                    info,
                    delta,
                    context,
                    network,
                    httpEngine,
                    uri,
                    original.getURL(),
                    total,
                    current,
                    etag,
                    mime,
                    uid,
                    addHeaders,
                    updateProgress,
                    ssl,
                    reject);
        }

        private static boolean isDrm(Object thread, String mime) {
            try {
                ClassLoader loader = thread.getClass().getClassLoader();
                Class<?> drm = loader.loadClass(
                        "com.android.providers.downloads.DownloadDrmHelper");
                Method method = findMethod(
                        drm, "isDrmConvertNeeded", String.class);
                return Boolean.TRUE.equals(method.invoke(null, mime));
            } catch (Throwable ignored) {
                // If OEM removed the legacy DRM helper, ordinary files remain eligible.
                return false;
            }
        }
    }
}
