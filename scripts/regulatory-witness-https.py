#!/usr/bin/env python3
"""Read-only, regulatory HTTPS witness endpoint. Keep signing/database files off application host.
Write/record stays an independent operator CLI operation. Deploy behind a network access policy.
"""
import argparse
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import json
from pathlib import Path
import ssl

spec = importlib.util.spec_from_file_location("witness", Path(__file__).with_name("regulatory-witness.py"))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)

def server(address, database, key, token, tls_cert, tls_key, floor):
    w.require(floor is not None, "Independent checkpoint floor required for HTTPS witness")
    w.require(w.HEX.fullmatch(token), "Invalid authorized scope")
    w.private_file(database)
    w.private_file(tls_key)
    w.public_key(key)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def setup(self):
            self.request.settimeout(8)
            super().setup()
        def answer(self, status, value):
            data = json.dumps(value, separators=(",", ":")).encode("ascii")
            self.send_response(status)
            self.send_header("Content-Type","application/json")
            self.send_header("Cache-Control","no-store")
            self.send_header("Content-Length",str(len(data)))
            self.send_header("Connection","close")
            self.end_headers()
            self.wfile.write(data)
        def do_POST(self):
            self.close_connection = True
            if self.path != "/v1/latest":
                self.answer(404,{"error":"unavailable"});return
            supplied = self.headers.get("Authorization","")
            if not hmac.compare_digest(supplied.encode("utf-8"), ("Bearer "+token).encode("ascii")):
                self.answer(401,{"error":"unauthorized"});return
            if self.headers.get("Transfer-Encoding") is not None or len(self.headers.get_all("Content-Length",[])) != 1:
                self.answer(400,{"error":"invalid request"});return
            try:
                size=int(self.headers.get("Content-Length","0"))
                w.require(0<size<=512 and self.headers.get_content_type()=="application/json","Invalid request")
                request=json.loads(self.rfile.read(size))
                w.require(isinstance(request,dict) and set(request)=={"nonce"},
                          "Invalid scope")
                w.require(isinstance(request["nonce"],str) and w.HEX.fullmatch(request["nonce"]),"Invalid challenge")
            except (ValueError,TypeError):
                self.answer(400,{"error":"invalid request"});return
            try:
                result=w.attest(database,key,nonce=request["nonce"],floor=floor)
            except Exception:
                self.answer(503,{"error":"unavailable"});return
            self.answer(200,result)
    class WitnessServer(HTTPServer):
        def get_request(self):
            sock, addr=super().get_request()
            sock.settimeout(8)
            return sock,addr
    httpd=WitnessServer(address,Handler)
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version=ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(str(tls_cert),str(tls_key))
    httpd.socket=context.wrap_socket(httpd.socket,server_side=True,do_handshake_on_connect=False)
    return httpd

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind",default="127.0.0.1")
    parser.add_argument("--port",type=int,default=9443)
    parser.add_argument("--database",required=True,type=Path)
    parser.add_argument("--key",required=True,type=Path)
    parser.add_argument("--token-file",required=True,type=Path)
    parser.add_argument("--tls-cert",required=True,type=Path)
    parser.add_argument("--tls-key",required=True,type=Path)
    parser.add_argument("--floor-sequence",required=True,type=int)
    parser.add_argument("--floor-head",required=True)
    args=parser.parse_args()
    w.private_file(args.token_file)
    with args.token_file.open("rb") as f:
        secret=f.read(33)
    w.require(len(secret)==32,"Expected 32-byte token file")
    token=secret.hex()
    with server((args.bind,args.port),args.database,args.key,token,args.tls_cert,args.tls_key,(args.floor_sequence,args.floor_head)) as httpd:
        httpd.serve_forever()

if __name__=="__main__":
    main()
