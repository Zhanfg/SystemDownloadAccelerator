package dev.axymorrsen.systemdownloadaccelerator;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/** Builds a one-tap diagnostics report that is safe to share with the developer. */
final class DiagnosticReport {
    static final class Result {
        final boolean success;
        final String displayPath;
        final Uri uri;
        final String error;

        Result(boolean success, String displayPath, Uri uri, String error) {
            this.success = success;
            this.displayPath = displayPath;
            this.uri = uri;
            this.error = error;
        }
    }

    private static final int MAX_CAPTURE_BYTES = 2 * 1024 * 1024;

    private DiagnosticReport() {}

    static Result generate(
            Context context,
            XposedService service,
            List<String> engineEvents,
            String selfTestStatus) {
        try {
            String report = buildReport(
                    context,
                    service,
                    engineEvents,
                    selfTestStatus);
            return saveReport(context, report);
        } catch (Throwable t) {
            return new Result(
                    false,
                    "",
                    null,
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
        }
    }

    private static String buildReport(
            Context context,
            XposedService service,
            List<String> engineEvents,
            String selfTestStatus) {
        StringBuilder out = new StringBuilder(64 * 1024);

        appendHeader(out, "SystemDownloadAccelerator diagnostics");
        append(out, "generated_at", timestamp());
        append(out, "module_version", BuildConfig.VERSION_NAME);
        append(out, "module_version_code", BuildConfig.VERSION_CODE);
        append(out, "build_id", BuildConfig.BUILD_ID);

        appendHeader(out, "Device");
        append(out, "manufacturer", Build.MANUFACTURER);
        append(out, "brand", Build.BRAND);
        append(out, "model", Build.MODEL);
        append(out, "device", Build.DEVICE);
        append(out, "product", Build.PRODUCT);
        append(out, "android_release", Build.VERSION.RELEASE);
        append(out, "sdk", Build.VERSION.SDK_INT);
        append(out, "build_display", Build.DISPLAY);
        append(out, "build_fingerprint", Build.FINGERPRINT);
        append(out, "kernel", System.getProperty("os.version", "unknown"));

        appendHeader(out, "Self-test");
        append(out, "status", selfTestStatus == null ? "(none)" : selfTestStatus);

        out.append("engine_events_ui:\n");
        if (engineEvents == null || engineEvents.isEmpty()) {
            out.append("  (none received)\n");
        } else {
            for (String event : engineEvents) {
                out.append("  - ").append(sanitize(event)).append('\n');
            }
        }

        out.append("engine_events_persistent:\n");
        List<String> persistentEvents =
                TelemetryProvider.readEvents(context);
        if (persistentEvents.isEmpty()) {
            out.append("  (none stored)\n");
        } else {
            for (String event : persistentEvents) {
                out.append("  - ").append(sanitize(event)).append('\n');
            }
        }

        appendHeader(out, "LSPosed / libxposed");
        if (service == null) {
            out.append("service: disconnected\n");
        } else {
            try {
                append(out, "framework", service.getFrameworkName());
                append(out, "framework_version", service.getFrameworkVersion());
                append(out, "api", service.getApiVersion());
                append(out, "framework_properties", service.getFrameworkProperties());

                List<String> scope = service.getScope();
                out.append("scope:\n");
                if (scope == null || scope.isEmpty()) {
                    out.append("  (empty)\n");
                } else {
                    for (String item : scope) {
                        out.append("  - ").append(item).append('\n');
                    }
                }

                out.append("running_targets:\n");
                List<HookedTarget> targets = service.getApiVersion() >= XposedService.API_102
                        ? service.getRunningTargets()
                        : new ArrayList<>();
                if (targets == null || targets.isEmpty()) {
                    out.append("  (none)\n");
                } else {
                    for (HookedTarget target : targets) {
                        out.append("  - process=")
                                .append(sanitize(target.getProcessName()))
                                .append(" pid=").append(target.getPid())
                                .append(" uid=").append(target.getUid())
                                .append(" state=").append(target.getState())
                                .append(" loadedVersion=")
                                .append(target.getLoadedVersionCode())
                                .append('\n');
                    }
                }
            } catch (Throwable t) {
                out.append("service_read_error: ")
                        .append(t.getClass().getSimpleName())
                        .append(": ")
                        .append(sanitize(t.getMessage()))
                        .append('\n');
            }
        }

        appendHeader(out, "Relevant processes (root best-effort)");
        CommandResult ps = runRoot(
                "ps -A 2>&1 | grep -E 'providers.downloads|android.process.media|systemui|systemdownloadaccelerator|lspd|zygote' | grep -v grep",
                5);
        appendCommand(out, "ps", ps);

        appendHeader(out, "Android logcat (filtered)");
        CommandResult logcat = runRoot(
                "logcat -d -v threadtime -t 2500",
                8);
        if (!logcat.success) {
            logcat = runShell(
                    "logcat -d -v threadtime -t 2500",
                    8);
        }
        append(out, "logcat_source", logcat.source);
        append(out, "logcat_exit", logcat.exitCode);
        append(out, "logcat_timeout", logcat.timedOut);
        out.append(filterLogcat(logcat.output));

        appendHeader(out, "LSPosed module log (root best-effort)");
        CommandResult lsp = runRoot(
                "grep -R -a -E 'SysDlAccel|systemdownloadaccelerator|SystemDownloadAccelerator' /data/adb/lspd/log 2>/dev/null | tail -n 600",
                8);
        appendCommand(out, "lspd_log", lsp);

        appendHeader(out, "Package snapshot");
        CommandResult pkg = runRoot(
                "dumpsys package dev.axymorrsen.systemdownloadaccelerator 2>/dev/null | grep -E 'versionName=|versionCode=|enabled=|MainActivity'",
                5);
        appendCommand(out, "package", pkg);

        out.append("\n=== END ===\n");
        return out.toString();
    }

    private static Result saveReport(Context context, String report) throws Exception {
        String fileName = "SysDlDiag_" + fileTimestamp() + ".txt";
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/SystemDownloadAccelerator");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }

            boolean completed = false;
            try (OutputStream stream = resolver.openOutputStream(uri, "w")) {
                if (stream == null) {
                    throw new IllegalStateException("MediaStore output stream unavailable");
                }
                stream.write(bytes);
                stream.flush();
                completed = true;
            } finally {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, completed ? 0 : 1);
                resolver.update(uri, done, null, null);
                if (!completed) {
                    resolver.delete(uri, null, null);
                }
            }

            return new Result(
                    true,
                    "Download/SystemDownloadAccelerator/" + fileName,
                    uri,
                    null);
        }

