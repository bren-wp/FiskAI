<?php
declare(strict_types=1);
require_once __DIR__.'/../src/FiscalContract.php';
function expectRejected(callable $f):void {
    try {$f();} catch(InvalidArgumentException $e){return;}
    throw new RuntimeException('Invalid input accepted.');
}
$dir=sys_get_temp_dir().'/fiskai-contract-'.bin2hex(random_bytes(8));
if(!mkdir($dir,0700))throw new RuntimeException('Cannot create temporary directory.');
try {
    // Synthetic regression fixture only; not a Porezna uprava contract.
    $xsd='<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:fiskai:test" xmlns:t="urn:fiskai:test" elementFormDefault="qualified"><xs:include schemaLocation="types.xsd"/><xs:element name="Test" type="t:Amount"/></xs:schema>';
    $types='<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:fiskai:test"><xs:simpleType name="Amount"><xs:restriction base="xs:decimal"/></xs:simpleType></xs:schema>';
    file_put_contents($dir.'/root.xsd',$xsd);file_put_contents($dir.'/types.xsd',$types);
    $manifest=['root.xsd'=>hash('sha256',$xsd),'types.xsd'=>hash('sha256',$types)];
    $xml='<Test xmlns="urn:fiskai:test">12.50</Test>';
    FiscalContract::validate($xml,$dir,$manifest,'root.xsd');
    expectRejected(fn()=>FiscalContract::validate('<Test xmlns="urn:fiskai:test">wrong</Test>',$dir,$manifest,'root.xsd'));
    expectRejected(fn()=>FiscalContract::validate('<!DOCTYPE Test><Test/>',$dir,$manifest,'root.xsd'));
    expectRejected(fn()=>FiscalContract::validate($xml,$dir,['root.xsd'=>$manifest['root.xsd']],'root.xsd'));
    file_put_contents($dir.'/types.xsd',$types."\n");
    expectRejected(fn()=>FiscalContract::validate($xml,$dir,$manifest,'root.xsd'));
    $external=str_replace('types.xsd','https://example.invalid/types.xsd',$xsd);
    file_put_contents($dir.'/root.xsd',$external);
    $manifest['root.xsd']=hash('sha256',$external);
    expectRejected(fn()=>FiscalContract::validate($xml,$dir,$manifest,'root.xsd'));
    echo "Offline contract validation tests passed\n";
} finally {
    foreach(glob($dir.'/*') as $file)unlink($file);
    rmdir($dir);
}
