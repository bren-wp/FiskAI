#!/usr/bin/env python3
"""Loopback TLS challenge regression with independent synthetic signing and TLS keys."""
import base64
import importlib.util
import json
from pathlib import Path
import ssl
import tempfile
import threading
import urllib.error
import urllib.request

spec=importlib.util.spec_from_file_location("https_witness",Path(__file__).with_name("educ-witness-https.py"))
service=importlib.util.module_from_spec(spec);spec.loader.exec_module(service)
w=service.w
with tempfile.TemporaryDirectory() as work:
    root=Path(work);db=root/"witness.db";key=root/"sign.pem";tls=root/"tls.pem";cert=root/"cert.pem"
    key.write_bytes(w.openssl("genpkey","-algorithm","EC","-pkeyopt","ec_paramgen_curve:prime256v1"));key.chmod(0o600)
    w.openssl("req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=localhost",
              "-addext","subjectAltName=DNS:localhost","-keyout",str(tls),"-out",str(cert))
    tls.chmod(0o600);w.init(db,key)
    subject="a1000000-0000-4000-8000-000000000001"
    history={"protocol":"FISKAI-EDUC-HISTORY-V1","subject":subject,"keyId":"fixture","heads":[w.ZERO,"a"*64]}
    w.attest(db,key,raw=w.canonical(history))
    token="b"*64
    server=service.server(("127.0.0.1",0),db,key,subject,token,cert,tls)
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        url=f"https://localhost:{server.server_port}/v1/latest"
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(cert))))
        def call(nonce,owner=subject,auth=token,status=200):
            req=urllib.request.Request(url,data=w.canonical({"subject":owner,"nonce":nonce}),
                  headers={"Authorization":"Bearer "+auth,"Content-Type":"application/json"})
            try:
                with opener.open(req,timeout=10) as response:
                    assert response.status==status
                    return json.loads(response.read())
            except urllib.error.HTTPError as error:
                assert error.code==status
        public=root/"public.pem";public.write_bytes(w.signing_public(key));sig=root/"sig.der"
        def verify(reply,nonce,head):
            statement=base64.b64decode(reply["statement"],validate=True)
            assert reply["nonce"]==nonce and json.loads(statement)["head"]==head
            sig.write_bytes(base64.b64decode(reply["signature"],validate=True))
            w.openssl("dgst","-sha256","-verify",str(public),"-signature",str(sig),data=b"FISKAI-EDUC-FRESH-V1\n"+nonce.encode()+b"\n"+statement)
            return statement
        nonce="c"*64;reply=call(nonce);statement=verify(reply,nonce,"a"*64)
        try:
            w.openssl("dgst","-sha256","-verify",str(public),"-signature",str(sig),data=b"FISKAI-EDUC-FRESH-V1\n"+b"d"*64+b"\n"+statement)
        except Exception:
            pass
        else:
            raise AssertionError("Replayed signature accepted")
        call(nonce,auth="0"*64,status=401)
        call(nonce,owner="a1000000-0000-4000-8000-000000000002",status=400)
        call("bad",status=400)
        history["heads"].append("e"*64);w.attest(db,key,raw=w.canonical(history))
        verify(call("d"*64),"d"*64,"e"*64)
        print("Witness loopback HTTPS: fresh signature, replay rejection, authorization and updated head passed.")
    finally:
        server.shutdown();thread.join(timeout=10);server.server_close()
