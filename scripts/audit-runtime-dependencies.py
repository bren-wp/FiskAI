#!/usr/bin/env python3
"""Known-vulnerability lookup over the resolved Android runtime SBOM, not a full security audit."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import ssl
import urllib.request

URL="https://api.osv.dev/v1/querybatch"
MAX=8*1024*1024
PURL=re.compile(r"pkg:maven/[A-Za-z0-9._%+-]+/[A-Za-z0-9._%+-]+@[A-Za-z0-9._%+-]+")
ID=re.compile(r"[A-Za-z0-9_.:-]{1,200}")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        raise ValueError("Unexpected OSV redirect")

def packages(document):
    if document.get("bomFormat")!="CycloneDX":
        raise ValueError("Expected CycloneDX SBOM")
    items=document.get("components")
    if not isinstance(items,list) or not 1<=len(items)<=5000:
        raise ValueError("Empty or excessive dependency inventory")
    result=[]
    for component in items:
        purl=component.get("purl")
        if not isinstance(purl,str) or not PURL.fullmatch(purl):
            raise ValueError("Unrecognized runtime package")
        result.append(purl)
    if len(set(result))!=len(result):
        raise ValueError("Duplicate dependency inventory")
    return sorted(result)

def request(queries):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    req=urllib.request.Request(URL,data=json.dumps({"queries":queries}).encode("utf-8"),
        headers={"Content-Type":"application/json","Accept":"application/json","Accept-Encoding":"identity"})
    with opener.open(req,timeout=45) as response:
        if response.status!=200 or response.geturl()!=URL:
            raise ValueError("Unexpected OSV response")
        raw=response.read(MAX+1)
    if not raw or len(raw)>MAX:
        raise ValueError("OSV response capacity exceeded")
    return json.loads(raw)

def scan(purls,query=request):
    result={purl:set() for purl in purls}
    for offset in range(0,len(purls),100):
        pending=[(purl,None) for purl in purls[offset:offset+100]]
        seen=set()
        for _ in range(20):
            if not pending:break
            queries=[dict({"package":{"purl":purl}},**({"page_token":token} if token else {})) for purl,token in pending]
            rows=query(queries).get("results")
            if not isinstance(rows,list) or len(rows)!=len(pending):
                raise ValueError("Incomplete OSV response")
            next_pending=[]
            for (purl,_),row in zip(pending,rows):
                if not isinstance(row,dict) or set(row)-{"vulns","next_page_token"}:
                    raise ValueError("Unexpected OSV result")
                vulns=row.get("vulns",[])
                if not isinstance(vulns,list):
                    raise ValueError("Invalid vulnerability list")
                for finding in vulns:
                    ident=finding.get("id")
                    if not isinstance(ident,str) or not ID.fullmatch(ident):
                        raise ValueError("Invalid advisory identifier")
                    result[purl].add(ident)
                token=row.get("next_page_token")
                if token:
                    if not isinstance(token,str) or len(token)>4096 or (purl,token) in seen:
                        raise ValueError("Invalid or repeated OSV page")
                    seen.add((purl,token))
                    next_pending.append((purl,token))
            pending=next_pending
        if pending:
            raise ValueError("Incomplete paginated OSV response")
    return [{"purl":purl,"advisories":sorted(result[purl])} for purl in sorted(result)]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sbom",type=pathlib.Path)
    parser.add_argument("report",type=pathlib.Path)
    args=parser.parse_args()
    with args.sbom.open("rb") as stream: raw=stream.read(MAX+1)
    if len(raw)>MAX: raise ValueError("SBOM capacity exceeded")
    purls=packages(json.loads(raw))
    result=scan(purls)
    count=sum(bool(row["advisories"]) for row in result)
    report={"scope":"Android resolved runtime dependencies only","source":URL,
        "observedAt":datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "sbomSha256":hashlib.sha256(raw).hexdigest(),"packageCount":len(result),
        "affectedPackageCount":count,"packages":result}
    args.report.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(f"OSV: {len(result)} runtime packages queried; {count} with matching advisory IDs.")
    if count: raise SystemExit("Known advisory matches require review; release blocked.")

if __name__=="__main__":main()
