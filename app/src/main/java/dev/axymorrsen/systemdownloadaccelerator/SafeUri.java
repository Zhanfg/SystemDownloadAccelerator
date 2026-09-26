package dev.axymorrsen.systemdownloadaccelerator;

import android.net.Uri;

/** Removes query, fragment, user-info and detailed path data before logcat. */
final class SafeUri {
    private SafeUri() {}

    static String redact(String raw) {
        if (raw == null || raw.isBlank()) return "<unknown>";

        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return "<opaque>";

            StringBuilder out = new StringBuilder()
                    .append(scheme).append("://").append(host);

            int port = uri.getPort();
            if (port != -1) out.append(':').append(port);

            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                out.append("/…");
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "<invalid-uri>";
        }
    }
}
