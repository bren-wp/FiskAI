<?php
declare(strict_types=1);
require_once __DIR__.'/../src/HistoricalLegalDecisionResolver.php';
require_once __DIR__.'/../src/RegulatoryFreshness.php';
$v=json_decode(file_get_contents(__DIR__.'/../../app/src/androidTest/assets/regulatory-vector.json'),true,16,JSON_THROW_ON_ERROR);
function bytes(string $s):string {
    $b=base64_decode($s,true);if($b===false)throw new RuntimeException('Invalid fixture.');return $b;
}
if($v['fixture']!=='SYNTHETIC_TEST_ONLY')throw new RuntimeException('Invalid fixture scope.');
$public=bytes($v['publicKey']);$catalogs=array_map('bytes',$v['catalogs']);
$resolver=HistoricalLegalDecisionResolver::verified(bytes($v['history']),bytes($v['historySignature']),$public,
    $v['publicKeySha256'],$v['sequence'],$v['head'],$catalogs);
$r=$resolver->resolveByDigest($v['catalogSha256'],'2027-01-01','NON_VAT_COVERED','STANDARD_DOMESTIC_B2B');
if($r['sequence']!==2||$r['decision']['issue']!=='REQUIRED')throw new RuntimeException('Parity failure.');
$approved=['sequence'=>$v['sequence'],'head'=>$v['head'],'catalogSha256'=>$v['catalogSha256']];
$reply=array_intersect_key($v,array_flip(['statement','signature','nonce','publicKeySha256']));
RegulatoryFreshness::verify($approved,$v['nonce'],$reply,$public,$v['publicKeySha256']);
try{RegulatoryFreshness::verify($approved,str_repeat('b',64),$reply,$public,$v['publicKeySha256']);}
catch(InvalidArgumentException|DomainException){echo "Shared public regulatory fixture verified in PHP.\n";exit(0);}
throw new RuntimeException('Replay accepted.');
