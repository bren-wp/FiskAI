#!/usr/bin/env python3
"""Check an independently approved original official ZIP; no execution or extraction."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

MAX_ARCHIVE = 64 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024
MAX_ENTRY = 16 * 1024 * 1024
SOURCES = {
    "validator-2026-03-13": "https://porezna.gov.hr/fiskalizacija/api/dokumenti/197",
    "ubl-2026-04-15": "https://porezna.gov.hr/fiskalizacija/api/dokumenti/198",
    "efiskalizacija-2026-02-12": "https://porezna.gov.hr/fiskalizacija/api/dokumenti/175",
    "eizvjestavanje-2026-02-12": "https://porezna.gov.hr/fiskalizacija/api/dokumenti/187",
}

def verify(path, kind, expected):
    if kind not in SOURCES or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Approved bundle kind and SHA-256 required")
    if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_ARCHIVE:
        raise ValueError("Invalid archive")
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            raise ValueError("Archive digest mismatch")
        stream.seek(0)
        with zipfile.ZipFile(stream) as archive:
            entries = archive.infolist()
            if not 1 <= len(entries) <= 2000:
                raise ValueError("Invalid archive entry count")
            result = {}
            seen = set()
            total = 0
            for item in entries:
                name = item.orig_filename
                parts = PurePosixPath(name).parts
                if (not name or len(name) > 512 or name.startswith("/") or "\\" in name
                        or ":" in name or "\x00" in name or any(ord(c) < 32 for c in name)
                        or any(p in ("", ".", "..") for p in name.rstrip("/").split("/"))
                        or not parts or name.rstrip("/").casefold() in seen):
                    raise ValueError("Unsafe or duplicate archive path")
                seen.add(name.rstrip("/").casefold())
                mode = stat.S_IFMT(item.external_attr >> 16)
                if mode not in (0, stat.S_IFREG, stat.S_IFDIR) or item.flag_bits & 1:
                    raise ValueError("Unsupported archive entry")
                if item.is_dir():
                    if item.file_size:
                        raise ValueError("Invalid directory")
                    continue
                total += item.file_size
                if item.file_size > MAX_ENTRY or total > MAX_TOTAL:
                    raise ValueError("Archive expansion limit exceeded")
                with archive.open(item) as member:
                    data = member.read(MAX_ENTRY + 1)
                if len(data) != item.file_size:
                    raise ValueError("Archive member size mismatch")
                result[name] = hashlib.sha256(data).hexdigest()
    if not result:
        raise ValueError("Empty archive")
    return {"kind": kind, "source": SOURCES[kind], "archiveSha256": actual,
            "files": dict(sorted(result.items()))}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=SOURCES)
    parser.add_argument("archive", type=Path)
    parser.add_argument("approved_sha256")
    args = parser.parse_args()
    print(json.dumps(verify(args.archive, args.kind, args.approved_sha256), indent=2))
