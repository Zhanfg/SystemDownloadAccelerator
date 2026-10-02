package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Small per-host history used only as a warm-start hint.
 *
 * Runtime throughput remains authoritative. Learned state can raise initial
 * concurrency only to 8 and never raises the adaptive hard ceiling.
 */
final class HostProfileStore {
    private static final String PREFS = "sysdl_host_profiles";
    private static final int MIN_SUCCESS_SAMPLES = 2;
    private static final int MAX_LEARNED_INITIAL = 8;

    private HostProfileStore() {}

    static int recommendedInitial(
            Context context,
            String host,
            int policyInitial,
            int maxWorkers) {
        int base = Math.max(
                1,
                Math.min(policyInitial, maxWorkers));
        String prefix = prefix(host);
        if (context == null || prefix == null) {
            return base;
        }

        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(
                            PREFS,
                            Context.MODE_PRIVATE);
            int successes = prefs.getInt(prefix + ".ok", 0);
            int failures = prefs.getInt(prefix + ".fail", 0);
            int learned = prefs.getInt(prefix + ".workers", 0);

            if (successes < MIN_SUCCESS_SAMPLES
                    || learned <= base
                    || failures > successes) {
                return base;
            }

            return Math.min(
                    maxWorkers,
                    Math.max(
                            base,
                            Math.min(
                                    MAX_LEARNED_INITIAL,
                                    learned)));
        } catch (Throwable ignored) {
            return base;
        }
    }

    static void recordSuccess(
            Context context,
            String host,
            int usefulWorkers,
            double bytesPerSecond) {
        String prefix = prefix(host);
        if (context == null || prefix == null) return;

        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(
                            PREFS,
                            Context.MODE_PRIVATE);
            int oldWorkers = prefs.getInt(prefix + ".workers", 0);
            int observed = Math.max(
                    1,
                    Math.min(64, usefulWorkers));
            int smoothed = oldWorkers <= 0
                    ? observed
                    : Math.max(
                            1,
                            (int) Math.round(
                                    oldWorkers * 0.60
                                            + observed * 0.40));
            int successes = Math.min(
                    10_000,
                    prefs.getInt(prefix + ".ok", 0) + 1);
            int failures = Math.max(
                    0,
                    prefs.getInt(prefix + ".fail", 0) - 1);

            prefs.edit()
                    .putInt(prefix + ".workers", smoothed)
                    .putInt(prefix + ".ok", successes)
                    .putInt(prefix + ".fail", failures)
                    .putLong(
                            prefix + ".bps",
                            Math.max(0L, Math.round(bytesPerSecond)))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static void recordFailure(Context context, String host) {
        String prefix = prefix(host);
        if (context == null || prefix == null) return;

        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(
                            PREFS,
                            Context.MODE_PRIVATE);
            int failures = Math.min(
                    10_000,
                    prefs.getInt(prefix + ".fail", 0) + 1);
            prefs.edit()
                    .putInt(prefix + ".fail", failures)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static String prefix(String host) {
        if (host == null) return null;
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;

        StringBuilder out = new StringBuilder("h_");
        for (int i = 0; i < normalized.length() && i < 120; i++) {
            char c = normalized.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '-'
                    || c == '_') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }
}
