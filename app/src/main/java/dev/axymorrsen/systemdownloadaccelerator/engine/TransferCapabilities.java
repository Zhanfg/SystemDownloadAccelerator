package dev.axymorrsen.systemdownloadaccelerator.engine;

/** Immutable result of a future HEAD / Range 0-0 capability probe. */
public final class TransferCapabilities {
    public final long contentLength;
    public final boolean acceptsRanges;
    public final boolean hasValidator;
    public final boolean contentEncoded;

    public TransferCapabilities(long contentLength, boolean acceptsRanges,
                                boolean hasValidator, boolean contentEncoded) {
        this.contentLength = contentLength;
        this.acceptsRanges = acceptsRanges;
        this.hasValidator = hasValidator;
        this.contentEncoded = contentEncoded;
    }
}
