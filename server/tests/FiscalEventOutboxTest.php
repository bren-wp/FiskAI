<?php
declare(strict_types=1);
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/FiscalEventOutbox.php';
function verify(bool $ok):void {if(!$ok)throw new RuntimeException('Fiscal event outbox test failed.');}
function uuid():string {$s=bin2hex(random_bytes(16));return substr($s,0,8).'-'.substr($s,8,4).'-'.substr($s,12,4).'-'.substr($s,16,4).'-'.substr($s,20);}
$db=db();$outbox=new FiscalEventOutbox($db);
$subject=uuid();$other=uuid();$event=uuid();$document=uuid();$digest=hash('sha256','synthetic evidence');
$prepare=fn()=> $outbox->prepare('EDUC',$subject,$event,$document,'PAYMENT','2026-12-31',$digest);
$a=$prepare();verify($a['state']==='PREPARED'&&$a['deadline_date']==='2027-01-20'&&$a['rule_version']==='HR-F2-2026-v1');
verify($prepare()===$a);
verify($outbox->read('EDUC',$other,$event)===null);
try{$outbox->prepare('EDUC',$subject,$event,$document,'PAYMENT','2026-12-31',hash('sha256','changed'));throw new RuntimeException('Conflicting evidence accepted');}catch(DomainException){}
verify($prepare()===$a);
$token=$outbox->claim('EDUC',$subject,$event);verify(is_string($token));
verify($outbox->claim('EDUC',$subject,$event)===null);
verify(!$outbox->markUncertain('EDUC',$subject,$event,str_repeat('0',64)));
verify($outbox->markUncertain('EDUC',$subject,$event,$token));
verify($outbox->claim('EDUC',$subject,$event)===null);
verify($prepare()['state']==='UNCERTAIN');
$prod=$outbox->prepare('PROD',$subject,$event,$document,'PAYMENT','2026-12-31',$digest);
verify($prod['state']==='PREPARED');
try{$outbox->claim('PROD',$subject,$event);throw new RuntimeException('Production claim accepted');}catch(LogicException){}
$interrupted=uuid();
$outbox->prepare('EDUC',$subject,$interrupted,$document,'RECEIVED','2027-01-01',$digest);
verify(is_string($outbox->claim('EDUC',$subject,$interrupted)));
verify($outbox->recoverInterrupted($subject)===0);
$stmt=$db->prepare("UPDATE fiscal_event_outbox SET claimed_at=UTC_TIMESTAMP(6)-INTERVAL 6 MINUTE WHERE environment='EDUC' AND subject_id=? AND event_id=?");
$stmt->execute([$subject,$interrupted]);
verify($outbox->recoverInterrupted($other)===0);
verify($outbox->recoverInterrupted($subject)===1);
verify($outbox->read('EDUC',$subject,$interrupted)['state']==='UNCERTAIN');
verify($outbox->claim('EDUC',$subject,$interrupted)===null);
echo "Fiscal event outbox integration tests passed\n";
