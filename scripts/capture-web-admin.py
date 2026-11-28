#!/usr/bin/env python3
"""Actual Chromium screenshots and admin publishing E2E on disposable CI DB only.
TLS is terminated by an isolated loopback fixture; no production credentials/data.
"""
import base64
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/"screenshots/actual"
def run(*args,env=None):
    return subprocess.run(args,cwd=ROOT,env=env,check=True,capture_output=True,timeout=30).stdout
def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        return sock.getsockname()[1]
def wait_port(proc,number):
    for _ in range(100):
        if proc.poll() is not None:raise RuntimeError("Fixture process stopped")
        try:
            with socket.create_connection(("127.0.0.1",number),timeout=.2):return
        except OSError:time.sleep(.1)
    raise RuntimeError("Fixture unavailable")
def stop(proc):
    if proc is None:return
    proc.terminate()
    try:proc.wait(timeout=5)
    except subprocess.TimeoutExpired:proc.kill();proc.wait()
def eventually(check):
    for _ in range(40):
        if check():return
        time.sleep(.25)
    raise RuntimeError("Expected browser content unavailable")

def main():
    if os.environ.get("FISKAI_DB_DSN")!="mysql:host=127.0.0.1;port=3306;dbname=fiskai_test;charset=utf8mb4":
        raise RuntimeError("Disposable CI database required")
    chrome=shutil.which("google-chrome") or shutil.which("chromium")
    driver=shutil.which("chromedriver")
    if driver is None and os.environ.get("CHROMEWEBDRIVER"):
        candidate=Path(os.environ["CHROMEWEBDRIVER"])/"chromedriver"
        if candidate.is_file():driver=str(candidate)
    if not chrome or not driver:raise RuntimeError("Chrome and matching ChromeDriver required")
    with tempfile.TemporaryDirectory() as work:
        d=Path(work)
        cert=d/"tls.pem";key=d/"tls.key";secret=secrets.token_hex(32)
        user="fixture-operator";password=secrets.token_hex(24)
        auth="Basic "+base64.b64encode((user+":"+password).encode()).decode()
        hash_env=dict(os.environ,FISKAI_FIXTURE_PASSWORD=password)
        password_hash=run("php","-r","echo password_hash(getenv('FISKAI_FIXTURE_PASSWORD'), PASSWORD_BCRYPT);",env=hash_env).decode()
        run("openssl","req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=localhost",
            "-addext","subjectAltName=DNS:localhost","-keyout",str(key),"-out",str(cert))
        router=d/"router.php"
        router.write_text("<?php\nif(!hash_equals("+json.dumps(secret)+",(string)($_SERVER['HTTP_X_TEST_TLS']??''))){http_response_code(403);exit;}\n"+
            "$_SERVER['HTTPS']='on';\n"+
            "$path=parse_url($_SERVER['REQUEST_URI'],PHP_URL_PATH);\n"+
            "$root="+json.dumps(str(ROOT/"web/public"))+";\n"+
            "if($path==='/style.css'){header('Content-Type: text/css');readfile($root.'/style.css');return true;}\n"+
            "if($path==='/')$path='/index.php';\n"+
            "if(!in_array($path,['/index.php','/admin.php','/api.php'],true)){http_response_code(404);return true;}\n"+
            "require $root.$path;\n",encoding="utf-8")
        env=dict(os.environ,FISKAI_ADMIN_ENABLED="1",FISKAI_ADMIN_USERNAME=user,FISKAI_ADMIN_PASSWORD_HASH=password_hash,
            FISKAI_SERVER_RECOVERY_MODE="0")
        php_port=port();driver_port=port();php=None;browser=None;server=None;thread=None;session=None
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def wd(method,path,value=None):
            data=None if value is None else json.dumps(value).encode()
            req=urllib.request.Request(f"http://127.0.0.1:{driver_port}"+path,data=data,method=method,
                headers={"Content-Type":"application/json"})
            with opener.open(req,timeout=30) as reply:
                return json.loads(reply.read(12*1024*1024))["value"]
        try:
            php=subprocess.Popen(["php","-d","session.save_path="+str(d),"-S",f"127.0.0.1:{php_port}",
                "-t",str(ROOT/"web/public"),str(router)],cwd=ROOT,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            wait_port(php,php_port)
            class Proxy(BaseHTTPRequestHandler):
                def log_message(self,*args):pass
                def handle_request(self):
                    if urllib.parse.urlsplit(self.path).path not in {"/","/index.php","/style.css","/api.php","/admin.php"}:
                        self.send_error(404);return
                    if self.headers.get("Transfer-Encoding") is not None:
                        self.send_error(400);return
                    n=int(self.headers.get("Content-Length","0"))
                    if not 0<=n<=1000000:self.send_error(413);return
                    headers={k:self.headers[k] for k in ("Authorization","Cookie","Content-Type") if k in self.headers}
                    headers["X-Test-TLS"]=secret
                    upstream=http.client.HTTPConnection("127.0.0.1",php_port,timeout=15)
                    try:
                        upstream.request(self.command,self.path,self.rfile.read(n) if n else None,headers)
                        reply=upstream.getresponse();body=reply.read(4*1024*1024+1)
                        if len(body)>4*1024*1024:raise RuntimeError("Fixture response too large")
                        self.send_response(reply.status)
                        for name,value in reply.getheaders():
                            if name.lower() in {"content-type","content-security-policy","cache-control","x-content-type-options",
                                "referrer-policy","x-robots-tag","set-cookie","location","www-authenticate","content-disposition"}:
                                self.send_header(name,value)
                        self.send_header("Content-Length",str(len(body)));self.end_headers();self.wfile.write(body)
                    finally:upstream.close()
                def do_GET(self):self.handle_request()
                def do_POST(self):self.handle_request()
            server=ThreadingHTTPServer(("127.0.0.1",0),Proxy)
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(cert,key);server.socket=context.wrap_socket(server.socket,server_side=True)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            base=f"https://localhost:{server.server_port}"
            tls=urllib.request.build_opener(urllib.request.ProxyHandler({}),
                urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(cert))))
            def check_status(req,expected):
                try:
                    with tls.open(req,timeout=15) as result:assert result.status==expected
                except urllib.error.HTTPError as error:assert error.code==expected,(error.code,expected)
            check_status(urllib.request.Request(base+"/admin.php"),401)
            check_status(urllib.request.Request(base+"/admin.php",data=b"csrf=invalid&publication=%7B%7D",
                headers={"Authorization":auth,"Content-Type":"application/x-www-form-urlencoded"}),403)
            browser=subprocess.Popen([driver,f"--port={driver_port}","--allowed-ips=127.0.0.1"],
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            wait_port(browser,driver_port)
            session=wd("POST","/session",{"capabilities":{"alwaysMatch":{"browserName":"chrome","acceptInsecureCerts":True,
                "goog:chromeOptions":{"binary":chrome,"args":["--headless=new","--no-sandbox","--disable-dev-shm-usage",
                    "--window-size=1440,1000","--disable-background-networking"]}}}})["sessionId"]
            route="/session/"+session
            def command(method,path,data=None):return wd(method,route+path,data)
            def script(js,args=None):return command("POST","/execute/sync",{"script":js,"args":args or []})
            def page(path):command("POST","/url",{"url":base+path})
            def body():return script("return document.body.innerText")
            def capture(folder,name):
                png=base64.b64decode(command("GET","/screenshot"),validate=True)
                if len(png)<1000 or not png.startswith(b"\x89PNG\r\n\x1a\n"):raise RuntimeError("Invalid browser screenshot")
                path=OUT/folder;path.mkdir(parents=True,exist_ok=True);(path/name).write_bytes(png)
            command("POST","/goog/cdp/execute",{"cmd":"Network.enable","params":{}})
            command("POST","/goog/cdp/execute",{"cmd":"Network.setExtraHTTPHeaders","params":{"headers":{"Authorization":auth}}})
            page("/admin.php");eventually(lambda:"Administracija cjenika" in body())
            publication={"site":"UI_FIXTURE","kind":"USLUGE","identifier":"12345678903","location":"TEST Zagreb",
                "rows":[{"naziv":"Primjer usluge","maloprodajna_cijena":"12.50","bazna_cijena":"10.00","poseban_oblik_prodaje":False}]}
            script("document.querySelector('#publication').value=arguments[0];document.querySelector('.admin-form').requestSubmit();",
                [json.dumps(publication,ensure_ascii=False)])
            eventually(lambda:"Objavljena je verzija" in body() or "Cjenik je već objavljen" in body())
            assert "UI_FIXTURE" in body()
            capture("admin","01-cjenici.png")
            command("POST","/goog/cdp/execute",{"cmd":"Network.setExtraHTTPHeaders","params":{"headers":{}}})
            page("/");eventually(lambda:"Javni cjenici i arhiva" in body() and "UI_FIXTURE" in body())
            capture("web","01-pocetna.png")
            csv=tls.open(base+"/api.php?action=download&site=UI_FIXTURE&kind=USLUGE&format=csv",timeout=10).read()
            assert b"12.50;NE;10.00" in csv
            print("Actual Chromium public/admin captures; unauthorized access, CSRF, publication and download passed.")
        finally:
            if session:
                try:wd("DELETE","/session/"+session)
                except Exception:pass
            stop(browser)
            if server:
                if thread:server.shutdown();thread.join(timeout=5)
                server.server_close()
            stop(php)

if __name__=="__main__":main()
