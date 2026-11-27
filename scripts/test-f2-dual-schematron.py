#!/usr/bin/env python3
"""Run real pinned EN and HR rules on synthetic XSD-valid diagnostic invoices.

Does not approve the contracts or establish production readiness. An intentionally
incomplete invoice proves both engines actually evaluate rules, not just emit XML.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile

def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

prepare = module("prepare", "prepare-f2-hr-schematron.py")
runner = module("runner", "run-f2-schematron.py")
v = runner.v
PROFILE = "urn:cen.eu:en16931:2017#compliant#urn:mfin.gov.hr:cius-2025:1.0#conformant#urn:mfin.gov.hr:ext-2025:1.0"
COMPILER_BLOBS = {
    "iso_svrl_for_xslt2.xsl": "373270d0055b99d21731224b872c46818f209b8e",
    "iso_schematron_skeleton_for_saxon.xsl": "d18f21626a972d8cde5e010bd92b8b7b9bbd1b14",
}

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def blob(data):
    return hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()

def fixture(profile, code):
    data = v.sample("Invoice").decode()
    data = data.replace("<cbc:ID>TEST-1</cbc:ID>",
                        f"<cbc:CustomizationID>{profile}</cbc:CustomizationID><cbc:ProfileID>P1</cbc:ProfileID><cbc:ID>TEST-1</cbc:ID>")
    data = data.replace("</cbc:IssueDate>", "</cbc:IssueDate><cbc:IssueTime>10:00:00</cbc:IssueTime><cbc:DueDate>2026-12-01</cbc:DueDate><cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode><cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>")
    data = data.replace("<cac:Item/>", f"<cac:Item><cbc:Name>Test</cbc:Name><cac:CommodityClassification><cbc:ItemClassificationCode listID=\"CG\">{code}</cbc:ItemClassificationCode></cac:CommodityClassification></cac:Item>")
    return data.encode()

def findings(report):
    return {f["id"] for f in report["findings"] if f["type"] == "failed-assert"}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", type=Path)
    parser.add_argument("en_xslt", type=Path)
    parser.add_argument("compiler", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    require(not args.output.exists(), "Output directory already exists")
    pins = {}
    for name, expected in COMPILER_BLOBS.items():
        data = runner.read(args.compiler / name, 2 * 1024 * 1024)
        require(blob(data) == expected, "ISO compiler blob mismatch")
        pins[name] = sha(data)
    en_pin = sha(runner.read(args.en_xslt, 8 * 1024 * 1024))
    runner.stylesheet(args.en_xslt, en_pin, en=True)
    expanded = prepare.expand(args.archives)
    compiled = prepare.compile_hr(expanded, prepare.verified_compiler(args.compiler, pins))
    args.output.mkdir(mode=0o700)
    hr = args.output / "hr-compiled-CANDIDATE.xsl"
    hr.write_bytes(compiled)
    hr_pin = sha(compiled)
    results = {}
    with tempfile.TemporaryDirectory() as work:
        for name, profile, code in (
            ("known-profile", PROFILE, "01.11.11"),
            ("bad-profile", PROFILE.replace("ext-2025", "ext[1]2025"), "01.11.11"),
            ("bad-kpd", PROFILE, "99.99.99"),
        ):
            document = Path(work) / (name + ".xml")
            document.write_bytes(fixture(profile, code))
            result = runner.run(args.archives, document, args.en_xslt, en_pin, hr, hr_pin, args.output / name)
            en = findings(result["en16931"])
            croatian = findings(result["hrCius"])
            require("BR-06" in en and "BR-07" in en, "Expected EN seller/buyer name assertions missing")
            require(("HR-BR-5" in croatian) == (name == "bad-profile"), "Wrong HR customization assertion outcome")
            require(("HR-BR-CL-2" in croatian) == (name == "bad-kpd"), "Wrong HR code-list assertion outcome")
            require(result["dispatchAuthorized"] is False and result["officialAcceptance"] is False, "Unexpected authorization")
            results[name] = {"enFailedAssertions": sorted(en), "hrFailedAssertions": sorted(croatian)}
    # A complete positive example must produce zero findings in both actual reports.
    positive = Path(__file__).with_name("fixtures") / "f2-positive-invoice.xml"
    good = runner.run(args.archives, positive, args.en_xslt, en_pin, hr, hr_pin, args.output / "complete-invoice")
    results["complete-invoice"] = {"enFailedAssertions": sorted(findings(good["en16931"])),
                                  "hrFailedAssertions": sorted(findings(good["hrCius"]))}
    # Croatian rules require IssueTime and SellerContact, while the base EN
    # UBL restrictions report these as warnings. Preserve both raw reports.
    # This proves the expected diagnostic outcome, NOT unconditional approval.
    expected_warnings = [
        {"id": "UBL-CR-006", "flag": "warning", "type": "failed-assert"},
        {"id": "UBL-CR-200", "flag": "warning", "type": "failed-assert"},
    ]
    require(good["en16931"]["findings"] == expected_warnings and not good["hrCius"]["findings"],
            "Unexpected complete invoice findings; inspect preserved SVRL")
    results["complete-invoice"]["status"] = "NATIONAL_PROFILE_WARNINGS_REQUIRE_REVIEW"
    results["complete-invoice"]["enFindings"] = good["en16931"]["findings"]
    with tempfile.TemporaryDirectory() as work:
        changed = Path(work) / "wrong-total.xml"
        data = positive.read_bytes()
        altered = data.replace(b'<cbc:PayableAmount currencyID="EUR">12.50</cbc:PayableAmount>',
                               b'<cbc:PayableAmount currencyID="EUR">12.51</cbc:PayableAmount>')
        require(data != altered, "Monetary mutation failed")
        changed.write_bytes(altered)
        bad = runner.run(args.archives, changed, args.en_xslt, en_pin, hr, hr_pin, args.output / "wrong-total")
        require("BR-CO-16" in findings(bad["en16931"]), "Expected payable amount assertion missing")
        results["wrong-total"] = {"enFailedAssertions": sorted(findings(bad["en16931"])),
                                  "hrFailedAssertions": sorted(findings(bad["hrCius"]))}
    summary = {"status": "DUAL_SCHEMATRON_DIAGNOSTIC_REGRESSION", "compilerSha256": pins,
               "enStylesheetSha256": en_pin, "hrStylesheetSha256": hr_pin, "cases": results,
               "officialAcceptance": False, "dispatchAuthorized": False}
    (args.output / "SUMMARY.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))

if __name__ == "__main__":
    main()
