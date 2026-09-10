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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class WordbookPhoneticEnricherTest {
    private Context context;
    private File fixture;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        fixture = context.getDatabasePath("phonetics-enricher-fixture.db");
        context.deleteDatabase(fixture.getName());
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(fixture, null)) {
            database.execSQL("CREATE TABLE phonetics (word TEXT PRIMARY KEY COLLATE NOCASE, phonetic TEXT NOT NULL)");
            database.execSQL("INSERT INTO phonetics(word, phonetic) VALUES ('cache', 'kæʃ')");
            database.execSQL("INSERT INTO phonetics(word, phonetic) VALUES ('interrupt', 'ˌɪntəˈrʌpt')");
            database.execSQL("INSERT INTO phonetics(word, phonetic) VALUES ('register', 'ˈredʒɪstə')");
        }
    }

    @After public void cleanup() {
        context.deleteDatabase(fixture.getName());
    }

    private PhoneticEnrichmentResult enrich(String source) {
        ImportPreview preview = WordbookImporter.parse(source, "network.csv");
        try (PhoneticDictionary dictionary = new PhoneticDictionary(context, fixture)) {
            return new WordbookPhoneticEnricher(dictionary).enrich(preview);
        }
    }

    @Test public void keepsImportedPhoneticInsteadOfOverwritingIt() {
        PhoneticEnrichmentResult result = enrich("word,translation,phonetic\ncache,缓存,/CUSTOM/\n");
        assertEquals("/CUSTOM/", result.preview.items.get(0).phonetic);
        assertEquals(1, result.existing);
        assertEquals(0, result.filled);
        assertEquals(0, result.missing);
    }

    @Test public void fillsOnlyMissingPhoneticsWithNormalizedExactMatch() {
        PhoneticEnrichmentResult result = enrich("word,translation,phonetic,definition,category,context\n  Cache  ,缓存,,临时存储,计算机,a cache\n");
        WordbookImporter.Item item = result.preview.items.get(0);
        assertEquals("Cache", item.front);
        assertEquals("kæʃ", item.phonetic);
        assertEquals("缓存", item.back);
        assertEquals("临时存储", item.definition);
        assertEquals("计算机", item.category);
        assertEquals("a cache", item.context);
        assertEquals(0, result.existing);
        assertEquals(1, result.filled);
        assertEquals(0, result.missing);
    }

    @Test public void leavesUnknownTermsEmptyAndCountsEveryOutcome() {
        PhoneticEnrichmentResult result = enrich(
                "word,translation,phonetic\n" +
                "register,寄存器,/CUSTOM/\n" +
                "Cache,高速缓存,\n" +
                "interrupt,中断,\n" +
                "some-unknown-special-term,未知术语,\n");
        assertEquals("/CUSTOM/", result.preview.items.get(0).phonetic);
        assertEquals("kæʃ", result.preview.items.get(1).phonetic);
        assertEquals("ˌɪntəˈrʌpt", result.preview.items.get(2).phonetic);
        assertEquals("", result.preview.items.get(3).phonetic);
        assertEquals(1, result.existing);
        assertEquals(2, result.filled);
        assertEquals(1, result.missing);
        assertFalse(result.dictionaryUnavailable);
    }

    @Test public void dictionaryFailureLeavesThePreviewImportable() {
        ImportPreview preview = WordbookImporter.parse("word,translation\nunknown-term,未知术语\n", "a.csv");
        PhoneticEnrichmentResult result = new WordbookPhoneticEnricher(new WordbookPhoneticEnricher.Lookup() {
            @Override public String find(String word) { return ""; }
            @Override public boolean isUnavailable() { return true; }
        }).enrich(preview);
        assertEquals("", result.preview.items.get(0).phonetic);
        assertEquals("未知术语", result.preview.items.get(0).back);
        assertEquals(1, result.missing);
        assertTrue(result.dictionaryUnavailable);
    }
}
