package dev.axymorrsen.systemdownloadaccelerator.core;

import java.util.Locale;

/** Tolerant Content-Range parser for real-world servers. */
public final class ContentRange {
    public final long from;
    public final long to;
    public final long total;

    private ContentRange(long from, long to, long total) {
        this.from = from;
        this.to = to;
        this.total = total;
    }

    public static ContentRange parse(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("bytes ")) {
            text = text.substring(6).trim();
        }

        int slash = text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1) return null;

        String range = text.substring(0, slash).trim();
        String totalText = text.substring(slash + 1).trim();
        if ("*".equals(range) || "*".equals(totalText)) return null;

        int dash = range.indexOf('-');
        if (dash <= 0 || dash == range.length() - 1) return null;

        try {
            long from = Long.parseLong(range.substring(0, dash).trim());
            long to = Long.parseLong(range.substring(dash + 1).trim());
            long total = Long.parseLong(totalText);
            if (from < 0L || to < from || total <= to) return null;
            return new ContentRange(from, to, total);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean startsAt(long expectedStart) {
        return from == expectedStart;
    }
}
