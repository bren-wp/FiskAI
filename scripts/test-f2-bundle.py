#!/usr/bin/env python3
"""Synthetic archive fixtures only; no official bundle is redistributed."""
import hashlib
import importlib.util
from pathlib import Path
import stat
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location("bundle", Path(__file__).with_name("verify-f2-bundle.py"))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)
kind = "validator-2026-03-13"

def rejects(action):
    try:
        action()
    except (ValueError, zipfile.BadZipFile):
        return
    raise AssertionError("Invalid archive accepted")

with tempfile.TemporaryDirectory() as work:
    path = Path(work) / "fixture.zip"

    def write(entries):
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as z:
            for name, value in entries:
                z.writestr(name, value)
        return hashlib.sha256(path.read_bytes()).hexdigest()

    pin = write([("schema/main.xsd", b"fixture"), ("rules/main.xsl", b"rules")])
    result = bundle.verify(path, kind, pin)
    assert result["files"]["schema/main.xsd"] == hashlib.sha256(b"fixture").hexdigest()
    rejects(lambda: bundle.verify(path, kind, "0" * 64))
    for name in ("../escape", "/absolute", "a/../escape", "a\\escape", "C:/file"):
        pin = write([(name, b"fixture")])
        rejects(lambda: bundle.verify(path, kind, pin))
    pin = write([("a.xsd", b"one"), ("A.xsd", b"two")])
    rejects(lambda: bundle.verify(path, kind, pin))
    link = zipfile.ZipInfo("link")
    link.create_system = 3
    link.external_attr = (stat.S_IFLNK | 0o777) << 16
    pin = write([(link, b"outside")])
    rejects(lambda: bundle.verify(path, kind, pin))
    bundle.MAX_ENTRY = 4
    pin = write([("large.xsd", b"12345")])
    rejects(lambda: bundle.verify(path, kind, pin))
    print("F2 archive: valid manifest, digest, path, symlink and size checks passed.")
