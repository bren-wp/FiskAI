#!/usr/bin/env python3
"""Exercise the independent witness with a synthetic signing key and persisted history."""
import base64
import importlib.util
import json
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location("witness", Path(__file__).with_name("educ-witness.py"))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)

def reject(fn):
    try:
        fn()
    except ValueError:
        return
    raise AssertionError("Invalid history accepted")

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    db = root / "witness.db"
    key = root / "key.pem"
    key.write_bytes(w.openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1"))
    key.chmod(0o600)
    pin = w.init(db,key)
    subject = "a1000000-0000-4000-8000-000000000001"
    history = {"protocol":"FISKAI-EDUC-HISTORY-V1","subject":subject,"keyId":"fixture","heads":[w.ZERO,"a"*64]}
    def record(data):
        return w.attest(db,key,raw=w.canonical(data))
    def statement(result):
        assert result["publicKeySha256"] == pin
        public = root / "public.pem"
        signature = root / "signature.der"
        public.write_bytes(w.signing_public(key))
        signature.write_bytes(base64.b64decode(result["signature"],validate=True))
        raw = base64.b64decode(result["statement"],validate=True)
        w.openssl("dgst","-sha256","-verify",str(public),"-signature",str(signature),data=raw)
        return json.loads(raw)
    assert statement(record(history))["position"] == 1
    assert statement(record(history))["position"] == 1
    later = dict(history,heads=[*history["heads"],"b"*64])
    assert statement(record(later))["position"] == 2
    reject(lambda:record(history))
    reject(lambda:record(dict(later,heads=[w.ZERO,"c"*64,"b"*64])))
    reject(lambda:record(dict(later,keyId="new")))
    assert statement(w.attest(db,key,subject=subject))["head"] == "b"*64
    other = root / "other.pem"
    other.write_bytes(w.openssl("genpkey","-algorithm","EC","-pkeyopt","ec_paramgen_curve:prime256v1"))
    other.chmod(0o600)
    reject(lambda:w.attest(db,other,subject=subject))
    reject(lambda:w.attest(db,key,subject="a1000000-0000-4000-8000-000000000002"))
    print("Witness monotonic history, signed head, fork/rollback/key rejection passed.")
