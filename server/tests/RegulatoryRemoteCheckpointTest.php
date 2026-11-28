<?php
declare(strict_types=1);
require_once __DIR__.'/../src/RegulatoryRemoteCheckpoint.php';
if($argc!==2)throw new InvalidArgumentException('Expected approved JSON.');
$current=json_decode(file_get_contents($argv[1]),true,8,JSON_THROW_ON_ERROR);
RegulatoryRemoteCheckpoint::requireFresh($current);
$old=$current;$old['head']=str_repeat('0',64);
try{RegulatoryRemoteCheckpoint::requireFresh($old);throw new LogicException('Stale checkpoint accepted.');}
catch(DomainException){}
putenv('FISKAI_REGULATORY_WITNESS_TLS_SPKI=sha256//'.base64_encode(str_repeat('x',32)));
try{RegulatoryRemoteCheckpoint::requireFresh($current);throw new LogicException('Wrong TLS pin accepted.');}
catch(RuntimeException){}
echo "PHP remote regulatory checkpoint: TLS pin, signed nonce and stale head verified.\n";
