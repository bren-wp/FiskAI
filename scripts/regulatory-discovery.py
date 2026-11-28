#!/usr/bin/env python3
"""Discover new Croatian official-gazette ELI identifiers, not legal conclusions.
Official API: https://narodne-novine.nn.hr/nn_api_hr.aspx
Only fixed official HTTPS endpoints; no automatic rule activation.
"""
import argparse
import datetime
import json
import pathlib
import re
import ssl
import time
import urllib.request

BASE = "https://narodne-novine.nn.hr/api/"
ELI = "https://narodne-novine.nn.hr/eli/sluzbeni/"
LIMIT = 1024 * 1024
MAX_EDITIONS = 500
ACT = re.compile(r"[0-9]{1,8}[A-Za-z]?")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Unexpected official API redirect")

def edition_list(data):
    if not isinstance(data,list) or len(data)>MAX_EDITIONS or any(type(n) is not int or not 1<=n<=1000 for n in data) or len(set(data))!=len(data):
        raise ValueError("Invalid official editions response")
    return sorted(data)

def act_list(data):
    if not isinstance(data,list) or len(data)>10000 or any(not isinstance(n,str) or not ACT.fullmatch(n) for n in data) or len(set(data))!=len(data):
        raise ValueError("Invalid official acts response")
    return sorted(data)

class OfficialAPI:
    def __init__(self):
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()))
        self.last=0.0
    def __call__(self,endpoint,payload):
        if endpoint not in {"editions","acts","act"}:
            raise ValueError("Unknown official API endpoint")
        time.sleep(max(0,0.5-(time.monotonic()-self.last)))
        self.last=time.monotonic()
        url=BASE+endpoint
        req=urllib.request.Request(url,data=json.dumps(payload).encode("ascii"),
            headers={"Content-Type":"application/json","Accept":"application/json",
                     "Accept-Encoding":"identity","User-Agent":"FiskAI regulatory discovery/1.0"})
        with self.opener.open(req,timeout=20) as response:
            if response.status!=200 or response.geturl()!=url:
                raise ValueError("Unexpected official API response")
            raw=response.read(LIMIT+1)
        if not raw or len(raw)>LIMIT:
            raise ValueError("Official API response size invalid")
        return json.loads(raw)

def collect(years,api):
    if len(years)!=2 or years[0]+1!=years[1] or not 2026<=years[1]<=9999:
        raise ValueError("Expected current and preceding year")
    inventory={}
    total=0
    for year in years:
        editions=edition_list(api("editions",{"part":"SL","year":year}))
        total+=len(editions)
        if total>MAX_EDITIONS:
            raise ValueError("Edition capacity exceeded; manual review required")
        inventory[str(year)]={}
        for edition in editions:
            acts=act_list(api("acts",{"part":"SL","year":year,"number":edition}))
            inventory[str(year)][str(edition)]=acts
    return {"format":1,"years":years,"editions":inventory}

def identifiers(inventory):
    if not isinstance(inventory,dict) or set(inventory)!={"format","years","editions"} or inventory["format"]!=1:
        raise ValueError("Invalid discovery inventory")
    years=inventory["years"]
    if not isinstance(years,list) or len(years)!=2 or any(type(y) is not int for y in years) or years[0]+1!=years[1]:
        raise ValueError("Invalid discovery years")
    editions=inventory["editions"]
    if not isinstance(editions,dict) or set(editions)!={str(y) for y in years}:
        raise ValueError("Invalid inventory scope")
    result=set()
    count=0
    for year in years:
        rows=editions[str(year)]
        if not isinstance(rows,dict):
            raise ValueError("Invalid editions inventory")
        count+=len(rows)
        if count>MAX_EDITIONS:
            raise ValueError("Edition capacity exceeded")
        for edition,acts in rows.items():
            if not isinstance(edition,str) or not re.fullmatch(r"[1-9][0-9]{0,3}",edition) or int(edition)>1000:
                raise ValueError("Invalid edition identity")
            for act in act_list(acts):
                result.add(f"{year}/{edition}/{act}")
    return result

