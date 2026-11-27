#!/usr/bin/env python3
"""Regression against actual user-supplied archive bytes; no fiscal network calls."""
import importlib.util
from pathlib import Path
import shutil
import sys
import tempfile

spec = importlib.util.spec_from_file_location('adapter', Path(__file__).with_name('validate-f2-uploaded.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


def reject(call):
    try:
        call()
    except (ValueError, v.ET.XMLSyntaxError, v.ET.DocumentInvalid):
        return
    raise AssertionError('Invalid input accepted')


root = Path(sys.argv[1])
result = v.inspect(root)
assert result['officialAcceptance'] is False
assert result['dispatchAuthorized'] is False
assert result['schematron']['executed'] is False
assert result['schematron']['hrAssertions'] == 71
assert result['schematron']['codeAssertions'] == 2
assert sum(row['files'] for row in result['archives'].values()) == 26
with tempfile.TemporaryDirectory() as work:
    directory = Path(work)
    for kind in ('Invoice', 'CreditNote'):
        document = directory / (kind + '.xml')
        document.write_bytes(v.sample(kind))
        assert v.inspect(root, document)['document']['kind'] == kind
        document.write_bytes(v.sample(kind).replace(b'<cbc:ID>TEST-1</cbc:ID>', b''))
        reject(lambda: v.inspect(root, document))
    for raw in (b'<!DOCTYPE a SYSTEM "file:///etc/passwd"><a/>', b'<a>\x00</a>', b'\xff'):
        reject(lambda: v.parse_document(raw))
    copied = directory / 'archives'
    shutil.copytree(root, copied)
    changed = copied / next(iter(v.PINS))
    changed.write_bytes(changed.read_bytes() + b'x')
    reject(lambda: v.load(copied))
    files, _ = v.load(root)
    invoice = v.one(files, 'UBL-Invoice-2.1.xsd')
    changed_schema = files[invoice].replace(b'../common/UBL-CommonAggregateComponents-2.1.xsd', b'file:///etc/passwd')
    assert changed_schema != files[invoice]
    files[invoice] = changed_schema
    try:
        v.compile_schemas(files)
    except (ValueError, v.ET.XMLSchemaParseError):
        pass
    else:
        raise AssertionError('External schema reference accepted')
print('Actual supplied XSD diagnostics, positive/negative documents, unsafe XML, changed archive and external import checks passed.')
