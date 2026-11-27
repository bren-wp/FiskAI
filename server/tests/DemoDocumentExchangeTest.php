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
require_once __DIR__.'/../src/DemoDocumentReplica.php';
$replicaKey=random_bytes(32);
$replica=new DemoDocumentReplica($db,$replicaKey);
$auth='Bearer '.$device['token'];
$proof=new DemoDeviceProof($db);
$send=function(string $action,array $request,?array $challenge=null)use($proof,$auth,$key,$device,$replica):array{
    $c=$challenge??$proof->challenge($auth,$request['subjectId']);
    $body=json_encode($request,JSON_THROW_ON_ERROR);
    $message=DemoDeviceProof::message($action,$c['subjectId'],$c['deviceId'],$c['nonce'],hash('sha256',$device['token']),$body);
    if(!openssl_sign($message,$sig,$key,OPENSSL_ALGO_SHA256))throw new RuntimeException('Request signing failed.');
    return $replica->exchange($auth,$action,$request,['nonce'=>$c['nonce'],'signature'=>base64_encode($sig),'body'=>$body]);
};
$pull=['protocol'=>1,'subjectId'=>$subject];$push=$pull+['envelope'=>$envelope];
exchangeReject(DemoSyncAuthException::class,fn()=>$send('demo-documents-pull',$pull));
DemoDocumentReplica::provision($db,$subject,$owner);
DemoDocumentReplica::provision($db,$subject,$owner);
exchangeReject(DomainException::class,fn()=>DemoDocumentReplica::provision($db,$subject,'00000000001'));
$challenge=$proof->challenge($auth,$subject);
if($send('demo-documents-push',$push,$challenge)['accepted']!==[$row['documentId']])throw new RuntimeException('Replica ack mismatch.');
exchangeReject(DemoSyncAuthException::class,fn()=>$send('demo-documents-push',$push,$challenge));
if($send('demo-documents-push',$push)['accepted']!==[$row['documentId']])throw new RuntimeException('Replica retry mismatch.');
$remote=$send('demo-documents-pull',$pull);
if($remote['documents']!==[$row]||$remote['ownerOib']!==$owner)throw new RuntimeException('Replica snapshot mismatch.');
// A second independently enrolled credential in the same scope can read the stored document.
$secondDevice=$sync->provision($subject);
$proof->bind($secondDevice['deviceId'],$public);
$secondAuth='Bearer '.$secondDevice['token'];
$c=$proof->challenge($secondAuth,$subject);$bytes=json_encode($pull,JSON_THROW_ON_ERROR);
openssl_sign(DemoDeviceProof::message('demo-documents-pull',$subject,$secondDevice['deviceId'],$c['nonce'],hash('sha256',$secondDevice['token']),$bytes),$sig,$key,OPENSSL_ALGO_SHA256);
if($replica->exchange($secondAuth,'demo-documents-pull',$pull,['nonce'=>$c['nonce'],'signature'=>base64_encode($sig),'body'=>$bytes])!==$remote)
    throw new RuntimeException('Second device could not read replica.');
$changed=$body;$changedSnapshot=json_decode($row['snapshot'],true,16,JSON_THROW_ON_ERROR);$changedSnapshot['total']='12.51';
$changed['documents'][0]['snapshot']=json_encode($changedSnapshot,JSON_THROW_ON_ERROR);
$changed['documents'][0]['digest']=hash('sha256',$changed['documents'][0]['snapshot']);
$extra=$row;$extra['documentId']='b7000000-0000-4000-8000-000000000002';
$extraSnapshot=$changedSnapshot;$extraSnapshot['number']=2;$extra['snapshot']=json_encode($extraSnapshot,JSON_THROW_ON_ERROR);$extra['digest']=hash('sha256',$extra['snapshot']);
array_unshift($changed['documents'],$extra);
exchangeReject(DemoSyncConflict::class,fn()=>$send('demo-documents-push',$pull+['envelope'=>$sign($changed)]));
if($send('demo-documents-pull',$pull)!==$remote)throw new RuntimeException('Conflicting batch partially committed.');
$s=$db->prepare('SELECT sealed_document FROM demo_sync_document WHERE subject_id=?');$s->execute([$subject]);
if(str_contains($s->fetchColumn(),'sellerOib'))throw new RuntimeException('Replica stored plaintext.');
$sync->revoke($device['deviceId']);
exchangeReject(DemoSyncAuthException::class,fn()=>$send('demo-documents-pull',$pull));
exchangeReject(DomainException::class,fn()=>DemoDocumentExchange::verifyEnrolled($db,$envelope,$subject,$owner,$device['deviceId']));
echo "Signed DEMO document review: enrolled key, exact snapshot, isolation, duplicate and revoke tests passed.\n";
