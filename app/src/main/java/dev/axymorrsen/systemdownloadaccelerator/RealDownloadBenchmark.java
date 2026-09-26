package dev.axymorrsen.systemdownloadaccelerator;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-network performance test through Android DownloadManager.
 *
 * The caller provides an HTTP/HTTPS large-file URL. The test measures posted
 * DownloadManager progress while the injected accelerator independently emits
 * ADAPT_* telemetry for worker decisions.
 */
final class RealDownloadBenchmark {
    interface Listener {
        void onUpdate(
                String message,
                boolean terminal,
                boolean success);
    }

    static final class Session {
        private final AtomicBoolean cancelled;
        private final DownloadManager manager;
        private volatile long downloadId = -1L;

        Session(
                AtomicBoolean cancelled,
                DownloadManager manager) {
            this.cancelled = cancelled;
            this.manager = manager;
        }

        void setDownloadId(long id) {
            downloadId = id;
        }

        void cancel() {
            cancelled.set(true);
            long id = downloadId;
            if (id >= 0L) {
                try {
                    manager.remove(id);
                } catch (Throwable ignored) {
                }
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }

    private RealDownloadBenchmark() {}

    static Session run(
            Context context,
            String rawUrl,
            Listener listener) {
        Context app = context.getApplicationContext();
        DownloadManager manager =
                (DownloadManager) app.getSystemService(
                        Context.DOWNLOAD_SERVICE);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Session session = new Session(cancelled, manager);

        new Thread(
                () -> execute(
                        app,
                        manager,
                        rawUrl,
                        session,
                        listener),
                "sysdl-real-benchmark")
                .start();
        return session;
    }

    private static void execute(
            Context context,
            DownloadManager manager,
            String rawUrl,
            Session session,
            Listener listener) {
        if (manager == null) {
            listener.onUpdate(
                    "无法获取系统 DownloadManager",
                    true,
                    false);
            return;
        }

        File destination = null;
        long downloadId = -1L;

        try {
            Uri uri = validateUrl(rawUrl);
            if (isMetered(context)) {
                listener.onUpdate(
                        "当前网络被 Android 标记为计费网络；"
                                + "大文件性能测试默认拒绝启动，避免消耗蜂窝流量。",
                        true,
                        false);
                return;
            }

            File dir = context.getExternalFilesDir(
                    Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                throw new IllegalStateException(
                        "external-files Downloads unavailable");
            }

            String fileName =
                    "SysDlBench-"
                            + System.currentTimeMillis()
                            + ".bin";
            destination = new File(dir, fileName);
            if (destination.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }

            DownloadManager.Request request =
                    new DownloadManager.Request(uri)
                            .setTitle(
                                    "System Download Accelerator benchmark")
                            .setDescription(
                                    "Real-network adaptive concurrency test")
                            .setAllowedOverMetered(false)
                            .setAllowedOverRoaming(false)
                            .setDestinationInExternalFilesDir(
                                    context,
                                    Environment.DIRECTORY_DOWNLOADS,
                                    fileName);

            EngineTelemetry.emit(
                    context,
                    "BENCH_BEGIN",
                    "url=" + safeOrigin(uri));

            downloadId = manager.enqueue(request);
            session.setDownloadId(downloadId);

            listener.onUpdate(
                    "真实性能测试已提交 · ID "
                            + downloadId
                            + " · 等待服务器返回文件长度",
                    false,
                    false);

            long startedNs = System.nanoTime();
            long previousNs = startedNs;
            long previousBytes = 0L;
            long lastUiNs = 0L;

            while (!session.isCancelled()) {
                DownloadManager.Query query =
                        new DownloadManager.Query()
                                .setFilterById(downloadId);

                try (Cursor cursor = manager.query(query)) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        throw new IllegalStateException(
                                "benchmark download disappeared");
                    }

                    int status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_STATUS));
                    long downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                    long nowNs = System.nanoTime();
                    long deltaNs = Math.max(
                            1L,
                            nowNs - previousNs);
                    long deltaBytes = Math.max(
                            0L,
                            downloaded - previousBytes);

                    double currentBps =
                            deltaBytes
                                    * 1_000_000_000.0
                                    / deltaNs;
                    double elapsedSeconds =
                            Math.max(
                                    0.001,
                                    (nowNs - startedNs)
                                            / 1_000_000_000.0);
                    double averageBps =
                            downloaded / elapsedSeconds;

                    if (lastUiNs == 0L
                            || nowNs - lastUiNs
                            >= 900_000_000L) {
                        listener.onUpdate(
                                progressMessage(
                                        status,
                                        downloaded,
                                        total,
                                        currentBps,
                                        averageBps),
                                false,
                                false);
                        lastUiNs = nowNs;
                    }

                    if (status
                            == DownloadManager.STATUS_SUCCESSFUL) {
                        EngineTelemetry.emit(
                                context,
                                "BENCH_COMPLETE",
                                "bytes=" + downloaded
                                        + " avgMiBs="
                                        + formatMiBps(
                                                averageBps));
                        listener.onUpdate(
                                "真实性能测试完成 · "
                                        + formatBytes(downloaded)
                                        + " · 平均 "
                                        + formatMiBps(averageBps)
                                        + " MiB/s",
                                true,
                                true);
                        return;
                    }

                    if (status
                            == DownloadManager.STATUS_FAILED) {
                        int reason = cursor.getInt(
                                cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_REASON));
                        EngineTelemetry.emit(
                                context,
                                "BENCH_FAILED",
                                "reason=" + reason);
                        listener.onUpdate(
                                "真实性能测试失败 · reason="
                                        + reason,
                                true,
                                false);
                        return;
                    }

