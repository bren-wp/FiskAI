#!/usr/bin/env python3
"""Verify exactly the same synthetic public bytes as PHP and Android."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
v=json.loads((root/"app/src/androidTest/assets/regulatory-vector.json").read_text())
assert v["fixture"]=="SYNTHETIC_TEST_ONLY"
decode=lambda s:base64.b64decode(s,validate=True)
sha=lambda b:hashlib.sha256(b).hexdigest()
catalogs=list(map(decode,v["catalogs"]))
head="0"*64
history=b"FISKAI-REGULATORY-HISTORY-V1\n"
for seq,catalog in enumerate(catalogs,1):
    line=f"{seq}|{head}|{sha(catalog)}\n".encode()
    history+=line
    head=sha(line)
assert history==decode(v["history"])
assert head==v["head"] and sha(catalogs[-1])==v["catalogSha256"]
statement=f'{v["sequence"]}|{head}|{v["catalogSha256"]}\n'.encode()
assert statement==decode(v["statement"])
with tempfile.TemporaryDirectory() as tmp:
    key=Path(tmp)/"public.pem";sig=Path(tmp)/"signature.der"
    public=decode(v["publicKey"]);key.write_bytes(public)
    assert sha(public)==v["publicKeySha256"]
    def verify(data,signature,expected):
        sig.write_bytes(decode(signature))
        result=subprocess.run(["openssl","dgst","-sha256","-verify",str(key),"-signature",str(sig)],input=data,capture_output=True)
        assert (result.returncode==0)==expected
    verify(history,v["historySignature"],True)
    verify(b"FISKAI-REGULATORY-FRESH-V1\n"+v["nonce"].encode()+b"\n"+statement,v["signature"],True)
    verify(b"FISKAI-REGULATORY-FRESH-V1\n"+b"b"*64+b"\n"+statement,v["signature"],False)
print("Shared public regulatory fixture verified in Python/OpenSSL.")
