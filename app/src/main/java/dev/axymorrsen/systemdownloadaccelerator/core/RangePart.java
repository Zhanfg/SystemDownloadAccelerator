package dev.axymorrsen.systemdownloadaccelerator.core;

/** Immutable byte range owned by one scheduler task. End is inclusive. */
public final class RangePart {
    public final int index;
    public final long from;
    public final long to;

    public RangePart(int index, long from, long to) {
        if (index < 0) throw new IllegalArgumentException("index < 0");
        if (from < 0L) throw new IllegalArgumentException("from < 0");
        if (to < from) throw new IllegalArgumentException("to < from");
        this.index = index;
        this.from = from;
        this.to = to;
    }

    public long length() {
        return to - from + 1L;
    }

    @Override
    public String toString() {
        return "RangePart{" + index + ":" + from + "-" + to + "}";
    }
}
