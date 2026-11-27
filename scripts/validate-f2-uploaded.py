#!/usr/bin/env python3
"""Offline XSD diagnostics on the four user-supplied F2 archives (not official approval)."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import zipfile
from urllib.parse import unquote, urlsplit
from lxml import etree as ET

PINS = {
    'validator-2026-03-13.zip': 'd6250b00b1cd385c835e3dd4c3abc71a683e9b890f04f41c0ae1e04f3c3e790c',
    'ubl-2026-04-15.zip': '549ced211d2b463db0e5821ed9e604580140aa1870ee8636d207206d872c8388',
    'efiskalizacija-2026-02-12.zip': '7ca59ff821069acee929fc53f9872670206b831d034f70c27960cb1d2346eebd',
    'eizvjestavanje-2026-02-12.zip': 'a559b9ef7dc413ada4b81cc6ce56030f1efc1e0abf8e7f2712d834080ae79103',
}
BASE = 'file:///fiskai-f2-virtual/'
XSD = 'http://www.w3.org/2001/XMLSchema'
SCH = 'http://purl.oclc.org/dsdl/schematron'
WSDL = 'http://schemas.xmlsoap.org/wsdl/'
SOAP = 'http://schemas.xmlsoap.org/wsdl/soap/'
CBC = 'urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2'
CAC = 'urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def load(root):
    files = {}
    inventory = {}
    for name, pin in PINS.items():
        path = root / name
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 64 * 1024 * 1024, 'Invalid archive')
        with path.open('rb') as stream:
            require(hashlib.file_digest(stream, 'sha256').hexdigest() == pin, 'Uploaded archive hash mismatch: ' + name)
            stream.seek(0)
            with zipfile.ZipFile(stream) as z:
                seen = set()
                total = 0
                require(1 <= len(z.infolist()) <= 2000, 'Invalid archive count')
                for item in z.infolist():
                    raw = item.orig_filename
                    part = raw.rstrip('/')
                    require(part and not raw.startswith('/') and '\\' not in raw and ':' not in raw and
                            not any(ord(c) < 32 for c in raw) and all(p not in ('', '.', '..') for p in part.split('/')) and
                            part.casefold() not in seen and not stat.S_ISLNK(item.external_attr >> 16) and not item.flag_bits & 1,
                            'Unsafe archive member')
                    seen.add(part.casefold())
                    if item.is_dir():
                        require(item.file_size == 0, 'Invalid directory')
                        continue
                    total += item.file_size
                    require(item.file_size <= 16 * 1024 * 1024 and total <= 128 * 1024 * 1024, 'Archive capacity exceeded')
                    data = z.read(item)
                    require(len(data) == item.file_size, 'Invalid member size')
                    files[name + '/' + part] = data
                inventory[name] = {'sha256': pin, 'files': sum(k.startswith(name + '/') for k in files)}
    return files, inventory


class LocalOnly(ET.Resolver):
    def __init__(self, resources):
        self.resources = resources

    def resolve(self, url, public_id, context):
        parsed = urlsplit(url)
        require(parsed.scheme == 'file' and not parsed.netloc and not parsed.query and not parsed.fragment, 'External XML resource blocked')
        path = unquote(parsed.path)
        require(path.startswith('/fiskai-f2-virtual/'), 'Outside XML resource blocked')
        name = path.removeprefix('/fiskai-f2-virtual/')
        require(name in self.resources, 'Unlisted XML resource: ' + name)
        return self.resolve_string(self.resources[name], context, base_url=BASE + name)


def parse_resource(files, name):
    parser = ET.XMLParser(resolve_entities=False, load_dtd=False, no_network=True, huge_tree=False)
    parser.resolvers.add(LocalOnly(files))
    return ET.fromstring(files[name], parser, base_url=BASE + name)


def parse_document(data):
    require(0 < len(data) <= 1048576 and b'\x00' not in data and b'<!' not in data, 'Unsafe document')
    data.decode('utf-8', errors='strict')
    parser = ET.XMLParser(resolve_entities=False, load_dtd=False, no_network=True, huge_tree=False)
    parser.resolvers.add(LocalOnly({}))
    result = ET.fromstring(data, parser)
    require(not result.getroottree().docinfo.doctype, 'DOCTYPE blocked')
    return result


def one(files, suffix):
    found = [name for name in files if name.endswith('/' + suffix)]
    require(len(found) == 1, 'Expected exactly one ' + suffix)
    return found[0]


def compile_schemas(files):
    schemas = {}
    for kind in ('Invoice', 'CreditNote'):
        name = one(files, 'UBL-' + kind + '-2.1.xsd')
        schemas[kind] = ET.XMLSchema(parse_resource(files, name))
    return schemas


def sample(kind):
    # XSD-only synthetic fixture. Intentionally not a legally valid eInvoice.
    return (f'<{kind} xmlns="urn:oasis:names:specification:ubl:schema:xsd:{kind}-2" xmlns:cbc="{CBC}" xmlns:cac="{CAC}">'
            '<cbc:ID>TEST-1</cbc:ID><cbc:IssueDate>2026-11-27</cbc:IssueDate>'
            '<cac:AccountingSupplierParty/><cac:AccountingCustomerParty/>'
            '<cac:LegalMonetaryTotal><cbc:PayableAmount currencyID="EUR">12.50</cbc:PayableAmount></cac:LegalMonetaryTotal>'
            f'<cac:{kind}Line><cbc:ID>1</cbc:ID><cbc:LineExtensionAmount currencyID="EUR">12.50</cbc:LineExtensionAmount><cac:Item/></cac:{kind}Line></{kind}>').encode()


def inspect(root, invoice=None):
    files, inventory = load(root)
    schemas = compile_schemas(files)
    for kind, schema in schemas.items():
        schema.assertValid(parse_document(sample(kind)))
        require(not schema.validate(parse_document(sample(kind).replace(b'<cbc:ID>TEST-1</cbc:ID>', b''))), 'Invalid XSD fixture accepted')
    sch = parse_resource(files, one(files, 'HR-CIUS-EXT-EN16931-UBL.sch'))
    includes = sch.findall(f'.//{{{SCH}}}include')
    require(len(includes) == 1 and includes[0].get('href') == 'codelist/HR-CIUS-EXT-EN16931-UBL-codes.sch', 'Unexpected Schematron includes')
    codes = parse_resource(files, one(files, 'HR-CIUS-EXT-EN16931-UBL-codes.sch'))
    services = {}
    for service in ('eFiskalizacija', 'eIzvjestavanje'):
        name = one(files, service + 'Schema.xsd')
        schema = parse_resource(files, name)
        wsdl = parse_resource(files, one(files, service + 'Service.wsdl'))
        imports = schema.findall(f'{{{XSD}}}import')
        require(len(imports) == 1 and imports[0].get('schemaLocation') == 'xmldsig-core-schema.xsd' and imports[0].get('namespace') == 'http://www.w3.org/2000/09/xmldsig#', 'Unexpected service dependency')
        # Diagnostic only: supplied UBL contains a pinned XMLDSig copy. Do not silently approve it as the service dependency.
        resources = dict(files)
        resources[str(PurePosixPath(name).parent / 'xmldsig-core-schema.xsd')] = files[one(files, 'UBL-xmldsig-core-schema-2.1.xsd')]
        compiled = ET.XMLSchema(parse_resource(resources, name))
        for element in schema.findall(f'{{{XSD}}}element'):
            invalid = ET.Element(f'{{{schema.get("targetNamespace")}}}{element.get("name")}')
            require(not compiled.validate(invalid), 'Empty service message accepted')
        services[service] = {'diagnosticSchemaCompiled': True, 'missingOriginalDependency': 'xmldsig-core-schema.xsd',
            'diagnosticDependencySha256': hashlib.sha256(resources[str(PurePosixPath(name).parent / 'xmldsig-core-schema.xsd')]).hexdigest(),
            'operations': [e.get('name') for e in wsdl.findall(f'.//{{{WSDL}}}portType/{{{WSDL}}}operation')],
            'addresses': [e.get('location') for e in wsdl.findall(f'.//{{{SOAP}}}address')],
            'rootElements': [e.get('name') for e in schema.findall(f'{{{XSD}}}element')]}
    result = {'status': 'USER_SUPPLIED_XSD_DIAGNOSTICS_ONLY', 'archives': inventory,
              'ubl': {'Invoice': 'XSD_PASS_POSITIVE_AND_NEGATIVE', 'CreditNote': 'XSD_PASS_POSITIVE_AND_NEGATIVE'},
              'schematron': {'queryBinding': sch.get('queryBinding'), 'hrAssertions': len(sch.findall(f'.//{{{SCH}}}assert')),
                  'codeAssertions': len(codes.findall(f'.//{{{SCH}}}assert')), 'executed': False},
              'services': services, 'officialAcceptance': False, 'dispatchAuthorized': False}
    if invoice is not None:
        with invoice.open('rb') as stream:
            data = stream.read(1048577)
        doc = parse_document(data)
        kind = ET.QName(doc).localname
        require(kind in schemas, 'Only UBL Invoice or CreditNote supported')
        schemas[kind].assertValid(doc)
        result['document'] = {'sha256': hashlib.sha256(data).hexdigest(), 'kind': kind, 'status': 'XSD_ONLY'}
    return result


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('directory', type=Path)
    cli.add_argument('--document', type=Path)
    args = cli.parse_args()
    print(json.dumps(inspect(args.directory, args.document), indent=2))
