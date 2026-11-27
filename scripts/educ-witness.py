#!/usr/bin/env python3
"""Independent operator-controlled monotonic witness (SQLite, no HTTP).
Deploy on a separate trust domain. A rollback of the witness itself is out of scope.
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
UUID = re.compile(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
HEX = re.compile(r"[0-9a-f]{64}")

def require(ok, message):
    if not ok:
        raise ValueError(message)

def canonical(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode("ascii")

def private_file(path):
    s = path.lstat()
    require(stat.S_ISREG(s.st_mode) and s.st_uid == os.getuid() and s.st_mode & 0o077 == 0,
            "Expected owner-only regular file")

def openssl(*args, data=None):
    return subprocess.run(["openssl", *args], input=data, capture_output=True,
                          timeout=10, check=True).stdout

def signing_public(key):
    private_file(key)
    public = openssl("pkey", "-in", str(key), "-passin", "pass:", "-pubout")
    details = openssl("pkey", "-pubin", "-text_pub", "-noout", data=public)
    require(b"ASN1 OID: prime256v1" in details, "P-256 signing key required")
    return public

def init(path, key):
    pin = hashlib.sha256(signing_public(key)).hexdigest()
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA synchronous=FULL")
        db.execute("CREATE TABLE config(pin TEXT NOT NULL)")
        db.execute("INSERT INTO config VALUES (?)", (pin,))
        db.execute("CREATE TABLE heads(subject TEXT NOT NULL, key_id TEXT NOT NULL, position INTEGER NOT NULL, head TEXT NOT NULL, PRIMARY KEY(subject,position))")
    return pin

def history(raw):
    require(len(raw) <= 100000, "History too large")
    obj = json.loads(raw)
    require(isinstance(obj, dict) and set(obj) == {"protocol","subject","keyId","heads"}
            and obj["protocol"] == "FISKAI-EDUC-HISTORY-V1", "Invalid history")
    subject, key_id, heads = obj["subject"], obj["keyId"], obj["heads"]
    require(isinstance(subject, str) and UUID.fullmatch(subject) and subject != "00000000-0000-0000-0000-000000000000", "Invalid subject")
    require(isinstance(key_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", key_id), "Invalid key ID")
    require(isinstance(heads, list) and 1 <= len(heads) <= 1001 and heads[0] == ZERO
            and all(isinstance(h, str) and HEX.fullmatch(h) for h in heads)
            and len(set(heads)) == len(heads), "Invalid chain prefix")
    return subject, key_id, heads

def attest(path, key, *, raw=None, subject=None, nonce=None):
    require(nonce is None or (raw is None and isinstance(nonce,str) and HEX.fullmatch(nonce)), "Invalid freshness challenge")
    private_file(path)
    public = signing_public(key)
    pin = hashlib.sha256(public).hexdigest()
    db = sqlite3.connect(path.resolve().as_uri() + "?mode=rw", uri=True, timeout=10)
    try:
        db.execute("PRAGMA synchronous=FULL")
        db.execute("BEGIN IMMEDIATE")
        require(db.execute("SELECT pin FROM config").fetchall() == [(pin,)], "Witness signing key mismatch")
        if raw is not None:
            subject, key_id, heads = history(raw)
        else:
            require(isinstance(subject, str) and UUID.fullmatch(subject), "Invalid subject")
        rows = db.execute("SELECT key_id,position,head FROM heads WHERE subject=? ORDER BY position", (subject,)).fetchall()
        require(all(p == i for i, (_, p, _) in enumerate(rows)), "Witness history damaged")
        if raw is not None:
            require(len(rows) <= len(heads), "Rollback rejected")
            require(all(k == key_id and h == heads[p] for k, p, h in rows), "History fork or key change rejected")
            db.executemany("INSERT INTO heads VALUES (?,?,?,?)", [(subject,key_id,i,heads[i]) for i in range(len(rows),len(heads))])
            position = len(heads) - 1
            head = heads[-1]
        else:
            require(bool(rows), "Unknown subject")
            key_id, position, head = rows[-1]
        statement = canonical({"protocol":"FISKAI-EDUC-HEAD-V1","subject":subject,"keyId":key_id,"position":position,"head":head})
        signed = statement if nonce is None else b"FISKAI-EDUC-FRESH-V1\n" + nonce.encode("ascii") + b"\n" + statement
        signature = openssl("dgst", "-sha256", "-sign", str(key), "-passin", "pass:", data=signed)
        db.commit()
        result = {"statement":base64.b64encode(statement).decode(), "signature":base64.b64encode(signature).decode(),
                  "publicKeySha256":pin}
        if nonce is not None:
            result["nonce"] = nonce
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
    parser.add_argument("command", choices=["init","record","latest"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    if args.command == "init":
        require(args.value is None, "Unexpected argument")
        print(init(args.database,args.key))
    elif args.command == "record":
        require(args.value is not None, "History file required")
        with open(args.value,"rb") as stream:
            raw=stream.read(100001)
        print(json.dumps(attest(args.database,args.key,raw=raw)))
    else:
        print(json.dumps(attest(args.database,args.key,subject=args.value)))

if __name__ == "__main__":
    main()
