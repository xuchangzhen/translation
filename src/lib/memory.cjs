const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { toIPA } = require("phonemize");

const MEMORY_SCHEMA_VERSION = 2;

function isSingleEnglishWord(value) {
  return /^[A-Za-z][A-Za-z.'’_-]*$/.test(String(value || "").trim());
}

function normalizeIpa(value) {
  const cleaned = String(value || "")
    .replace(/\b(?:ipa|phonetic)\s*:?/gi, "")
    .replace(/[\u3400-\u9fff]+/g, "")
    .trim();
  if (!cleaned) return "";
  const wrapped = cleaned.match(/[\/\[]\s*([^\/\]]+?)\s*[\/\]]/);
  const content = String(wrapped?.[1] || cleaned)
    .replace(/^[\/\[]+|[\/\]]+$/g, "")
    .trim();
  return content ? `/${content}/` : "";
}

function localEnglishIpa(value) {
  if (!isSingleEnglishWord(value)) return "";
  try {
    return normalizeIpa(toIPA(String(value).trim(), "en-US"));
  } catch {
    return "";
  }
}

function normalizeKey(value) {
  return String(value || "")
    .normalize("NFKC")
    .toLocaleLowerCase("en-US")
    .replace(/\s+/g, " ")
    .replace(/^[\s.,!?;:，。！？；：“”‘’]+|[\s.,!?;:，。！？；：“”‘’]+$/g, "")
    .trim();
}

