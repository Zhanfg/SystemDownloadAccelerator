package io.github.zhanfg.sda.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Shared immutable state consumed by Material and Miuix renderers. */
public final class DownloadUiState {
    private static final String PREFS = "pending_download_confirmations";

    public final String token;
    public final long downloadId;
    public final String fileName;
    public final String url;
    public final String sourcePackage;
    public final String mimeType;
    public final long fileSize;
    public final String destination;
    public final String threadMode;

    private DownloadUiState(
            String token,
            long downloadId,
            String fileName,
            String url,
            String sourcePackage,
            String mimeType,
            long fileSize,
            String destination,
            String threadMode
    ) {
        this.token = token;
        this.downloadId = downloadId;
        this.fileName = value(fileName, "download.bin");
        this.url = value(url, "");
        this.sourcePackage = value(sourcePackage, "系统应用");
        this.mimeType = value(mimeType, "application/octet-stream");
        this.fileSize = fileSize;
        this.destination = value(destination, "内部存储 / Download");
        this.threadMode = value(threadMode, "自动 · 多线程可用时启用");
    }

    public static DownloadUiState consume(Context context, String token) {
        if (token == null || !token.matches("[a-fA-F0-9]{16,160}")) return null;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String prefix = token + ":";
        if (!preferences.getBoolean(prefix + "present", false)) return null;
        DownloadUiState state = new DownloadUiState(
                token,
                preferences.getLong(prefix + "download_id", -1L),
                preferences.getString(prefix + "file_name", null),
                preferences.getString(prefix + "url", null),
                preferences.getString(prefix + "source_package", null),
                preferences.getString(prefix + "mime_type", null),
                preferences.getLong(prefix + "file_size", -1L),
                preferences.getString(prefix + "destination", null),
                preferences.getString(prefix + "thread_mode", null)
        );
        preferences.edit()
                .remove(prefix + "present")
                .remove(prefix + "download_id")
                .remove(prefix + "file_name")
                .remove(prefix + "url")
                .remove(prefix + "source_package")
                .remove(prefix + "mime_type")
                .remove(prefix + "file_size")
                .remove(prefix + "destination")
                .remove(prefix + "thread_mode")
                .apply();
        return state;
    }

    public static void store(Context context, String token, android.os.Bundle request) {
        String prefix = token + ":";
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(prefix + "present", true)
                .putLong(prefix + "download_id", request.getLong("download_id", -1L))
                .putString(prefix + "file_name", request.getString("file_name", "download.bin"))
                .putString(prefix + "url", request.getString("url", ""))
                .putString(prefix + "source_package", request.getString("source_package", "系统应用"))
                .putString(prefix + "mime_type", request.getString("mime_type", "application/octet-stream"))
                .putLong(prefix + "file_size", request.getLong("file_size", -1L))
                .putString(prefix + "destination", request.getString("destination", "内部存储 / Download"))
                .putString(prefix + "thread_mode", request.getString("thread_mode", "自动 · 多线程可用时启用"))
                .commit();
    }

    public String formattedSize() {
        if (fileSize < 0) return "开始后获取";
        if (fileSize < 1024) return fileSize + " B";
        double value = fileSize;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    public String compactType() {
        int slash = mimeType.indexOf('/');
        if (slash >= 0 && slash + 1 < mimeType.length()) {
            return mimeType.substring(slash + 1).toUpperCase(Locale.ROOT);
        }
        return mimeType;
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
