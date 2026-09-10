package com.linguabridge.memory;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Read-only access to the versioned phonetic dictionary bundled as an asset.
 *
 * SQLite needs a real file, so the asset is installed once in app-private storage.
 * The version is part of the file name, which allows a later dictionary update to
 * coexist with and supersede an older installed copy.
 */
public final class PhoneticDictionary implements AutoCloseable, WordbookPhoneticEnricher.Lookup {
    private static final String TAG = "PhoneticDictionary";
    static final String ASSET_NAME = "phonetics-v1.db";

    private final Context context;
    private final File suppliedDatabase;
    private SQLiteDatabase database;
    private boolean attemptedOpen;
    private boolean unavailable;

    public PhoneticDictionary(Context context) {
        this(context.getApplicationContext(), null);
    }

    /** Visible to unit tests so they can query a small real SQLite fixture. */
    PhoneticDictionary(Context context, File suppliedDatabase) {
        this.context = context.getApplicationContext();
        this.suppliedDatabase = suppliedDatabase;
    }

    /** Returns an empty string for absent entries or when the optional dictionary is unavailable. */
    @Override
    public synchronized String find(String word) {
        String normalized = WordbookImporter.normalizeFront(word == null ? "" : word);
        if (normalized.isEmpty()) return "";
        SQLiteDatabase opened = open();
        if (opened == null) return "";
        try (Cursor cursor = opened.rawQuery(
                "SELECT phonetic FROM phonetics WHERE word = ? COLLATE NOCASE LIMIT 1",
                new String[]{normalized})) {
            return cursor.moveToFirst() ? cursor.getString(0).trim() : "";
        } catch (RuntimeException exception) {
            unavailable = true;
            Log.w(TAG, "Local phonetic dictionary query failed; continuing without enrichment.", exception);
            closeDatabase();
            return "";
        }
    }

    @Override
    public synchronized boolean isUnavailable() {
        return unavailable;
    }

    private SQLiteDatabase open() {
        if (database != null) return database;
        if (attemptedOpen) return null;
        attemptedOpen = true;
        try {
            File file = suppliedDatabase == null ? installAsset() : suppliedDatabase;
            if (file == null || !file.isFile() || file.length() == 0) throw new IOException("Dictionary file is missing.");
            database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            return database;
        } catch (Exception exception) {
            unavailable = true;
            Log.w(TAG, "Local phonetic dictionary is unavailable; importing without enrichment.", exception);
            return null;
        }
    }

    private File installAsset() throws IOException {
        File directory = context.getNoBackupFilesDir();
        if (directory == null) directory = context.getFilesDir();
        if (directory == null) throw new IOException("Unable to access application storage.");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create application storage.");
        File target = new File(directory, ASSET_NAME);
        if (target.isFile() && target.length() > 0) return target;
        if (target.exists() && !target.delete()) throw new IOException("Unable to replace incomplete dictionary copy.");

        File temporary = new File(directory, ASSET_NAME + ".tmp");
        if (temporary.exists() && !temporary.delete()) throw new IOException("Unable to replace incomplete dictionary copy.");
        boolean moved = false;
        try (InputStream input = context.getAssets().open(ASSET_NAME);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
            if (!temporary.renameTo(target)) throw new IOException("Unable to install local phonetic dictionary.");
            moved = true;
            return target;
        } finally {
            if (!moved && temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Unable to remove incomplete local phonetic dictionary copy.");
            }
        }
    }

    private void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    @Override
    public synchronized void close() {
        closeDatabase();
    }
}
