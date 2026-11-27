#!/usr/bin/env python3
"""Verify actual Room compiler output, not a handwritten schema."""
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
variants = ("compileDebugJavaWithJavac", "compileDemoJavaWithJavac", "compileReleaseJavaWithJavac")
documents = []
for variant in variants:
    path = root / variant / "hr.brendigo.fiskai.room.FiscalRoomDatabase" / "10.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    db = document["database"]
    assert db["version"] == 10, f"Unexpected schema version: {variant}"
    entities = {e["tableName"]: e for e in db["entities"]}
    assert set(entities) == {"fiscal_document", "fiscal_outbox", "incoming_invoice", "bank_transaction", "receipt_event", "demo_payment_preparation"}
    assert entities["fiscal_outbox"]["foreignKeys"], "Missing document reference"
    assert len(entities["fiscal_document"]["indices"]) == 2, "Missing uniqueness indices"
    assert entities["receipt_event"]["foreignKeys"], "Missing bank transaction reference"
    assert any(index["unique"] for index in entities["receipt_event"]["indices"]), "Missing bank receipt uniqueness"
    assert len(entities["demo_payment_preparation"]["foreignKeys"]) == 2, "Missing payment evidence references"
    assert {tuple(index["columnNames"]): index["unique"] for index in entities["demo_payment_preparation"]["indices"]} == {("documentId",): False, ("eventId",): True}, "Unexpected payment allocation indexes"
    documents.append(document)
assert all(document == documents[0] for document in documents), "Room schema differs across variants"
print("All three Room schemas match.")
