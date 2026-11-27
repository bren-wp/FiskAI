<?php
declare(strict_types=1);
require_once __DIR__.'/../src/F2ProviderGrant.php';

// Standalone synthetic signature tests: no database, DOM, network or live provider.
function grantReject(string $type,callable $call):void {
    try{$call();}catch(Throwable $e){if($e instanceof $type)return;throw $e;}
    throw new RuntimeException('Invalid provider grant accepted.');
}
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
$other=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
if(!$key||!$other)throw new RuntimeException('Fixture keys unavailable.');
$public=openssl_pkey_get_details($key)['key'];$pin=hash('sha256',$public);
$otherPublic=openssl_pkey_get_details($other)['key'];
$subject='c1000000-0000-4000-8000-000000000001';$owner='12345678903';
$grant=['environment'=>'EDUC','subject'=>$subject,'ownerOib'=>$owner,'providerOib'=>'00000000001','operation'=>'ISSUED',
    'validFrom'=>'2026-12-01','validUntil'=>'2026-12-31','authorizationSha256'=>hash('sha256','synthetic fixture')];
$sign=function(array $value)use($key):array {
    $statement=json_encode($value,JSON_THROW_ON_ERROR);
    if(!openssl_sign("FISKAI-F2-PROVIDER-GRANT-V1\n".$statement,$signature,$key,OPENSSL_ALGO_SHA256))
        throw new RuntimeException('Fixture signing failed.');
    return [$statement,$signature];
};
[$statement,$signature]=$sign($grant);
$verify=fn($date,$direction='ISSUED',$tenant=null,$oib=null)=>F2ProviderGrant::verify($statement,$signature,$public,$pin,$tenant??$subject,$oib??$owner,$direction,$date);
foreach(['2026-12-01','2026-12-31'] as $date){
    $result=$verify($date);
    if($result['externalAuthorizationVerified']!==false||$result['grantSha256']!==hash('sha256',$statement)||$result['signerSha256']!==$pin)
        throw new RuntimeException('Unexpected local grant evidence.');
}
grantReject(DomainException::class,fn()=>$verify('2026-11-30'));
grantReject(DomainException::class,fn()=>$verify('2027-01-01'));
grantReject(InvalidArgumentException::class,fn()=>$verify('2026-02-30'));
grantReject(DomainException::class,fn()=>$verify('2026-12-01','RECEIVED'));
grantReject(DomainException::class,fn()=>$verify('2026-12-01','ISSUED','c1000000-0000-4000-8000-000000000002'));
grantReject(DomainException::class,fn()=>$verify('2026-12-01','ISSUED',null,'00000000001'));
grantReject(InvalidArgumentException::class,fn()=>F2ProviderGrant::verify($statement,$signature,$public,str_repeat('0',64),$subject,$owner,'ISSUED','2026-12-01'));
grantReject(DomainException::class,fn()=>F2ProviderGrant::verify($statement,$signature,$otherPublic,hash('sha256',$otherPublic),$subject,$owner,'ISSUED','2026-12-01'));
grantReject(DomainException::class,fn()=>F2ProviderGrant::verify($statement.' ',$signature,$public,$pin,$subject,$owner,'ISSUED','2026-12-01'));
foreach([['environment'=>'PROD'],['providerOib'=>'00000000002'],['externalAuthorizationVerified'=>true],['validFrom'=>'2027-01-01']] as $change){
    [$altered,$sig]=$sign(array_replace($grant,$change));
    grantReject(DomainException::class,fn()=>F2ProviderGrant::verify($altered,$sig,$public,$pin,$subject,$owner,'ISSUED','2026-12-01'));
}
echo "F2 provider grant: 15 synthetic scope, validity and cryptographic scenarios passed; no external authorization.\n";