def changes(current,previous=None):
    observed=identifiers(current)
    if previous is None:
        return {"status":"INITIAL_REVIEW_REQUIRED","new":[],"missing":[],"inventoryCount":len(observed)}
    old=identifiers(previous)
    if previous["years"][-1]>current["years"][-1]:
        raise ValueError("Discovery calendar rollback")
    # Retire only the year outside the rolling window, not missing identifiers in a retained year.
    years={str(y) for y in current["years"]}
    comparable={i for i in old if i.split("/",1)[0] in years}
    new=sorted(observed-old)
    missing=sorted(comparable-observed)
    return {"status":"REVIEW_REQUIRED" if new or missing else "NO_NEW_IDENTIFIERS",
            "new":[ELI+i for i in new],"missing":[ELI+i for i in missing],"inventoryCount":len(observed)}

def classify(title):
    """Review routing, never a legal applicability decision."""
    if not isinstance(title,str) or not 1<=len(title)<=4000:
        raise ValueError("Invalid title")
    normalized=title.casefold()
    groups={
        "fiscalization": ("fiskaliz", "e-račun", "eračun", "elektroničk", "kpd"),
        "tax": ("porez", "pdv", "trošarin", "doprinos"),
        "accounting": ("računovod", "revizij", "financijsk izvješt", "knjigovod"),
        "prices": ("cijen", "potrošač", "trgovin"),
        "business": ("obrt", "trgovačk", "platn", "radn"),
    }
    return [key for key,terms in groups.items() if any(term in normalized for term in terms)] or ["UNCLASSIFIED_REVIEW"]

def metadata(eli,api):
    if not eli.startswith(ELI):
        raise ValueError("Unexpected official identifier")
    parts=eli[len(ELI):].split("/")
    if len(parts)!=3 or not re.fullmatch(r"[0-9]{4}",parts[0]) or not re.fullmatch(r"[1-9][0-9]{0,3}",parts[1]) or not ACT.fullmatch(parts[2]):
        raise ValueError("Malformed official identifier")
    year,edition,act=parts
    data=api("act",{"part":"SL","year":int(year),"number":int(edition),"act_num":act,"format":"JSON-LD"})
    if not isinstance(data,list) or len(data)>10000:
        raise ValueError("Invalid official metadata")
    matches=[row for row in data if isinstance(row,dict) and row.get("@id")==eli+"/hrv"]
    if len(matches)!=1:
        raise ValueError("Missing unique Croatian expression")
    titles=matches[0].get("http://data.europa.eu/eli/ontology#title")
    if not isinstance(titles,list) or len(titles)!=1 or not isinstance(titles[0],dict):
        raise ValueError("Missing unique Croatian title")
    title=titles[0].get("@value")
    return {"eli":eli,"title":title,"reviewTopics":classify(title),"status":"REVIEW_REQUIRED"}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory",type=pathlib.Path,required=True)
    parser.add_argument("--report",type=pathlib.Path,required=True)
    parser.add_argument("--baseline",type=pathlib.Path)
    args=parser.parse_args()
    year=datetime.datetime.now(datetime.timezone.utc).year
    api=OfficialAPI()
    observed=collect([year-1,year],api)
    if args.baseline is not None:
        with args.baseline.open("rb") as stream:
            raw=stream.read(8*LIMIT+1)
        if len(raw)>8*LIMIT:
            raise ValueError("Baseline capacity exceeded")
        previous=json.loads(raw)
    else:
        previous=None
    report=changes(observed,previous)
    report['classified']=[metadata(eli,api) for eli in report['new'][:200]]
    report['unclassifiedPending']=report['new'][200:]
    args.inventory.write_text(json.dumps(observed,sort_keys=True,indent=2)+"\n",encoding="utf-8")
    args.report.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(report["status"]+": "+str(len(report["new"]))+" new identifiers; "+str(len(report["missing"]))+" missing identifiers.")
    if report["status"]!="NO_NEW_IDENTIFIERS":
        print("::warning::Regulatory discovery requires manual review; no legal rules changed.")

if __name__=="__main__":
    main()
