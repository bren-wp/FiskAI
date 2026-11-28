#!/usr/bin/env python3
"""Independent regulatory witness. Operator-only append, nonce-bound read.
Keep database and key on a separate trust domain; witness-host rollback is out of scope.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import subprocess

ZERO = "0" * 64
HEX = re.compile(r"[0-9a-f]{64}")
PREFIX = b"FISKAI-REGULATORY-HISTORY-V1\n"
FRESH = b"FISKAI-REGULATORY-FRESH-V1\n"
MAX = 1024 * 1024

def require(value, message):
    if not value:
        raise ValueError(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def private_file(path):
    s = path.lstat()
    require(stat.S_ISREG(s.st_mode) and s.st_uid == os.getuid() and not s.st_mode & 0o077,
            "Expected owner-only regular file")

def openssl(*args, data=None):
    return subprocess.run(["openssl", *args], input=data, capture_output=True,
                          timeout=10, check=True).stdout

def public_key(key):
    private_file(key)
    public = openssl("pkey", "-in", str(key), "-passin", "pass:", "-pubout")
    details = openssl("pkey", "-pubin", "-text_pub", "-noout", data=public)
    require(b"ASN1 OID: prime256v1" in details, "P-256 key required")
    return public

def init(path, key):
    pin = sha(public_key(key))
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA synchronous=FULL")
        db.execute("CREATE TABLE config(pin TEXT NOT NULL)")
        db.execute("INSERT INTO config VALUES (?)", (pin,))
        db.execute("CREATE TABLE revisions(sequence INTEGER PRIMARY KEY, catalog BLOB NOT NULL, digest TEXT NOT NULL UNIQUE, head TEXT NOT NULL UNIQUE)")
    return pin

def rows_verified(db):
    rows = db.execute("SELECT sequence,length(catalog),digest,head FROM revisions ORDER BY sequence LIMIT 1001").fetchall()
    require(len(rows) <= 1000 and sum(row[1] for row in rows) <= 16*MAX, "History capacity exceeded")
    prior = ZERO
    result = []
    for i, (seq, size, digest, head) in enumerate(rows, 1):
        require(seq == i and 0 < size <= MAX and HEX.fullmatch(digest) and HEX.fullmatch(head), "Invalid stored history")
        catalog = db.execute("SELECT catalog FROM revisions WHERE sequence=?", (seq,)).fetchone()[0]
        require(sha(catalog) == digest, "Stored catalog integrity failure")
        line = f"{seq}|{prior}|{digest}\n".encode("ascii")
        require(sha(line) == head, "Stored history chain failure")
        prior = head
        result.append((seq, digest, head, line))
    return result

def attest(path, key, *, catalog=None, expected_head=None, nonce=None):
    require((catalog is None) != (nonce is None), "Choose append or fresh read")
    if catalog is not None:
        require(isinstance(catalog, bytes) and 0 < len(catalog) <= MAX and
                isinstance(expected_head, str) and HEX.fullmatch(expected_head), "Invalid append")
        require(isinstance(json.loads(catalog), dict), "Expected catalog object")
    else:
        require(isinstance(nonce, str) and HEX.fullmatch(nonce) and expected_head is None, "Invalid challenge")
    private_file(path)
    pin = sha(public_key(key))
    db = sqlite3.connect(path.resolve().as_uri() + "?mode=rw", uri=True, timeout=10)
    try:
        db.execute("PRAGMA synchronous=FULL")
        db.execute("BEGIN IMMEDIATE")
        require(db.execute("SELECT pin FROM config").fetchall() == [(pin,)], "Signing key mismatch")
        rows = rows_verified(db)
        head = rows[-1][2] if rows else ZERO
        if catalog is not None:
            require(expected_head == head, "Stale append or concurrent revision")
            digest = sha(catalog)
            require(all(row[1] != digest for row in rows), "Previously recorded catalog cannot be appended")
            require(len(rows) < 1000, "Revision capacity exceeded")
            seq = len(rows)+1
            line = f"{seq}|{head}|{digest}\n".encode("ascii")
            head = sha(line)
            db.execute("INSERT INTO revisions VALUES (?,?,?,?)", (seq,catalog,digest,head))
            rows = rows_verified(db)
        require(bool(rows), "No approved history")
        seq, digest, head, _ = rows[-1]
        history = PREFIX + b"".join(row[3] for row in rows)
        statement = f"{seq}|{head}|{digest}\n".encode("ascii")
        sign = lambda data: openssl("dgst", "-sha256", "-sign", str(key), "-passin", "pass:", data=data)
        if nonce is None:
            result = {"history": base64.b64encode(history).decode(),
                      "signature": base64.b64encode(sign(history)).decode(),
                      "publicKeySha256": pin, "sequence": seq, "head": head}
        else:
            result = {"statement": base64.b64encode(statement).decode(),
                      "signature": base64.b64encode(sign(FRESH+nonce.encode("ascii")+b"\n"+statement)).decode(),
                      "publicKeySha256": pin, "nonce": nonce}
        db.commit()
        return result
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("key", type=Path)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init")
    append = sub.add_parser("append")
    append.add_argument("catalog", type=Path)
    append.add_argument("expected_head")
    latest = sub.add_parser("latest")
    latest.add_argument("nonce")
    args = parser.parse_args()
    if args.command == "init":
        print(init(args.database,args.key))
    elif args.command == "append":
        with args.catalog.open("rb") as stream:
            catalog = stream.read(MAX+1)
        print(json.dumps(attest(args.database,args.key,catalog=catalog,expected_head=args.expected_head)))
    else:
        print(json.dumps(attest(args.database,args.key,nonce=args.nonce)))

if __name__ == "__main__":
    main()
