package com.v4atune.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ViperBridge {
    static final String VIPER_PKG = "com.llsl.viper4android";
    private static final String PREFS = "v4atune";
    private static final String KEY_LAST_BACKUP = "last_backup";

    static final class Paths {
        final String appBase;
        final String db;       // optional
        final String prefs;    // optional
        final String kernelDir;

        Paths(String appBase, String db, String prefs, String kernelDir) {
            this.appBase = appBase == null ? "" : appBase;
            this.db = db == null ? "" : db;
            this.prefs = prefs == null ? "" : prefs;
            this.kernelDir = kernelDir;
        }

        boolean hasDb() { return !db.isBlank(); }
        boolean hasPrefs() { return !prefs.isBlank(); }
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

        String appBase = "";
        String db = "";
        String pref = "";

        RootShell.Result pm = RootShell.exec(
                "dumpsys package " + RootShell.shQuote(VIPER_PKG) + " 2>/dev/null | " +
                        "sed -n 's/^[[:space:]]*dataDir=//p' | head -n1"
        );
        if (pm.ok() && !pm.output.isBlank()) appBase = pm.output.trim();

        List<String> roots = Arrays.asList(
                "/data/user",
                "/data/user_de",
                "/data/data",
                "/data_mirror/data_ce",
                "/data_mirror/data_de"
        );

        // DataStore is the primary persistent store. Find it independently of Room.
        List<String> prefCandidates = new ArrayList<>();
        if (!appBase.isBlank()) {
            prefCandidates.add(appBase + "/files/datastore/viper_preferences.preferences_pb");
        }
        prefCandidates.add("/data/user/0/" + VIPER_PKG + "/files/datastore/viper_preferences.preferences_pb");
        prefCandidates.add("/data/data/" + VIPER_PKG + "/files/datastore/viper_preferences.preferences_pb");

        for (String p : prefCandidates) {
            if (rootFileExists(p)) { pref = p; break; }
        }
        if (pref.isBlank()) {
            RootShell.Result f = RootShell.exec(
                    "find /data/user /data/user_de /data/data /data_mirror/data_ce /data_mirror/data_de " +
                            "-maxdepth 9 -type f -name 'viper_preferences.preferences_pb' " +
                            "-path '*/" + VIPER_PKG + "/*' 2>/dev/null | head -n1"
            );
            if (f.ok() && !f.output.isBlank()) pref = f.output.trim();
        }

        // Room exists in newer managers, but is optional for compatibility.
        List<String> dbCandidates = new ArrayList<>();
        if (!appBase.isBlank()) dbCandidates.add(appBase + "/databases/viper4android.db");
        dbCandidates.add("/data/user/0/" + VIPER_PKG + "/databases/viper4android.db");
        dbCandidates.add("/data/data/" + VIPER_PKG + "/databases/viper4android.db");
        for (String p : dbCandidates) {
            if (rootFileExists(p)) { db = p; break; }
        }
        if (db.isBlank()) {
            RootShell.Result f = RootShell.exec(
                    "find /data/user /data/user_de /data/data /data_mirror/data_ce /data_mirror/data_de " +
                            "-maxdepth 9 -type f -name 'viper4android.db' -path '*/" + VIPER_PKG + "/*' " +
                            "2>/dev/null | head -n1"
            );
            if (f.ok() && !f.output.isBlank()) db = f.output.trim();
        }

        // Last-resort namespace view of a running manager process.
        if (pref.isBlank() || db.isBlank()) {
            RootShell.Result pid = RootShell.exec("pidof " + VIPER_PKG + " | awk '{print $1}'");
            if (pid.ok() && !pid.output.isBlank()) {
                String root = "/proc/" + pid.output.trim() + "/root";
                String p = root + "/data/user/0/" + VIPER_PKG + "/files/datastore/viper_preferences.preferences_pb";
                String d = root + "/data/user/0/" + VIPER_PKG + "/databases/viper4android.db";
                if (pref.isBlank() && rootFileExists(p)) pref = p;
                if (db.isBlank() && rootFileExists(d)) db = d;
            }
        }

        if (appBase.isBlank()) {
            if (!pref.isBlank()) appBase = stripSuffix(pref, "/files/datastore/viper_preferences.preferences_pb");
            else if (!db.isBlank()) appBase = stripSuffix(db, "/databases/viper4android.db");
        }

        // Driver-only mode is valid: live DSP can still be configured directly.
        if (pref.isBlank() && db.isBlank() && !ViperLiveControl.available()) {
            throw new IllegalStateException("ViPER storage and viper.control are both unavailable");
        }

        String kernelDir = "/sdcard/Android/data/" + VIPER_PKG + "/files/Kernel";
        return new Paths(appBase, db, pref, kernelDir);
    }

    File backup(Paths paths) throws Exception {
        File base = new File(context.getExternalFilesDir(null), "backup/" + System.currentTimeMillis());
        if (!base.mkdirs() && !base.isDirectory()) throw new IllegalStateException("Cannot create backup directory");

        forceStopViper();
        String uid = String.valueOf(Process.myUid());
        List<String> cmds = new ArrayList<>();

        if (paths.hasDb()) {
            cmds.add("cp " + RootShell.shQuote(paths.db) + " " +
                    RootShell.shQuote(new File(base, "viper4android.db").getAbsolutePath()));
        }
        if (paths.hasPrefs()) {
            cmds.add("cp " + RootShell.shQuote(paths.prefs) + " " +
                    RootShell.shQuote(new File(base, "viper_preferences.preferences_pb").getAbsolutePath()));
        }
        if (!cmds.isEmpty()) {
            cmds.add("chown -R " + uid + ":" + uid + " " + RootShell.shQuote(base.getAbsolutePath()));
            RootShell.Result r = RootShell.exec(String.join(" && ", cmds));
            if (!r.ok()) throw new IllegalStateException("Backup failed: " + r.output);
        }

        // Record discovery state for debugging/version compatibility.
        Files.write(new File(base, "paths.txt").toPath(),
                ("appBase=" + paths.appBase + "\n" +
                        "db=" + paths.db + "\n" +
                        "prefs=" + paths.prefs + "\n" +
                        "driver=" + driverReady() + "\n").getBytes(StandardCharsets.UTF_8));

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_BACKUP, base.getAbsolutePath()).apply();
        return base;
    }

    void apply(Paths paths, JSONObject profile, File generatedKernel) throws Exception {
        if (profile == null) throw new IllegalArgumentException("Profile is null");
        forceStopViper();

        File work = new File(context.getCacheDir(), "viper-write");
        if (!work.exists() && !work.mkdirs()) throw new IllegalStateException("Cannot create work directory");
        String uid = String.valueOf(Process.myUid());

        File dbCopy = new File(work, "viper4android.db");
        File prefsCopy = new File(work, "viper_preferences.preferences_pb");

        if (paths.hasDb()) {
            RootShell.Result copyDb = RootShell.exec(
                    "cp " + RootShell.shQuote(paths.db) + " " + RootShell.shQuote(dbCopy.getAbsolutePath()) + " && " +
                            "chown " + uid + ":" + uid + " " + RootShell.shQuote(dbCopy.getAbsolutePath())
            );
            if (copyDb.ok()) {
                try {
                    writeSpeakerDb(dbCopy, profile);
                    RootShell.Result writeDb = RootShell.exec(
                            "cat " + RootShell.shQuote(dbCopy.getAbsolutePath()) + " > " + RootShell.shQuote(paths.db) + " && " +
                                    "rm -f " + RootShell.shQuote(paths.db + "-wal") + " " + RootShell.shQuote(paths.db + "-shm") + "; " +
                                    "restorecon " + RootShell.shQuote(paths.db) + " 2>/dev/null || true"
                    );
                    if (!writeDb.ok()) throw new IllegalStateException("Room DB write failed: " + writeDb.output);
                } catch (Exception ignored) {
                    // Compatibility rule: Room is an optional mirror, never a hard dependency.
                }
            }
        }

        if (paths.hasPrefs()) {
            RootShell.Result copyPrefs = RootShell.exec(
                    "cp " + RootShell.shQuote(paths.prefs) + " " + RootShell.shQuote(prefsCopy.getAbsolutePath()) + " && " +
                            "chown " + uid + ":" + uid + " " + RootShell.shQuote(prefsCopy.getAbsolutePath())
            );
            if (!copyPrefs.ok()) throw new IllegalStateException("Cannot read ViPER DataStore: " + copyPrefs.output);

            PrefProto.patchProfile(prefsCopy, profile);

            RootShell.Result writePrefs = RootShell.exec(
                    "cat " + RootShell.shQuote(prefsCopy.getAbsolutePath()) + " > " + RootShell.shQuote(paths.prefs) + "; " +
                            "restorecon " + RootShell.shQuote(paths.prefs) + " 2>/dev/null || true"
            );
            if (!writePrefs.ok()) throw new IllegalStateException("DataStore write failed: " + writePrefs.output);
        }

        if (generatedKernel != null && generatedKernel.isFile()) {
            RootShell.Result kr = RootShell.exec(
                    "mkdir -p " + RootShell.shQuote(paths.kernelDir) + " && " +
                            "cp " + RootShell.shQuote(generatedKernel.getAbsolutePath()) + " " +
                            RootShell.shQuote(paths.kernelDir + "/" + generatedKernel.getName()) + " && " +
                            "chmod 0644 " + RootShell.shQuote(paths.kernelDir + "/" + generatedKernel.getName())
            );
            if (!kr.ok()) throw new IllegalStateException("Cannot install FIR kernel: " + kr.output);
        }

        launchViper();

        // Authoritative live path: independent of manager database version.
        if (!ViperLiveControl.available()) {
            throw new IllegalStateException("viper.control disappeared after ViPER reload");
        }
        ViperLiveControl.applyProfile(profile);
        if (generatedKernel != null && generatedKernel.isFile()
                && profile.getJSONObject("convolver").optBoolean("enable", false)) {
            ViperLiveControl.streamConvolver(generatedKernel);
        }
    }

    boolean restoreLast() throws Exception {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String path = sp.getString(KEY_LAST_BACKUP, "");
        if (path == null || path.isBlank()) return false;
        File base = new File(path);
        File db = new File(base, "viper4android.db");
        File prefs = new File(base, "viper_preferences.preferences_pb");
        if (!db.isFile() && !prefs.isFile()) return false;

        Paths target = locate();
        forceStopViper();
        List<String> cmds = new ArrayList<>();
        if (db.isFile() && target.hasDb()) {
            cmds.add("cat " + RootShell.shQuote(db.getAbsolutePath()) + " > " + RootShell.shQuote(target.db));
            cmds.add("rm -f " + RootShell.shQuote(target.db + "-wal") + " " + RootShell.shQuote(target.db + "-shm"));
        }
        if (prefs.isFile() && target.hasPrefs()) {
            cmds.add("cat " + RootShell.shQuote(prefs.getAbsolutePath()) + " > " + RootShell.shQuote(target.prefs));
        }
        if (cmds.isEmpty()) return false;
        RootShell.Result r = RootShell.exec(String.join(" && ", cmds) + "; restorecon " +
                (target.hasDb() ? RootShell.shQuote(target.db) + " " : "") +
                (target.hasPrefs() ? RootShell.shQuote(target.prefs) : "") + " 2>/dev/null || true");
        if (!r.ok()) throw new IllegalStateException("Restore failed: " + r.output);
        launchViper();
        return true;
    }

    private boolean rootFileExists(String p) {
        if (p == null || p.isBlank()) return false;
        RootShell.Result r = RootShell.exec("test -f " + RootShell.shQuote(p) + " && echo ok");
        return r.output.contains("ok");
    }

    private static String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private void writeSpeakerDb(File dbFile, JSONObject profile) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            // Only touch DBs that actually have the expected new-manager table.
            android.database.Cursor c = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='device_settings'", null);
            boolean hasTable;
            try { hasTable = c.moveToFirst(); } finally { c.close(); }
            if (!hasTable) return;

            db.beginTransaction();
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put("device_name", "Speaker");
                values.put("is_headphone", 0);
                values.put("settings_json", profile.toString());
                values.put("last_connected", System.currentTimeMillis());
                int rows = db.update("device_settings", values, "device_id=?", new String[]{"speaker"});
                if (rows == 0) {
                    values.put("device_id", "speaker");
                    db.insertOrThrow("device_settings", null, values);
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
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    void launchViper() {
        RootShell.exec(
                "am start -n " + VIPER_PKG + "/.ui.MainActivity >/dev/null 2>&1 || " +
                        "monkey -p " + VIPER_PKG + " 1 >/dev/null 2>&1"
        );
        try { Thread.sleep(1600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class PrefProto {
        private enum Kind { BOOL, INT, FLOAT, STRING }
        private record Value(Kind kind, Object value) {}

        private static final class Field {
            final int number;
            final int wire;
            final byte[] raw;
            Field(int number, int wire, byte[] raw) {
                this.number = number; this.wire = wire; this.raw = raw;
            }
        }

        static void patchProfile(File file, JSONObject root) throws Exception {
            LinkedHashMap<String, Value> changes = new LinkedHashMap<>();
            put(changes, "master_enable", Kind.BOOL, true);
            put(changes, "global_mode", Kind.BOOL, true);
            put(changes, "auto_start", Kind.BOOL, true);

            scalar(changes, root, "masterLimiter", "threshold", 0x10110, Kind.FLOAT);
            scalar(changes, root, "masterLimiter", "outputVolume", 0x10111, Kind.FLOAT);
            scalar(changes, root, "masterLimiter", "channelPan", 0x10112, Kind.FLOAT);

            scalar(changes, root, "playbackGainControl", "enable", 0x10120, Kind.BOOL);
            scalar(changes, root, "playbackGainControl", "strength", 0x10121, Kind.FLOAT);
            scalar(changes, root, "playbackGainControl", "maxGain", 0x10122, Kind.FLOAT);
            scalar(changes, root, "playbackGainControl", "outputThreshold", 0x10123, Kind.FLOAT);

            scalar(changes, root, "lufs", "enable", 0x10130, Kind.BOOL);
            scalar(changes, root, "lufs", "target", 0x10131, Kind.FLOAT);
            scalar(changes, root, "lufs", "maxGain", 0x10132, Kind.FLOAT);
            scalar(changes, root, "lufs", "speed", 0x10133, Kind.INT);

            String[] fetKeys={"enable","threshold","ratio","knee","kneeAuto","gain","gainAuto","attack",
                    "attackAuto","release","releaseAuto","kneeMulti","maxAttack","maxRelease","crest","adapt","noClip"};
            int[] fetIds={0x10140,0x10141,0x10142,0x10143,0x10144,0x10145,0x10146,0x10147,
                    0x10148,0x10149,0x1014A,0x1014B,0x1014C,0x1014D,0x1014E,0x1014F,0x10150};
            Kind[] fetTypes={Kind.BOOL,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT,Kind.BOOL,Kind.FLOAT,Kind.BOOL,Kind.FLOAT,
                    Kind.BOOL,Kind.FLOAT,Kind.BOOL,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT,Kind.BOOL};
            scalarSet(changes,root,"fetCompressor",fetKeys,fetIds,fetTypes);

            scalarSet(changes,root,"bass",
                    new String[]{"enable","mode","frequency","gain","antiPop"},
                    new int[]{0x10160,0x10161,0x10162,0x10163,0x10164},
                    new Kind[]{Kind.BOOL,Kind.INT,Kind.INT,Kind.FLOAT,Kind.BOOL});
            scalarSet(changes,root,"bassMono",
                    new String[]{"enable","mode","frequency","gain","antiPop"},
                    new int[]{0x10170,0x10171,0x10172,0x10173,0x10174},
                    new Kind[]{Kind.BOOL,Kind.INT,Kind.INT,Kind.FLOAT,Kind.BOOL});
            scalarSet(changes,root,"psychoacousticBass",
                    new String[]{"enable","cutoff","intensity","harmonicOrder","originalLevel"},
                    new int[]{0x10180,0x10181,0x10182,0x10183,0x10184},
                    new Kind[]{Kind.BOOL,Kind.INT,Kind.FLOAT,Kind.INT,Kind.FLOAT});
            scalarSet(changes,root,"spectrumExtension",
                    new String[]{"enable","strength","exciter"},
                    new int[]{0x10190,0x10191,0x10192},
                    new Kind[]{Kind.BOOL,Kind.INT,Kind.FLOAT});

            scalar(changes,root,"equalizer","enable",0x101A0,Kind.BOOL);
            scalar(changes,root,"equalizer","bandCount",0x101A2,Kind.INT);
            list(changes,root,"equalizer","bands",0x101A3,1);
            put(changes,"equalizer_presetId",Kind.INT,-1);

            scalar(changes,root,"convolver","enable",0x101B0,Kind.BOOL);
            scalar(changes,root,"convolver","crossChannel",0x101B5,Kind.FLOAT);
            put(changes,"convolver_kernelFile",Kind.STRING,
                    root.getJSONObject("convolver").optString("kernelFile",""));

            scalar(changes,root,"ddc","enable",0x101C0,Kind.BOOL);
            put(changes,"ddc_device",Kind.STRING,root.getJSONObject("ddc").optString("device",""));

            scalarSet(changes,root,"fieldSurround",
                    new String[]{"enable","widening","midImage","depth"},
                    new int[]{0x101D0,0x101D1,0x101D2,0x101D3},
                    new Kind[]{Kind.BOOL,Kind.FLOAT,Kind.FLOAT,Kind.INT});
            scalarSet(changes,root,"diffSurround",
                    new String[]{"enable","delay","reverse","wetDryMix","lpCutoff"},
                    new int[]{0x101E0,0x101E1,0x101E2,0x101E3,0x101E4},
                    new Kind[]{Kind.BOOL,Kind.FLOAT,Kind.BOOL,Kind.FLOAT,Kind.INT});
            scalarSet(changes,root,"stereoImager",
                    new String[]{"enable","lowWidth","midWidth","highWidth","lowCrossover","highCrossover"},
                    new int[]{0x101F0,0x101F1,0x101F2,0x101F3,0x101F4,0x101F5},
                    new Kind[]{Kind.BOOL,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT,Kind.INT,Kind.INT});
            scalarSet(changes,root,"headphoneSurround",
                    new String[]{"enable","quality"},new int[]{0x10200,0x10201},new Kind[]{Kind.BOOL,Kind.INT});
            scalarSet(changes,root,"reverb",
                    new String[]{"enable","roomSize","width","damp","wet","dry"},
                    new int[]{0x10210,0x10211,0x10212,0x10213,0x10214,0x10215},
                    new Kind[]{Kind.BOOL,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT});
            scalarSet(changes,root,"dynamicSystem",
                    new String[]{"enable","xLow","xHigh","yLow","yHigh","sideGainLow","sideGainHigh","strength"},
                    new int[]{0x10220,0x10221,0x10222,0x10223,0x10224,0x10225,0x10226,0x10227},
                    new Kind[]{Kind.BOOL,Kind.INT,Kind.INT,Kind.INT,Kind.INT,Kind.FLOAT,Kind.FLOAT,Kind.FLOAT});
            put(changes,"dynamicSystem_presetId",Kind.INT,-1);
            scalarSet(changes,root,"clarity",
                    new String[]{"enable","mode","gain"},new int[]{0x10230,0x10231,0x10232},
                    new Kind[]{Kind.BOOL,Kind.INT,Kind.FLOAT});
            scalarSet(changes,root,"cure",
                    new String[]{"enable","crossfeedPreset"},new int[]{0x10240,0x10241},
                    new Kind[]{Kind.BOOL,Kind.INT});
            scalar(changes,root,"tubeSimulator","enable",0x10250,Kind.BOOL);
            scalarSet(changes,root,"analogX",
                    new String[]{"enable","mode"},new int[]{0x10260,0x10261},new Kind[]{Kind.BOOL,Kind.INT});
            scalar(changes,root,"speakerCorrection","enable",0x10270,Kind.BOOL);

            scalar(changes,root,"multibandCompressor","enable",0x10280,Kind.BOOL);
            list(changes,root,"multibandCompressor","crossovers",0x10282,0);
            list(changes,root,"multibandCompressor","thresholds",0x10283,4);
            list(changes,root,"multibandCompressor","ratios",0x10284,4);
            list(changes,root,"multibandCompressor","knees",0x10285,4);
            list(changes,root,"multibandCompressor","kneeAutos",0x10286,-1);
            list(changes,root,"multibandCompressor","gains",0x10287,4);
            list(changes,root,"multibandCompressor","gainAutos",0x10288,-1);
            list(changes,root,"multibandCompressor","attacks",0x10289,4);
            list(changes,root,"multibandCompressor","attackAutos",0x1028A,-1);
            list(changes,root,"multibandCompressor","releases",0x1028B,4);
            list(changes,root,"multibandCompressor","releaseAutos",0x1028C,-1);
            list(changes,root,"multibandCompressor","kneeMultis",0x1028D,4);
            list(changes,root,"multibandCompressor","maxAttacks",0x1028E,4);
            list(changes,root,"multibandCompressor","maxReleases",0x1028F,4);
            list(changes,root,"multibandCompressor","crests",0x10290,4);
            list(changes,root,"multibandCompressor","adapts",0x10291,4);
            list(changes,root,"multibandCompressor","noClips",0x10292,-1);
            list(changes,root,"multibandCompressor","bandEnables",0x10293,-1);

            scalar(changes,root,"dynamicEq","enable",0x102A0,Kind.BOOL);
            scalar(changes,root,"dynamicEq","bandCount",0x102A1,Kind.INT);
            list(changes,root,"dynamicEq","freqs",0x102A2,0);
            list(changes,root,"dynamicEq","qs",0x102A3,4);
            list(changes,root,"dynamicEq","gains",0x102A4,4);
            list(changes,root,"dynamicEq","thresholds",0x102A5,4);
            list(changes,root,"dynamicEq","attacks",0x102A6,4);
            list(changes,root,"dynamicEq","releases",0x102A7,4);
            list(changes,root,"dynamicEq","filterTypes",0x102A8,0);

            patch(file,changes);
        }

        private static void scalarSet(Map<String,Value> m,JSONObject root,String group,
                                      String[] keys,int[] ids,Kind[] kinds)throws Exception{
            for(int n=0;n<keys.length;n++)scalar(m,root,group,keys[n],ids[n],kinds[n]);
        }

        private static void scalar(Map<String,Value> m,JSONObject root,String group,String key,int id,Kind kind)throws Exception{
            JSONObject g=root.getJSONObject(group);
            Object v=switch(kind){
                case BOOL -> g.getBoolean(key);
                case INT -> g.getInt(key);
                case FLOAT -> (float)g.getDouble(key);
                case STRING -> g.getString(key);
            };
            put(m,String.valueOf(id),kind,v);
        }

        // decimals: 0=int, 1=double-list 1 decimal, 4=float-list 4 decimals, -1=bool list.
        private static void list(Map<String,Value> m,JSONObject root,String group,String key,int id,int decimals)throws Exception{
            JSONArray a=root.getJSONObject(group).getJSONArray(key);
            StringBuilder s=new StringBuilder();
            for(int n=0;n<a.length();n++){
                if(n>0)s.append(';');
                if(decimals==-1)s.append(a.getBoolean(n)?'1':'0');
                else if(decimals==0)s.append(a.getInt(n));
                else s.append(String.format(Locale.US,decimals==1?"%.1f":"%.4f",a.getDouble(n)));
            }
            put(m,String.valueOf(id),Kind.STRING,s.toString());
        }

        private static void put(Map<String,Value> m,String key,Kind kind,Object value){m.put(key,new Value(kind,value));}

        private static void patch(File file,Map<String,Value> changes)throws Exception{
            byte[] data=Files.readAllBytes(file.toPath());
            List<Field> top=parse(data);
            Map<String,Boolean> seen=new LinkedHashMap<>();
            for(String k:changes.keySet())seen.put(k,false);
            ByteArrayOutputStream out=new ByteArrayOutputStream();

            for(Field f:top){
                if(f.number==1 && f.wire==2){
                    byte[] entry=lenPayload(f.raw);
                    String key=mapKey(entry);
                    Value v=changes.get(key);
                    if(v!=null){
                        out.write(prefEntry(key,v));
                        seen.put(key,true);
                    }else out.write(f.raw);
                }else out.write(f.raw);
            }
            for(Map.Entry<String,Value> e:changes.entrySet()){
                if(!seen.get(e.getKey()))out.write(prefEntry(e.getKey(),e.getValue()));
            }
            byte[] patched=out.toByteArray();
            parse(patched); // structural validation
            Files.write(file.toPath(),patched);
        }

        private static String mapKey(byte[] entry)throws Exception{
            for(Field f:parse(entry)){
                if(f.number==1 && f.wire==2)return new String(lenPayload(f.raw),StandardCharsets.UTF_8);
            }
            return "";
        }

        private static byte[] prefEntry(String key,Value v)throws Exception{
            byte[] keyField=lenField(1,key.getBytes(StandardCharsets.UTF_8));
            byte[] valMsg;
            switch(v.kind){
                case BOOL -> valMsg=varintField(1,(Boolean)v.value?1:0);
                case FLOAT -> valMsg=fixed32Field(2,Float.floatToRawIntBits(((Number)v.value).floatValue()));
                case INT -> valMsg=varintField(3,((Number)v.value).intValue());
                case STRING -> valMsg=lenField(5,String.valueOf(v.value).getBytes(StandardCharsets.UTF_8));
                default -> throw new IllegalStateException();
            }
            return lenField(1,concat(keyField,lenField(2,valMsg)));
        }

        private static List<Field> parse(byte[] data)throws Exception{
            List<Field> out=new ArrayList<>(); int[] p={0};
            while(p[0]<data.length){
                int start=p[0]; long tag=readVarint(data,p); int num=(int)(tag>>>3),wire=(int)(tag&7);
                switch(wire){
                    case 0 -> readVarint(data,p);
                    case 1 -> p[0]+=8;
                    case 2 -> {int len=(int)readVarint(data,p);p[0]+=len;}
                    case 5 -> p[0]+=4;
                    default -> throw new IllegalArgumentException("Unsupported protobuf wire type "+wire);
                }
                if(p[0]>data.length)throw new IllegalArgumentException("Truncated protobuf");
                out.add(new Field(num,wire,Arrays.copyOfRange(data,start,p[0])));
            }
            return out;
        }

        private static byte[] lenPayload(byte[] raw)throws Exception{
            int[] p={0};readVarint(raw,p);int len=(int)readVarint(raw,p);
            return Arrays.copyOfRange(raw,p[0],p[0]+len);
        }

        private static long readVarint(byte[] b,int[] pos)throws Exception{
            long value=0;int shift=0;
            while(true){
                if(pos[0]>=b.length)throw new IllegalArgumentException("Truncated varint");
                int x=b[pos[0]++]&0xff;value|=(long)(x&0x7f)<<shift;
                if((x&0x80)==0)return value;
                shift+=7;if(shift>63)throw new IllegalArgumentException("Bad varint");
            }
        }

        private static byte[] varint(long v){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            do{int b=(int)(v&0x7f);v>>>=7;if(v!=0)b|=0x80;out.write(b);}while(v!=0);
            return out.toByteArray();
        }

        private static byte[] lenField(int n,byte[] payload)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            out.write(varint(((long)n<<3)|2));out.write(varint(payload.length));out.write(payload);return out.toByteArray();
        }

        private static byte[] varintField(int n,long value)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            out.write(varint((long)n<<3));out.write(varint(value));return out.toByteArray();
        }

        private static byte[] fixed32Field(int n,int bits)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            out.write(varint(((long)n<<3)|5));
            ByteBuffer b=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits);
            out.write(b.array());return out.toByteArray();
        }

        private static byte[] concat(byte[] a,byte[] b){
            byte[] out=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,out,a.length,b.length);return out;
        }
    }
}
