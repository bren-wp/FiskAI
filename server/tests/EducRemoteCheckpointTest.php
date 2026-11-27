<?php
declare(strict_types=1);
require_once __DIR__.'/../src/EducRemoteCheckpoint.php';
function rejectFresh(callable $f):void {
    try{$f();}catch(InvalidArgumentException|DomainException){return;}
    throw new RuntimeException('Invalid witness proof accepted.');
}
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
if(!$key)throw new RuntimeException('Fixture unavailable.');
$public=openssl_pkey_get_details($key)['key'];$pin=hash('sha256',$public);
$head=['protocol'=>'FISKAI-EDUC-HEAD-V1','subject'=>'a1000000-0000-4000-8000-000000000001','keyId'=>'fixture','position'=>2,'head'=>str_repeat('a',64)];
$nonce=str_repeat('b',64);$statement=EducExternalCheckpoint::bytes($head);
if(!openssl_sign("FISKAI-EDUC-FRESH-V1\n".$nonce."\n".$statement,$sig,$key,OPENSSL_ALGO_SHA256))
    throw new RuntimeException('Fixture signing failed.');
$reply=['statement'=>base64_encode($statement),'signature'=>base64_encode($sig),'publicKeySha256'=>$pin,'nonce'=>$nonce];
EducRemoteCheckpoint::verify($head,$nonce,$reply,$public,$pin);
rejectFresh(fn()=>EducRemoteCheckpoint::verify($head,str_repeat('c',64),$reply,$public,$pin));
$changed=$reply;$changed['nonce']=str_repeat('c',64);
rejectFresh(fn()=>EducRemoteCheckpoint::verify($head,$changed['nonce'],$changed,$public,$pin));
$changed=$head;$changed['position']=1;
rejectFresh(fn()=>EducRemoteCheckpoint::verify($changed,$nonce,$reply,$public,$pin));
rejectFresh(fn()=>EducRemoteCheckpoint::verify($head,$nonce,$reply,$public,str_repeat('0',64)));
echo "Remote checkpoint: fresh proof accepted; replay, changed nonce, stale head and wrong pin rejected.\n";
