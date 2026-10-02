package dev.axymorrsen.systemdownloadaccelerator;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fair process-wide socket budget.
 *
 * One large download may use the full adaptive ceiling. When downloads overlap,
 * the worker budget is divided across sessions and across sessions on one host.
 */
final class GlobalTransferGovernor {
    static final int GLOBAL_WORKER_LIMIT = 64;
    static final int SHARED_HOST_WORKER_LIMIT = 48;

    static final class Lease {
        private final long id;
        private final String host;
        private final int maxWorkers;
        private boolean closed;

        Lease(long id, String host, int maxWorkers) {
            this.id = id;
            this.host = host;
            this.maxWorkers = Math.max(1, maxWorkers);
        }

        int allowed(int requested) {
            return GlobalTransferGovernor.allowed(this, requested);
        }

        void close() {
            GlobalTransferGovernor.unregister(this);
        }
    }

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<Long, Lease> LEASES = new HashMap<>();

    private GlobalTransferGovernor() {}

    static synchronized Lease register(String host, int maxWorkers) {
        String normalized = host == null
                ? ""
                : host.trim().toLowerCase(Locale.ROOT);
        Lease lease = new Lease(
                NEXT_ID.incrementAndGet(),
                normalized,
                maxWorkers);
        LEASES.put(lease.id, lease);
        return lease;
    }

    private static synchronized int allowed(
            Lease lease,
            int requested) {
        if (lease == null || lease.closed) {
            return 1;
        }

        int wanted = Math.max(
                1,
                Math.min(requested, lease.maxWorkers));

        int sessions = Math.max(1, LEASES.size());
        int globalShare = sessions <= 1
                ? GLOBAL_WORKER_LIMIT
                : Math.max(2, GLOBAL_WORKER_LIMIT / sessions);

        int sameHost = 0;
        if (!lease.host.isEmpty()) {
            for (Lease item : LEASES.values()) {
                if (!item.closed && lease.host.equals(item.host)) {
                    sameHost++;
                }
            }
        }

        int hostShare = sameHost <= 1
                ? SHARED_HOST_WORKER_LIMIT
                : Math.max(2, SHARED_HOST_WORKER_LIMIT / sameHost);

        return Math.max(
                1,
                Math.min(
                        wanted,
                        Math.min(globalShare, hostShare)));
    }

    private static synchronized void unregister(Lease lease) {
        if (lease == null || lease.closed) {
            return;
        }
        lease.closed = true;
        LEASES.remove(lease.id);
    }

    static synchronized int activeSessions() {
        return LEASES.size();
    }

    static synchronized void resetForTests() {
        for (Lease lease : LEASES.values()) {
            lease.closed = true;
        }
        LEASES.clear();
    }
}
