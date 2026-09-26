package dev.axymorrsen.systemdownloadaccelerator.engine;

public final class Segment {
    public final long startInclusive;
    public final long endInclusive;

    public Segment(long startInclusive, long endInclusive) {
        if (startInclusive < 0 || endInclusive < startInclusive) {
            throw new IllegalArgumentException("Invalid segment range");
        }
        this.startInclusive = startInclusive;
        this.endInclusive = endInclusive;
    }

    public long length() {
        return endInclusive - startInclusive + 1L;
    }
}
