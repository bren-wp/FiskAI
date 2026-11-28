#!/usr/bin/env python3
"""Read-only Cellar discovery using the official act-by-act publication date predicate."""
import argparse
import datetime
import json
import pathlib
import re
import ssl
import urllib.parse
import urllib.request

ENDPOINT="https://publications.europa.eu/webapi/rdf/sparql"
LIMIT=4*1024*1024
MAX_ROWS=5000
OJ=re.compile(r"https?://publications\.europa\.eu/resource/oj/L_[0-9]{9}(?:R\([0-9]{2}\))?")
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        raise ValueError("Unexpected Cellar redirect")

def query(start,end):
    if not isinstance(start,datetime.date) or not isinstance(end,datetime.date) or not 0<=(end-start).days<=31:
        raise ValueError("Invalid bounded discovery interval")
    return f"""PREFIX cdm: <http://publications.europa.eu/ontology/cdm#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
SELECT DISTINCT ?act ?published WHERE {{
 ?work owl:sameAs ?act .
 ?work cdm:official-journal-act_date_publication ?published .
 FILTER (?published >= "{start.isoformat()}"^^xsd:date && ?published <= "{end.isoformat()}"^^xsd:date)
 FILTER (regex(str(?act), "/resource/oj/L_[0-9]"))
}} ORDER BY ?published ?act LIMIT {MAX_ROWS+1}"""

def decode(data,start,end):
    rows=data["results"]["bindings"]
    if not isinstance(rows,list) or len(rows)>MAX_ROWS:
        raise ValueError("Cellar result truncated or invalid")
    observed={}
    for row in rows:
        act=row["act"]; published=row["published"]
        if act["type"]!="uri" or not OJ.fullmatch(act["value"]) or published["type"] not in {"literal","typed-literal"}:
            raise ValueError("Unexpected official identifier")
        date=datetime.date.fromisoformat(published["value"])
        if not start<=date<=end:
            raise ValueError("Publication outside requested range")
        key=act["value"].split("/")[-1]
        record={"oj":key,"published":date.isoformat(),"status":"REVIEW_REQUIRED"}
        if key in observed and observed[key]!=record:
            raise ValueError("Inconsistent official publication")
        observed[key]=record
    return [observed[k] for k in sorted(observed)]

def fetch(start,end):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    payload=urllib.parse.urlencode({"query":query(start,end),"format":"application/sparql-results+json"}).encode("ascii")
    req=urllib.request.Request(ENDPOINT,data=payload,headers={"Accept":"application/sparql-results+json",
        "Content-Type":"application/x-www-form-urlencoded","Accept-Encoding":"identity","User-Agent":"FiskAI regulatory discovery/1.0"})
    with opener.open(req,timeout=60) as response:
        if response.status!=200 or response.geturl()!=ENDPOINT:
            raise ValueError("Unexpected Cellar response")
        raw=response.read(LIMIT+1)
    if not raw or len(raw)>LIMIT:
        raise ValueError("Cellar response capacity exceeded")
    return decode(json.loads(raw),start,end)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=pathlib.Path,required=True)
    args=parser.parse_args()
    end=datetime.datetime.now(datetime.timezone.utc).date()
    start=end-datetime.timedelta(days=13)
    rows=fetch(start,end)
    if not rows:
        raise ValueError("No L-series acts in rolling window; review endpoint")
    result={"format":1,"from":start.isoformat(),"until":end.isoformat(),"acts":rows,"status":"REVIEW_REQUIRED"}
    args.output.write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print("EU L-series official identifiers for review:",len(rows))

if __name__=="__main__":
    main()
