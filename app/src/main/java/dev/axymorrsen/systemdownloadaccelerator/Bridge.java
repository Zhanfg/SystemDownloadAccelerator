package dev.axymorrsen.systemdownloadaccelerator;

/**
 * Reserved cross-process contract between Provider, Downloads UI and SystemUI.
 *
 * No IPC transport is enabled yet. Keeping the schema separate prevents UI code
 * from becoming a second source of truth for download state.
 */
final class Bridge {
    static final int SCHEMA_VERSION = 1;

    static final String EVENT_PROGRESS = "progress";
    static final String EVENT_COMPLETE = "complete";
    static final String EVENT_FAILED = "failed";

    private Bridge() {}
}
