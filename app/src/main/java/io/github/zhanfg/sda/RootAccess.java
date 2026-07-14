package io.github.zhanfg.sda;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Performs the only interactive root probe used by first-run setup and diagnostics. */
public final class RootAccess {
    private RootAccess() { }

    public static Result request(long timeoutSeconds) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id -u")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(Math.max(3L, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, "Root 请求超时");
            }
            String output = readAll(process.getInputStream()).trim();
            boolean granted = process.exitValue() == 0
                    && ("0".equals(output) || output.startsWith("0\n"));
            if (granted) return new Result(true, "Root 权限已授予");
            return new Result(false, output.isEmpty()
                    ? "Root 被拒绝或 su 不可用"
                    : "Root 未授予：" + sanitize(output));
        } catch (Throwable error) {
            return new Result(false, "无法调用 su：" + error.getClass().getSimpleName());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) output.write(buffer, 0, read);
            if (output.size() > 4096) break;
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String sanitize(String text) {
        String value = text.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    public static final class Result {
        public final boolean granted;
        public final String message;

        Result(boolean granted, String message) {
            this.granted = granted;
            this.message = message;
        }
    }
}
