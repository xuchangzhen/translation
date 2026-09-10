package com.linguabridge.memory;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class PhoneticDictionaryTest {
    private Context context;
    private File fixture;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        fixture = context.getDatabasePath("phonetics-fixture.db");
        context.deleteDatabase(fixture.getName());
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(fixture, null)) {
            database.execSQL("CREATE TABLE phonetics (word TEXT PRIMARY KEY COLLATE NOCASE, phonetic TEXT NOT NULL)");
            database.execSQL("INSERT INTO phonetics(word, phonetic) VALUES ('cache', 'kæʃ')");
            database.execSQL("INSERT INTO phonetics(word, phonetic) VALUES ('interrupt', 'ˌɪntəˈrʌpt')");
        }
    }

    @After public void cleanup() {
        context.deleteDatabase(fixture.getName());
    }

    @Test public void findsNormalizedWordsFromRealSqliteFixture() {
        try (PhoneticDictionary dictionary = new PhoneticDictionary(context, fixture)) {
            assertEquals("kæʃ", dictionary.find("cache"));
            assertEquals("kæʃ", dictionary.find("  CACHE  "));
            assertEquals("", dictionary.find("not-exist"));
        }
    }
}
