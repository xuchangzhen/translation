package com.linguabridge.memory;

import java.util.Collections;
import java.util.List;

public final class ImportPreview {
    public final String fileName, name;
    public final String encoding;
    public final List<WordbookImporter.Item> items;
    public final int detected, duplicates, invalid;
    ImportPreview(String fileName, String name, List<WordbookImporter.Item> items, int detected, int duplicates, int invalid) {
        this(fileName, name, items, detected, duplicates, invalid, "UTF-8");
    }
    ImportPreview(String fileName, String name, List<WordbookImporter.Item> items, int detected, int duplicates, int invalid, String encoding) {
        this.fileName = fileName; this.name = name;
        this.encoding = encoding;
        this.items = Collections.unmodifiableList(items);
        this.detected = detected; this.duplicates = duplicates; this.invalid = invalid;
    }
}
