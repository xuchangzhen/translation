package com.linguabridge.memory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class WordbookImporterTest {
    @Test public void csvAliasesAndBom() {
        ImportPreview p = WordbookImporter.parse("\uFEFFword,translation,phonetic\r\nabandon,放弃,/ipa/\r\n", "CET4.csv");
        assertEquals("CET4", p.name); assertEquals(1, p.items.size());
        assertEquals("abandon", p.items.get(0).front); assertEquals("放弃", p.items.get(0).back); assertEquals("/ipa/", p.items.get(0).phonetic);
    }
    @Test public void tabSeparatedAndComments() {
        for (String name : new String[]{"a.tsv", "a.txt"}) {
            ImportPreview p = WordbookImporter.parse("# 备注\n \n cache\t 高速缓存 \n", name);
            assertEquals(1, p.detected); assertEquals("cache", p.items.get(0).front); assertEquals("高速缓存", p.items.get(0).back);
        }
    }
    @Test public void quotedCommasQuotesAndNewlines() {
        ImportPreview p = WordbookImporter.parse("\"cache\",\"高速缓存,临时存储\"\r\n\"quote\",\"包含\"\"引号\"\"\n和换行\"", "a.csv");
        assertEquals(2, p.items.size()); assertEquals("高速缓存,临时存储", p.items.get(0).back);
        assertEquals("包含\"引号\"\n和换行", p.items.get(1).back);
    }
    @Test public void deduplicatesAndCountsInvalid() {
        ImportPreview p = WordbookImporter.parse("front,back\nCache,旧\n cache ,新\n,无效\nbroken", "a.csv");
        assertEquals(4, p.detected); assertEquals(1, p.duplicates); assertEquals(2, p.invalid);
        assertEquals(1, p.items.size()); assertEquals("新", p.items.get(0).back);
    }
    @Test public void jsonObjectAndArray() {
        String items = "[{\"term\":\"cache\",\"meaning\":\"高速缓存\",\"definition\":\"临时存储\",\"tag\":\"计算机\",\"example\":\"a cache\",\"ipa\":\"/kæʃ/\"},null]";
        for (String source : new String[]{items, "{\"name\":\"计算机\",\"items\":" + items + "}"}) {
            ImportPreview p = WordbookImporter.parse(source, "sample.json");
            assertEquals(1, p.invalid); assertEquals("临时存储", p.items.get(0).definition);
            assertEquals("计算机", p.items.get(0).category); assertEquals("a cache", p.items.get(0).context);
        }
        assertEquals("计算机", WordbookImporter.parse("{\"name\":\"计算机\",\"items\":" + items + "}", "sample.json").name);
    }
    @Test public void errorsAreReadable() {
        for (String source : new String[]{"word,wrong\na,b", "\"unclosed,a"}) {
            try { WordbookImporter.parse(source, "a.csv"); fail(); }
            catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("CSV")); }
        }
        try { WordbookImporter.parse("{broken", "a.json"); fail(); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("JSON 格式错误")); }
    }

    @Test public void readAutoDetectsUtf16AndExplicitGbk() throws Exception {
        byte[] utf16 = ("word,translation\ncache,缓存").getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[utf16.length + 2]; withBom[0] = (byte) 0xFF; withBom[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, withBom, 2, utf16.length);
        ImportPreview detected = WordbookImporter.read(new ByteArrayInputStream(withBom), "a.csv");
        assertEquals("UTF-16LE", detected.encoding); assertEquals("缓存", detected.items.get(0).back);
        byte[] gbk = "word,translation\ncache,缓存".getBytes(Charset.forName("GB18030"));
        ImportPreview explicit = WordbookImporter.read(new ByteArrayInputStream(gbk), "a.csv", "GBK");
        assertEquals("GB18030", explicit.encoding); assertEquals("缓存", explicit.items.get(0).back);
    }
}
