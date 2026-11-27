<?php
declare(strict_types=1);
require_once __DIR__.'/../src/F2ValidationEvidence.php';

// CI-only bridge for actual Saxon-generated SVRL. The temporary key is not
// an authorized production validator, and no document is sent or approved.
if($argc!==3)throw new InvalidArgumentException('Usage: FIXTURE_XML DIAGNOSTICS_DIRECTORY');
$xml=file_get_contents($argv[1]);$dir=$argv[2].'/complete-invoice/';
$diagnostics=json_decode(file_get_contents($dir.'diagnostics.json'),true,16,JSON_THROW_ON_ERROR);
$en=file_get_contents($dir.'en16931.svrl.xml');$hr=file_get_contents($dir.'hr-cius.svrl.xml');
if(!hash_equals(hash('sha256',$xml),$diagnostics['documentSha256'])||
    !hash_equals(hash('sha256',$en),$diagnostics['en16931']['sha256'])||
    !hash_equals(hash('sha256',$hr),$diagnostics['hrCius']['sha256'])||
    $diagnostics['officialAcceptance']!==false||$diagnostics['dispatchAuthorized']!==false)
    throw new RuntimeException('Unexpected diagnostic evidence.');
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
if(!$key)throw new RuntimeException('Fixture signing key unavailable.');
$public=openssl_pkey_get_details($key)['key'];$pin=hash('sha256',$public);
$schema=hash('sha256','CI-only synthetic schema manifest pin');
$enPin=$diagnostics['enStylesheetSha256'];$hrPin=$diagnostics['hrStylesheetSha256'];
$sign=function(string $enReport,string $hrReport)use($xml,$schema,$enPin,$hrPin,$key):array{
    $statement=json_encode(['protocol'=>'FISKAI-F2-DUAL-SVRL-V2','documentSha256'=>hash('sha256',$xml),
        'schemaManifestSha256'=>$schema,'enManifestSha256'=>$enPin,'hrManifestSha256'=>$hrPin,
        'enSvrlSha256'=>hash('sha256',$enReport),'hrSvrlSha256'=>hash('sha256',$hrReport)],JSON_THROW_ON_ERROR);
    if(!openssl_sign("FISKAI-F2-DUAL-VALIDATION-V2\n".$statement,$sig,$key,OPENSSL_ALGO_SHA256))
        throw new RuntimeException('Fixture signing failed.');
    return [$statement,$sig];
};
$verify=fn($enReport,$hrReport,$statement,$signature)=>F2ValidationEvidence::verifyDual(
    $xml,$schema,$enPin,$hrPin,$enReport,$hrReport,$statement,$signature,$public,$pin);
[$statement,$sig]=$sign($en,$hr);
$result=$verify($en,$hr,$statement,$sig);
if($result['status']!=='SIGNED_DUAL_SVRL_REVIEW_REQUIRED'||$result['enWarnings']!==['UBL-CR-006','UBL-CR-200']||
    $result['dispatchAuthorized']!==false)throw new RuntimeException('National profile findings not preserved.');
function reject(callable $fn):void{
    try{$fn();}catch(DomainException|InvalidArgumentException){return;}
    throw new RuntimeException('Unexpected evidence accepted.');
}
reject(fn()=>$verify($hr,$en,$statement,$sig));
reject(fn()=>$verify($en.' ',$hr,$statement,$sig));
foreach([
    str_replace('flag="warning"','flag="fatal"',$en),
    str_replace('UBL-CR-006','OTHER-WARNING',$en),
    file_get_contents($argv[2].'/wrong-total/en16931.svrl.xml')
] as $bad){
    [$badStatement,$badSignature]=$sign($bad,$hr);
    reject(fn()=>$verify($bad,$hr,$badStatement,$badSignature));
}
echo "Actual dual SVRL bytes verified with ephemeral CI key; national warnings retained, fatal and unexpected findings rejected. No approval or dispatch.\n";
