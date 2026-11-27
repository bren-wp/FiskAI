#!/usr/bin/env python3
"""Acquire official CIS archives for human approval, never as trusted runtime contracts."""
import hashlib
import io
import json
import pathlib
import re
import stat
import sys
import urllib.request
import urllib.error
import time
import http.client
import zipfile

BASE = "https://porezna-uprava.gov.hr/UserDocsImages/Fiskalizacija/Tehni%C4%8Dke%20specifikacije/"
SOURCE = "https://porezna.gov.hr/fiskalizacija/gotovinski-racuni/tehnicki-podaci/o/tehnicki-podaci"
NAMES = ("Fiskalizacija-WSDL-EDUC_v1.10.zip", "Fiskalizacija-WSDL-PROD_v1.10.zip")
MAX_ARCHIVE = 16 * 1024 * 1024
MAX_EXPANDED = 32 * 1024 * 1024
MAX_FILE = 4 * 1024 * 1024

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Unexpected redirect in official contract download")

def digest(data):
    return hashlib.sha256(data).hexdigest()

def inspect(data, dest):
    if not 0 < len(data) <= MAX_ARCHIVE:
        raise ValueError("Invalid archive length")
    entries = {}
    seen = set()
    expanded = 0
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if len(archive.infolist()) > 256:
            raise ValueError("Too many archive entries")
        for item in archive.infolist():
            name = item.filename
            parts = name.rstrip("/").split("/")
            if any(not p or p in {".", ".."} or not re.fullmatch(r"[A-Za-z0-9_. -]+", p) for p in parts):
                raise ValueError("Unsafe archive path")
            if name.casefold() in seen or stat.S_ISLNK(item.external_attr >> 16) or item.flag_bits & 1:
                raise ValueError("Duplicate, symbolic link or encrypted archive member")
            seen.add(name.casefold())
            if item.is_dir():
                continue
            expanded += item.file_size
            if item.file_size > MAX_FILE or expanded > MAX_EXPANDED:
                raise ValueError("Archive expanded-size limit exceeded")
            if item.file_size > max(1, item.compress_size) * 500:
                raise ValueError("Suspicious compression ratio")
            # Verify CRC on every member; extract only WSDL/XSD documents.
            payload = archive.read(item)
            if len(payload) != item.file_size:
                raise ValueError("Unexpected archive member length")
            if pathlib.PurePosixPath(name).suffix.lower() not in {".wsdl", ".xsd"}:
                continue
            if b"\x00" in payload or b"<!DOCTYPE" in payload.upper() or b"<!ENTITY" in payload.upper():
                raise ValueError("Unsupported XML encoding or DTD")
            target = dest.joinpath(*parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as stream:
                stream.write(payload)
            entries[name] = digest(payload)
    extensions = {pathlib.PurePosixPath(n).suffix.lower() for n in entries}
    if not {".wsdl", ".xsd"}.issubset(extensions):
        raise ValueError("Official bundle must contain both WSDL and XSD")
    return entries

def main():
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=False)
    opener = urllib.request.build_opener(NoRedirect())
    manifest = {"source": SOURCE, "status": "UNREVIEWED", "archives": {}}
    for name in NAMES:
        url = BASE + name
        req = urllib.request.Request(url, headers={"User-Agent": "FiskAI-Contract-Audit/1.0"})
        for attempt in range(3):
            try:
                with opener.open(req, timeout=30) as response:
                    if response.status != 200 or response.geturl() != url:
                        raise ValueError("Unexpected official source response")
                    data = response.read(MAX_ARCHIVE + 1)
                break
            except (TimeoutError, urllib.error.URLError, http.client.IncompleteRead) as exc:
                if isinstance(exc, urllib.error.HTTPError) and exc.code not in {408, 429, 500, 502, 503, 504}:
                    raise
                # A certificate error or non-transient connection error must not be bypassed.
                if isinstance(exc, urllib.error.URLError) and not isinstance(exc, urllib.error.HTTPError):
                    if not isinstance(exc.reason, (TimeoutError, ConnectionResetError, ConnectionRefusedError)):
                        raise
                if attempt == 2:
                    raise
                print(f"Transient download error for {name}; attempt {attempt+1}/3 failed.", file=sys.stderr)
                time.sleep(2 ** (attempt + 1))
        if len(data) > MAX_ARCHIVE:
            raise ValueError("Contract archive exceeds limit")
        files = inspect(data, out / name.removesuffix(".zip"))
        (out / name).write_bytes(data)
        manifest["archives"][name] = {"url": url, "sha256": digest(data), "files": files}
    (out / "UNREVIEWED-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
