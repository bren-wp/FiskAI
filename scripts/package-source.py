#!/usr/bin/env python3
"""Package tracked source trees, never local credentials or build caches."""
import pathlib
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
def main():
    out = pathlib.Path(sys.argv[1]).resolve()
    out.mkdir(parents=True, exist_ok=True)
    for name, paths in {
        "source": [],
        "web": ["web"],
        "server": ["server"],
        "brending": ["brending"],
        "database": ["server/database"],
    }.items():
        target = out / f"FiskAI-{name}.zip"
        cmd = ["git", "archive", "--format=zip", "--prefix=FiskAI/", "HEAD"]
        if paths:
            cmd.extend(["--", *paths])
        with target.open("wb") as stream:
            subprocess.run(cmd, cwd=ROOT, stdout=stream, check=True)
        with zipfile.ZipFile(target) as archive:
            if not any(not i.is_dir() for i in archive.infolist()) or archive.testzip() is not None:
                raise RuntimeError(f"Invalid or empty package: {target.name}")
        print(target.name)
if __name__ == "__main__":
    main()
