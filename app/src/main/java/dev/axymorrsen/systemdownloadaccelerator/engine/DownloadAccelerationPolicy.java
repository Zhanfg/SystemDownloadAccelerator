package dev.axymorrsen.systemdownloadaccelerator.engine;

/** Unknown or unsafe transfers stay on the original DownloadProvider path. */
public final class DownloadAccelerationPolicy {
    private static final long MIN_RANGE_SIZE_BYTES = 8L * 1024L * 1024L;

    public AccelerationDecision evaluate(TransferCapabilities caps) {
        if (caps == null
                || caps.contentLength < MIN_RANGE_SIZE_BYTES
                || !caps.acceptsRanges
                || !caps.hasValidator
                || caps.contentEncoded) {
            return AccelerationDecision.PASSTHROUGH;
        }
        return AccelerationDecision.RANGE_ELIGIBLE;
    }
}
