package dev.axymorrsen.systemdownloadaccelerator;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Small exported IPC sink used only for module diagnostics.
 *
 * Writes are accepted only from this app or the three configured system scopes.
 * Data is stored in the module app's private SharedPreferences as a bounded ring.
 */
public final class TelemetryProvider extends ContentProvider {
    static final String AUTHORITY =
            "dev.axymorrsen.systemdownloadaccelerator.telemetry";
    static final Uri EVENTS_URI =
            Uri.parse("content://" + AUTHORITY + "/events");

    private static final String PREFS = "engine_telemetry";
    private static final int CAPACITY = 96;

    private static final Set<String> ALLOWED_PACKAGES =
            new HashSet<>(Arrays.asList(
                    "dev.axymorrsen.systemdownloadaccelerator",
                    "com.android.providers.downloads",
                    "com.android.providers.downloads.ui",
                    "com.android.systemui"));

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Context context = getContext();
        if (context == null
                || values == null
                || !EVENTS_URI.getPath().equals(uri.getPath())
                || !callerAllowed(context)) {
            return null;
        }

        String phase = clean(values.getAsString("phase"), 96);
        String detail = clean(values.getAsString("detail"), 1800);
        Long whenValue = values.getAsLong("when");
        long when = whenValue == null
                ? android.os.SystemClock.elapsedRealtime()
                : whenValue;
        int uid = Binder.getCallingUid();

        synchronized (TelemetryProvider.class) {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long seq = prefs.getLong("seq", 0L) + 1L;
            int slot = (int) (seq % CAPACITY);

            prefs.edit()
                    .putLong("seq", seq)
                    .putString(
                            "event." + slot,
                            seq + "\t"
                                    + when + "\t"
                                    + uid + "\t"
                                    + phase + "\t"
                                    + detail)
                    .apply();
        }

        context.getContentResolver().notifyChange(EVENTS_URI, null);
        return Uri.withAppendedPath(EVENTS_URI, "latest");
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        Context context = getContext();
        MatrixCursor cursor =
                new MatrixCursor(new String[]{
                        "seq", "when", "uid", "phase", "detail"
                });

        if (context == null
                || Binder.getCallingUid() != context.getApplicationInfo().uid
                || !EVENTS_URI.getPath().equals(uri.getPath())) {
            return cursor;
        }

        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long maxSeq = prefs.getLong("seq", 0L);
        long minSeq = Math.max(1L, maxSeq - CAPACITY + 1L);

        for (long seq = minSeq; seq <= maxSeq; seq++) {
            int slot = (int) (seq % CAPACITY);
            String raw = prefs.getString("event." + slot, null);
            if (raw == null) continue;

            String[] parts = raw.split("\\t", 5);
            if (parts.length != 5) continue;

            try {
                long storedSeq = Long.parseLong(parts[0]);
                if (storedSeq != seq) continue;
                cursor.addRow(new Object[]{
                        storedSeq,
                        Long.parseLong(parts[1]),
                        Integer.parseInt(parts[2]),
                        parts[3],
                        parts[4]
                });
            } catch (NumberFormatException ignored) {
            }
        }
        return cursor;
    }

    static List<String> readEvents(Context context) {
        ArrayList<String> events = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                EVENTS_URI, null, null, null, null)) {
            if (cursor == null) return events;

            int uid = cursor.getColumnIndexOrThrow("uid");
            int phase = cursor.getColumnIndexOrThrow("phase");
            int detail = cursor.getColumnIndexOrThrow("detail");
            while (cursor.moveToNext()) {
                events.add(
                        cursor.getString(phase)
                                + " · "
                                + cursor.getString(detail)
                                + " · uid="
                                + cursor.getInt(uid));
            }
        } catch (Throwable ignored) {
        }
        return events;
    }

    static void clearEvents(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private static boolean callerAllowed(Context context) {
        int uid = Binder.getCallingUid();
        if (uid == context.getApplicationInfo().uid) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) return false;

        for (String pkg : packages) {
            if (ALLOWED_PACKAGES.contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String out = value
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ');
        return out.length() <= max ? out : out.substring(0, max);
    }

    @Override
    public int delete(
            Uri uri,
            String selection,
            String[] selectionArgs) {
        Context context = getContext();
        if (context == null
                || Binder.getCallingUid() != context.getApplicationInfo().uid) {
            return 0;
        }
        clearEvents(context);
        return 1;
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.sysdl.telemetry";
    }
}