function stripMarkdown(value) {
  return String(value || "")
    .replace(/```[\s\S]*?```|~~~[\s\S]*?~~~/g, " ")
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/^\s{0,3}(?:#{1,6}\s+|>\s*|[-+*]\s+|\d+[.)]\s+)/gm, "")
    .replace(/[*_~]{1,3}/g, "")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/<[^>]+>/g, " ")
    .replace(/[ \t]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function splitLearningUnits(value) {
  const cleaned = stripMarkdown(value);
  if (!cleaned) return [];
  return cleaned
    .split(/\n+/)
    .flatMap((block) => block.split(/(?<=[.!?。！？])(?:\s+|(?=[^\s]))/u))
    .map((item) => item.replace(/\s+/g, " ").trim())
    .filter((item) => item.length >= 2 && item.length <= 500)
    .slice(0, 8);
}

function latinScore(value) {
  return (String(value || "").match(/[A-Za-z]/g) || []).length;
}

function orientPair(sourceText, translation, sourceLanguage, targetLanguage) {
  const sourceLatin = latinScore(sourceText);
  const translationLatin = latinScore(translation);
  const shouldReverse =
    targetLanguage === "en" ||
    (sourceLanguage !== "en" && translationLatin > sourceLatin * 1.5);
  if (shouldReverse) {
    return {
      front: translation,
      back: sourceText,
      frontLanguage: targetLanguage,
      backLanguage: sourceLanguage
    };
  }
  return {
    front: sourceText,
    back: translation,
    frontLanguage: sourceLanguage,
    backLanguage: targetLanguage
  };
}

function uniqueNotes(values) {
  return [...new Set(values.map((value) => String(value || "").trim()).filter(Boolean))];
}

function pairedLearningUnits(sourceText, translation) {
  const sourceUnits = splitLearningUnits(sourceText);
  const translationUnits = splitLearningUnits(translation);
  if (!sourceUnits.length || !translationUnits.length) return [];
  if (sourceUnits.length === translationUnits.length) {
    return sourceUnits.map((source, index) => ({
      source,
      translation: translationUnits[index]
    }));
  }
  if (Math.abs(sourceUnits.length - translationUnits.length) <= 1) {
    return sourceUnits
      .slice(0, Math.min(sourceUnits.length, translationUnits.length))
      .map((source, index) => ({ source, translation: translationUnits[index] }));
  }
  const source = stripMarkdown(sourceText);
  const translated = stripMarkdown(translation);
  return source.length <= 1000 && translated.length <= 1000
    ? [{ source, translation: translated }]
    : [];
}

function createMemoryCandidates(sourceText, result) {
  const text = String(sourceText || "").trim();
  const translation = String(result?.translation || "").trim();
  if (!text || !translation) return [];

  const sourceLanguage = String(result?.sourceLanguage || "auto");
  const targetLanguage = String(result?.targetLanguage || "zh-CN");
  const explanation = String(result?.explanation || "").trim();
  const terms = Array.isArray(result?.terms) ? result.terms.slice(0, 4) : [];
  const abbreviations = Array.isArray(result?.abbreviations)
    ? result.abbreviations.slice(0, 3)
    : [];
  const candidates = [];

  pairedLearningUnits(text, translation).forEach((pair, pairIndex) => {
    const oriented = orientPair(
      pair.source,
      pair.translation,
      sourceLanguage,
      targetLanguage
    );
    const singleWord = isSingleEnglishWord(oriented.front);
    const searchablePair = normalizeKey(`${pair.source} ${pair.translation}`);
    const relevantTerms = terms.filter((term) => {
      const termKey = normalizeKey(term?.term);
      return termKey && searchablePair.includes(termKey);
    });
    candidates.push({
      type: singleWord ? "word" : "sentence",
      ...oriented,
      phonetic: singleWord
        ? normalizeIpa(result?.phonetic) || localEnglishIpa(oriented.front)
        : "",
      technicalNotes: uniqueNotes([
        pairIndex === 0 || relevantTerms.length ? explanation : "",
        ...relevantTerms.map((term) =>
          term?.definition
            ? `${String(term.category || "技术").trim()}：${String(term.definition).trim()}`
            : ""
        )
      ]),
      context: "",
      terms: relevantTerms
        .map((term) => ({
          term: String(term?.term || "").trim(),
          translation: String(term?.translation || "").trim(),
          definition: String(term?.definition || "").trim(),
          category: String(term?.category || "技术").trim()
        }))
        .filter((term) => term.term)
    });
  });

  const plainSource = stripMarkdown(text).replace(/\s+/g, " ").trim();
  for (const term of terms) {
    const termText = String(term?.term || "").trim();
    const termTranslation = String(term?.translation || "").trim();
    if (!termText || !termTranslation || !/[A-Za-z]/.test(termText)) continue;
    const oriented = orientPair(termText, termTranslation, "en", targetLanguage);
    candidates.push({
      type: "word",
      ...oriented,
      phonetic: isSingleEnglishWord(oriented.front)
        ? localEnglishIpa(oriented.front)
        : "",
      technicalNotes: uniqueNotes([
        term?.definition
          ? `${String(term.category || "技术").trim()}：${String(term.definition).trim()}`
          : "",
        explanation
      ]),
      context:
        plainSource && normalizeKey(plainSource) !== normalizeKey(termText)
          ? plainSource.slice(0, 500)
          : "",
      terms: []
    });
  }

  for (const abbreviation of abbreviations) {
    const shortName = String(abbreviation?.abbreviation || "").trim();
    const fullName = String(abbreviation?.fullName || "").trim();
    if (!/^[A-Z][A-Z0-9.+#-]{1,11}$/.test(shortName) || !fullName) continue;
    const matchingTerm = terms.find(
      (term) => normalizeKey(term?.term) === normalizeKey(shortName)
    );
    candidates.push({
      type: "word",
      front: shortName,
      back: uniqueNotes([fullName, matchingTerm?.translation]).join(" · "),
      frontLanguage: "en",
      backLanguage: targetLanguage,
      phonetic: "",
      technicalNotes: uniqueNotes([
        matchingTerm?.definition
          ? `${String(matchingTerm.category || "技术").trim()}：${String(matchingTerm.definition).trim()}`
          : "",
        explanation
      ]),
      context: plainSource.slice(0, 500),
      terms: []
    });
  }

  const seen = new Set();
  return candidates
    .filter((candidate) => {
      const key = normalizeKey(candidate.front);
      if (!key || !normalizeKey(candidate.back) || seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, 10);
}

class MemoryOutboxStore {
  constructor(userDataPath) {
    this.filePath = path.join(userDataPath, "memory-outbox.json");
    this.backupFilePath = path.join(userDataPath, "memory-outbox.backup.json");
    this.legacyFilePath = path.join(userDataPath, "memory.json");
    this.data = this.read();
  }

  normalize(parsed, migrateLegacy = false) {
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("待同步词条文件内容无效");
    }
    return {
      schemaVersion: MEMORY_SCHEMA_VERSION,
      lastSyncedAt: Number(parsed.lastSyncedAt || 0),
      items: Array.isArray(parsed.items)
        ? parsed.items
            .filter((item) => item && item.id && item.front && item.back)
            .map((item) => ({
              id: String(item.id),
              key: item.key || normalizeKey(item.front),
              type: item.type === "sentence" ? "sentence" : "word",
              front: String(item.front),
              back: String(item.back),
              frontLanguage: String(item.frontLanguage || "en"),
              backLanguage: String(item.backLanguage || "zh-CN"),
              phonetic: String(item.phonetic || ""),
              technicalNotes: Array.isArray(item.technicalNotes)
                ? uniqueNotes(item.technicalNotes)
                : [],
              context: String(item.context || ""),
              terms: Array.isArray(item.terms) ? item.terms : [],
              createdAt: Number(item.createdAt || Date.now()),
              updatedAt: Number(item.updatedAt || item.createdAt || Date.now()),
              lastSeenAt: Number(item.lastSeenAt || item.updatedAt || Date.now()),
              encounterCount: Math.max(1, Number(item.encounterCount || 1)),
              syncState: migrateLegacy ? "pending" : item.syncState || "pending",
              syncedAt: migrateLegacy ? 0 : Number(item.syncedAt || 0),
              archivedAt: Number(item.archivedAt || 0)
            }))
        : []
    };
  }

  readFile(filePath, migrateLegacy = false) {
    return this.normalize(JSON.parse(fs.readFileSync(filePath, "utf8")), migrateLegacy);
  }

  read() {
    for (const candidate of [this.filePath, this.backupFilePath]) {
      try {
        return this.readFile(candidate);
      } catch {
        // Try the last known-good outbox before migrating legacy desktop cards.
      }
    }
    try {
      return this.readFile(this.legacyFilePath, true);
    } catch {
      return { schemaVersion: MEMORY_SCHEMA_VERSION, lastSyncedAt: 0, items: [] };
    }
  }

  backupCurrentFile() {
    if (!fs.existsSync(this.filePath)) return;
    try {
      this.readFile(this.filePath);
      fs.copyFileSync(this.filePath, this.backupFilePath);
    } catch {
      // Never replace a valid backup with a corrupt current file.
    }
  }

  save() {
    const directory = path.dirname(this.filePath);
    const temporaryPath = path.join(directory, `memory-outbox.${process.pid}.tmp`);
    fs.mkdirSync(directory, { recursive: true });
    this.backupCurrentFile();
    fs.writeFileSync(temporaryPath, JSON.stringify(this.data, null, 2), {
      mode: 0o600
    });
    try {
      fs.renameSync(temporaryPath, this.filePath);
    } catch (error) {
      try {
        fs.copyFileSync(temporaryPath, this.filePath);
        fs.unlinkSync(temporaryPath);
      } catch {
        if (fs.existsSync(temporaryPath)) fs.unlinkSync(temporaryPath);
        throw error;
      }
    }
  }

  capture(sourceText, result, now = Date.now()) {
    const candidates = createMemoryCandidates(sourceText, result);
    let added = 0;
    let updated = 0;
    for (const candidate of candidates) {
      const key = normalizeKey(candidate.front);
      const existing = this.data.items.find(
        (item) => !item.archivedAt && item.key === key
      );
      if (existing) {
        existing.back = candidate.back;
        existing.frontLanguage = candidate.frontLanguage;
        existing.backLanguage = candidate.backLanguage;
        existing.type = candidate.type;
        existing.phonetic = candidate.phonetic || existing.phonetic || "";
        existing.technicalNotes = uniqueNotes([
          ...(existing.technicalNotes || []),
          ...candidate.technicalNotes
        ]);
        existing.context = candidate.context || existing.context || "";
        existing.terms = candidate.terms.length
          ? candidate.terms
          : existing.terms || [];
        existing.updatedAt = now;
        existing.syncState = "pending";
        existing.syncedAt = 0;
        if (now - Number(existing.lastSeenAt || 0) > 5 * 60 * 1000) {
          existing.encounterCount = Number(existing.encounterCount || 1) + 1;
          existing.lastSeenAt = now;
        }
        updated += 1;
        continue;
      }
      this.data.items.push({
        id: crypto.randomUUID(),
        key,
        ...candidate,
        createdAt: now,
        updatedAt: now,
        lastSeenAt: now,
        encounterCount: 1,
        syncState: "pending",
        syncedAt: 0,
        archivedAt: 0
      });
      added += 1;
    }
    if (added || updated) this.save();
    return { added, updated, candidateCount: candidates.length };
  }

  pending(limit = 100) {
    return this.data.items
      .filter((item) => !item.archivedAt && item.syncState !== "synced")
      .sort((left, right) => Number(left.updatedAt || 0) - Number(right.updatedAt || 0))
      .slice(0, Math.max(1, limit))
      .map((item) => ({ ...item }));
  }

  markSynced(sentItems, now = Date.now()) {
    const sentVersions = new Map(
      sentItems.map((item) => [String(item.id), Number(item.updatedAt || 0)])
    );
    let synced = 0;
    for (const item of this.data.items) {
      const sentVersion = sentVersions.get(String(item.id));
      if (typeof sentVersion !== "number" || Number(item.updatedAt || 0) > sentVersion) {
        continue;
      }
      item.syncState = "synced";
      item.syncedAt = now;
      synced += 1;
    }
    if (synced) {
      this.data.lastSyncedAt = now;
      this.save();
    }
    return synced;
  }

  status() {
    const items = this.data.items.filter((item) => !item.archivedAt);
    return {
      total: items.length,
      pending: items.filter((item) => item.syncState !== "synced").length,
      synced: items.filter((item) => item.syncState === "synced").length,
      lastSyncedAt: Number(this.data.lastSyncedAt || 0)
    };
  }
}

module.exports = {
  MEMORY_SCHEMA_VERSION,
  MemoryOutboxStore,
  createMemoryCandidates,
  normalizeIpa,
  normalizeKey,
  splitLearningUnits,
  stripMarkdown
};
