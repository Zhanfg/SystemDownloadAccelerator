package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.net.Network;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Process-local metadata for HttpURLConnection instances created by
 * DownloadProvider. Weak keys avoid extending platform connection lifetime.
 */
final class ConnectionRegistry {
    static final class ProviderExecution {
        final Context context;
        final Network network;
        final int requestingUid;

        ProviderExecution(Context context, Network network, int requestingUid) {
            this.context = context;
            this.network = network;
            this.requestingUid = requestingUid;
        }
    }

    static final class Metadata {
        final Context context;
        final Network network;
        final int requestingUid;

        private final LinkedHashMap<String, List<String>> headers =
                new LinkedHashMap<>();

        Metadata(Context context, Network network, int requestingUid) {
            this.context = context;
            this.network = network;
            this.requestingUid = requestingUid;
        }

        synchronized void setHeader(String name, String value) {
            if (name == null || value == null) return;
            String key = normalize(name);
            ArrayList<String> values = new ArrayList<>(1);
            values.add(value);
            headers.put(key, values);
        }

        synchronized void addHeader(String name, String value) {
            if (name == null || value == null) return;
            String key = normalize(name);
            headers.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(value);
        }

        synchronized Map<String, List<String>> snapshotHeaders() {
            LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                copy.put(
                        entry.getKey(),
                        Collections.unmodifiableList(
                                new ArrayList<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(copy);
        }

        private static String normalize(String name) {
            return name.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static final ThreadLocal<ProviderExecution> CURRENT =
            new ThreadLocal<>();
    private static final ThreadLocal<Boolean> INTERNAL =
            ThreadLocal.withInitial(() -> false);

    private static final Map<HttpURLConnection, Metadata> CONNECTIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Context processContext;

    private ConnectionRegistry() {}

    static void initialize(Context context) {
        if (context != null) {
            Context app = context.getApplicationContext();
            processContext = app != null ? app : context;
        }
    }

    static Context processContext() {
        return processContext;
    }

    static void enterProviderExecution(ProviderExecution execution) {
        CURRENT.set(execution);
        if (execution != null && execution.context != null) {
            initialize(execution.context);
        }
    }

    static void leaveProviderExecution() {
        CURRENT.remove();
    }

    static ProviderExecution currentExecution() {
        return CURRENT.get();
    }

    static boolean isInternalRequest() {
        return Boolean.TRUE.equals(INTERNAL.get());
    }

    static <T> T internal(ThrowingSupplier<T> supplier) throws Exception {
        boolean previous = isInternalRequest();
        INTERNAL.set(true);
        try {
            return supplier.get();
        } finally {
            INTERNAL.set(previous);
        }
    }

    static void internal(ThrowingRunnable runnable) throws Exception {
        internal(() -> {
            runnable.run();
            return null;
        });
    }

    static Metadata register(
            HttpURLConnection connection,
            Network explicitNetwork) {
        ProviderExecution execution = CURRENT.get();

        Context context = execution != null && execution.context != null
                ? execution.context
                : processContext;

        Network network = explicitNetwork != null
                ? explicitNetwork
                : execution == null ? null : execution.network;

        int uid = execution == null ? -1 : execution.requestingUid;

        Metadata metadata = new Metadata(context, network, uid);
        CONNECTIONS.put(connection, metadata);
        return metadata;
    }

    static Metadata get(HttpURLConnection connection) {
        return CONNECTIONS.get(connection);
    }

    static void recordSet(
            HttpURLConnection connection,
            String name,
            String value) {
        Metadata metadata = get(connection);
        if (metadata != null) {
            metadata.setHeader(name, value);
        }
    }

    static void recordAdd(
            HttpURLConnection connection,
            String name,
            String value) {
        Metadata metadata = get(connection);
        if (metadata != null) {
            metadata.addHeader(name, value);
        }
    }

    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
