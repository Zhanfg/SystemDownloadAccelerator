package dev.axymorrsen.systemdownloadaccelerator;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end DownloadManager self-test.
 *
 * A loopback HTTP server avoids public-network variability while still forcing
 * Android DownloadManager -> DownloadProvider -> DownloadThread to perform a
 * real HTTP transfer. The server supports byte ranges and a stable validator so
 * it can also be reused by the segmented-transfer tests later.
 */
final class DownloadSelfTest {
    interface Listener {
        void onUpdate(String message, boolean terminal, boolean success);
    }

    private static final int TEST_BYTES = 1024 * 1024;
    private static final byte[] BLOCK = new byte[16 * 1024];

    private DownloadSelfTest() {}

    static void run(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        new Thread(() -> execute(app, listener), "sysdl-selftest").start();
    }

    private static void execute(Context context, Listener listener) {
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            listener.onUpdate("无法获取系统 DownloadManager", true, false);
            return;
        }

        long downloadId = -1L;
        File destination = null;
        AtomicBoolean stopServer = new AtomicBoolean(false);

        try (ServerSocket server = new ServerSocket(
                0, 8, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(1000);

            Thread serverThread = new Thread(
                    () -> serve(server, stopServer),
                    "sysdl-loopback-http");
            serverThread.start();

            int port = server.getLocalPort();
            listener.onUpdate("本地测试服务器已启动 · localhost:" + port,
                    false, false);

            String fileName = "SysDlProbe-" + System.currentTimeMillis() + ".bin";
            File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                throw new IllegalStateException("external-files Downloads directory unavailable");
            }
            destination = new File(dir, fileName);
            if (destination.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }

            Uri uri = Uri.parse("http://localhost:" + port + "/probe.bin");
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle("System Download Accelerator self-test")
                    .setDescription("Local DownloadManager / DownloadProvider probe")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setDestinationInExternalFilesDir(
                            context, Environment.DIRECTORY_DOWNLOADS, fileName);

            downloadId = manager.enqueue(request);
            listener.onUpdate("已提交 DownloadManager · ID " + downloadId,
                    false, false);

            long deadline = System.currentTimeMillis() + 20_000L;
            int lastStatus = -1;

            while (System.currentTimeMillis() < deadline) {
                DownloadManager.Query query =
                        new DownloadManager.Query().setFilterById(downloadId);
                try (Cursor cursor = manager.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long downloaded = cursor.getLong(
                                cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = cursor.getLong(
                                cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                        if (status != lastStatus) {
                            listener.onUpdate(
                                    statusText(status) + " · "
                                            + downloaded + "/"
                                            + (total > 0 ? total : TEST_BYTES)
                                            + " bytes",
                                    false,
                                    false);
                            lastStatus = status;
                        }

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            listener.onUpdate(
                                    "自检成功 · 原生 DownloadManager 完成 "
                                            + downloaded + " bytes",
                                    true,
                                    true);
                            return;
                        }

                        if (status == DownloadManager.STATUS_FAILED) {
                            int reason = cursor.getInt(
                                    cursor.getColumnIndexOrThrow(
                                            DownloadManager.COLUMN_REASON));
                            String detail = "下载失败 · reason=" + reason;
                            if (reason == 400) {
                                detail += " · HTTP 400/明文策略拒绝";
                            }
                            listener.onUpdate(detail, true, false);
                            return;
                        }
                    }
                }

                Thread.sleep(250L);
            }

            listener.onUpdate("自检超时：20 秒内未完成本地下载", true, false);
        } catch (Throwable t) {
            String detail = t.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = t.getClass().getSimpleName();
            }
            listener.onUpdate("自检异常：" + detail, true, false);
        } finally {
            stopServer.set(true);
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

    private static void serve(ServerSocket server, AtomicBoolean stop) {
        long deadline = System.currentTimeMillis() + 25_000L;
        while (!stop.get() && System.currentTimeMillis() < deadline) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                handle(socket);
            } catch (java.net.SocketTimeoutException ignored) {
                // Periodically re-check stop/deadline.
            } catch (Throwable ignored) {
                if (!stop.get()) {
                    // Keep the probe server alive for another request.
                }
            }
        }
    }

    private static void handle(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isBlank()) {
            return;
        }

        String range = null;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            if ("range".equalsIgnoreCase(name)) {
                range = line.substring(colon + 1).trim();
            }
        }

        long start = 0L;
        long end = TEST_BYTES - 1L;
        boolean partial = false;

        if (range != null && range.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String startText = spec.substring(0, dash).trim();
                String endText = spec.substring(dash + 1).trim();
                if (!startText.isEmpty()) {
                    start = Long.parseLong(startText);
                }
                if (!endText.isEmpty()) {
                    end = Math.min(Long.parseLong(endText), TEST_BYTES - 1L);
                }
                if (start <= end && start < TEST_BYTES) {
                    partial = true;
                }
            }
        }

        if (start >= TEST_BYTES || end < start) {
            write416(socket);
            return;
        }

        long length = end - start + 1L;
        BufferedWriter headers = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));

        headers.write(partial
                ? "HTTP/1.1 206 Partial Content\r\n"
                : "HTTP/1.1 200 OK\r\n");
        headers.write("Content-Type: application/octet-stream\r\n");
        headers.write("Content-Length: " + length + "\r\n");
        headers.write("Accept-Ranges: bytes\r\n");
        headers.write("ETag: \"sysdl-loopback-v1\"\r\n");
        headers.write("Last-Modified: Sat, 26 Sep 2026 00:00:00 GMT\r\n");
        headers.write("Content-Encoding: identity\r\n");
        if (partial) {
            headers.write("Content-Range: bytes " + start + "-" + end
                    + "/" + TEST_BYTES + "\r\n");
        }
        headers.write("Connection: close\r\n");
        headers.write("\r\n");
        headers.flush();

        // HEAD-like requests should not receive a body.
        if (requestLine.startsWith("HEAD ")) {
            return;
        }

        OutputStream out = socket.getOutputStream();
        long remaining = length;
        while (remaining > 0L) {
            int count = (int) Math.min(BLOCK.length, remaining);
            out.write(BLOCK, 0, count);
            remaining -= count;
        }
        out.flush();
    }

    private static void write416(Socket socket) throws Exception {
        BufferedWriter headers = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        headers.write("HTTP/1.1 416 Range Not Satisfiable\r\n");
        headers.write("Content-Range: bytes */" + TEST_BYTES + "\r\n");
        headers.write("Content-Length: 0\r\n");
        headers.write("Connection: close\r\n\r\n");
        headers.flush();
    }

    private static String statusText(int status) {
        switch (status) {
            case DownloadManager.STATUS_PENDING:
                return "等待下载";
            case DownloadManager.STATUS_RUNNING:
                return "DownloadThread 正在传输";
            case DownloadManager.STATUS_PAUSED:
                return "下载暂停";
            case DownloadManager.STATUS_SUCCESSFUL:
                return "下载完成";
            case DownloadManager.STATUS_FAILED:
                return "下载失败";
            default:
                return "状态 " + status;
        }
    }
}
