package io.github.zhanfg.sda;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Stores the DownloadProvider history mirrored by the LSPosed hook. */
public final class HistoryProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.zhanfg.sda.history";
    public static final Uri RECORDS_URI = Uri.parse("content://" + AUTHORITY + "/records");

    private static final int RECORDS = 1;
    private static final int RECORD = 2;
    private static final String DOWNLOADS_PACKAGE = "com.android.providers.downloads";
    private static final String TABLE = "records";

    private static final Set<String> ALLOWED_COLUMNS = new HashSet<>(Arrays.asList(
            "download_id", "title", "source_url", "source_package", "mime_type",
            "status", "total_bytes", "current_bytes", "last_modified", "local_uri",
            "local_path", "destination_hint", "error_text"
    ));

    private final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
    private Database database;

    public HistoryProvider() {
        matcher.addURI(AUTHORITY, "records", RECORDS);
        matcher.addURI(AUTHORITY, "records/#", RECORD);
    }

    @Override
    public boolean onCreate() {
        database = new Database(requireContext());
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return matcher.match(uri) == RECORD
                ? "vnd.android.cursor.item/vnd.sda.download"
                : "vnd.android.cursor.dir/vnd.sda.download";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        enforceCaller(false);
        SQLiteDatabase db = database.getReadableDatabase();
        if (matcher.match(uri) == RECORD) {
            selection = appendSelection(selection, "download_id=?");
            selectionArgs = appendArg(selectionArgs, uri.getLastPathSegment());
        }
        Cursor cursor = db.query(TABLE, projection, selection, selectionArgs,
                null, null, sortOrder == null ? "last_modified DESC, download_id DESC" : sortOrder);
        if (getContext() != null) {
            cursor.setNotificationUri(getContext().getContentResolver(), RECORDS_URI);
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        enforceCaller(true);
        if (matcher.match(uri) != RECORDS) {
            throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
        ContentValues safe = sanitize(values);
        Long id = safe.getAsLong("download_id");
        if (id == null) {
            throw new IllegalArgumentException("download_id is required");
        }

        SQLiteDatabase db = database.getWritableDatabase();
        int updated = db.update(TABLE, safe, "download_id=?",
                new String[]{String.valueOf(id)});
        if (updated == 0) {
            db.insertOrThrow(TABLE, null, safe);
        }
        notifyChanged(id);
        return ContentUris.withAppendedId(RECORDS_URI, id);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        enforceCaller(true);
        ContentValues safe = sanitize(values);
        if (matcher.match(uri) == RECORD) {
            selection = appendSelection(selection, "download_id=?");
            selectionArgs = appendArg(selectionArgs, uri.getLastPathSegment());
        }
        int count = database.getWritableDatabase().update(TABLE, safe, selection, selectionArgs);
        if (count > 0) {
            notifyChanged(-1);
        }
        return count;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceCaller(true);
        if (matcher.match(uri) == RECORD) {
            selection = appendSelection(selection, "download_id=?");
            selectionArgs = appendArg(selectionArgs, uri.getLastPathSegment());
        }
        int count = database.getWritableDatabase().delete(TABLE, selection, selectionArgs);
        if (count > 0) {
            notifyChanged(-1);
        }
        return count;
    }

    private ContentValues sanitize(ContentValues input) {
        ContentValues output = new ContentValues();
        if (input == null) {
            return output;
        }
        for (String key : input.keySet()) {
            if (!ALLOWED_COLUMNS.contains(key)) {
                continue;
            }
            Object value = input.get(key);
            if (value == null) {
                output.putNull(key);
            } else if (value instanceof String) {
                output.put(key, (String) value);
            } else if (value instanceof Integer) {
                output.put(key, (Integer) value);
            } else if (value instanceof Long) {
                output.put(key, (Long) value);
            } else if (value instanceof Boolean) {
                output.put(key, (Boolean) value);
            } else if (value instanceof byte[]) {
                output.put(key, (byte[]) value);
            } else {
                output.put(key, String.valueOf(value));
            }
        }
        return output;
    }

    private void enforceCaller(boolean write) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) {
            return;
        }
        if (uid == Process.SYSTEM_UID) {
            return;
        }
        if (getContext() != null) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String name : packages) {
                    if (DOWNLOADS_PACKAGE.equals(name)) {
                        return;
                    }
                }
            }
        }
        throw new SecurityException((write ? "Write" : "Read") + " denied for uid " + uid);
    }

    private void notifyChanged(long id) {
        if (getContext() == null) {
            return;
        }
        getContext().getContentResolver().notifyChange(RECORDS_URI, null);
        if (id >= 0) {
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(RECORDS_URI, id), null);
        }
    }

    private android.content.Context requireContext() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context unavailable");
        }
        return getContext();
    }

    private static String appendSelection(String selection, String clause) {
        return selection == null || selection.isEmpty()
                ? clause
                : "(" + selection + ") AND (" + clause + ")";
    }

    private static String[] appendArg(String[] args, String arg) {
        if (args == null) {
            return new String[]{arg};
        }
        String[] result = Arrays.copyOf(args, args.length + 1);
        result[args.length] = arg;
        return result;
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(android.content.Context context) {
            super(context, "download_history.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + "download_id INTEGER PRIMARY KEY,"
                    + "title TEXT,"
                    + "source_url TEXT,"
                    + "source_package TEXT,"
                    + "mime_type TEXT,"
                    + "status INTEGER NOT NULL DEFAULT 190,"
                    + "total_bytes INTEGER NOT NULL DEFAULT -1,"
                    + "current_bytes INTEGER NOT NULL DEFAULT 0,"
                    + "last_modified INTEGER NOT NULL DEFAULT 0,"
                    + "local_uri TEXT,"
                    + "local_path TEXT,"
                    + "destination_hint TEXT,"
                    + "error_text TEXT"
                    + ")");
            db.execSQL("CREATE INDEX idx_history_lastmod ON " + TABLE
                    + "(last_modified DESC)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }
}
