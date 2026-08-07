package io.github.zhanfg.sda;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-JVM HTTP contract tests for the exact byte-range behavior used by the engine. */
public final class RangeHttpContractTest {
    private static final String ETAG = "\"sda-range-fixture-v1\"";

    @Test
    public void parallelRangesReassembleOriginalBytes() throws Exception {
        byte[] source = fixtureBytes(2 * 1024 * 1024 + 137);
        try (LocalRangeServer server = new LocalRangeServer(source)) {
            int chunkSize = 256 * 1024;
            int chunks = (source.length + chunkSize - 1) / chunkSize;
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<byte[]>> futures = new ArrayList<>();
                for (int index = 0; index < chunks; index++) {
                    final long start = (long) index * chunkSize;
                    final long end = Math.min(source.length - 1L, start + chunkSize - 1L);
                    futures.add(workers.submit(() -> fetchExactRange(server.url(), start, end, ETAG)));
                }

                ByteArrayOutputStream assembled = new ByteArrayOutputStream(source.length);
                for (Future<byte[]> future : futures) {
                    assembled.write(future.get(10, TimeUnit.SECONDS));
                }
                assertArrayEquals(source, assembled.toByteArray());
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void ifRangeMismatchForcesWholeBodyResponseAndRuntimeFallback() throws Exception {
        byte[] source = fixtureBytes(512 * 1024 + 31);
        try (LocalRangeServer server = new LocalRangeServer(source)) {
            HttpURLConnection connection = (HttpURLConnection) server.url().openConnection();
            try {
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Range", "bytes=128-255");
                connection.setRequestProperty("If-Range", "\"different-resource\"");

                assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
                assertEquals(source.length, connection.getContentLengthLong());
                assertFalse(RangeProtocol.resolveBaseWindow(
                        connection.getResponseCode(),
                        true,
                        "bytes=128-255",
                        connection.getHeaderField("Content-Range"),
                        connection.getContentLengthLong()).accepted);
            } finally {
                connection.disconnect();
            }
        }
    }

    private static byte[] fetchExactRange(URL url, long start, long end, String validator)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            connection.setRequestProperty("If-Range", validator);

            assertEquals(HttpURLConnection.HTTP_PARTIAL, connection.getResponseCode());
            assertEquals(validator, connection.getHeaderField("ETag"));
            RangeProtocol.ContentRange range = RangeProtocol.parseContentRange(
                    connection.getHeaderField("Content-Range"));
            assertNotNull(range);
            assertEquals(start, range.start);
            assertEquals(end, range.end);

            int expected = Math.toIntExact(end - start + 1L);
            ByteArrayOutputStream output = new ByteArrayOutputStream(expected);
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                }
            }
            byte[] result = output.toByteArray();
            assertEquals(expected, result.length);
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] fixtureBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) ((index * 31 + index / 17) & 0xff);
        }
        return bytes;
    }

    private static final class LocalRangeServer implements AutoCloseable {
        private final byte[] body;
        private final ServerSocket serverSocket;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Future<?> acceptLoop;

        LocalRangeServer(byte[] body) throws IOException {
            this.body = body;
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.acceptLoop = executor.submit(() -> {
                while (!closed.get()) {
                    try {
                        Socket socket = serverSocket.accept();
                        executor.submit(() -> handle(socket));
                    } catch (IOException error) {
                        if (!closed.get()) throw new RuntimeException(error);
                    }
                }
            });
        }

        URL url() throws IOException {
            return new URL("http", serverSocket.getInetAddress().getHostAddress(),
                    serverSocket.getLocalPort(), "/fixture.bin");
        }

        private void handle(Socket socket) {
            try (Socket client = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         client.getInputStream(), StandardCharsets.US_ASCII));
                 OutputStream output = client.getOutputStream()) {
                String requestLine = reader.readLine();
                if (requestLine == null || !requestLine.startsWith("GET ")) return;

                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }

                String rangeHeader = headers.get("range");
                String ifRange = headers.get("if-range");
                RangeProtocol.RequestRange requested = RangeProtocol.parseSingleRange(rangeHeader);
                if (requested == null || (ifRange != null && !ETAG.equals(ifRange))) {
                    writeResponse(output, 200, "OK", null, 0, body.length - 1);
                    return;
                }

                long end = requested.openEnded || requested.end == null
                        ? body.length - 1L
                        : requested.end;
                if (requested.start >= body.length || end >= body.length) {
                    writeHeaders(output, 416, "Range Not Satisfiable", 0,
                            "Content-Range: bytes */" + body.length + "\r\n");
                    return;
                }
                writeResponse(output, 206, "Partial Content", requested.start, requested.start, end);
            } catch (IOException ignored) {
                // A test-side client may disconnect after receiving the required response.
            }
        }

        private void writeResponse(OutputStream output, int status, String reason,
                                   Long rangeStart, long start, long end) throws IOException {
            long length = end - start + 1L;
            String extra = "ETag: " + ETAG + "\r\n"
                    + "Accept-Ranges: bytes\r\n";
            if (rangeStart != null) {
                extra += "Content-Range: bytes " + start + "-" + end + "/" + body.length + "\r\n";
            }
            writeHeaders(output, status, reason, length, extra);
            output.write(body, Math.toIntExact(start), Math.toIntExact(length));
            output.flush();
        }

        private static void writeHeaders(OutputStream output, int status, String reason,
                                         long contentLength, String extra) throws IOException {
            String response = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Length: " + contentLength + "\r\n"
                    + (extra == null ? "" : extra)
                    + "Connection: close\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) return;
            serverSocket.close();
            executor.shutdownNow();
            try {
                acceptLoop.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
