package io.github.zhanfg.sda;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Bounded HTTP Range accelerator for the ColorOS DownloadProvider.
 *
 * <p>Parallel workers only write private temporary chunks. The original vendor copy method remains
 * the sole writer to the DownloadProvider-owned destination descriptor. Acceleration is enabled
 * only when the original request Range state, server response window, local destination offset,
 * resource validator and a strict 206 probe all agree. Any uncertainty before accelerated bytes
 * are exposed falls back to the system input stream.</p>
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "SysDownloadAccel";
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final String[] TRANSFER_CLASSES = {
            "com.android.providers.downloads.d",
            "com.android.providers.downloads.e"
    };
    private static final String STATUS_URI = "content://io.github.zhanfg.sda.rootbridge";
    private static final long MAX_SPOOL_WINDOW_BYTES = 512L * 1024L * 1024L;

    private static final ThreadLocal<ActiveTransfer> ACTIVE_TRANSFER = new ThreadLocal<>();
    private static final Map<Object, ParallelRangeInputStream> ACTIVE_SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<HttpURLConnection, RequestSnapshot> REQUEST_SNAPSHOTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<File> ACTIVE_SPOOL_DIRS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Range engine loaded in " + param.getProcessName()
                + ", API " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        for (String className : TRANSFER_CLASSES) {
            try {
                installHooks(param.getClassLoader(), className);
            } catch (Throwable error) {
                log(Log.WARN, TAG, "Transfer class unavailable: " + className, error);
            }
        }
    }

    private void installHooks(ClassLoader classLoader, String className) throws Exception {
        Class<?> transferClass = Class.forName(className, false, classLoader);
        Method transferMethod = findTransferMethod(transferClass);
        Method copyMethod = findCopyMethod(transferClass);
        Method configureMethod = findConfigureMethod(transferClass);

        if (transferMethod == null || copyMethod == null || configureMethod == null) {
            log(Log.WARN, TAG, "Compatibility preflight rejected " + className
                    + ": required transfer/copy/configure methods were not found uniquely");
            return;
        }
        if (copyMethod.getReturnType() != Void.TYPE) {
            log(Log.WARN, TAG, "Compatibility preflight rejected " + className
                    + ": copy method is not void");
            return;
        }

        transferMethod.setAccessible(true);
        copyMethod.setAccessible(true);
        configureMethod.setAccessible(true);

        hook(configureMethod)
                .setId("sda.request-snapshot." + className)
                .setPriority(PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    HttpURLConnection connection = (HttpURLConnection) chain.getArg(0);
                    if (connection != null) {
                        REQUEST_SNAPSHOTS.put(connection, RequestSnapshot.capture(connection));
                    }
                    return result;
                });

        hook(transferMethod)
                .setId("sda.connection." + className)
                .setPriority(PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    ActiveTransfer previous = ACTIVE_TRANSFER.get();
                    HttpURLConnection connection = (HttpURLConnection) chain.getArg(0);
                    RequestSnapshot configured = REQUEST_SNAPSHOTS.remove(connection);
                    RequestSnapshot latest = RequestSnapshot.capture(connection);
                    RequestSnapshot snapshot = latest.known
                            ? latest
                            : (configured != null ? configured : latest);
                    ACTIVE_TRANSFER.set(new ActiveTransfer(
                            chain.getThisObject(), connection, configureMethod, snapshot));
                    try {
                        return chain.proceed();
                    } finally {
                        if (previous == null) ACTIVE_TRANSFER.remove();
                        else ACTIVE_TRANSFER.set(previous);
                    }
                });

        hook(copyMethod)
                .setId("sda.range-input." + className)
                .setPriority(PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> interceptCopy(chain, className));

        Method cancelMethod = findCancelMethod(transferClass);
        if (cancelMethod != null) {
            cancelMethod.setAccessible(true);
            hook(cancelMethod)
                    .setId("sda.cancel." + className)
                    .setPriority(PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ParallelRangeInputStream session;
                        synchronized (ACTIVE_SESSIONS) {
                            session = ACTIVE_SESSIONS.get(chain.getThisObject());
                        }
                        if (session != null) session.cancel("system cancellation");
                        return chain.proceed();
                    });
        }

        log(Log.INFO, TAG, "Range hooks ready: " + className
                + " transfer=" + transferMethod.getName()
                + " copy=" + copyMethod.getName()
                + " configure=" + configureMethod.getName());
    }

    private Object interceptCopy(XposedInterface.Chain chain, String className) throws Throwable {
        Object owner = chain.getThisObject();
        ActiveTransfer active = ACTIVE_TRANSFER.get();
        if (active == null || active.owner != owner || active.connection == null) {
            return chain.proceed();
        }

        InputStream originalInput = (InputStream) chain.getArg(0);
        OutputStream output = (OutputStream) chain.getArg(1);
        FileDescriptor descriptor = (FileDescriptor) chain.getArg(2);
        if (originalInput == null || output == null || descriptor == null || !descriptor.valid()) {
            return chain.proceed();
        }
        if (output.getClass().getName().contains("DrmOutputStream")) {
            report(owner, "fallback", "DRM output stream", 0, 0);
            return chain.proceed();
        }

        Settings settings = Settings.load(this);
        if (!settings.enabled) {
            report(owner, "disabled", "Range engine disabled or preferences unavailable", 0, 0);
            return chain.proceed();
        }

        Preflight preflight;
        try {
            preflight = Preflight.inspect(owner, active, settings, descriptor);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Preflight error; using system transfer", error);
            report(owner, "fallback", "preflight error: " + error.getClass().getSimpleName(), 0, 0);
            return chain.proceed();
        }
        if (!preflight.accepted) {
            log(Log.INFO, TAG, "System fallback: " + preflight.reason);
            report(owner, "fallback", preflight.reason, 0, preflight.remaining);
            return chain.proceed();
        }

        ParallelRangeInputStream accelerated;
        try {
            accelerated = new ParallelRangeInputStream(this, owner, preflight, settings);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to start Range session; using system transfer", error);
            report(owner, "fallback", "session init: " + error.getClass().getSimpleName(),
                    0, preflight.remaining);
            return chain.proceed();
        }

        synchronized (ACTIVE_SESSIONS) {
            ACTIVE_SESSIONS.put(owner, accelerated);
        }
        report(owner, "active", "parallel Range input", accelerated.workerCount(),
                preflight.remaining);
        long started = System.nanoTime();
        try {
            Object[] args = {accelerated, output, descriptor};
            Object result = chain.proceed(args);
            long elapsed = Math.max(1L, System.nanoTime() - started);
            long bytesPerSecond = (long) (preflight.remaining * 1_000_000_000.0 / elapsed);
            log(Log.INFO, TAG, "Accelerated copy completed: " + preflight.remaining
                    + " bytes, workers=" + accelerated.workerCount()
                    + ", avg=" + bytesPerSecond + " B/s, class=" + className);
            report(owner, "complete", "Range completed at " + bytesPerSecond + " B/s",
                    accelerated.workerCount(), preflight.remaining);
            return result;
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "Accelerated input failed after " + accelerated.deliveredBytes()
                            + " bytes; DownloadProvider retry/resume will remain authoritative",
                    error);
            report(owner, "error", "Range stream: " + error.getClass().getSimpleName(),
                    accelerated.workerCount(), accelerated.deliveredBytes());
            throw error;
        } finally {
            synchronized (ACTIVE_SESSIONS) {
                ACTIVE_SESSIONS.remove(owner);
            }
            accelerated.close();
        }
    }

    private void report(Object owner, String status, String detail, int threads, long bytes) {
        try {
            Context context = findContext(owner);
            if (context == null) return;
            Bundle extras = new Bundle();
            extras.putString("status", status);
            extras.putString("detail", detail == null ? "" : detail);
            extras.putInt("threads", Math.max(0, threads));
            extras.putLong("bytes", Math.max(0L, bytes));
            context.getContentResolver().call(
                    android.net.Uri.parse(STATUS_URI),
                    "report_engine_status", null, extras);
        } catch (Throwable ignored) {
            // Telemetry must never affect the system download path.
        }
    }

    private static Method findTransferMethod(Class<?> type) {
        try {
            Method exact = type.getDeclaredMethod("u", HttpURLConnection.class);
            exact.setAccessible(true);
            return exact;
        } catch (Throwable ignored) {
        }
        Method match = null;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1
                    && HttpURLConnection.class.isAssignableFrom(params[0])
                    && !"g".equals(method.getName())) {
                if (match != null) return null;
                match = method;
            }
        }
        return match;
    }

    private static Method findCopyMethod(Class<?> type) {
        try {
            Method exact = type.getDeclaredMethod(
                    "t", InputStream.class, OutputStream.class, FileDescriptor.class);
            exact.setAccessible(true);
            return exact;
        } catch (Throwable ignored) {
        }
        Method match = null;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 3
                    && InputStream.class.isAssignableFrom(params[0])
                    && OutputStream.class.isAssignableFrom(params[1])
                    && FileDescriptor.class.isAssignableFrom(params[2])) {
                if (match != null) return null;
                match = method;
            }
        }
        return match;
    }

    private static Method findConfigureMethod(Class<?> type) {
        try {
            Method exact = type.getDeclaredMethod("g", HttpURLConnection.class);
            exact.setAccessible(true);
            return exact;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findCancelMethod(Class<?> type) {
        try {
            Method method = type.getDeclaredMethod("s");
            return method.getParameterTypes().length == 0 ? method : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context findContext(Object owner) {
        Class<?> cursor = owner == null ? null : owner.getClass();
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value instanceof Context) return (Context) value;
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static Network findNetwork(Object owner) {
        Class<?> cursor = owner == null ? null : owner.getClass();
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                if (!Network.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value instanceof Network) return (Network) value;
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static Long descriptorOffset(FileDescriptor descriptor) {
        if (descriptor == null || !descriptor.valid()) return null;
        try {
            return Os.lseek(descriptor, 0L, OsConstants.SEEK_CUR);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void cleanupStaleSpool(File root) {
        try {
            File[] files = root.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6);
            for (File file : files) {
                if (!file.isDirectory() || !file.getName().startsWith("sda-range-")) continue;
                File absolute = file.getAbsoluteFile();
                if (ACTIVE_SPOOL_DIRS.contains(absolute)) continue;
                if (file.lastModified() >= cutoff) continue;
                deleteRecursively(file);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static final class RequestSnapshot {
        final boolean known;
        final String rangeHeader;

        RequestSnapshot(boolean known, String rangeHeader) {
            this.known = known;
            this.rangeHeader = rangeHeader;
        }

        static RequestSnapshot capture(HttpURLConnection connection) {
            if (connection == null) return new RequestSnapshot(false, null);
            try {
                return new RequestSnapshot(true, connection.getRequestProperty("Range"));
            } catch (Throwable ignored) {
                return new RequestSnapshot(false, null);
            }
        }
    }

    private static final class ActiveTransfer {
        final Object owner;
        final HttpURLConnection connection;
        final Method configureMethod;
        final RequestSnapshot requestSnapshot;

        ActiveTransfer(Object owner, HttpURLConnection connection, Method configureMethod,
                       RequestSnapshot requestSnapshot) {
            this.owner = owner;
            this.connection = connection;
            this.configureMethod = configureMethod;
            this.requestSnapshot = requestSnapshot;
        }
    }

    private static final class Settings {
        final boolean enabled;
        final int maxThreads;
        final int initialThreads;
        final long minimumSize;
        final long chunkSize;
        final int connectTimeoutMs;
        final int readTimeoutMs;

        Settings(boolean enabled, int maxThreads, int initialThreads,
                 long minimumSize, long chunkSize,
                 int connectTimeoutMs, int readTimeoutMs) {
            this.enabled = enabled;
            this.maxThreads = maxThreads;
            this.initialThreads = initialThreads;
            this.minimumSize = minimumSize;
            this.chunkSize = chunkSize;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }

        static Settings load(ModuleMain module) {
            boolean enabled = false;
            int maxThreads = 8;
            int initialThreads = 4;
            int minimumSizeMb = 32;
            int chunkSizeMb = 16;
            try {
                SharedPreferences preferences = module.getRemotePreferences("module_settings");
                enabled = preferences.getBoolean("enabled", false);
                maxThreads = clamp(preferences.getInt("max_threads", 8), 2, 16);
                initialThreads = clamp(preferences.getInt("initial_threads", 4), 2, maxThreads);
                minimumSizeMb = clamp(preferences.getInt("min_size_mb", 32), 8, 4096);
                chunkSizeMb = clamp(preferences.getInt("chunk_size_mb", 16), 4, 64);
            } catch (Throwable ignored) {
                // Preference IPC failure is fail-closed: normal DownloadProvider transfer wins.
            }
            return new Settings(enabled, maxThreads, initialThreads,
                    minimumSizeMb * 1024L * 1024L,
                    chunkSizeMb * 1024L * 1024L,
                    20_000, 30_000);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class Preflight {
        final boolean accepted;
        final String reason;
        final Object owner;
        final HttpURLConnection baseConnection;
        final Method configureMethod;
        final Object configureLock = new Object();
        final Network network;
        final URL url;
        final Validator validator;
        final long current;
        final long total;
        final long remaining;
        final int threads;

        private Preflight(boolean accepted, String reason, Object owner,
                          HttpURLConnection baseConnection, Method configureMethod,
                          Network network, URL url, Validator validator,
                          long current, long total, int threads) {
            this.accepted = accepted;
            this.reason = reason;
            this.owner = owner;
            this.baseConnection = baseConnection;
            this.configureMethod = configureMethod;
            this.network = network;
            this.url = url;
            this.validator = validator;
            this.current = current;
            this.total = total;
            this.remaining = Math.max(0L, total - current);
            this.threads = threads;
        }

        static Preflight inspect(Object owner, ActiveTransfer active, Settings settings,
                                 FileDescriptor descriptor) throws Exception {
            HttpURLConnection base = active.connection;
            if (!"GET".equalsIgnoreCase(base.getRequestMethod())) {
                return reject(owner, active, "non-GET transfer");
            }
            String encoding = base.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.isEmpty()
                    && !"identity".equalsIgnoreCase(encoding)) {
                return reject(owner, active, "encoded response: " + encoding);
            }

            RangeProtocol.BaseWindow baseWindow = RangeProtocol.resolveBaseWindow(
                    base.getResponseCode(),
                    active.requestSnapshot.known,
                    active.requestSnapshot.rangeHeader,
                    base.getHeaderField("Content-Range"),
                    base.getContentLengthLong());
            if (!baseWindow.accepted) {
                return reject(owner, active, baseWindow.reason);
            }

            String offsetIssue = RangeProtocol.validateDestinationOffset(
                    baseWindow.current, descriptorOffset(descriptor));
            if (offsetIssue != null) {
                return reject(owner, active, offsetIssue);
            }

            long current = baseWindow.current;
            long total = baseWindow.total;
            long remaining = total - current;
            if (remaining < settings.minimumSize) {
                return reject(owner, active, "below acceleration threshold");
            }

            URL url = base.getURL();
            if (url == null) return reject(owner, active, "missing source URL");
            Validator validator = Validator.from(base);
            if (!validator.stable()) {
                return reject(owner, active, "no stable ETag/Last-Modified validator");
            }

            int threads = RangeProtocol.chooseThreads(
                    remaining, settings.initialThreads, settings.maxThreads, settings.chunkSize);
            if (threads < 2) return reject(owner, active, "scheduler selected one worker");

            Network network = findNetwork(owner);
            Preflight candidate = new Preflight(true, null, owner, base,
                    active.configureMethod, network, url, validator,
                    current, total, threads);
            if (!candidate.probe(settings)) {
                return reject(owner, active, "server rejected strict byte-range probe");
            }
            return candidate;
        }

        private boolean probe(Settings settings) throws Exception {
            HttpURLConnection probe = null;
            InputStream input = null;
            try {
                probe = openRangeConnection(this, current, current, settings);
                int code = probe.getResponseCode();
                if (code != HttpURLConnection.HTTP_PARTIAL) return false;
                RangeProtocol.ContentRange range = RangeProtocol.parseContentRange(
                        probe.getHeaderField("Content-Range"));
                if (range == null || range.start != current || range.end != current
                        || range.total != total) return false;
                if (!validator.matches(probe)) return false;
                String encoding = probe.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.isEmpty()
                        && !"identity".equalsIgnoreCase(encoding)) return false;
                long declaredLength = probe.getContentLengthLong();
                if (declaredLength > 0L && declaredLength != 1L) return false;
                input = probe.getInputStream();
                return input.read() >= 0;
            } finally {
                closeQuietly(input);
                if (probe != null) probe.disconnect();
            }
        }

        private static Preflight reject(Object owner, ActiveTransfer active, String reason) {
            return new Preflight(false, reason, owner, active.connection,
                    active.configureMethod, findNetwork(owner),
                    active.connection.getURL(), Validator.from(active.connection),
                    0L, 0L, 0);
        }
    }

    private static final class Validator {
        final String strongEtag;
        final String lastModified;

        Validator(String strongEtag, String lastModified) {
            this.strongEtag = strongEtag;
            this.lastModified = lastModified;
        }

        static Validator from(HttpURLConnection connection) {
    String etag = trim(connection.getHeaderField("ETag"));
    if (etag != null && etag.regionMatches(true, 0, "W/", 0, 2)) etag = null;
    if (etag != null) return new Validator(etag, null);

    String modified = trim(connection.getHeaderField("Last-Modified"));
    if (modified == null) return new Validator(null, null);

    long modifiedMillis;
    long responseDateMillis;
    try {
        modifiedMillis = connection.getHeaderFieldDate("Last-Modified", -1L);
        responseDateMillis = connection.getHeaderFieldDate("Date", -1L);
    } catch (Throwable ignored) {
        return new Validator(null, null);
    }
    if (!RangeProtocol.isStrongLastModified(modifiedMillis, responseDateMillis)) {
        return new Validator(null, null);
    }
    return new Validator(null, modified);
}

        boolean stable() {
            return strongEtag != null || lastModified != null;
        }

        void apply(HttpURLConnection connection) {
            if (strongEtag != null) connection.setRequestProperty("If-Range", strongEtag);
            else if (lastModified != null) connection.setRequestProperty("If-Range", lastModified);
        }

        boolean matches(HttpURLConnection connection) {
            if (strongEtag != null) {
                return strongEtag.equals(trim(connection.getHeaderField("ETag")));
            }
            return lastModified != null
                    && lastModified.equals(trim(connection.getHeaderField("Last-Modified")));
        }

        private static String trim(String value) {
            if (value == null) return null;
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    private static HttpURLConnection openRangeConnection(
            Preflight preflight, long start, long end, Settings settings) throws Exception {
        HttpURLConnection connection = preflight.network == null
                ? (HttpURLConnection) preflight.url.openConnection()
                : (HttpURLConnection) preflight.network.openConnection(preflight.url);
        connection.setConnectTimeout(nonZero(
                preflight.baseConnection.getConnectTimeout(), settings.connectTimeoutMs));
        connection.setReadTimeout(nonZero(
                preflight.baseConnection.getReadTimeout(), settings.readTimeoutMs));
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setDoInput(true);

        try {
            synchronized (preflight.configureLock) {
                preflight.configureMethod.invoke(preflight.owner, connection);
            }
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }

        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        preflight.validator.apply(connection);
        return connection;
    }

    private static int nonZero(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static final class ParallelRangeInputStream extends InputStream {
        private final ModuleMain module;
        private final Preflight preflight;
        private final Settings settings;
        private final ExecutorService executor;
        private final ConcurrentHashMap<Integer, Future<ChunkFile>> futures =
                new ConcurrentHashMap<>();
        private final Set<HttpURLConnection> activeConnections =
                Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final File sessionDir;
        private final long chunkSize;
        private final int chunkCount;
        private final int workerCount;
        private final int windowSize;

        private int nextToSchedule;
        private int readChunkIndex;
        private FileInputStream currentInput;
        private ChunkFile currentChunk;
        private long delivered;

        ParallelRangeInputStream(ModuleMain module, Object owner,
                                 Preflight preflight, Settings settings) throws IOException {
            this.module = module;
            this.preflight = preflight;
            this.settings = settings;
            this.chunkSize = settings.chunkSize;
            long count;
            try {
                count = Math.addExact(preflight.remaining, chunkSize - 1L) / chunkSize;
            } catch (ArithmeticException overflow) {
                throw new IOException("Range chunk count overflow", overflow);
            }
            if (count <= 0L || count > Integer.MAX_VALUE) {
                throw new IOException("invalid chunk count: " + count);
            }
            this.chunkCount = (int) count;

            RangeProtocol.SpoolPlan spoolPlan = RangeProtocol.planSpool(
                    chunkCount, preflight.threads, chunkSize, MAX_SPOOL_WINDOW_BYTES);
            if (!spoolPlan.accepted) {
                throw new IOException("unsafe spool plan: " + spoolPlan.reason);
            }
            this.workerCount = spoolPlan.workers;
            this.windowSize = spoolPlan.windowChunks;

            Context context = findContext(owner);
            if (context == null || context.getCacheDir() == null) {
                throw new IOException("DownloadProvider cache directory unavailable");
            }
            File root = new File(context.getCacheDir(), "sda-range-spool");
            if (!root.exists() && !root.mkdirs()) throw new IOException("private spool root unavailable");
            cleanupStaleSpool(root);

            long spoolBudget = Math.min(preflight.remaining, spoolPlan.windowBytes);
            long reserve = 64L * 1024L * 1024L;
            long usable = root.getUsableSpace();
            if (usable > 0L && usable < spoolBudget + reserve) {
                throw new IOException("insufficient temporary storage for bounded Range spool");
            }
            this.sessionDir = new File(root,
                    "sda-range-" + android.os.Process.myPid() + "-" + System.nanoTime())
                    .getAbsoluteFile();
            if (!sessionDir.mkdirs()) throw new IOException("unable to create Range spool");
            ACTIVE_SPOOL_DIRS.add(sessionDir);

            try {
                this.executor = Executors.newFixedThreadPool(workerCount, runnable -> {
                    Thread thread = new Thread(runnable, "SDA-range-worker");
                    thread.setDaemon(true);
                    return thread;
                });
                scheduleWindow();
            } catch (RuntimeException | Error failure) {
                ACTIVE_SPOOL_DIRS.remove(sessionDir);
                deleteRecursively(sessionDir);
                throw failure;
            }
        }

        int workerCount() {
            return workerCount;
        }

        long deliveredBytes() {
            return delivered;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) throw new NullPointerException("buffer");
            if (offset < 0 || length < 0 || offset > buffer.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (closed.get()) throw new IOException("Range stream closed");
            if (delivered >= preflight.remaining) return -1;

            ensureCurrentChunk();
            int request = (int) Math.min((long) length, currentChunk.length - currentChunk.read);
            int read = currentInput.read(buffer, offset, request);
            if (read < 0) throw new EOFException("premature end of spooled Range chunk");
            currentChunk.read += read;
            delivered += read;

            if (currentChunk.read == currentChunk.length) finishCurrentChunk();
            return read;
        }

        private void ensureCurrentChunk() throws IOException {
            if (currentInput != null) return;
            Future<ChunkFile> future = futures.get(readChunkIndex);
            if (future == null) {
                synchronized (this) {
                    scheduleWindow();
                    future = futures.get(readChunkIndex);
                }
            }
            if (future == null) throw new IOException("missing scheduled Range chunk");
            try {
                currentChunk = future.get();
                currentInput = new FileInputStream(currentChunk.file);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancel("interrupted while waiting for Range chunk");
                throw new IOException("interrupted while waiting for Range chunk", interrupted);
            } catch (ExecutionException failure) {
                cancel("Range worker failed");
                Throwable cause = failure.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                throw new IOException("Range worker failed", cause);
            } catch (CancellationException cancelled) {
                throw new IOException("Range worker cancelled", cancelled);
            }
        }

        private void finishCurrentChunk() {
            closeQuietly(currentInput);
            currentInput = null;
            if (currentChunk != null) {
                //noinspection ResultOfMethodCallIgnored
                currentChunk.file.delete();
            }
            futures.remove(readChunkIndex);
            currentChunk = null;
            readChunkIndex++;
            scheduleWindow();
        }

        private synchronized void scheduleWindow() {
            if (closed.get()) return;
            int upper = Math.min(chunkCount, readChunkIndex + windowSize);
            while (nextToSchedule < upper) {
                final int index = nextToSchedule++;
                futures.put(index, executor.submit(() -> downloadChunk(index)));
            }
        }

        private ChunkFile downloadChunk(int index) throws Exception {
            RangeProtocol.ChunkBounds bounds = RangeProtocol.chunkBounds(
                    preflight.current, preflight.total, chunkSize, index);
            long absoluteStart = bounds.start;
            long absoluteEnd = bounds.end;
            long expectedLength = bounds.length();
            Throwable last = null;

            for (int attempt = 1; attempt <= 2; attempt++) {
                if (closed.get()) throw new CancellationException("Range session closed");
                File file = new File(sessionDir,
                        String.format(Locale.ROOT, "%08d.part", index));
                HttpURLConnection connection = null;
                InputStream input = null;
                FileOutputStream output = null;
                try {
                    connection = openRangeConnection(
                            preflight, absoluteStart, absoluteEnd, settings);
                    activeConnections.add(connection);
                    int response = connection.getResponseCode();
                    if (response != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IOException("expected HTTP 206, got " + response);
                    }
                    RangeProtocol.ContentRange range = RangeProtocol.parseContentRange(
                            connection.getHeaderField("Content-Range"));
                    if (range == null || range.start != absoluteStart
                            || range.end != absoluteEnd || range.total != preflight.total) {
                        throw new IOException("invalid Content-Range for chunk " + index);
                    }
                    if (!preflight.validator.matches(connection)) {
                        throw new IOException("resource validator changed during Range transfer");
                    }
                    String encoding = connection.getHeaderField("Content-Encoding");
                    if (encoding != null && !encoding.isEmpty()
                            && !"identity".equalsIgnoreCase(encoding)) {
                        throw new IOException("encoded Range response: " + encoding);
                    }
                    long declaredLength = connection.getContentLengthLong();
                    if (declaredLength > 0L && declaredLength != expectedLength) {
                        throw new IOException("Range length mismatch: " + declaredLength
                                + "/" + expectedLength);
                    }

                    input = connection.getInputStream();
                    output = new FileOutputStream(file, false);
                    byte[] buffer = new byte[128 * 1024];
                    long remaining = expectedLength;
                    while (remaining > 0L) {
                        if (closed.get() || Thread.currentThread().isInterrupted()) {
                            throw new CancellationException("Range session cancelled");
                        }
                        int request = (int) Math.min(buffer.length, remaining);
                        int count = input.read(buffer, 0, request);
                        if (count < 0) throw new EOFException("premature Range EOF");
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                    output.flush();
                    if (file.length() != expectedLength) {
                        throw new IOException("spooled chunk length mismatch: " + file.length()
                                + "/" + expectedLength);
                    }
                    return new ChunkFile(file, expectedLength);
                } catch (Throwable error) {
                    last = error;
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    if (error instanceof CancellationException || closed.get()) {
                        if (error instanceof Exception) throw (Exception) error;
                        if (error instanceof Error) throw (Error) error;
                        throw new IOException("Range session cancelled", error);
                    }
                } finally {
                    closeQuietly(output);
                    closeQuietly(input);
                    if (connection != null) {
                        activeConnections.remove(connection);
                        connection.disconnect();
                    }
                }
            }

            if (last instanceof Exception) throw (Exception) last;
            if (last instanceof Error) throw (Error) last;
            throw new IOException("Range chunk failed", last);
        }

        void cancel(String reason) {
            if (!closed.compareAndSet(false, true)) return;
            module.log(Log.INFO, TAG, "Cancelling Range session: " + reason);
            disconnectWorkers();
            for (Future<ChunkFile> future : futures.values()) future.cancel(true);
            executor.shutdownNow();
            awaitWorkers();
            closeQuietly(currentInput);
            currentInput = null;
            ACTIVE_SPOOL_DIRS.remove(sessionDir);
            deleteRecursively(sessionDir);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            disconnectWorkers();
            for (Future<ChunkFile> future : futures.values()) future.cancel(true);
            executor.shutdownNow();
            awaitWorkers();
            closeQuietly(currentInput);
            currentInput = null;
            ACTIVE_SPOOL_DIRS.remove(sessionDir);
            deleteRecursively(sessionDir);
        }

        private void disconnectWorkers() {
            for (HttpURLConnection connection : new ArrayList<>(activeConnections)) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }

        private void awaitWorkers() {
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class ChunkFile {
        final File file;
        final long length;
        long read;

        ChunkFile(File file, long length) {
            this.file = file;
            this.length = length;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
