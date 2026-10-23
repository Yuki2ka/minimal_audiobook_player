package io.github.yuki2ka.audiobookplayer.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;

/**
 * Tiny SQLite store with two tables:
 *
 * <ul>
 *   <li>{@code positions}: one row per file with a saved playback position, keyed
 *       by the content fingerprint. Rows are deleted as soon as a file is
 *       finished, so the table stays small.</li>
 *   <li>{@code folder_state}: the last played file and scroll offset for each
 *       folder, used to reopen a folder at the right place.</li>
 * </ul>
 *
 * The queue order itself is not stored: the unified list is rebuilt from the
 * natural sort order and rotated to the last played file, so a single string
 * (in SharedPreferences) is enough state.
 */
public final class Db extends SQLiteOpenHelper {

    private static final String NAME = "library.db";
    private static final int VERSION = 1;

    private static final String T_POSITIONS = "positions";
    private static final String T_FOLDER = "folder_state";

    /** A saved position. */
    public static final class Position {
        public final String path;
        public final long positionMs;
        public final long durationMs;

        Position(String path, long positionMs, long durationMs) {
            this.path = path;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    /** Last played file and scroll offset of a folder. */
    public static final class FolderState {
        public final String lastPath;
        public final int scroll;

        FolderState(String lastPath, int scroll) {
            this.lastPath = lastPath;
            this.scroll = scroll;
        }
    }

    public Db(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_POSITIONS + " ("
                + "hash TEXT PRIMARY KEY NOT NULL,"
                + "path TEXT NOT NULL,"
                + "position INTEGER NOT NULL,"
                + "duration INTEGER NOT NULL,"
                + "updated INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX idx_positions_path ON " + T_POSITIONS + "(path)");
        db.execSQL("CREATE TABLE " + T_FOLDER + " ("
                + "folder TEXT PRIMARY KEY NOT NULL,"
                + "last_path TEXT NOT NULL,"
                + "scroll INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first release. Future migrations go here.
    }

    public Position findPosition(String hash) {
        if (hash == null) {
            return null;
        }
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT path, position, duration FROM " + T_POSITIONS + " WHERE hash=?",
                new String[]{hash});
        try {
            if (cursor.moveToFirst()) {
                return new Position(cursor.getString(0), cursor.getLong(1), cursor.getLong(2));
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    public Position findPositionByPath(String path) {
        if (path == null) {
            return null;
        }
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT path, position, duration FROM " + T_POSITIONS + " WHERE path=?",
                new String[]{path});
        try {
            if (cursor.moveToFirst()) {
                return new Position(cursor.getString(0), cursor.getLong(1), cursor.getLong(2));
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    /** Inserts or replaces the row for a file (the fingerprint is the key). */
    public void savePosition(String hash, String path, long positionMs, long durationMs) {
        if (hash == null || path == null || positionMs <= 0) {
            return;
        }
        ContentValues values = new ContentValues(5);
        values.put("hash", hash);
        values.put("path", path);
        values.put("position", positionMs);
        values.put("duration", durationMs);
        values.put("updated", System.currentTimeMillis());
        // OR REPLACE also removes a stale row that still uses the same path
        // (the file was replaced by a different recording).
        getWritableDatabase().insertWithOnConflict(
                T_POSITIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deletePosition(String hash, String path) {
        SQLiteDatabase db = getWritableDatabase();
        if (hash != null) {
            db.delete(T_POSITIONS, "hash=?", new String[]{hash});
        }
        if (path != null) {
            db.delete(T_POSITIONS, "path=?", new String[]{path});
        }
    }

    public void clearPositions() {
        getWritableDatabase().delete(T_POSITIONS, null, null);
    }

    /** All positions keyed by path, for rendering "Resume 1:23:45" in lists. */
    public HashMap<String, Long> loadPositions() {
        HashMap<String, Long> result = new HashMap<>();
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT path, position FROM " + T_POSITIONS + " WHERE position > 0", null);
        try {
            while (cursor.moveToNext()) {
                result.put(cursor.getString(0), cursor.getLong(1));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    public void saveFolderState(String folder, String lastPath, int scroll) {
        if (folder == null || lastPath == null) {
            return;
        }
        ContentValues values = new ContentValues(3);
        values.put("folder", folder);
        values.put("last_path", lastPath);
        values.put("scroll", Math.max(0, scroll));
        getWritableDatabase().insertWithOnConflict(
                T_FOLDER, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public FolderState loadFolderState(String folder) {
        if (folder == null) {
            return null;
        }
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT last_path, scroll FROM " + T_FOLDER + " WHERE folder=?",
                new String[]{folder});
        try {
            if (cursor.moveToFirst()) {
                return new FolderState(cursor.getString(0), cursor.getInt(1));
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    public void deleteFolderState(String folder) {
        getWritableDatabase().delete(T_FOLDER, "folder=?", new String[]{folder});
    }
}
