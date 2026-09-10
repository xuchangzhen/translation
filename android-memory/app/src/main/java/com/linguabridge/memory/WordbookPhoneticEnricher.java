package com.linguabridge.memory;

import java.util.ArrayList;
import java.util.List;

/** Adds locally-known phonetics to a parsed preview without changing parsing or persistence. */
public final class WordbookPhoneticEnricher {
    interface Lookup {
        String find(String word);
        boolean isUnavailable();
    }

    private final Lookup dictionary;

    public WordbookPhoneticEnricher(PhoneticDictionary dictionary) {
        this((Lookup) dictionary);
    }

    WordbookPhoneticEnricher(Lookup dictionary) {
        this.dictionary = dictionary;
    }

    public PhoneticEnrichmentResult enrich(ImportPreview source) {
        List<WordbookImporter.Item> items = new ArrayList<>(source.items.size());
        int existing = 0;
        int filled = 0;
        int missing = 0;
        for (WordbookImporter.Item item : source.items) {
            if (!item.phonetic.trim().isEmpty()) {
                existing++;
                items.add(item);
                continue;
            }
            String phonetic = dictionary.find(item.front);
            if (phonetic != null && !phonetic.trim().isEmpty()) {
                filled++;
                items.add(copyWithPhonetic(item, phonetic.trim()));
            } else {
                missing++;
                items.add(item);
            }
        }
        ImportPreview preview = new ImportPreview(source.fileName, source.name, items,
                source.detected, source.duplicates, source.invalid, source.encoding);
        return new PhoneticEnrichmentResult(preview, existing, filled, missing, dictionary.isUnavailable());
    }

    private WordbookImporter.Item copyWithPhonetic(WordbookImporter.Item item, String phonetic) {
        return new WordbookImporter.Item(item.front, item.back, phonetic,
                item.definition, item.category, item.context);
    }
}
