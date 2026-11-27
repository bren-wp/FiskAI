#!/usr/bin/env python3
"""Check exact retrieved contract bytes against independently inspected artifact 15181221845.
This is an offline contract test, not approval of any production endpoint."""
import hashlib
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
pins = json.loads(pathlib.Path("server/contracts/cis-v1.10.sha256.json").read_text())
def check(path, expected):
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError(f"Contract digest mismatch: {path.name}")
for archive, expected in pins.items():
    check(root / archive, expected["sha256"])
    base = root / archive.removesuffix(".zip")
    for relative, digest in expected.items():
        if relative == "sha256":
            continue
        paths = [p for p in base.rglob(pathlib.Path(relative).name) if p.as_posix().endswith("/" + relative)]
        if len(paths) != 1:
            raise ValueError("Missing or ambiguous contract")
        check(paths[0], digest)
    wsdl = next(base.rglob("FiskalizacijaService.wsdl"))
    addresses = [node.get("location") for node in ET.parse(wsdl).iter() if node.tag.endswith("}address")]
    if addresses != ["https://cistest.apis-it.hr:8449/FiskalizacijaServiceTest"]:
        raise ValueError("WSDL address changed; operator review required")
    print(f"{archive}: exact pinned bytes; test URL in WSDL")
