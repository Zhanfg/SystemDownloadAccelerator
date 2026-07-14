package io.github.zhanfg.sda.xposed;

import android.content.SharedPreferences;
import android.net.Network;
import android.system.Os;
import android.util.Log;

import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Real libxposed API 102 entry for the Android/ColorOS system DownloadProvider.
 *
 * <p>The first implementation deliberately hooks the vendor copy loop instead of replacing the
 * whole download thread. The system still owns destination creation, pre-allocation, status
 * transitions, notifications and final fsync. When ranged transfer is not provably safe the
 * original copy loop is executed unchanged.</p>
 */
public final class RealDownloadAcceleratorModule extends XposedModule {
    private static final String TAG = "SysDownloadAccel";
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final String[] TRANSFER_CLASSES = {
            "com.android.providers.downloads.d",
            "com.android.providers.downloads.e"
    };

    private static final ThreadLocal<ActiveTransfer> ACTIVE_TRANSFER = new ThreadLocal<>();
    private static final Map<Object, AtomicBoolean> CANCEL_FLAGS =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Real engine loaded in " + param.getProcessName()
                + ", API " + getApiVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        final ClassLoader classLoader = param.getClassLoader();
        for (String className : TRANSFER_CLASSES) {
            try {
                installTransferHooks(classLoader, className);
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Failed to install real engine hooks for " + className, error);
            }
        }
    }

    private void installTransferHooks(ClassLoader classLoader, String className) throws Exception {
        Class<?> transferClass = Class.forName(className, false, classLoader);
        Method transferMethod = findMethod(
                transferClass,
                "u",
                HttpURLConnection.class
        );
        Method copyMethod = findMethod(
                transferClass,
                "t",
                InputStream.class,
                OutputStream.class,
                FileDescriptor.class
        );

        transferMethod.setAccessible(true);
        copyMethod.setAccessible(true);

        hook(transferMethod)
                .setId("sda.active-connection." + className)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    ActiveTransfer previous = ACTIVE_TRANSFER.get();
                    HttpURLConnection connection = (HttpURLConnection) chain.getArg(0);
                    ACTIVE_TRANSFER.set(new ActiveTransfer(chain.getThisObject(), connection));
                    try {
                        return chain.proceed();
                    } finally {
                        if (previous == null) {
                            ACTIVE_TRANSFER.remove();
                        } else {
                            ACTIVE_TRANSFER.set(previous);
                        }
                    }
                });

        hook(copyMethod)
                .setId("sda.range-copy." + className)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> interceptCopyLoop(className, chain));

        Method cancelMethod = findOptionalMethod(transferClass, "s");
        if (cancelMethod != null) {
            cancelMethod.setAccessible(true);
            hook(cancelMethod)
                    .setId("sda.cancel." + className)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        cancelFlag(chain.getThisObject()).set(true);
                        return chain.proceed();
                    });
        }

        log(Log.INFO, TAG, "Real hooks installed: " + className + ".u/.t"
                + (cancelMethod == null ? "" : "/.s"));
    }

    private Object interceptCopyLoop(String className, XposedInterface.Chain chain) throws Throwable {
        Object owner = chain.getThisObject();
        ActiveTransfer active = ACTIVE_TRANSFER.get();
        if (active == null || active.owner != owner || active.connection == null) {
            return chain.proceed();
        }

        OutputStream output = (OutputStream) chain.getArg(1);
        FileDescriptor fileDescriptor = (FileDescriptor) chain.getArg(2);
        if (output == null || fileDescriptor == null || !fileDescriptor.valid()) {
            return chain.proceed();
        }
        if (output.getClass().getName().contains("DrmOutputStream")) {
            log(Log.INFO, TAG, "DRM stream detected; using system transfer");
            return chain.proceed();
        }

        Settings settings = Settings.load(this);
        if (!settings.enabled) {
            return chain.proceed();
        }

        AtomicBoolean cancelled = cancelFlag(owner);
        cancelled.set(false);

        RangeEngine engine = new RangeEngine(this, owner, active.connection, fileDescriptor,
                cancelled, settings, className);
        RangeResult result;
        try {
            result = engine.execute();
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Range engine aborted before commit; falling back", error);
            return chain.proceed();
        }

        if (!result.handled) {
            if (result.reason != null) {
                log(Log.INFO, TAG, "System fallback: " + result.reason);
            }
            return chain.proceed();
        }

        log(Log.INFO, TAG, "Ranged transfer complete: " + result.bytes
                + " bytes using " + result.threads + " workers");
        return null;
    }

    private static AtomicBoolean cancelFlag(Object owner) {
        synchronized (CANCEL_FLAGS) {
            AtomicBoolean flag = CANCEL_FLAGS.get(owner);
            if (flag == null) {
                flag = new AtomicBoolean(false);
                CANCEL_FLAGS.put(owner, flag);
            }
            return flag;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Method findOptionalMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return findMethod(type, name, parameters);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static final class ActiveTransfer {
        final Object owner;
        final HttpURLConnection connection;

        ActiveTransfer(Object owner, HttpURLConnection connection) {
            this.owner = owner;
            this.connection = connection;
        }
    }

    private static final class Settings {
        final boolean enabled;
        final boolean strictRange;
        final boolean autoFallback;
        final int maxThreads;
        final int initialThreads;
        final long minimumSize;
        final long minimumPartSize;

        Settings(boolean enabled, boolean strictRange, boolean autoFallback, int maxThreads,
                 int initialThreads, long minimumSize, long minimumPartSize) {
            this.enabled = enabled;
            this.strictRange = strictRange;
            this.autoFallback = autoFallback;
            this.maxThreads = maxThreads;
            this.initialThreads = initialThreads;
            this.minimumSize = minimumSize;
            this.minimumPartSize = minimumPartSize;
        }

        static Settings load(RealDownloadAcceleratorModule module) {
            boolean enabled = true;
            boolean strictRange = true;
            boolean autoFallback = true;
            int maxThreads = 1024;
            int initialThreads = 4;
            int minimumSizeMb = 32;
            int minimumPartSizeMb = 16;
            try {
                SharedPreferences preferences = module.getRemotePreferences("module_settings");
                enabled = preferences.getBoolean("enabled", enabled);
                strictRange = preferences.getBoolean("strict_range", strictRange);
                autoFallback = preferences.getBoolean("auto_fallback", autoFallback);
                maxThreads = clamp(preferences.getInt("max_threads", maxThreads), 1, 1024);
                initialThreads = clamp(preferences.getInt("initial_threads", initialThreads), 1, 1024);
                minimumSizeMb = clamp(preferences.getInt("min_size_mb", minimumSizeMb), 1, 1024 * 1024);
            } catch (Throwable ignored) {
                // The UI-to-Xposed service bridge is installed in the next stage. Safe defaults remain active.
            }
            return new Settings(enabled, strictRange, autoFallback, maxThreads, initialThreads,
                    minimumSizeMb * 1024L * 1024L,
                    minimumPartSizeMb * 1024L * 1024L);
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }

    private static final class RangeEngine {
        private static final Pattern CONTENT_RANGE = Pattern.compile(
                "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
                Pattern.CASE_INSENSITIVE
        );

        private final RealDownloadAcceleratorModule module;
        private final Object owner;
        private final HttpURLConnection baseConnection;
        private final FileDescriptor fileDescriptor;
        private final AtomicBoolean cancelled;
        private final Settings settings;
        private final String className;
        private final Object state;
        private final Field totalField;
        private final Field currentField;
        private final Field mimeField;
        private final Field etagField;
        private final Field urlField;
        private final Method configureConnectionMethod;
        private final Method progressFlushMethod;
        private final Object info;
        private final Field infoCurrentField;
        private final Field ownerWroteField;
        private final String destinationPath;

        RangeEngine(RealDownloadAcceleratorModule module, Object owner,
                    HttpURLConnection baseConnection, FileDescriptor fileDescriptor,
                    AtomicBoolean cancelled, Settings settings, String className) throws Exception {
            this.module = module;
            this.owner = owner;
            this.baseConnection = baseConnection;
            this.fileDescriptor = fileDescriptor;
            this.cancelled = cancelled;
            this.settings = settings;
            this.className = className;

            this.state = findState(owner);
            Class<?> stateClass = state.getClass();
            this.totalField = field(stateClass, "g");
            this.currentField = field(stateClass, "h");
            this.mimeField = field(stateClass, "c");
            this.etagField = field(stateClass, "i");
            this.urlField = field(stateClass, "p");

            this.configureConnectionMethod = findMethod(owner.getClass(), "g", HttpURLConnection.class);
            this.progressFlushMethod = findMethod(owner.getClass(), "v", FileDescriptor.class);
            this.info = findInfo(owner);
            this.infoCurrentField = findFieldInHierarchy(info.getClass(), "k");
            this.ownerWroteField = findOwnerWroteField(owner.getClass());
            this.destinationPath = readStringField(owner, "v");
        }

        RangeResult execute() throws Throwable {
            long total = totalField.getLong(state);
            long current = currentField.getLong(state);
            long remaining = total - current;
            if (total <= 0 || current < 0 || remaining <= 0) {
                return RangeResult.fallback("unknown or completed length");
            }
            if (remaining < settings.minimumSize) {
                return RangeResult.fallback("remaining data below threshold");
            }

            String mime = stringValue(mimeField.get(state));
            if (mime != null && mime.toLowerCase(Locale.ROOT).contains("drm")) {
                return RangeResult.fallback("DRM MIME type");
            }

            URL url = baseConnection.getURL();
            if (url == null) {
                String stateUrl = stringValue(urlField.get(state));
                if (stateUrl == null || stateUrl.isEmpty()) {
                    return RangeResult.fallback("missing source URL");
                }
                url = new URL(stateUrl);
            }

            String etag = stringValue(etagField.get(state));
            if (!supportsRanges(url, current, total, etag)) {
                return RangeResult.fallback("server did not validate byte ranges");
            }

            int threadCount = chooseThreadCount(remaining);
            if (threadCount < 2) {
                return RangeResult.fallback("scheduler selected one worker");
            }

            List<Segment> segments = plan(current, total, threadCount);
            if (segments.size() < 2) {
                return RangeResult.fallback("file cannot be split safely");
            }

            long startedAt = System.nanoTime();
            AtomicLong transferred = new AtomicLong();
            ExecutorService executor = Executors.newFixedThreadPool(
                    segments.size(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "SDA-range-worker");
                        thread.setDaemon(true);
                        return thread;
                    }
            );
            List<Future<Long>> futures = new ArrayList<>(segments.size());
            try {
                for (Segment segment : segments) {
                    URL finalUrl = url;
                    futures.add(executor.submit(new RangeWorker(
                            finalUrl, segment, total, etag, transferred
                    )));
                }

                long completed = 0;
                for (Future<Long> future : futures) {
                    completed += future.get();
                }
                if (completed != remaining) {
                    throw new EOFException("Ranged byte count mismatch: " + completed + "/" + remaining);
                }
                if (cancelled.get()) {
                    throw new CancellationException("system download cancelled");
                }

                if (!verifyExpectedMd5()) {
                    throw new IOException("Vendor MD5 verification failed");
                }

                // Commit progress only after every segment and optional checksum succeeded. Until this
                // point the original system InputStream remains untouched, so any failure can safely
                // fall back and overwrite all speculative positional writes.
                currentField.setLong(state, total);
                if (infoCurrentField != null) {
                    infoCurrentField.setLong(info, total);
                }
                if (ownerWroteField != null) {
                    ownerWroteField.setBoolean(owner, true);
                }
                progressFlushMethod.invoke(owner, fileDescriptor);

                long elapsedNanos = Math.max(1L, System.nanoTime() - startedAt);
                long bytesPerSecond = (long) ((remaining * 1_000_000_000.0) / elapsedNanos);
                setOptionalLongField(state, "k", bytesPerSecond);
                return RangeResult.success(remaining, segments.size());
            } catch (Throwable error) {
                cancelled.set(true);
                for (Future<Long> future : futures) {
                    future.cancel(true);
                }
                if (!settings.autoFallback) {
                    throw unwrap(error);
                }
                module.log(Log.WARN, TAG, "Speculative range transfer failed; system stream will overwrite it", unwrap(error));
                return RangeResult.fallback("range worker failure");
            } finally {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private boolean supportsRanges(URL url, long current, long total, String etag) throws Exception {
            int baseCode = baseConnection.getResponseCode();
            if (baseCode == HttpURLConnection.HTTP_PARTIAL) {
                String header = baseConnection.getHeaderField("Content-Range");
                ContentRange parsed = ContentRange.parse(header);
                if (parsed != null && parsed.total == total && parsed.start == current) {
                    return true;
                }
            }
            String acceptRanges = baseConnection.getHeaderField("Accept-Ranges");
            if ("bytes".equalsIgnoreCase(acceptRanges)) {
                return true;
            }
            if (!settings.strictRange) {
                return false;
            }

            HttpURLConnection probe = null;
            try {
                probe = openConnection(url, current, current, etag);
                int response = probe.getResponseCode();
                if (response != HttpURLConnection.HTTP_PARTIAL) {
                    return false;
                }
                ContentRange parsed = ContentRange.parse(probe.getHeaderField("Content-Range"));
                return parsed != null && parsed.start == current && parsed.end == current
                        && parsed.total == total;
            } finally {
                if (probe != null) {
                    InputStream stream = probe.getErrorStream();
                    if (stream == null) {
                        try {
                            stream = probe.getInputStream();
                        } catch (IOException ignored) {
                            stream = null;
                        }
                    }
                    closeQuietly(stream);
                    probe.disconnect();
                }
            }
        }

        private int chooseThreadCount(long remaining) {
            int automatic;
            if (remaining < 256L * 1024L * 1024L) {
                automatic = 2;
            } else if (remaining < 2L * 1024L * 1024L * 1024L) {
                automatic = 4;
            } else if (remaining < 8L * 1024L * 1024L * 1024L) {
                automatic = 8;
            } else {
                automatic = 16;
            }
            int requested = Math.max(settings.initialThreads, automatic);
            int byPartSize = (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                    remaining / settings.minimumPartSize));
            return Math.max(1, Math.min(Math.min(settings.maxThreads, requested), byPartSize));
        }

        private List<Segment> plan(long start, long total, int count) {
            long remaining = total - start;
            int actual = (int) Math.min(count, Math.max(1L, remaining / settings.minimumPartSize));
            if (actual < 2) {
                return Collections.singletonList(new Segment(start, total - 1));
            }
            List<Segment> segments = new ArrayList<>(actual);
            long baseSize = remaining / actual;
            long extra = remaining % actual;
            long cursor = start;
            for (int index = 0; index < actual; index++) {
                long size = baseSize + (index < extra ? 1 : 0);
                long end = cursor + size - 1;
                segments.add(new Segment(cursor, end));
                cursor = end + 1;
            }
            return segments;
        }

        private HttpURLConnection openConnection(URL url, long start, long end, String etag)
                throws Exception {
            HttpURLConnection connection;
            Network network = findNetwork(owner);
            if (network != null) {
                connection = (HttpURLConnection) network.openConnection(url);
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }
            connection.setConnectTimeout(nonZero(baseConnection.getConnectTimeout(), 20_000));
            connection.setReadTimeout(nonZero(baseConnection.getReadTimeout(), 30_000));
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);

            try {
                configureConnectionMethod.invoke(owner, connection);
            } catch (InvocationTargetException invocation) {
                throwAsException(invocation.getCause());
            }
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            if (etag != null && !etag.isEmpty()) {
                connection.setRequestProperty("If-Match", etag);
            }
            return connection;
        }

        private boolean verifyExpectedMd5() {
            try {
                String expected = readStringField(info, "Q");
                if (expected == null || expected.isEmpty()) {
                    return true;
                }
                if (destinationPath == null || destinationPath.isEmpty()) {
                    return false;
                }
                File file = new File(destinationPath);
                if (!file.isFile()) {
                    return false;
                }
                MessageDigest digest = MessageDigest.getInstance("MD5");
                try (InputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
                StringBuilder actual = new StringBuilder(32);
                for (byte value : digest.digest()) {
                    actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                }
                return expected.equalsIgnoreCase(actual.toString());
            } catch (Throwable error) {
                module.log(Log.WARN, TAG, "Unable to complete vendor MD5 verification", error);
                return false;
            }
        }

        private final class RangeWorker implements Callable<Long> {
            private final URL url;
            private final Segment segment;
            private final long expectedTotal;
            private final String etag;
            private final AtomicLong transferred;

            RangeWorker(URL url, Segment segment, long expectedTotal, String etag,
                        AtomicLong transferred) {
                this.url = url;
                this.segment = segment;
                this.expectedTotal = expectedTotal;
                this.etag = etag;
                this.transferred = transferred;
            }

            @Override
            public Long call() throws Exception {
                HttpURLConnection connection = null;
                InputStream input = null;
                try {
                    checkCancelled();
                    connection = openConnection(url, segment.start, segment.end, etag);
                    int response = connection.getResponseCode();
                    if (response != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IOException("Expected HTTP 206, got " + response);
                    }
                    ContentRange contentRange = ContentRange.parse(
                            connection.getHeaderField("Content-Range"));
                    if (contentRange == null
                            || contentRange.start != segment.start
                            || contentRange.end != segment.end
                            || contentRange.total != expectedTotal) {
                        throw new IOException("Invalid Content-Range for " + segment);
                    }
                    String workerEtag = connection.getHeaderField("ETag");
                    if (etag != null && workerEtag != null && !etag.equals(workerEtag)) {
                        throw new IOException("ETag changed during transfer");
                    }

                    input = connection.getInputStream();
                    byte[] buffer = new byte[128 * 1024];
                    long position = segment.start;
                    long remaining = segment.length();
                    while (remaining > 0) {
                        checkCancelled();
                        int request = (int) Math.min(buffer.length, remaining);
                        int read = input.read(buffer, 0, request);
                        if (read < 0) {
                            throw new EOFException("Premature EOF in " + segment);
                        }
                        PositionalWriter.write(fileDescriptor, buffer, 0, read, position);
                        position += read;
                        remaining -= read;
                        transferred.addAndGet(read);
                    }
                    if (input.read() != -1) {
                        throw new IOException("Server exceeded declared range " + segment);
                    }
                    return segment.length();
                } finally {
                    closeQuietly(input);
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            private void checkCancelled() {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("range worker cancelled");
                }
            }
        }

        private static int nonZero(int value, int fallback) {
            return value > 0 ? value : fallback;
        }
    }

    private static final class PositionalWriter {
        private static final Method PWRITE_ARRAY = findPwriteArray();

        static void write(FileDescriptor descriptor, byte[] data, int offset, int length,
                          long fileOffset) throws Exception {
            int writtenTotal = 0;
            if (PWRITE_ARRAY != null) {
                while (writtenTotal < length) {
                    Object result = PWRITE_ARRAY.invoke(null, descriptor, data,
                            offset + writtenTotal, length - writtenTotal,
                            fileOffset + writtenTotal);
                    int written = ((Number) result).intValue();
                    if (written <= 0) {
                        throw new IOException("pwrite returned " + written);
                    }
                    writtenTotal += written;
                }
                return;
            }

            // Fallback for framework builds exposing only ByteBuffer positional I/O.
            FileOutputStream stream = new FileOutputStream(descriptor);
            FileChannel channel = stream.getChannel();
            ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
            long position = fileOffset;
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer, position);
                if (written <= 0) {
                    throw new IOException("FileChannel positional write returned " + written);
                }
                position += written;
            }
            // Do not close the wrapper: it references the DownloadProvider-owned descriptor.
        }

        private static Method findPwriteArray() {
            try {
                Method method = Os.class.getMethod("pwrite", FileDescriptor.class,
                        byte[].class, int.class, int.class, long.class);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static final class ContentRange {
        final long start;
        final long end;
        final long total;

        ContentRange(long start, long end, long total) {
            this.start = start;
            this.end = end;
            this.total = total;
        }

        static ContentRange parse(String header) {
            if (header == null) {
                return null;
            }
            Matcher matcher = RangeEngine.CONTENT_RANGE.matcher(header.trim());
            if (!matcher.matches() || "*".equals(matcher.group(3))) {
                return null;
            }
            try {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                long total = Long.parseLong(matcher.group(3));
                if (start < 0 || end < start || total <= end) {
                    return null;
                }
                return new ContentRange(start, end, total);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static final class Segment {
        final long start;
        final long end;

        Segment(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start + 1;
        }

        @Override
        public String toString() {
            return start + "-" + end;
        }
    }

    private static final class RangeResult {
        final boolean handled;
        final long bytes;
        final int threads;
        final String reason;

        private RangeResult(boolean handled, long bytes, int threads, String reason) {
            this.handled = handled;
            this.bytes = bytes;
            this.threads = threads;
            this.reason = reason;
        }

        static RangeResult success(long bytes, int threads) {
            return new RangeResult(true, bytes, threads, null);
        }

        static RangeResult fallback(String reason) {
            return new RangeResult(false, 0, 0, reason);
        }
    }

    private static Object findState(Object owner) throws IllegalAccessException {
        String expectedName = owner.getClass().getName() + "$c";
        for (Field candidate : owner.getClass().getDeclaredFields()) {
            if (expectedName.equals(candidate.getType().getName())) {
                candidate.setAccessible(true);
                Object value = candidate.get(owner);
                if (value != null) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("Vendor transfer state not found");
    }

    private static Object findInfo(Object owner) throws IllegalAccessException {
        for (Field candidate : owner.getClass().getDeclaredFields()) {
            if ("com.android.providers.downloads.a".equals(candidate.getType().getName())) {
                candidate.setAccessible(true);
                Object value = candidate.get(owner);
                if (value != null) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("DownloadInfo field not found");
    }

    private static Network findNetwork(Object owner) {
        for (Field candidate : owner.getClass().getDeclaredFields()) {
            if (Network.class.isAssignableFrom(candidate.getType())) {
                try {
                    candidate.setAccessible(true);
                    return (Network) candidate.get(owner);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Field findOwnerWroteField(Class<?> ownerClass) {
        String preferred = ownerClass.getName().endsWith(".d") ? "k" : "i";
        try {
            Field field = ownerClass.getDeclaredField(preferred);
            if (field.getType() == boolean.class) {
                field.setAccessible(true);
                return field;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field findFieldInHierarchy(Class<?> type, String name) {
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
        return null;
    }

    private static String readStringField(Object object, String name) {
        try {
            Field field = findFieldInHierarchy(object.getClass(), name);
            return field == null ? null : stringValue(field.get(object));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setOptionalLongField(Object object, String name, long value) {
        try {
            Field field = findFieldInHierarchy(object.getClass(), name);
            if (field != null && field.getType() == long.class) {
                field.setLong(object, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null) {
            return ((InvocationTargetException) error).getCause();
        }
        return error;
    }

    private static void throwAsException(Throwable error) throws Exception {
        if (error instanceof Exception) {
            throw (Exception) error;
        }
        if (error instanceof Error) {
            throw (Error) error;
        }
        throw new RuntimeException(error);
    }

    private static void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }
}
