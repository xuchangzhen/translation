#!/usr/bin/env python3
"""Build the small, offline phonetic lookup database used by the Android app."""

import argparse
import csv
import sqlite3
import sys
import unicodedata
from pathlib import Path


def normalize_word(value: str) -> str:
    """Match WordbookImporter.normalizeFront: NFKC, trim, lowercase, collapsed spaces."""
    return " ".join(unicodedata.normalize("NFKC", value).strip().lower().split())


def flush(connection: sqlite3.Connection, batch: list[tuple[str, str]]) -> int:
    if not batch:
        return 0
    before = connection.total_changes
    connection.executemany(
        "INSERT OR IGNORE INTO phonetics(word, phonetic) VALUES (?, ?)", batch
    )
    batch.clear()
    return connection.total_changes - before


def build(source: Path, output: Path) -> tuple[int, int, int]:
    if not source.is_file():
        raise ValueError(f"ECDICT source does not exist: {source}")
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()

    written = 0
    ignored = 0
    batch: list[tuple[str, str]] = []
    connection = sqlite3.connect(output)
    try:
        connection.executescript(
            """
            PRAGMA journal_mode=OFF;
            PRAGMA synchronous=OFF;
            CREATE TABLE phonetics (
                word TEXT PRIMARY KEY COLLATE NOCASE,
                phonetic TEXT NOT NULL
            );
            """
        )
        with source.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            headers = {header.strip().lower() for header in (reader.fieldnames or [])}
            if not {"word", "phonetic"}.issubset(headers):
                raise ValueError("ECDICT CSV must contain word and phonetic headers.")
            connection.execute("BEGIN")
            for row in reader:
                word = normalize_word((row.get("word") or ""))
                phonetic = (row.get("phonetic") or "").strip()
                if not word or not phonetic:
                    ignored += 1
                    continue
                batch.append((word, phonetic))
                if len(batch) >= 1000:
                    before = len(batch)
                    inserted = flush(connection, batch)
                    written += inserted
                    ignored += before - inserted
            before = len(batch)
            inserted = flush(connection, batch)
            written += inserted
            ignored += before - inserted
            connection.commit()
        # The primary key already creates the case-insensitive lookup index.
        connection.execute("VACUUM")
    finally:
        connection.close()
    return written, ignored, output.stat().st_size


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Path to ECDICT ecdict.csv")
    parser.add_argument("--output", required=True, type=Path, help="Output SQLite database path")
    arguments = parser.parse_args()
    try:
        written, ignored, size = build(arguments.source, arguments.output)
    except (OSError, ValueError, csv.Error, sqlite3.Error) as error:
        print(f"Failed to build phonetic database: {error}", file=sys.stderr)
        return 1
    print(f"Written: {written}")
    print(f"Ignored: {ignored}")
    print(f"Database size: {size} bytes ({size / (1024 * 1024):.2f} MiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
