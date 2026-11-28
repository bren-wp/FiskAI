<?php
declare(strict_types=1);
require_once __DIR__.'/../src/RegulatoryTrustAnchor.php';
function reject(callable $fn):void {
    try{$fn();}catch(InvalidArgumentException|DomainException){return;}
    throw new RuntimeException('Invalid history accepted.');
}
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
if(!$key)throw new RuntimeException('Fixture key unavailable.');
$public=openssl_pkey_get_details($key)['key'];$pin=hash('sha256',$public);
$active=file_get_contents(__DIR__.'/../contracts/legal-rules.json');
$old='{"fixture":"synthetic previous revision"}';
$line1='1|'.str_repeat('0',64).'|'.hash('sha256',$old)."\n";
$line2='2|'.hash('sha256',$line1).'|'.hash('sha256',$active)."\n";
$prefix="FISKAI-REGULATORY-HISTORY-V1\n";$history=$prefix.$line1.$line2;$head=hash('sha256',$line2);
$sign=function(string $bytes)use($key):string{
    if(!openssl_sign($bytes,$sig,$key,OPENSSL_ALGO_SHA256))throw new RuntimeException('Fixture signature failed.');
    return $sig;
};
$signature=$sign($history);
$x=RegulatoryTrustAnchor::verify($history,$signature,$public,$pin,2,$head,[$old,$active]);
if($x['sequence']!==2||$x['dispatchAuthorized']!==false||$x['catalogSha256']!==hash('sha256',$active))throw new RuntimeException('Invalid verified result.');
reject(fn()=>RegulatoryTrustAnchor::verify($history,$signature,$public,$pin,2,$head,[$old,$active.' ']));
reject(fn()=>RegulatoryTrustAnchor::verify($history,$signature,$public,str_repeat('0',64),2,$head,[$old,$active]));
reject(fn()=>RegulatoryTrustAnchor::verify($history,$signature,$public,$pin,2,str_repeat('0',64),[$old,$active]));
reject(fn()=>RegulatoryTrustAnchor::verify($prefix.$line1,$sign($prefix.$line1),$public,$pin,2,$head,[$old,$active]));
reject(fn()=>RegulatoryTrustAnchor::verify($history.' ',$signature,$public,$pin,2,$head,[$old,$active]));
$badLine='2|'.str_repeat('0',64).'|'.hash('sha256',$active)."\n";
reject(fn()=>RegulatoryTrustAnchor::verify($prefix.$line1.$badLine,$sign($prefix.$line1.$badLine),$public,$pin,2,hash('sha256',$badLine),[$old,$active]));
reject(fn()=>RegulatoryTrustAnchor::verify($prefix.$line1,$sign($prefix.$line1),$public,$pin,1,hash('sha256',$line1),[$old]));
echo "Regulatory history signature, revision chain, active catalog and independent latest head checks passed.\n";
