package com.v4atune.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ViperBridge {
    static final String VIPER_PKG = "com.llsl.viper4android";
    private static final String PREFS = "v4atune";
    private static final String KEY_LAST_BACKUP = "last_backup";

    static final class Paths {
        final String appBase;
        final String db;
        final String prefs;
        final String kernelDir;

        Paths(String appBase, String db, String prefs, String kernelDir) {
            this.appBase = appBase;
            this.db = db;
            this.prefs = prefs;
            this.kernelDir = kernelDir;
        }
    }

    private final Context context;

    ViperBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean driverReady() {
        RootShell.Result r = RootShell.exec("service check viper.control");
        return r.ok() && r.output.contains("found") && !r.output.contains("not found");
    }

    Paths locate() throws Exception {
        if (!RootShell.available()) throw new IllegalStateException("Root permission is required");

        String pkg = RootShell.shQuote(VIPER_PKG);
        RootShell.Result pm = RootShell.exec(
                "dumpsys package " + pkg + " 2>/dev/null | " +
                        "sed -n 's/^[[:space:]]*dataDir=//p' | head -n1"
        );
        List<String> bases = new ArrayList<>();
        if (pm.ok() && !pm.output.isBlank()) bases.add(pm.output.trim());
        bases.add("/data/user/0/" + VIPER_PKG);
        bases.add("/data/data/" + VIPER_PKG);

        String appBase = "";
        for (String base : bases) {
            RootShell.Result t = RootShell.exec(
                    "test -f " + RootShell.shQuote(base + "/databases/viper4android.db") + " && echo ok"
            );
            if (t.output.contains("ok")) {
                appBase = base;
                break;
            }
        }
        if (appBase.isBlank()) {
            RootShell.Result f = RootShell.exec(
                    "find /data/user /data/user_de /data/data -maxdepth 6 -type f " +
                            "-path '*/" + VIPER_PKG + "/databases/viper4android.db' 2>/dev/null | head -n1"
            );
            if (f.ok() && !f.output.isBlank()) {
                String db = f.output.trim();
                appBase = db.substring(0, db.length() - "/databases/viper4android.db".length());
            }
        }
        if (appBase.isBlank()) throw new IllegalStateException("ViPER database was not found");

        String db = appBase + "/databases/viper4android.db";
        String pref = appBase + "/files/datastore/viper_preferences.preferences_pb";
        RootShell.Result p = RootShell.exec("test -f " + RootShell.shQuote(pref) + " && echo ok");
        if (!p.output.contains("ok")) {
            RootShell.Result f = RootShell.exec(
                    "find /data/user /data/user_de /data/data -maxdepth 7 -type f " +
                            "-name 'viper_preferences.preferences_pb' -path '*/" + VIPER_PKG + "/*' 2>/dev/null | head -n1"
            );
            if (!f.ok() || f.output.isBlank()) throw new IllegalStateException("ViPER DataStore was not found");
            pref = f.output.trim();
        }

        String kernelDir = "/sdcard/Android/data/" + VIPER_PKG + "/files/Kernel";
        return new Paths(appBase, db, pref, kernelDir);
    }

    File backup(Paths paths) throws Exception {
        File base = new File(context.getExternalFilesDir(null), "backup/" + System.currentTimeMillis());
        if (!base.mkdirs() && !base.isDirectory()) throw new IllegalStateException("Cannot create backup directory");

        forceStopViper();

        String uid = String.valueOf(Process.myUid());
        String dbOut = new File(base, "viper4android.db").getAbsolutePath();
        String prefOut = new File(base, "viper_preferences.preferences_pb").getAbsolutePath();

        RootShell.Result r = RootShell.exec(
                "cp " + RootShell.shQuote(paths.db) + " " + RootShell.shQuote(dbOut) + " && " +
                        "cp " + RootShell.shQuote(paths.prefs) + " " + RootShell.shQuote(prefOut) + " && " +
                        "chown -R " + uid + ":" + uid + " " + RootShell.shQuote(base.getAbsolutePath())
        );
        if (!r.ok()) throw new IllegalStateException("Backup failed: " + r.output);

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_BACKUP, base.getAbsolutePath()).apply();
        return base;
    }

    void apply(Paths paths, JSONObject profile, File generatedKernel) throws Exception {
        if (profile == null) throw new IllegalArgumentException("Profile is null");
        forceStopViper();

        File work = new File(context.getCacheDir(), "viper-write");
        if (!work.exists() && !work.mkdirs()) throw new IllegalStateException("Cannot create work directory");
        File dbCopy = new File(work, "viper4android.db");
        File prefsCopy = new File(work, "viper_preferences.preferences_pb");

        String uid = String.valueOf(Process.myUid());
        RootShell.Result copy = RootShell.exec(
                "cp " + RootShell.shQuote(paths.db) + " " + RootShell.shQuote(dbCopy.getAbsolutePath()) + " && " +
                        "cp " + RootShell.shQuote(paths.prefs) + " " + RootShell.shQuote(prefsCopy.getAbsolutePath()) + " && " +
                        "chown " + uid + ":" + uid + " " +
                        RootShell.shQuote(dbCopy.getAbsolutePath()) + " " + RootShell.shQuote(prefsCopy.getAbsolutePath())
        );
        if (!copy.ok()) throw new IllegalStateException("Cannot read ViPER private state: " + copy.output);

        writeSpeakerDb(dbCopy, profile);
        PrefProto.patchBooleans(prefsCopy, new String[]{"master_enable", "global_mode", "auto_start"},
                new boolean[]{true, true, true});

        if (generatedKernel != null && generatedKernel.isFile()) {
            RootShell.Result kr = RootShell.exec(
                    "mkdir -p " + RootShell.shQuote(paths.kernelDir) + " && " +
                            "cp " + RootShell.shQuote(generatedKernel.getAbsolutePath()) + " " +
                            RootShell.shQuote(paths.kernelDir + "/" + generatedKernel.getName()) + " && " +
                            "chmod 0644 " + RootShell.shQuote(paths.kernelDir + "/" + generatedKernel.getName())
            );
            if (!kr.ok()) throw new IllegalStateException("Cannot install FIR kernel: " + kr.output);
        }

        RootShell.Result write = RootShell.exec(
                "cat " + RootShell.shQuote(dbCopy.getAbsolutePath()) + " > " + RootShell.shQuote(paths.db) + " && " +
                        "rm -f " + RootShell.shQuote(paths.db + "-wal") + " " + RootShell.shQuote(paths.db + "-shm") + " && " +
                        "cat " + RootShell.shQuote(prefsCopy.getAbsolutePath()) + " > " + RootShell.shQuote(paths.prefs) + "; " +
                        "restorecon " + RootShell.shQuote(paths.db) + " " + RootShell.shQuote(paths.prefs) + " 2>/dev/null || true"
        );
        if (!write.ok()) throw new IllegalStateException("Cannot write ViPER state: " + write.output);

        launchViper();
    }

    boolean restoreLast() throws Exception {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String path = sp.getString(KEY_LAST_BACKUP, "");
        if (path == null || path.isBlank()) return false;
        File base = new File(path);
        File db = new File(base, "viper4android.db");
        File prefs = new File(base, "viper_preferences.preferences_pb");
        if (!db.isFile() || !prefs.isFile()) return false;

        Paths target = locate();
        forceStopViper();
        RootShell.Result r = RootShell.exec(
                "cat " + RootShell.shQuote(db.getAbsolutePath()) + " > " + RootShell.shQuote(target.db) + " && " +
                        "rm -f " + RootShell.shQuote(target.db + "-wal") + " " + RootShell.shQuote(target.db + "-shm") + " && " +
                        "cat " + RootShell.shQuote(prefs.getAbsolutePath()) + " > " + RootShell.shQuote(target.prefs) + "; " +
                        "restorecon " + RootShell.shQuote(target.db) + " " + RootShell.shQuote(target.prefs) + " 2>/dev/null || true"
        );
        if (!r.ok()) throw new IllegalStateException("Restore failed: " + r.output);
        launchViper();
        return true;
    }

    private void writeSpeakerDb(File dbFile, JSONObject profile) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READWRITE
        );
        try {
            db.beginTransaction();
            try {
                long now = System.currentTimeMillis();
                android.content.ContentValues values = new android.content.ContentValues();
                values.put("device_name", "Speaker");
                values.put("is_headphone", 0);
                values.put("settings_json", profile.toString());
                values.put("last_connected", now);
                int rows = db.update("device_settings", values, "device_id=?", new String[]{"speaker"});
                if (rows == 0) {
                    values.put("device_id", "speaker");
                    long id = db.insertOrThrow("device_settings", null, values);
                    if (id < 0) throw new IllegalStateException("Failed to insert speaker profile");
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            db.close();
        }
    }

    private void forceStopViper() {
        RootShell.exec("am force-stop " + RootShell.shQuote(VIPER_PKG));
        try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    void launchViper() {
        RootShell.exec(
                "am start -n " + VIPER_PKG + "/.ui.MainActivity >/dev/null 2>&1 || " +
                        "monkey -p " + VIPER_PKG + " 1 >/dev/null 2>&1"
        );
        try { Thread.sleep(2200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class PrefProto {
        private static final class Field {
            final int number;
            final int wire;
            final byte[] raw;

            Field(int number, int wire, byte[] raw) {
                this.number = number;
                this.wire = wire;
                this.raw = raw;
            }
        }

        static void patchBooleans(File file, String[] keys, boolean[] values) throws Exception {
            byte[] data = Files.readAllBytes(file.toPath());
            List<Field> top = parse(data);
            boolean[] seen = new boolean[keys.length];
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            for (Field f : top) {
                if (f.number == 1 && f.wire == 2) {
                    byte[] entry = lenPayload(f.raw);
                    String key = mapKey(entry);
                    int idx = indexOf(keys, key);
                    if (idx >= 0) {
                        out.write(prefBoolEntry(keys[idx], values[idx]));
                        seen[idx] = true;
                    } else {
                        out.write(f.raw);
                    }
                } else {
                    out.write(f.raw);
                }
            }
            for (int i = 0; i < keys.length; i++) {
                if (!seen[i]) out.write(prefBoolEntry(keys[i], values[i]));
            }

            byte[] patched = out.toByteArray();
            parse(patched);
            Files.write(file.toPath(), patched);
        }

        private static int indexOf(String[] a, String s) {
            for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
            return -1;
        }

        private static String mapKey(byte[] entry) throws Exception {
            for (Field f : parse(entry)) {
                if (f.number == 1 && f.wire == 2) return new String(lenPayload(f.raw), java.nio.charset.StandardCharsets.UTF_8);
            }
            return "";
        }

        private static byte[] prefBoolEntry(String key, boolean value) throws Exception {
            byte[] keyField = lenField(1, key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] boolValue = varintField(1, value ? 1 : 0);
            byte[] valueField = lenField(2, boolValue);
            return lenField(1, concat(keyField, valueField));
        }

        private static List<Field> parse(byte[] data) throws Exception {
            List<Field> out = new ArrayList<>();
            int[] p = {0};
            while (p[0] < data.length) {
                int start = p[0];
                long tag = readVarint(data, p);
                int num = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                switch (wire) {
                    case 0 -> readVarint(data, p);
                    case 1 -> p[0] += 8;
                    case 2 -> {
                        int len = (int) readVarint(data, p);
                        p[0] += len;
                    }
                    case 5 -> p[0] += 4;
                    default -> throw new IllegalArgumentException("Unsupported protobuf wire type " + wire);
                }
                if (p[0] > data.length) throw new IllegalArgumentException("Truncated protobuf");
                out.add(new Field(num, wire, Arrays.copyOfRange(data, start, p[0])));
            }
            return out;
        }

        private static byte[] lenPayload(byte[] raw) throws Exception {
            int[] p = {0};
            readVarint(raw, p);
            int len = (int) readVarint(raw, p);
            return Arrays.copyOfRange(raw, p[0], p[0] + len);
        }

        private static long readVarint(byte[] b, int[] pos) throws Exception {
            long value = 0;
            int shift = 0;
            while (true) {
                if (pos[0] >= b.length) throw new IllegalArgumentException("Truncated varint");
                int x = b[pos[0]++] & 0xff;
                value |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return value;
                shift += 7;
                if (shift > 63) throw new IllegalArgumentException("Bad varint");
            }
        }

        private static byte[] varint(long v) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            do {
                int b = (int) (v & 0x7f);
                v >>>= 7;
                if (v != 0) b |= 0x80;
                out.write(b);
            } while (v != 0);
            return out.toByteArray();
        }

        private static byte[] lenField(int number, byte[] payload) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(varint(((long) number << 3) | 2));
            out.write(varint(payload.length));
            out.write(payload);
            return out.toByteArray();
        }

        private static byte[] varintField(int number, long value) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(varint((long) number << 3));
            out.write(varint(value));
            return out.toByteArray();
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] out = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }
}
