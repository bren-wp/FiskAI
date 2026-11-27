<?php
declare(strict_types=1);
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/DemoSync.php';
function verify(bool $ok):void {if(!$ok)throw new RuntimeException('Device proof test failed.');}
function rejected(string $class,callable $call):void {
    try{$call();}catch(Throwable $e){if($e instanceof $class)return;throw $e;}
    throw new RuntimeException('Expected rejection was missing.');
}
$db=db();$sync=new DemoSync($db);$proof=new DemoDeviceProof($db);
$subject='81000000-0000-4000-8000-000000000001';
$device=$sync->provision($subject);
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
$other=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
if(!$key||!$other)throw new RuntimeException('Fixture key unavailable.');
$public=fn($k)=>preg_replace('/\s|-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----/','',openssl_pkey_get_details($k)['key']);
$pin=$proof->bind($device['deviceId'],$public($key));
verify($pin===hash('sha256',base64_decode($public($key),true)));
verify($proof->bind($device['deviceId'],$public($key))===$pin);
rejected(DemoSyncConflict::class,fn()=>$proof->bind($device['deviceId'],$public($other)));
$auth='Bearer '.$device['token'];
$pull=['protocol'=>1,'subjectId'=>$subject];
$event=['eventId'=>'82000000-0000-4000-8000-000000000001','receiptId'=>hash('sha256','proof fixture'),
    'documentId'=>'83000000-0000-4000-8000-000000000001','evidenceSha256'=>hash('sha256','proof evidence'),
    'eventDate'=>'2026-12-31','amountCents'=>1250,'ruleVersion'=>'HR-F2-2026-v1','deadlineDate'=>'2027-01-20'];
$push=$pull+['events'=>[$event]];
$sign=function(array $challenge,array $request,string $action,$signingKey)use(&$device):array {
    $body=json_encode($request,JSON_THROW_ON_ERROR);
    $message=DemoDeviceProof::message($action,$challenge['subjectId'],$challenge['deviceId'],$challenge['nonce'],hash('sha256',$device['token']),$body);
    if(!openssl_sign($message,$sig,$signingKey,OPENSSL_ALGO_SHA256))throw new RuntimeException('Fixture signature failed.');
    return ['nonce'=>$challenge['nonce'],'signature'=>base64_encode($sig),'body'=>$body];
};
rejected(DemoSyncAuthException::class,fn()=>$sync->pull($auth,$pull));
rejected(DemoSyncAuthException::class,fn()=>$proof->challenge($auth,'81000000-0000-4000-8000-000000000002'));
$c=$proof->challenge($auth,$subject);
rejected(DemoSyncAuthException::class,fn()=>$sync->push($auth,$push,$sign($c,$push,'demo-sync',$other)));
$p=$sign($c,$push,'demo-sync',$key);
$changed=$push;$changed['events'][0]['amountCents']=1251;
rejected(DemoSyncAuthException::class,fn()=>$sync->push($auth,$changed,$p));
rejected(DemoSyncAuthException::class,fn()=>$sync->pull($auth,$pull,$sign($c,$pull,'demo-sync',$key)));
verify($sync->push($auth,$push,$p)['accepted']===[$event['eventId']]);
rejected(DemoSyncAuthException::class,fn()=>$sync->push($auth,$push,$p));
$c=$proof->challenge($auth,$subject);$old=$sign($c,$pull,'demo-sync-pull',$key);
$c=$proof->challenge($auth,$subject);
rejected(DemoSyncAuthException::class,fn()=>$sync->pull($auth,$pull,$old));
$p=$sign($c,$pull,'demo-sync-pull',$key);
verify($sync->pull($auth,$pull,$p)['events']===[$event]);
rejected(DemoSyncAuthException::class,fn()=>$sync->pull($auth,$pull,$p));
$c=$proof->challenge($auth,$subject);
$s=$db->prepare('UPDATE demo_sync_device_proof SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE device_id=?');
$s->execute([$device['deviceId']]);
rejected(DemoSyncAuthException::class,fn()=>$sync->pull($auth,$pull,$sign($c,$pull,'demo-sync-pull',$key)));
$c=$proof->challenge($auth,$subject);
$old=$sign($c,$pull,'demo-sync-pull',$key);
$device=$sync->rotate($device['deviceId']);$auth='Bearer '.$device['token'];
rejected(DemoSyncAuthException::class,fn()=>$sync->pull($auth,$pull,$old));
$c=$proof->challenge($auth,$subject);
verify($sync->pull($auth,$pull,$sign($c,$pull,'demo-sync-pull',$key))['events']===[$event]);
verify($sync->revoke($device['deviceId']));
rejected(DemoSyncAuthException::class,fn()=>$proof->challenge($auth,$subject));
echo "Device proof, single-use challenges, expiry, rotation and scope passed.\n";
