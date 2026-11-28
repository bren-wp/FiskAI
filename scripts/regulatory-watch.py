#!/usr/bin/env python3
"""Read-only change detection; raw response changes require human review."""
import argparse
import hashlib
import json
import pathlib
import ssl
import urllib.request

SOURCES = {
    "fiscalization-law": "https://narodne-novine.nn.hr/clanci/sluzbeni/2025_06_89_1233.html",
    "e-invoice": "https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/eracun",
    "f1-technical": "https://porezna.gov.hr/fiskalizacija/gotovinski-racuni/tehnicki-podaci/o/tehnicki-podaci",
    "f1-rulebook": "https://narodne-novine.nn.hr/clanci/sluzbeni/full/2025_12_153_2278.html",
    "f1-rulebook-amendment-2026": "https://narodne-novine.nn.hr/clanci/sluzbeni/2026_09_97_1181.html",
    "f2-rulebook": "https://narodne-novine.nn.hr/clanci/sluzbeni/2026_01_11_88.html",
    "f2-technical": "https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/fiskalizacija-bezgotovinskih-racuna",
    "accounting-2026": "https://narodne-novine.nn.hr/clanci/sluzbeni/2026_06_59_727.html",
    "vat-2026": "https://narodne-novine.nn.hr/clanci/sluzbeni/2026_03_32_377.html",
    "tax-procedure": "https://narodne-novine.nn.hr/clanci/sluzbeni/2025_12_151_2263.html",
    "corporate-tax": "https://narodne-novine.nn.hr/clanci/sluzbeni/2025_12_151_2261.html",
    "eu-vida-directive": "https://eur-lex.europa.eu/legal-content/HR/TXT/?uri=CELEX%3A32025L0516",
    "eu-vida-regulation": "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32025R0517",
    "eu-vida-implementing-regulation": "https://eur-lex.europa.eu/legal-content/HR/TXT/?uri=CELEX%3A32025R0518",
}
LIMIT = 8 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Redirect requires manual source review")


def compare(observed, baseline):
    return sorted(name for name in set(observed) | set(baseline)
                  if observed.get(name) != baseline.get(name))


def observe():
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    result = {}
    for name, url in SOURCES.items():
        req = urllib.request.Request(url, headers={"User-Agent": "FiskAI regulatory review/1.0", "Accept-Encoding": "identity"})
        with opener.open(req, timeout=30) as response:
            if response.status != 200 or response.geturl() != url:
                raise ValueError("Unexpected source response")
            data = response.read(LIMIT + 1)
            if not data or len(data) > LIMIT:
                raise ValueError("Unexpected source size")
            result[name] = {"url": url, "sha256": hashlib.sha256(data).hexdigest()}
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--baseline", type=pathlib.Path)
    args = parser.parse_args()
    observed = observe()
    args.output.write_text(json.dumps(observed, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    if args.baseline is None:
        print("First observation only: not approved legal evidence.")
        return
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    changed = compare(observed, baseline)
    if changed:
        raise SystemExit("REVIEW_REQUIRED: official source response changed: " + ", ".join(changed))
    print("Source response hashes unchanged; not a legal compliance determination.")


if __name__ == "__main__":
    main()
