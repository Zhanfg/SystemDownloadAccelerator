package dev.axymorrsen.systemdownloadaccelerator;

final class ProbeSnapshot {
    private final String safeUri;
    private final String mimeType;
    private final long totalBytes;
    private final long currentBytes;
    private final boolean hasEtag;

    ProbeSnapshot(String safeUri, String mimeType, long totalBytes,
                  long currentBytes, boolean hasEtag) {
        this.safeUri = safeUri;
        this.mimeType = mimeType;
        this.totalBytes = totalBytes;
        this.currentBytes = currentBytes;
        this.hasEtag = hasEtag;
    }

    static ProbeSnapshot empty() {
        return new ProbeSnapshot("<unknown>", null, -1L, -1L, false);
    }

    String toSafeLogString() {
        return "{uri=" + (safeUri == null ? "<unknown>" : safeUri)
                + ", mime=" + (mimeType == null ? "<unknown>" : mimeType)
                + ", current=" + currentBytes
                + ", total=" + totalBytes
                + ", etag=" + hasEtag
                + "}";
    }
}
