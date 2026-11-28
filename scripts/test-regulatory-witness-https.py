#!/usr/bin/env python3
"""Synthetic loopback integration across the actual PHP HTTPS client and Python witness."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import urllib.request
import urllib.error
import ssl

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("service", root/"scripts/regulatory-witness-https.py")
service = importlib.util.module_from_spec(spec); spec.loader.exec_module(service)
w = service.w

with tempfile.TemporaryDirectory() as work:
    p = Path(work)
    db,key,tls,cert = [p/n for n in ("witness.db","sign.pem","tls.pem","cert.pem")]
    key.write_bytes(w.openssl("genpkey","-algorithm","EC","-pkeyopt","ec_paramgen_curve:prime256v1"))
    key.chmod(0o600)
    w.openssl("req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=localhost",
              "-addext","subjectAltName=DNS:localhost","-keyout",str(tls),"-out",str(cert))
    tls.chmod(0o600)
    pin=w.init(db,key)
    catalog=(root/"server/contracts/legal-rules.json").read_bytes()
    result=w.attest(db,key,catalog=catalog,expected_head=w.ZERO)
    secret=os.urandom(32)
    tokenfile=p/"token"; tokenfile.write_bytes(secret);tokenfile.chmod(0o600)
    public=p/"public.pem";public.write_bytes(w.public_key(key))
    approved=p/"approved.json"
    approved.write_text(json.dumps({"sequence":1,"head":result["head"],"catalogSha256":w.sha(catalog)}))
    tls_pub=w.openssl("pkey","-in",str(tls),"-pubout","-outform","DER")
    tls_pin="sha256//"+base64.b64encode(bytes.fromhex(w.sha(tls_pub))).decode()
    server=service.server(("127.0.0.1",0),db,key,secret.hex(),cert,tls,(1,result["head"]))
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        url=f"https://localhost:{server.server_port}/v1/latest"
        env=dict(os.environ,FISKAI_REGULATORY_WITNESS_URL=url,FISKAI_REGULATORY_PUBLIC_KEY_SHA256=pin,
                 FISKAI_REGULATORY_PUBLIC_KEY_FILE=str(public),FISKAI_REGULATORY_WITNESS_TLS_SPKI=tls_pin,
                 FISKAI_REGULATORY_WITNESS_CA=str(cert),FISKAI_REGULATORY_WITNESS_CA_SHA256=w.sha(cert.read_bytes()),
                 FISKAI_REGULATORY_WITNESS_TOKEN_FILE=str(tokenfile))
        subprocess.run(["php",str(root/"server/tests/RegulatoryRemoteCheckpointTest.php"),str(approved)],env=env,check=True)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(cert))))
        for token,nonce,expected in [("0"*64,"a"*64,401),(secret.hex(),"bad",400)]:
            req=urllib.request.Request(url,data=json.dumps({"nonce":nonce}).encode(),
                headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"})
            try:
                opener.open(req,timeout=10)
                raise AssertionError("Invalid request accepted")
            except urllib.error.HTTPError as error:
                assert error.code==expected
    finally:
        server.shutdown();thread.join(timeout=10);server.server_close()
