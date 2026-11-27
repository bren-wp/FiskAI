#!/usr/bin/env python3
"""Synthetic approval fixtures; never represent official contract acceptance."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location("acceptance", Path(__file__).with_name("accept-f2-contracts.py"))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


def openssl(*args, data=None):
    return subprocess.run(["openssl", *args], input=data, capture_output=True, check=True, timeout=10).stdout


def reject(action):
    try:
        action()
    except (ValueError, zipfile.BadZipFile, subprocess.CalledProcessError):
        return
    raise AssertionError("Invalid acceptance evidence accepted")


with tempfile.TemporaryDirectory() as work:
    root = Path(work)
    key = root / "signer.pem"
    key.write_bytes(openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1"))
    public = openssl("pkey", "-in", str(key), "-pubout")
    pin = hashlib.sha256(public).hexdigest()
    manifest = {"protocol": "FISKAI-F2-CONTRACT-APPROVAL-V1", "files": {}}
    for name, source in a.SOURCES.items():
        path = root / name
        if name.endswith(".zip"):
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("fixture.xsd", b"synthetic")
        else:
            path.write_bytes(b"%PDF-1.7\nSynthetic fixture\n%%EOF\n")
        manifest["files"][name] = {"source": source, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def sign(raw):
        return openssl("dgst", "-sha256", "-sign", str(key), data=a.DOMAIN + raw)

    raw = json.dumps(manifest).encode()
    signature = sign(raw)
    result = a.accept(root, raw, signature, public, pin)
    assert len(result["files"]) == 8 and result["schematronExecuted"] is False and result["dispatchAuthorized"] is False
    reject(lambda: a.accept(root, raw, signature, public, "0" * 64))
    reject(lambda: a.accept(root, raw + b" ", signature, public, pin))
    reject(lambda: a.accept(root, raw, b"invalid", public, pin))
    candidate = json.dumps({"status": "UNREVIEWED_CANDIDATE_ONLY", "files": manifest["files"]}).encode()
    reject(lambda: a.accept(root, candidate, sign(candidate), public, pin))
    missing = json.loads(raw)
    missing["files"].pop(next(iter(a.SOURCES)))
    wrong = json.dumps(missing).encode()
    reject(lambda: a.accept(root, wrong, sign(wrong), public, pin))
    changed = json.loads(raw)
    changed["files"][next(iter(a.SOURCES))]["source"] = "https://example.test/other"
    wrong = json.dumps(changed).encode()
    reject(lambda: a.accept(root, wrong, sign(wrong), public, pin))
    duplicate = b'{"protocol":"wrong",' + raw[1:]
    reject(lambda: a.accept(root, duplicate, sign(duplicate), public, pin))
    path = root / next(iter(a.PDFS))
    original = path.read_bytes()
    path.write_bytes(original + b"changed")
    reject(lambda: a.accept(root, raw, signature, public, pin))
    path.unlink()
    reject(lambda: a.accept(root, raw, signature, public, pin))
    external = root / "copy.pdf"
    external.write_bytes(original)
    path.symlink_to(external)
    reject(lambda: a.accept(root, raw, signature, public, pin))
    print("F2 acceptance: 11 synthetic signature, scope, integrity and file checks passed; no execution or dispatch.")
