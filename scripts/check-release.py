#!/usr/bin/env python3
"""Fail closed before publishing a technical test release. Visual acceptance is manual."""
import hashlib
import pathlib
import re
import stat
import sys
import zipfile

REQUIRED = {
    "FiskAI.apk", "demo.apk", "FiskAI-unsigned.aab", "FiskAI-source.zip",
    "FiskAI-web.zip", "FiskAI-server.zip", "FiskAI-brending.zip",
    "FiskAI-database.zip", "FiskAI-screenshots.zip", "FiskAI-android.cdx.json", "FiskAI-room-schema.json",
    "INSTALLATION.md", "UPGRADE.md", "CHANGELOG.md", "THIRD_PARTY.md",
}

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def safe_archive(archive):
    seen = set()
    for entry in archive.infolist():
        name = entry.filename.rstrip("/")
        parts = name.split("/")
        if (not name or name.startswith("/") or "\\" in name or ":" in name
                or any(part in ("", ".", "..") for part in parts)
                or name in seen or stat.S_ISLNK(entry.external_attr >> 16)):
            raise ValueError("Unsafe or duplicate archive entry: " + entry.filename)
        seen.add(name)
    if not any(not i.is_dir() for i in archive.infolist()) or archive.testzip() is not None:
        raise ValueError("Empty or damaged archive")


def verify_branding(archive, package):
    prefix = "FiskAI/brending/originals/" + package + "/"
    files = {i.filename[len(prefix):] for i in archive.infolist()
             if not i.is_dir() and i.filename.startswith(prefix)}
    if not any(n.lower().endswith(".png") for n in files):
        raise ValueError("Original PNG package missing: " + package)
    expected_files = {}
    original_zip = False
    manifest = "FiskAI/brending/package-" + package + ".sha256"
    for line in archive.read(manifest).decode("utf-8").splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            raise ValueError("Invalid branding manifest: " + package)
        expected, name = match.groups()
        if name == "original ZIP":
            if original_zip:
                raise ValueError("Duplicate original ZIP checksum")
            original_zip = True
            continue
        if name in expected_files or name not in files:
            raise ValueError("Unexpected or duplicate branding file: " + name)
        expected_files[name] = expected
    if not original_zip or set(expected_files) != files:
        raise ValueError("Incomplete branding manifest: " + package)
    for name, expected in expected_files.items():
        with archive.open(prefix + name) as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
                raise ValueError("Original branding checksum mismatch: " + name)


def validate(dist):
    entries = list(dist.iterdir())
    if any(path.is_symlink() or not path.is_file() for path in entries):
        raise ValueError("Unexpected non-regular release artifact")
    names = {path.name for path in entries}
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
                safe_archive(archive)
    with zipfile.ZipFile(dist / "FiskAI-brending.zip") as archive:
        for package in ("01", "02"):
            verify_branding(archive, package)
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
