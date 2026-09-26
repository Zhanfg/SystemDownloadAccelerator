package com.v4atune.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

final class RootShell {
    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output.trim();
        }

        boolean ok() {
            return code == 0;
        }
    }

    private RootShell() {}

    static boolean available() {
        Result r = exec("id");
        return r.ok() && r.output.contains("uid=0");
    }

    static Result exec(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            int code = p.waitFor();
            return new Result(code, sb.toString());
        } catch (Exception e) {
            return new Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }

    static Result firstSuccessful(List<String> commands) {
        List<String> errors = new ArrayList<>();
        for (String cmd : commands) {
            Result r = exec(cmd);
            if (r.ok() && !r.output.isBlank()) return r;
            errors.add(r.output);
        }
        return new Result(1, String.join("\n", errors));
    }

    static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
