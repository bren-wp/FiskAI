#!/usr/bin/env python3
"""Expand the exact supplied HR Schematron and optionally compile via a reviewed ISO XSLT2 compiler."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile
from lxml import etree as ET

spec = importlib.util.spec_from_file_location('adapter', Path(__file__).with_name('validate-f2-uploaded.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
SCH = v.SCH
XSL = 'http://www.w3.org/1999/XSL/Transform'
COMPILER = ('iso_svrl_for_xslt2.xsl', 'iso_schematron_skeleton_for_saxon.xsl')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def expand(root):
    files, _ = v.load(root)
    source = v.one(files, 'HR-CIUS-EXT-EN16931-UBL.sch')
    codes = v.one(files, 'HR-CIUS-EXT-EN16931-UBL-codes.sch')
    schema = v.parse_resource(files, source)
    pattern = v.parse_resource(files, codes)
    require(schema.tag == f'{{{SCH}}}schema' and schema.get('queryBinding') == 'xslt2', 'Unexpected HR schema binding')
    require(pattern.tag == f'{{{SCH}}}pattern' and pattern.get('id') == 'HRCodesmodel', 'Unexpected code pattern')
    includes = schema.findall(f'.//{{{SCH}}}include')
    require(len(includes) == 1 and includes[0].getparent() is schema and
            includes[0].get('href') == 'codelist/HR-CIUS-EXT-EN16931-UBL-codes.sch', 'Unexpected include structure')
    require(not pattern.findall(f'.//{{{SCH}}}include'), 'Nested includes forbidden')
    schema.replace(includes[0], pattern)
    for tag in ('include', 'extends', 'phase'):
        require(not schema.findall(f'.//{{{SCH}}}{tag}'), 'Unsupported expansion construct: ' + tag)
    require(not schema.xpath('//*[@abstract or @is-a]'), 'Abstract schema requires independent compilation review')
    require(len(schema.findall(f'.//{{{SCH}}}assert')) == 73, 'Assertion count mismatch')
    require(len(schema.findall(f'.//{{{XSL}}}function')) > 0, 'Lost custom functions')
    return ET.tostring(schema, encoding='UTF-8', xml_declaration=True)


def verified_compiler(directory, pins):
    require(set(pins) == set(COMPILER), 'Exactly two independently reviewed compiler pins required')
    result = {}
    for name in COMPILER:
        path = directory / name
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 2 * 1024 * 1024, 'Invalid compiler file')
        data = path.read_bytes()
        require(isinstance(pins[name], str) and re.fullmatch(r'[0-9a-f]{64}', pins[name]) and
                hashlib.sha256(data).hexdigest() == pins[name], 'Compiler pin mismatch')
        require(not re.search(br'<!\s*(?:DOCTYPE|ENTITY)\b', data, re.I) and b'\x00' not in data, 'Compiler DTD forbidden')
        parser = ET.XMLParser(resolve_entities=False, load_dtd=False, no_network=True)
        parser.resolvers.add(v.LocalOnly({}))
        root = ET.fromstring(data, parser)
        require(root.tag in (f'{{{XSL}}}stylesheet', f'{{{XSL}}}transform'), 'Invalid compiler root')
        imports = [n.get('href') for n in root.findall(f'.//{{{XSL}}}import') + root.findall(f'.//{{{XSL}}}include')]
        expected = [COMPILER[1]] if name == COMPILER[0] else []
        require(imports == expected, 'Unexpected compiler dependencies')
        result[name] = data
    return result


def compile_hr(expanded, compiler):
    # Compile only trusted pinned source/compilers, in a separate offline environment without credentials.
    # Unlike validation, this stage allows the compiler's reviewed local import.
    from saxonche import PySaxonProcessor
    with tempfile.TemporaryDirectory() as work:
        directory = Path(work)
        for name, data in compiler.items():
            (directory / name).write_bytes(data)
        with PySaxonProcessor(license=False) as proc:
            proc.set_configuration_property('http://saxon.sf.net/feature/allowedProtocols', 'file')
            proc.set_configuration_property('http://saxon.sf.net/feature/allow-external-functions', 'false')
            xslt = proc.new_xslt30_processor()
            executable = xslt.compile_stylesheet(stylesheet_file=str(directory / COMPILER[0]))
            executable.set_parameter('allow-foreign', proc.make_string_value('true'))
            node = proc.parse_xml(xml_text=expanded.decode('utf-8'))
            output = executable.transform_to_string(xdm_node=node)
            require(isinstance(output, str), 'Schematron compilation failed')
            data = output.encode('utf-8')
    require(0 < len(data) <= 8 * 1024 * 1024, 'Invalid compiled stylesheet size')
    # The compiler output is a candidate, not an accepted validator. Verify independently before execution.
    root = ET.fromstring(data, ET.XMLParser(resolve_entities=False, load_dtd=False, no_network=True))
    require(root.tag in (f'{{{XSL}}}stylesheet', f'{{{XSL}}}transform') and root.findall(f'.//{{{XSL}}}function'), 'Missing compiled stylesheet/functions')
    return data


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('directory', type=Path)
    cli.add_argument('output', type=Path)
    cli.add_argument('--compiler-dir', type=Path)
    cli.add_argument('--compiler-pins', type=Path)
    args = cli.parse_args()
    require((args.compiler_dir is None) == (args.compiler_pins is None), 'Compiler directory and reviewed pins must be supplied together')
    expanded = expand(args.directory)
    if args.compiler_dir:
        require(args.compiler_pins.is_file() and args.compiler_pins.stat().st_size <= 16384, 'Invalid compiler manifest')
        pins = json.loads(args.compiler_pins.read_text(encoding='utf-8'))
        result = compile_hr(expanded, verified_compiler(args.compiler_dir, pins))
        status = 'UNREVIEWED_COMPILED_CANDIDATE'
    else:
        result = expanded
        status = 'EXPANDED_HR_SOURCE_ONLY'
    with args.output.open('xb') as stream:
        stream.write(result)
    print(json.dumps({'status': status, 'sha256': hashlib.sha256(result).hexdigest(), 'officialAcceptance': False, 'dispatchAuthorized': False}))
