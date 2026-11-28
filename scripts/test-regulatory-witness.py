#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("regulatory", root/"scripts/regulatory-witness.py")
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)

def reject(fn):
    try:
        fn()
    except ValueError:
        return
    raise AssertionError("Invalid history accepted")

with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp)
    key, db = path/"key.pem", path/"history.db"
    key.write_bytes(w.openssl("genpkey","-algorithm","EC","-pkeyopt","ec_paramgen_curve:prime256v1"))
    key.chmod(0o600)
    pin = w.init(db,key)
    old = b'{"fixture":1}'
    active = (root/"server/contracts/legal-rules.json").read_bytes()
    first = w.attest(db,key,catalog=old,expected_head=w.ZERO)
    second = w.attest(db,key,catalog=active,expected_head=first["head"])
    assert second["sequence"] == 2 and second["publicKeySha256"] == pin
    reject(lambda:w.attest(db,key,catalog=b'{"fixture":3}',expected_head=first["head"]))
    reject(lambda:w.attest(db,key,catalog=old,expected_head=second["head"]))
    nonce = "a"*64
    reply = w.attest(db,key,nonce=nonce)
    (path/"approved.json").write_text(json.dumps({"sequence":2,"head":second["head"],"catalogSha256":w.sha(active)}))
    (path/"reply.json").write_text(json.dumps(reply))
    (path/"public.pem").write_bytes(w.public_key(key))
    subprocess.run(["php",str(root/"server/tests/RegulatoryFreshnessTest.php"),str(path/"approved.json"),nonce,
                    str(path/"reply.json"),str(path/"public.pem")],check=True)
    with sqlite3.connect(db) as conn:
        conn.execute("UPDATE revisions SET catalog=? WHERE sequence=1",(b'{"tampered":1}',))
    reject(lambda:w.attest(db,key,nonce=nonce))
    print("Persistent regulatory witness: monotonic append, rollback, corruption and fresh proof passed.")
