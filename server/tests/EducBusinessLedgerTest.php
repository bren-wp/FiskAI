<?php
declare(strict_types=1);
require __DIR__.'/OfficialFiscalContractTest.php';
require_once __DIR__.'/../src/SchemaMigrator.php';
require_once __DIR__.'/../src/EducBusinessLedger.php';
require_once __DIR__.'/../src/CisEducDispatch.php';
function ledgerCheck(bool $ok):void {if(!$ok)throw new RuntimeException('Business ledger test failed.');}
function ledgerReject(string $type,callable $call):void {
    try{$call();}catch(Throwable $e){if($e instanceof $type)return;throw $e;}
    throw new RuntimeException('Expected business ledger rejection missing.');
}
$db=db();SchemaMigrator::apply($db,__DIR__.'/../database');
$ledger=new EducBusinessLedger($db);
$subject='a1000000-0000-4000-8000-000000000001';
$other='a1000000-0000-4000-8000-000000000002';
$doc='a2000000-0000-4000-8000-000000000001';
$message='a3000000-0000-4000-8000-000000000001';
$storageKey=random_bytes(32);
$ledger->provision($subject,'12345678903','ledger-fixture');
$ledger->provision($subject,'12345678903','ledger-fixture');
ledgerReject(DomainException::class,fn()=>$ledger->provision($other,'12345678903','ledger-fixture'));
$input=['documentId'=>$doc,'messageId'=>$message,'issuedAt'=>'27.11.2026 09:00:00','number'=>'1','premise'=>'PP1','register'=>'1',
    'operatorOib'=>'00000000001','netCents'=>1000,'rate'=>25,'payment'=>'G'];
$entry=$ledger->append($subject,$input,$storageKey);
ledgerCheck($entry['document']['taxCents']===250&&$entry['document']['totalCents']===1250);
ledgerCheck($ledger->append($subject,$input,$storageKey)===$entry);
$bad=$input;$bad['netCents']=1001;
ledgerReject(DomainException::class,fn()=>$ledger->append($subject,$bad,$storageKey));
$bad=$input;$bad['rate']=17;
ledgerReject(InvalidArgumentException::class,fn()=>$ledger->append($subject,$bad,$storageKey));
$second=$input;$second['documentId']='a2000000-0000-4000-8000-000000000002';$second['messageId']='a3000000-0000-4000-8000-000000000002';
ledgerReject(DomainException::class,fn()=>$ledger->append($subject,$second,$storageKey));
$second['number']='2';$second['netCents']=101;
$next=$ledger->append($subject,$second,$storageKey);
ledgerCheck($next['document']['taxCents']===25&&$next['mac']!==$entry['mac']);
ledgerCheck($ledger->read($subject,$doc,$storageKey)===$entry);
ledgerReject(RuntimeException::class,fn()=>$ledger->read($subject,$doc,random_bytes(32)));
ledgerReject(DomainException::class,fn()=>$ledger->read($other,$doc,$storageKey));
$db->beginTransaction();
try{
    $s=$db->prepare('DELETE FROM educ_business_document WHERE subject_id=? AND document_id=?');
    $s->execute([$subject,$second['documentId']]);
    ledgerReject(RuntimeException::class,fn()=>$ledger->read($subject,$doc,$storageKey));
}finally{$db->rollBack();}
$db->beginTransaction();
try{
    $s=$db->prepare('UPDATE educ_business_document SET entry_mac=? WHERE subject_id=? AND document_id=?');
    $s->execute([str_repeat('0',64),$subject,$doc]);
    ledgerReject(RuntimeException::class,fn()=>$ledger->read($subject,$doc,$storageKey));
}finally{$db->rollBack();}
$dispatch=new CisEducDispatch($db);
ledgerCheck($dispatch->prepareFromLedger($subject,$doc,$key,$credential,$pin,$pin,$dir,$storageKey)===$message);
$soap=$dispatch->open($subject,$message,$storageKey);
EducBusinessLedger::assertBound($entry['document'],$soap,$credential);
ledgerReject(DomainException::class,fn()=>EducBusinessLedger::assertBound($next['document'],$soap,$credential));
ledgerReject(DomainException::class,fn()=>EducBusinessLedger::assertBound($entry['document'],str_replace('12.50','12.51',$soap),$credential));
$s=$db->prepare('SELECT document_sha256 FROM cis_educ_response_evidence WHERE message_id=?');$s->execute([$message]);
ledgerCheck($s->fetchColumn()===$entry['digest']);
echo "EDUC immutable business ledger, document chain and exact signed request binding passed.\n";
