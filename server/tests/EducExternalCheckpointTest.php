<?php
declare(strict_types=1);
require_once __DIR__.'/../src/EducExternalCheckpoint.php';
function checkpointReject(string $type,callable $call):void {
    try{$call();}catch(Throwable $e){if($e instanceof $type)return;throw $e;}
    throw new RuntimeException('Expected checkpoint rejection missing.');
}
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
if(!$key)throw new RuntimeException('Fixture key unavailable.');
$public=openssl_pkey_get_details($key)['key'];$pin=hash('sha256',$public);
$head=['protocol'=>'FISKAI-EDUC-HEAD-V1','subject'=>'a1000000-0000-4000-8000-000000000001',
    'keyId'=>'fixture','position'=>2,'head'=>str_repeat('a',64)];
$statement=EducExternalCheckpoint::bytes($head);
if(!openssl_sign($statement,$signature,$key,OPENSSL_ALGO_SHA256))throw new RuntimeException('Fixture signing failed.');
EducExternalCheckpoint::verify($head,$statement,$signature,$public,$pin);
$next=$head;$next['position']=3;$next['head']=str_repeat('b',64);
checkpointReject(DomainException::class,fn()=>EducExternalCheckpoint::verify($next,$statement,$signature,$public,$pin));
checkpointReject(DomainException::class,fn()=>EducExternalCheckpoint::verify($head,$statement.' ',$signature,$public,$pin));
checkpointReject(InvalidArgumentException::class,fn()=>EducExternalCheckpoint::verify($head,$statement,$signature,$public,str_repeat('0',64)));
$foreign=$head;$foreign['subject']='a1000000-0000-4000-8000-000000000002';
checkpointReject(DomainException::class,fn()=>EducExternalCheckpoint::verify($foreign,$statement,$signature,$public,$pin));
$wrong=$head;$wrong['keyId']='changed';
checkpointReject(DomainException::class,fn()=>EducExternalCheckpoint::verify($wrong,$statement,$signature,$public,$pin));
putenv('FISKAI_EDUC_ANCHOR_PUBLIC_KEY_SHA256');
checkpointReject(LogicException::class,fn()=>EducExternalCheckpoint::requireLatest($head));
echo "External checkpoint: valid signature, stale head, changed bytes, wrong pin, foreign subject, changed key and missing trust tested.\n";
