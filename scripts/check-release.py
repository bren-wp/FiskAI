#!/usr/bin/env python3
"""Fail closed before publishing a technical test release. Visual acceptance is manual."""
import hashlib
import pathlib
import re
import sys
import zipfile

REQUIRED = {
    "FiskAI.apk", "demo.apk", "FiskAI-unsigned.aab", "FiskAI-source.zip",
    "FiskAI-web.zip", "FiskAI-server.zip", "FiskAI-brending.zip",
    "FiskAI-database.zip", "FiskAI-screenshots.zip", "FiskAI-android.cdx.json", "FiskAI-room-schema.json",
    "INSTALLATION.md", "CHANGELOG.md", "THIRD_PARTY.md",
}

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def validate(dist):
    names = {path.name for path in dist.iterdir() if path.is_file()}
    if not REQUIRED.issubset(names):
        raise ValueError("Missing release files: " + ", ".join(sorted(REQUIRED - names)))
    manifest = {}
    for line in (dist / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        if not match or match[2] in manifest:
            raise ValueError("Invalid checksum manifest")
        manifest[match[2]] = match[1]
    if set(manifest) != names - {"SHA256SUMS.txt"}:
        raise ValueError("Checksum manifest does not cover every release file")
    for name, expected in manifest.items():
        path = dist / name
        if not path.stat().st_size or digest(path) != expected:
            raise ValueError("Invalid artifact: " + name)
    for name in sorted(REQUIRED):
        if name.endswith((".zip", ".apk", ".aab")):
            with zipfile.ZipFile(dist / name) as archive:
                if not any(not i.is_dir() for i in archive.infolist()) or archive.testzip() is not None:
                    raise ValueError("Empty or damaged archive: " + name)
    with zipfile.ZipFile(dist / "FiskAI-brending.zip") as archive:
        files = set(archive.namelist())
        for package in ("01", "02"):
            prefix = "FiskAI/brending/originals/" + package + "/"
            if not any(n.startswith(prefix) and n.lower().endswith(".png") for n in files):
                raise ValueError("Original PNG package missing: " + package)
        for line in archive.read("FiskAI/brending/package-02.sha256").decode("utf-8").splitlines():
            if not line.strip():
                continue
            expected, name = line.split("  ", 1)
            if name == "original ZIP":
                continue
            with archive.open("FiskAI/brending/originals/02/" + name) as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
                    raise ValueError("Original branding checksum mismatch: " + name)
    with zipfile.ZipFile(dist / "FiskAI-screenshots.zip") as archive:
        names = archive.namelist()
        for platform in ("android", "web", "admin"):
            if not any(n.startswith(platform + "/") and n.lower().endswith(".png") for n in names):
                raise ValueError("Actual screenshots missing: " + platform)
    print("Release artifact checks passed; visual and legal acceptance are separate.")

if __name__ == "__main__":
    try:
        validate(pathlib.Path(sys.argv[1]))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        sys.exit(str(exc))
