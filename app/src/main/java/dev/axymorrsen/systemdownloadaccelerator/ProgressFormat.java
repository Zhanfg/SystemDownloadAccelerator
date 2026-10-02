package dev.axymorrsen.systemdownloadaccelerator;

import java.util.Locale;

/** Pure formatting helpers so progress math stays testable without Android stubs. */
final class ProgressFormat {
    private ProgressFormat() {}

    static String percent(double ratio) {
        double bounded = Math.max(
                0.0,
                Math.min(1.0, ratio));
        return String.format(
                Locale.ROOT,
                "%.1f%%",
                bounded * 100.0);
    }

    static String bytes(long bytes) {
        double value = Math.max(0L, bytes);
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024.0
                && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (unit == 0) {
            return ((long) value) + " " + units[unit];
        }
        return String.format(
                Locale.ROOT,
                value < 10.0 ? "%.1f %s" : "%.0f %s",
                value,
                units[unit]);
    }
}