                    previousBytes = downloaded;
                    previousNs = nowNs;
                }

                Thread.sleep(1000L);
            }

            EngineTelemetry.emit(
                    context,
                    "BENCH_CANCEL",
                    "user cancelled");
            listener.onUpdate(
                    "性能测试已取消",
                    true,
                    false);
        } catch (Throwable t) {
            String detail = t.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = t.getClass().getSimpleName();
            }
            EngineTelemetry.emit(
                    context,
                    "BENCH_ERROR",
                    t.getClass().getSimpleName()
                            + ": "
                            + detail);
            listener.onUpdate(
                    "性能测试异常：" + detail,
                    true,
                    false);
        } finally {
            if (downloadId >= 0L) {
                try {
                    manager.remove(downloadId);
                } catch (Throwable ignored) {
                }
            }
            if (destination != null && destination.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }
        }
    }

    private static Uri validateUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "请输入 HTTP/HTTPS 大文件直链");
        }

        Uri uri = Uri.parse(raw.trim());
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "只支持 HTTP/HTTPS URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "URL 缺少有效主机名");
        }
        return uri;
    }

    private static boolean isMetered(Context context) {
        try {
            ConnectivityManager cm =
                    context.getSystemService(
                            ConnectivityManager.class);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String progressMessage(
            int status,
            long downloaded,
            long total,
            double currentBps,
            double averageBps) {
        StringBuilder out = new StringBuilder();
        out.append(statusText(status))
                .append(" · ")
                .append(formatBytes(downloaded));

        if (total > 0L) {
            out.append("/")
                    .append(formatBytes(total));
            double percent =
                    Math.min(
                            100.0,
                            downloaded * 100.0 / total);
            out.append(" · ")
                    .append(String.format(
                            Locale.US,
                            "%.1f%%",
                            percent));
        }

        out.append(" · 当前 ")
                .append(formatMiBps(currentBps))
                .append(" MiB/s")
                .append(" · 平均 ")
                .append(formatMiBps(averageBps))
                .append(" MiB/s");
        return out.toString();
    }

    private static String statusText(int status) {
        switch (status) {
            case DownloadManager.STATUS_PENDING:
                return "等待下载";
            case DownloadManager.STATUS_RUNNING:
                return "下载中";
            case DownloadManager.STATUS_PAUSED:
                return "下载暂停";
            default:
                return "状态 " + status;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) return "未知";
        double mib = bytes / (1024.0 * 1024.0);
        if (mib < 1024.0) {
            return String.format(
                    Locale.US,
                    "%.1f MiB",
                    mib);
        }
        return String.format(
                Locale.US,
                "%.2f GiB",
                mib / 1024.0);
    }

    private static String formatMiBps(double bps) {
        return String.format(
                Locale.US,
                "%.2f",
                Math.max(0.0, bps)
                        / (1024.0 * 1024.0));
    }

    private static String safeOrigin(Uri uri) {
        StringBuilder out = new StringBuilder();
        out.append(uri.getScheme())
                .append("://")
                .append(uri.getHost());
        if (uri.getPort() >= 0) {
            out.append(":").append(uri.getPort());
        }
        out.append("/…");
        return out.toString();
    }
}
