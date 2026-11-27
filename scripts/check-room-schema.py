#!/usr/bin/env python3
"""Verify actual Room compiler output, not a handwritten schema."""
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
variants = ("compileDebugJavaWithJavac", "compileDemoJavaWithJavac", "compileReleaseJavaWithJavac")
documents = []
for variant in variants:
    path = root / variant / "hr.brendigo.fiskai.room.FiscalRoomDatabase" / "8.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    db = document["database"]
    assert db["version"] == 8, f"Unexpected schema version: {variant}"
    entities = {e["tableName"]: e for e in db["entities"]}
    assert set(entities) == {"fiscal_document", "fiscal_outbox", "incoming_invoice", "bank_transaction", "receipt_event"}
    assert entities["fiscal_outbox"]["foreignKeys"], "Missing document reference"
    assert len(entities["fiscal_document"]["indices"]) == 2, "Missing uniqueness indices"
    documents.append(document)
assert all(document == documents[0] for document in documents), "Room schema differs across variants"
print("All three Room schemas match.")
