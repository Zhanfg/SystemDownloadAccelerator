package dev.axymorrsen.systemdownloadaccelerator;

import java.lang.reflect.Field;

/**
 * Best-effort, read-only reflection against AOSP DownloadProvider internals.
 * Every lookup is optional so OEM field changes cannot break the host process.
 */
final class DownloadInfoInspector {
    private DownloadInfoInspector() {}

    static ProbeSnapshot inspect(Object downloadThread) {
        if (downloadThread == null) {
            return ProbeSnapshot.empty();
        }

        try {
            Object info = readField(downloadThread, "mInfo", "mDownloadInfo", "mRequest");
            Object source = info != null ? info : downloadThread;

            String uri = stringValue(readField(source, "mUri", "uri", "mUrl"));
            String mimeType = stringValue(readField(source, "mMimeType", "mimeType"));
            long totalBytes = longValue(readField(source, "mTotalBytes", "totalBytes"));
            long currentBytes = longValue(readField(source, "mCurrentBytes", "currentBytes"));
            Object etag = readField(source, "mETag", "etag", "mEtag");

            return new ProbeSnapshot(
                    SafeUri.redact(uri),
                    mimeType,
                    totalBytes,
                    currentBytes,
                    etag != null && !String.valueOf(etag).isEmpty()
            );
        } catch (Throwable ignored) {
            return ProbeSnapshot.empty();
        }
    }

    private static Object readField(Object target, String... names) {
        if (target == null) return null;

        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    // Try the next candidate or superclass.
                } catch (Throwable ignored) {
                    return null;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }
}
