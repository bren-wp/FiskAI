<?php
declare(strict_types=1);
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/DemoSync.php';
function verify(bool $ok):void {if(!$ok)throw new RuntimeException('DEMO sync test failed.');}
function expect(string $class,callable $test):void {
    try{$test();}catch(Throwable $e){if($e instanceof $class)return;throw $e;}
    throw new RuntimeException('Expected rejection was missing.');
}
$db=db();$sync=new DemoSync($db);
$subject='71000000-0000-4000-8000-000000000001';
$other='71000000-0000-4000-8000-000000000002';
$a=$sync->provision($subject);$b=$sync->provision($subject);$c=$sync->provision($other);
$event=['eventId'=>'72000000-0000-4000-8000-000000000001','receiptId'=>hash('sha256','synthetic receipt'),
    'documentId'=>'73000000-0000-4000-8000-000000000001','evidenceSha256'=>hash('sha256','synthetic evidence'),
    'eventDate'=>'2026-12-31','amountCents'=>1250,'ruleVersion'=>'HR-F2-2026-v1','deadlineDate'=>'2027-01-20'];
$request=['protocol'=>1,'subjectId'=>$subject,'events'=>[$event]];
$auth=fn(array $d)=>'Bearer '.$d['token'];
$first=$sync->push($auth($a),$request);
verify($first===['protocol'=>1,'subjectId'=>$subject,'status'=>'DEMO_METADATA_STORED','accepted'=>[$event['eventId']]]);
verify($sync->push($auth($b),$request)===$first);
expect(DemoSyncAuthException::class,fn()=>$sync->push($auth($c),$request));
expect(DemoSyncAuthException::class,fn()=>$sync->push('Bearer '.str_repeat('0',64),$request));
$foreign=$request;$foreign['subjectId']=$other;
verify($sync->push($auth($c),$foreign)['subjectId']===$other);
$changed=$event;$changed['amountCents']=1251;
$second=$event;$second['eventId']='72000000-0000-4000-8000-000000000002';$second['receiptId']=hash('sha256','second receipt');
$batch=$request;$batch['events']=[$second,$changed];
expect(DemoSyncConflict::class,fn()=>$sync->push($auth($a),$batch));
$s=$db->prepare('SELECT COUNT(*) FROM demo_sync_payment WHERE subject_id=?');$s->execute([$subject]);verify((int)$s->fetchColumn()===1);
$duplicate=$event;$duplicate['eventId']=$second['eventId'];
$batch['events']=[$duplicate];expect(DemoSyncConflict::class,fn()=>$sync->push($auth($a),$batch));
$bad=$second;$bad['deadlineDate']='2027-01-21';$batch['events']=[$second,$bad];
expect(InvalidArgumentException::class,fn()=>$sync->push($auth($a),$batch));
$s->execute([$subject]);verify((int)$s->fetchColumn()===1);
$batch['events']=[$second];verify($sync->push($auth($a),$batch)['accepted']===[$second['eventId']]);
$query=['protocol'=>1,'subjectId'=>$subject];
$snapshot=$sync->pull($auth($a),$query);
verify($snapshot===['protocol'=>1,'subjectId'=>$subject,'status'=>'DEMO_METADATA_SNAPSHOT','events'=>[$event,$second]]);
verify($sync->pull($auth($b),$query)===$snapshot);
expect(DemoSyncAuthException::class,fn()=>$sync->pull($auth($c),$query));
verify($sync->pull($auth($c),['protocol'=>1,'subjectId'=>$other])['events']===[$event]);
$rotated=$sync->rotate($b['deviceId']);
verify($rotated['deviceId']===$b['deviceId']&&$rotated['subjectId']===$subject&&$rotated['token']!==$b['token']);
expect(DemoSyncAuthException::class,fn()=>$sync->pull($auth($b),$query));
expect(DemoSyncAuthException::class,fn()=>$sync->push($auth($b),$request));
verify($sync->pull($auth($rotated),$query)===$snapshot);
$b=$rotated;
verify($sync->revoke($a['deviceId']));
expect(DemoSyncAuthException::class,fn()=>$sync->rotate($a['deviceId']));
expect(DemoSyncAuthException::class,fn()=>$sync->pull($auth($a),$query));
expect(DemoSyncAuthException::class,fn()=>$sync->push($auth($a),$request));
$s=$db->prepare('UPDATE demo_sync_device SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE device_id=?');$s->execute([$b['deviceId']]);
expect(DemoSyncAuthException::class,fn()=>$sync->push($auth($b),$request));
expect(DemoSyncAuthException::class,fn()=>$sync->pull($auth($b),$query));
$active=$sync->rotate($b['deviceId']);
verify($sync->pull($auth($active),$query)===$snapshot);
$s=$db->prepare('UPDATE demo_sync_payment SET amount_cents=amount_cents+1 WHERE subject_id=? AND event_id=?');
$s->execute([$subject,$event['eventId']]);
expect(RuntimeException::class,fn()=>$sync->pull($auth($active),$query));
$s=$db->prepare('SELECT token_sha256 FROM demo_sync_device WHERE device_id=?');$s->execute([$a['deviceId']]);
verify($s->fetchColumn()===hash('sha256',$a['token']));
echo "DEMO sync isolation, replay, revocation and rollback tests passed\n";
