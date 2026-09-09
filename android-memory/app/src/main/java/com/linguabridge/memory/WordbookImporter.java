package com.linguabridge.memory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parsing and validation only: never writes a database or uploads content. */
public final class WordbookImporter {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ITEMS = 100000;
    private static final String[] FRONT = {"front", "word", "term", "english"};
    private static final String[] BACK = {"back", "translation", "meaning", "chinese"};
    public static final class Item {
        public final String front, back, phonetic, definition, category, context;
        Item(String front, String back, String phonetic, String definition, String category, String context) {
            this.front = front; this.back = back; this.phonetic = phonetic;
            this.definition = definition; this.category = category; this.context = context;
        }
    }
    public static String normalizeFront(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    public static ImportPreview read(InputStream stream, String fileName) throws Exception {
        return read(stream, fileName, "auto");
    }
    public static ImportPreview read(InputStream stream, String fileName, String requestedEncoding) throws Exception {
        if (stream == null) throw new IllegalArgumentException("无法读取所选文件，请重新选择。");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_BYTES) throw new IllegalArgumentException("文件过大，请拆分成小于 64 MB 的文件。");
            bytes.write(buffer, 0, count);
        }
        byte[] raw = bytes.toByteArray();
        String encoding = requestedEncoding == null ? "AUTO" : requestedEncoding.trim().toUpperCase(Locale.ROOT);
        if ("AUTO".equals(encoding)) {
            if (raw.length >= 3 && (raw[0] & 255) == 0xEF && (raw[1] & 255) == 0xBB && (raw[2] & 255) == 0xBF) encoding = "UTF-8";
            else if (raw.length >= 2 && (raw[0] & 255) == 0xFF && (raw[1] & 255) == 0xFE) encoding = "UTF-16LE";
            else if (raw.length >= 2 && (raw[0] & 255) == 0xFE && (raw[1] & 255) == 0xFF) encoding = "UTF-16BE";
            else encoding = "UTF-8";
        }
        if ("GBK".equals(encoding)) encoding = "GB18030";
        if (!(encoding.equals("UTF-8") || encoding.equals("UTF-16LE") || encoding.equals("UTF-16BE") || encoding.equals("GB18030"))) {
            throw new IllegalArgumentException("不支持的文件编码。请选择 UTF-8、UTF-16 或 GB18030。");
        }
        String text;
        try {
            Charset charset = Charset.forName(encoding);
            text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString().replaceFirst("^\\uFEFF", "");
        } catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("文件编码无法识别，请另存为 UTF-8；也可以选择 GB18030/GBK 或 UTF-16。"); }
        return parse(text, fileName, encoding);
    }
    public static ImportPreview parse(String source, String fileName) {
        return parse(source, fileName, "UTF-8");
    }
    private static ImportPreview parse(String source, String fileName, String encoding) {
        String text = source.replaceFirst("^\\uFEFF", "");
        String name = fileName.replaceFirst("\\.[^.]+$", "");
        List<Map<String, String>> records = new ArrayList<>();
        int malformed = 0;
        String extension = fileName.toLowerCase(Locale.ROOT);
        if (extension.endsWith(".json") || text.trim().startsWith("{") || text.trim().startsWith("[")) {
            try {
                JSONTokener tokener = new JSONTokener(text);
                Object root = tokener.nextValue();
                if (tokener.nextClean() != 0) throw new IllegalArgumentException();
                JSONArray items;
                if (root instanceof JSONObject) {
                    JSONObject object = (JSONObject) root;
                    String jsonName = object.optString("name", "").trim();
                    if (!jsonName.isEmpty()) name = jsonName;
                    items = object.getJSONArray("items");
                } else if (root instanceof JSONArray) items = (JSONArray) root;
                else throw new IllegalArgumentException();
                if (items.length() > MAX_ITEMS) throw new IllegalArgumentException("词条超过 100000 条，请拆分文件。");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject object = items.optJSONObject(i);
                    if (object == null) { malformed++; continue; }
                    Map<String, String> record = new LinkedHashMap<>();
                    java.util.Iterator<String> keys = object.keys();
                    while (keys.hasNext()) {
                        String key = keys.next(); Object value = object.opt(key);
                        if (value instanceof String) record.put(key.trim().toLowerCase(Locale.ROOT), ((String) value).trim());
                    }
                    records.add(record);
                }
            } catch (Exception e) { throw new IllegalArgumentException("JSON 格式错误：请提供词条数组，或包含 name 和 items 的对象（最多 100000 条）。"); }
        } else {
            char delimiter = extension.endsWith(".csv") ? ',' : '\t';
            List<List<String>> rows = CsvWordbookParser.parse(text, delimiter);
            if (rows.size() > MAX_ITEMS + 1) throw new IllegalArgumentException("词条超过 100000 条，请拆分文件。");
            if (rows.isEmpty()) throw new IllegalArgumentException("文件中没有词条。");
            List<String> first = rows.get(0);
            List<String> header = new ArrayList<>();
            for (String value : first) header.add(value.toLowerCase(Locale.ROOT));
            boolean hasFront = contains(header, FRONT), hasBack = contains(header, BACK);
            if (hasFront != hasBack) throw new IllegalArgumentException("没有找到单词列或释义列。CSV 至少需要 word/front/term 和 translation/back/meaning 两列。");
            boolean headed = hasFront && hasBack;
            if (!headed) header = java.util.Arrays.asList("front", "back", "phonetic", "definition", "category", "context");
            for (int i = headed ? 1 : 0; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.size() < 2 || row.size() > header.size()) { malformed++; continue; }
                Map<String, String> record = new LinkedHashMap<>();
                for (int j = 0; j < row.size(); j++) record.put(header.get(j), row.get(j));
                records.add(record);
            }
        }
        if (records.size() + malformed > MAX_ITEMS) throw new IllegalArgumentException("词条超过 100000 条，请拆分文件。");
        Map<String, Item> unique = new LinkedHashMap<>();
        int invalid = malformed, duplicates = 0;
        for (Map<String, String> record : records) {
            Item item = new Item(value(record, FRONT), value(record, BACK), value(record, "phonetic", "ipa"),
                    value(record, "definition"), value(record, "category", "tag"), value(record, "context", "example"));
            if (item.front.isEmpty() || item.back.isEmpty() || item.front.length() > 1000 || item.back.length() > 2000 ||
                    item.phonetic.length() > 300 || item.definition.length() > 1000 || item.category.length() > 300 || item.context.length() > 2000) { invalid++; continue; }
            if (unique.put(normalizeFront(item.front), item) != null) duplicates++;
        }
        return new ImportPreview(fileName, name, new ArrayList<>(unique.values()), records.size() + malformed, duplicates, invalid, encoding);
    }
    private static boolean contains(List<String> values, String[] aliases) {
        for (String alias : aliases) if (values.contains(alias)) return true;
        return false;
    }
    private static String value(Map<String, String> record, String... aliases) {
        for (String alias : aliases) if (record.containsKey(alias) && !record.get(alias).trim().isEmpty()) return record.get(alias).trim();
        return "";
    }
}
