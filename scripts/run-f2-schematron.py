#!/usr/bin/env python3
"""Execute separately reviewed, precompiled EN and HR XSLT offline; no authorization."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
from lxml import etree as ET

spec = importlib.util.spec_from_file_location('adapter', Path(__file__).with_name('validate-f2-uploaded.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
EN_BLOB = '16298a68dde999dde512cf514b79c839b1ac8040'
XSL = 'http://www.w3.org/1999/XSL/Transform'
SVRL = 'http://purl.oclc.org/dsdl/svrl'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def read(path, limit):
    require(path.is_file() and not path.is_symlink(), 'Expected regular file')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    require(0 < len(data) <= limit, 'Input size exceeded')
    return data


def parse(data):
    require(b'\x00' not in data and not re.search(br'<!\s*(?:DOCTYPE|ENTITY)\b', data, re.I), 'DTD/entity forbidden')
    data.decode('utf-8', errors='strict')
    parser = ET.XMLParser(resolve_entities=False, load_dtd=False, no_network=True, huge_tree=False)
    parser.resolvers.add(v.LocalOnly({}))
    return ET.fromstring(data, parser)


def stylesheet(path, pin, en=False):
    data = read(path, 8 * 1024 * 1024)
    require(re.fullmatch(r'[0-9a-f]{64}', pin) and hashlib.sha256(data).hexdigest() == pin, 'Stylesheet pin mismatch')
    if en:
        blob = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()
        require(blob == EN_BLOB, 'Unexpected EN16931 v1.3.17 Git blob')
    root = parse(data)
    require(root.tag in (f'{{{XSL}}}stylesheet', f'{{{XSL}}}transform'), 'Expected compiled XSLT')
    for tag in ('include', 'import', 'use-package', 'result-document', 'evaluate', 'source-document'):
        require(not root.findall(f'.//{{{XSL}}}{tag}'), 'External or dynamic XSLT instruction forbidden: ' + tag)
    return ET.tostring(root, encoding='unicode')


def report(data):
    require(0 < len(data) <= 8 * 1024 * 1024, 'Invalid SVRL size')
    root = parse(data)
    require(root.tag == f'{{{SVRL}}}schematron-output', 'Expected SVRL result')
    require(root.findall(f'{{{SVRL}}}active-pattern') and root.findall(f'{{{SVRL}}}fired-rule'), 'No rule execution evidence')
    findings = []
    for node in root.iter():
        if node.tag in (f'{{{SVRL}}}failed-assert', f'{{{SVRL}}}successful-report'):
            findings.append({'id': node.get('id', ''), 'flag': node.get('flag', ''), 'type': ET.QName(node).localname})
    return {'status': 'FINDINGS_REQUIRE_REVIEW' if findings else 'NO_FINDINGS_REPORTED',
            'sha256': hashlib.sha256(data).hexdigest(), 'findings': findings}


def execute(document, en, hr):
    # This runtime must be independently installed and reviewed. Never auto-install or execute a candidate archive.
    from saxonche import PySaxonProcessor
    with PySaxonProcessor(license=False) as proc:
        proc.set_configuration_property('http://saxon.sf.net/feature/allowedProtocols', '')
        proc.set_configuration_property('http://saxon.sf.net/feature/allow-external-functions', 'false')
        xslt = proc.new_xslt30_processor()
        node = proc.parse_xml(xml_text=document)
        reports = []
        for sheet in (en, hr):
            executable = xslt.compile_stylesheet(stylesheet_text=sheet)
            result = executable.transform_to_string(xdm_node=node)
            require(isinstance(result, str), 'Schematron transform failed')
            reports.append(result.encode('utf-8'))
        return proc.version, reports


def run(root, document, en_path, en_pin, hr_path, hr_pin, output):
    files, _ = v.load(root)
    data = read(document, 1048576)
    doc = v.parse_document(data)
    kind = ET.QName(doc).localname
    schemas = v.compile_schemas(files)
    require(kind in schemas, 'Only UBL Invoice/CreditNote supported')
    schemas[kind].assertValid(doc)
    en = stylesheet(en_path, en_pin, en=True)
    hr = stylesheet(hr_path, hr_pin)
    require(not output.exists(), 'Refusing to overwrite report directory')
    engine, results = execute(ET.tostring(doc, encoding='unicode'), en, hr)
    summaries = [report(result) for result in results]
    result = {'status': 'OFFLINE_SCHEMATRON_DIAGNOSTICS_ONLY', 'engine': engine,
              'documentSha256': hashlib.sha256(data).hexdigest(), 'enStylesheetSha256': en_pin,
              'hrStylesheetSha256': hr_pin, 'en16931': summaries[0], 'hrCius': summaries[1],
              'officialAcceptance': False, 'dispatchAuthorized': False}
    output.mkdir(mode=0o700)
    for name, value in zip(('en16931.svrl.xml', 'hr-cius.svrl.xml'), results):
        with (output / name).open('xb') as stream:
            stream.write(value)
    with (output / 'diagnostics.json').open('x', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2)
    return result


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('directory', type=Path)
    cli.add_argument('document', type=Path)
    cli.add_argument('en_xslt', type=Path)
    cli.add_argument('en_sha256')
    cli.add_argument('hr_xslt', type=Path)
    cli.add_argument('hr_sha256')
    cli.add_argument('output', type=Path)
    a = cli.parse_args()
    result = run(a.directory, a.document, a.en_xslt, a.en_sha256, a.hr_xslt, a.hr_sha256, a.output)
    print(json.dumps(result, indent=2))
    if any(result[key]['findings'] for key in ('en16931', 'hrCius')):
        raise SystemExit(2)
