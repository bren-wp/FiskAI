#!/usr/bin/env python3
"""Loopback HTTPS integration test; never contacts a deployed server or CIS."""
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
def run(*args, data=None):
    return subprocess.run(args, cwd=ROOT, input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=True).stdout

def digest(data):
    return hashlib.sha256(data).hexdigest()

def encode(data):
    return json.dumps(data, separators=(",", ":")).encode("utf-8")

def main():
    subject = str(uuid.uuid4())
    device = json.loads(run("php", "server/bin/demo-sync-device.php", "provision", subject))
    token = device["token"]
    with tempfile.TemporaryDirectory() as work:
        d = Path(work)
        key = d / "device.pem"
        run("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(key))
        public = base64.b64encode(run("openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER")).decode()
        run("php", "server/bin/demo-sync-device.php", "bind", device["deviceId"], public)
        cert = d / "tls.pem"
        tlskey = d / "tls.key"
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
            "-keyout", str(tlskey), "-out", str(cert))
        secret = secrets.token_hex(32)
        # Test-only trusted TLS termination marker. It is not part of the deployed app.
        router = d / "router.php"
        router.write_text("<?php\nif (hash_equals(" + json.dumps(secret) +
            ", (string)(\$_SERVER['HTTP_X_TEST_TLS']??''))) \$_SERVER['HTTPS']='on';\n"
            "require " + json.dumps(str(ROOT / "web/public/api.php")) + ";\n")
        # Python does not interpolate PHP variables; remove the literal escape if present.
        router.write_text(router.read_text().replace("\\$", "$"))
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        env = dict(os.environ, FISKAI_DEMO_SYNC_ENABLED="1")
        php = subprocess.Popen(["php", "-S", f"127.0.0.1:{port}", "-t",
            str(ROOT / "web/public"), str(router)], cwd=ROOT, env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        server = None
        thread = None
        try:
            for _ in range(100):
                if php.poll() is not None:
                    raise RuntimeError("PHP fixture exited")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=.2):
                        break
                except OSError:
                    time.sleep(.05)
            else:
                raise RuntimeError("PHP fixture unavailable")

            class Proxy(BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass
                def do_POST(self):
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 <= length <= 65536:
                        self.send_error(413)
                        return
                    headers = {k: self.headers[k] for k in ("Authorization", "Content-Type",
                               "X-FiskAI-Nonce", "X-FiskAI-Signature") if k in self.headers}
                    headers["X-Test-TLS"] = secret
                    upstream = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
                    try:
                        upstream.request("POST", self.path, self.rfile.read(length), headers)
                        reply = upstream.getresponse()
                        body = reply.read(2*1024*1024+1)
                        if len(body) > 2*1024*1024:
                            raise RuntimeError("Response too large")
                        self.send_response(reply.status)
                        self.send_header("Content-Type", reply.getheader("Content-Type", "application/json"))
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                    finally:
                        upstream.close()

            server = ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(cert, tlskey)
            server.socket = context.wrap_socket(server.socket, server_side=True)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"https://localhost:{server.server_port}/api.php?action="
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(cert))))
            def post(action, body, proof=None, expected=200):
                headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
                if proof:
                    headers.update(proof)
                req = urllib.request.Request(base + action, data=body, headers=headers, method="POST")
                try:
                    with opener.open(req, timeout=20) as response:
                        assert response.status == expected
                        return json.loads(response.read())
                except urllib.error.HTTPError as error:
                    assert error.code == expected, f"Unexpected HTTP status {error.code}, expected {expected}"
                    return json.loads(error.read())
            pull = encode({"protocol": 1, "subjectId": subject})
            def sign(action, body):
                challenge = post("demo-sync-challenge", pull)
                assert challenge["subjectId"] == subject and challenge["deviceId"] == device["deviceId"]
                nonce = challenge["nonce"]
                message = "\n".join(["FISKAI-DEMO-PROOF-V1", action, subject, device["deviceId"],
                    nonce, digest(token.encode()), digest(body)]).encode()
                sig = run("openssl", "dgst", "-sha256", "-sign", str(key), data=message)
                return {"X-FiskAI-Nonce": nonce, "X-FiskAI-Signature": base64.b64encode(sig).decode()}
            direct = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            try:
                direct.request("POST", "/api.php?action=demo-sync-challenge", pull,
                    {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
                result = direct.getresponse()
                assert result.status == 403
                result.read()
            finally:
                direct.close()
            post("demo-sync-pull", pull, expected=401)
            event = {"eventId": str(uuid.uuid4()), "receiptId": digest(b"synthetic-receipt"),
                "documentId": str(uuid.uuid4()), "evidenceSha256": digest(b"synthetic-evidence"),
                "eventDate": "2026-12-31", "amountCents": 1250,
                "ruleVersion": "HR-F2-2026-v1", "deadlineDate": "2027-01-20"}
            push = encode({"protocol": 1, "subjectId": subject, "events": [event]})
            proof = sign("demo-sync", push)
            post("demo-sync", push + b" ", proof, expected=401)
            assert post("demo-sync", push, proof)["accepted"] == [event["eventId"]]
            post("demo-sync", push, proof, expected=401)
            assert post("demo-sync", push, sign("demo-sync", push))["accepted"] == [event["eventId"]]
            assert post("demo-sync-pull", pull, sign("demo-sync-pull", pull))["events"] == [event]
            run("php", "server/bin/demo-sync-device.php", "revoke", device["deviceId"])
            post("demo-sync-challenge", pull, expected=401)
            print("Loopback TLS: signed push/pull, exact body binding, replay, idempotency and revoke passed.")
        finally:
            if server is not None:
                if thread is not None:
                    server.shutdown()
                    thread.join(timeout=5)
                server.server_close()
            php.terminate()
            try:
                php.wait(timeout=5)
            except subprocess.TimeoutExpired:
                php.kill()
                php.wait()

if __name__ == "__main__":
    main()
