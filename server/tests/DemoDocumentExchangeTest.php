<?php
declare(strict_types=1);
require_once __DIR__.'/../src/DemoDocumentExchange.php';
require_once __DIR__.'/../src/DemoSync.php';
require_once __DIR__.'/../src/bootstrap.php';
function exchangeReject(string $type,callable $call):void {
    try{$call();}catch(Throwable $e){if($e instanceof $type)return;throw $e;}
    throw new RuntimeException('Expected document exchange rejection missing.');
}
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
if(!$key)throw new RuntimeException('Fixture unavailable.');
$public=preg_replace('/\s|-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----/','',openssl_pkey_get_details($key)['key']);
$subject='b5000000-0000-4000-8000-000000000001';$owner='12345678903';
$snapshot=['environment'=>'DEMO','sellerOib'=>$owner,'year'=>2026,'number'=>1,'premise'=>'PP1','register'=>'1',
    'draft'=>'b6000000-0000-4000-8000-000000000001','created'=>1795766400000,'total'=>'12.50'];
$plain=json_encode($snapshot,JSON_THROW_ON_ERROR);
$row=['documentId'=>'b7000000-0000-4000-8000-000000000001','digest'=>hash('sha256',$plain),'snapshot'=>$plain];
$body=['protocol'=>'FISKAI-DEMO-DOCUMENTS-V1','subjectId'=>$subject,'ownerOib'=>$owner,
    'keySha256'=>hash('sha256',base64_decode($public,true)),'documents'=>[$row]];
$sign=function(array $data)use($key):string {
    $payload=json_encode($data,JSON_THROW_ON_ERROR);
    if(!openssl_sign($payload,$sig,$key,OPENSSL_ALGO_SHA256))throw new RuntimeException('Fixture signing failed.');
    return json_encode(['payload'=>base64_encode($payload),'signature'=>base64_encode($sig)],JSON_THROW_ON_ERROR);
};
$envelope=$sign($body);
$db=db();$sync=new DemoSync($db);$device=$sync->provision($subject);
(new DemoDeviceProof($db))->bind($device['deviceId'],$public);
$result=DemoDocumentExchange::verifyEnrolled($db,$envelope,$subject,$owner,$device['deviceId']);
if($result['status']!=='DEMO_REVIEW_ONLY'||$result['documents']!==[['documentId'=>$row['documentId'],'digest'=>$row['digest'],'number'=>'2026/PP1/1/1']])
    throw new RuntimeException('Document review mismatch.');
exchangeReject(DomainException::class,fn()=>DemoDocumentExchange::verifyEnrolled($db,$envelope,$subject,'00000000001',$device['deviceId']));
$bad=$body;$bad['documents'][0]['snapshot'].=' ';
exchangeReject(DomainException::class,fn()=>DemoDocumentExchange::verify($sign($bad),$subject,$owner,$public));
$bad=$body;$snapshot['environment']='PROD';$plain=json_encode($snapshot,JSON_THROW_ON_ERROR);
$bad['documents'][0]['snapshot']=$plain;$bad['documents'][0]['digest']=hash('sha256',$plain);
exchangeReject(DomainException::class,fn()=>DemoDocumentExchange::verify($sign($bad),$subject,$owner,$public));
$bad=$body;$bad['documents'][]=$row;
exchangeReject(DomainException::class,fn()=>DemoDocumentExchange::verify($sign($bad),$subject,$owner,$public));
$outer=json_decode($envelope,true,8,JSON_THROW_ON_ERROR);$outer['payload']=base64_encode(json_encode($body).' ');
exchangeReject(DomainException::class,fn()=>DemoDocumentExchange::verify(json_encode($outer),$subject,$owner,$public));
$sync->revoke($device['deviceId']);
exchangeReject(DomainException::class,fn()=>DemoDocumentExchange::verifyEnrolled($db,$envelope,$subject,$owner,$device['deviceId']));
echo "Signed DEMO document review: enrolled key, exact snapshot, isolation, duplicate and revoke tests passed.\n";
