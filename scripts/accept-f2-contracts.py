#!/usr/bin/env python3
"""Check eight original F2 contract files against an independently signed manifest.

This checks bytes, not legal or executable conformance. The approval signer must
review sources independently; never sign a CI candidate inventory automatically.
Usage: accept-f2-contracts.py DIRECTORY APPROVAL_JSON SIGNATURE_DER PUBLIC_PEM PEM_SHA256
The signature covers b'FISKAI-F2-CONTRACT-APPROVAL-V1\n' + the exact JSON bytes.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location("bundle", Path(__file__).with_name("verify-f2-bundle.py"))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)
DOMAIN = b"FISKAI-F2-CONTRACT-APPROVAL-V1\n"
PDFS = {
    "f2-specification-2026-11-26.pdf": 222,
    "as4-2025-10-07.pdf": 140,
    "mps-2025-10-08.pdf": 141,
    "ams-2025-10-09.pdf": 142,
}
SOURCES = {kind + ".zip": url for kind, url in bundle.SOURCES.items()}
SOURCES.update({name: f"https://porezna.gov.hr/fiskalizacija/api/dokumenti/{id}" for name, id in PDFS.items()})


def require(ok, message):
    if not ok:
        raise ValueError(message)


def read(path, limit):
    require(path.is_file() and not path.is_symlink(), "Expected regular file")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    require(0 < len(data) <= limit, "Invalid file size")
    return data


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def accept(root, approval, signature, public, pin):
    require(re.fullmatch(r"[0-9a-f]{64}", pin) and hashlib.sha256(public).hexdigest() == pin,
            "Independent reviewer key mismatch")
    require(0 < len(approval) <= 16384 and 0 < len(signature) <= 80 and 0 < len(public) <= 8192,
            "Invalid approval material")
    with tempfile.TemporaryDirectory() as work:
        key = Path(work) / "public.pem"
        sig = Path(work) / "signature.der"
        key.write_bytes(public)
        sig.write_bytes(signature)
        info = subprocess.run(["openssl", "pkey", "-pubin", "-in", str(key), "-text_pub", "-noout"],
                              capture_output=True, timeout=10, check=True).stdout
        require(b"ASN1 OID: prime256v1" in info, "P-256 reviewer key required")
        result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(key), "-signature", str(sig)],
                                input=DOMAIN + approval, capture_output=True, timeout=10)
        require(result.returncode == 0, "Invalid reviewer signature")
    manifest = json.loads(approval, object_pairs_hook=unique_pairs)
    require(isinstance(manifest, dict) and set(manifest) == {"protocol", "files"}
            and manifest["protocol"] == "FISKAI-F2-CONTRACT-APPROVAL-V1"
            and isinstance(manifest["files"], dict) and set(manifest["files"]) == set(SOURCES),
            "Exactly eight approved original files required")
    require(root.is_dir() and not root.is_symlink(), "Invalid contract directory")
    verified = {}
    for name, source in SOURCES.items():
        entry = manifest["files"][name]
        require(isinstance(entry, dict) and set(entry) == {"source", "sha256"} and entry["source"] == source
                and isinstance(entry["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]),
                "Invalid approved file entry")
        path = root / name
        if name.endswith(".zip"):
            verified[name] = bundle.verify(path, name[:-4], entry["sha256"])
        else:
            data = read(path, bundle.MAX_ARCHIVE)
            require(data.startswith(b"%PDF-") and hashlib.sha256(data).hexdigest() == entry["sha256"],
                    "PDF header or checksum mismatch")
            verified[name] = entry.copy()
    return {"status": "REVIEWER_PINNED_BYTES_VERIFIED", "approvalSha256": hashlib.sha256(approval).hexdigest(),
            "reviewerKeySha256": pin, "schematronExecuted": False, "dispatchAuthorized": False, "files": verified}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("approval", type=Path)
    parser.add_argument("signature", type=Path)
    parser.add_argument("public_key", type=Path)
    parser.add_argument("public_key_sha256")
    args = parser.parse_args()
    print(json.dumps(accept(args.directory, read(args.approval, 16384), read(args.signature, 80),
                            read(args.public_key, 8192), args.public_key_sha256), indent=2))