        File root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null) {
            throw new IllegalStateException("external Downloads unavailable");
        }
        File dir = new File(root, "SystemDownloadAccelerator");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create diagnostics directory");
        }
        File file = new File(dir, fileName);
        try (OutputStream stream = new FileOutputStream(file)) {
            stream.write(bytes);
        }
        return new Result(true, file.getAbsolutePath(), Uri.fromFile(file), null);
    }

    private static String filterLogcat(String source) {
        StringBuilder out = new StringBuilder();
        out.append("filtered_lines:\n");

        if (source == null || source.isBlank()) {
            out.append("  (no logcat captured)\n");
            return out.toString();
        }

        String[] needles = {
                "SysDlAccel",
                "DownloadProvider",
                "DownloadThread",
                "DownloadManager",
                "HttpURLConnection",
                "HttpEngine",
                "Cronet",
                "libxposed",
                "LSPosed",
                "providers.downloads",
                "systemdownloadaccelerator"
        };

        int kept = 0;
        String[] lines = source.split("\\r?\\n");
        for (String line : lines) {
            boolean match = false;
            for (String needle : needles) {
                if (line.contains(needle)) {
                    match = true;
                    break;
                }
            }
            if (!match) continue;

            out.append(line).append('\n');
            kept++;
            if (kept >= 700) {
                out.append("... truncated after 700 matching lines ...\n");
                break;
            }
        }

        if (kept == 0) {
            out.append("  (no matching lines)\n");
        }
        return out.toString();
    }

    private static CommandResult runRoot(String command, int timeoutSeconds) {
        return run(
                new String[]{"su", "-c", command},
                timeoutSeconds,
                "root");
    }

    private static CommandResult runShell(String command, int timeoutSeconds) {
        return run(
                new String[]{"sh", "-c", command},
                timeoutSeconds,
                "shell");
    }

    private static CommandResult run(
            String[] command,
            int timeoutSeconds,
            String source) {
        Process process = null;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = null;

        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            Process finalProcess = process;
            reader = new Thread(() -> {
                try (InputStream input = finalProcess.getInputStream()) {
                    byte[] block = new byte[8192];
                    int total = 0;
                    int count;
                    while ((count = input.read(block)) >= 0) {
                        if (count == 0) continue;
                        int allowed = Math.min(
                                count,
                                MAX_CAPTURE_BYTES - total);
                        if (allowed > 0) {
                            buffer.write(block, 0, allowed);
                            total += allowed;
                        }
                        if (total >= MAX_CAPTURE_BYTES) {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "sysdl-diag-reader");
            reader.start();

            boolean done = process.waitFor(
                    timeoutSeconds,
                    TimeUnit.SECONDS);
            boolean timedOut = !done;
            if (timedOut) {
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            }

            if (reader != null) {
                reader.join(800L);
            }

            String output = buffer.toString(StandardCharsets.UTF_8);
            int exit = done ? process.exitValue() : -1;
            return new CommandResult(
                    done && exit == 0,
                    exit,
                    timedOut,
                    output,
                    source);
        } catch (Throwable t) {
            return new CommandResult(
                    false,
                    -1,
                    false,
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()),
                    source);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void appendCommand(
            StringBuilder out,
            String name,
            CommandResult result) {
        append(out, name + "_source", result.source);
        append(out, name + "_success", result.success);
        append(out, name + "_exit", result.exitCode);
        append(out, name + "_timeout", result.timedOut);
        out.append(name).append("_output:\n");
        if (result.output == null || result.output.isBlank()) {
            out.append("  (empty)\n");
        } else {
            out.append(result.output);
            if (!result.output.endsWith("\n")) {
                out.append('\n');
            }
        }
    }

    private static void appendHeader(StringBuilder out, String title) {
        out.append("\n=== ").append(title).append(" ===\n");
    }

    private static void append(StringBuilder out, String key, Object value) {
        out.append(key)
                .append(": ")
                .append(sanitize(String.valueOf(value)))
                .append('\n');
    }

    private static String sanitize(String value) {
        if (value == null) return "(null)";
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String timestamp() {
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        return format.format(new Date());
    }

    private static String fileTimestamp() {
        SimpleDateFormat format =
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return format.format(new Date());
    }

    private static final class CommandResult {
        final boolean success;
        final int exitCode;
        final boolean timedOut;
        final String output;
        final String source;

        CommandResult(
                boolean success,
                int exitCode,
                boolean timedOut,
                String output,
                String source) {
            this.success = success;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output;
            this.source = source;
        }
    }
}
