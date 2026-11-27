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

if(getenv('FISKAI_CI_RECOVERY_TEST')==='1'){
    require_once __DIR__.'/../src/CisEducRecovery.php';
    $archiveKey=random_bytes(32);
    $archive=(new CisEducRecovery($db))->export($subject,'ledger-fixture',$archiveKey,$storageKey);
    $dsn=getenv('FISKAI_DB_DSN');
    $name='fiskai_recovery_test_'.bin2hex(random_bytes(6));
    $targetDsn=str_replace('dbname=fiskai_test;','dbname='.$name.';',$dsn);
    if($targetDsn===$dsn)throw new RuntimeException('Expected CI database only.');
    $db->exec('CREATE DATABASE `'.$name.'` CHARACTER SET utf8mb4');
    $target=null;
    try{
        $target=new PDO($targetDsn,getenv('FISKAI_DB_USER'),getenv('FISKAI_DB_PASSWORD'),[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_EMULATE_PREPARES=>false]);
        SchemaMigrator::apply($target,__DIR__.'/../database');
        $restore=new CisEducRecovery($target);
        putenv('FISKAI_CIS_EDUC_ENABLED=0');putenv('FISKAI_CIS_RECOVERY_MODE=1');
        ledgerCheck($restore->restore($subject,'ledger-fixture',$archive,$archiveKey,$storageKey)===1);
        $restored=new EducBusinessLedger($target);
        ledgerCheck($restored->read($subject,$doc,$storageKey)===$entry);
        ledgerCheck($restored->read($subject,$second['documentId'],$storageKey)===$next);
        $recovered=new CisEducDispatch($target);
        ledgerCheck($recovered->state($subject,$message)==='UNCERTAIN');
        ledgerCheck($recovered->open($subject,$message,$storageKey)===$soap);
        ledgerReject(DomainException::class,fn()=>$recovered->reserve($subject,$message,$storageKey));
        $secondMessage=$recovered->prepareFromLedger($subject,$second['documentId'],$key,$credential,$pin,$pin,$dir,$storageKey);
        putenv('FISKAI_CIS_RECOVERY_MODE=0');
        // Subject quarantine must block even a new dispatch for a document that had no archived request.
        ledgerReject(LogicException::class,fn()=>$recovered->dispatchAuthorized($subject,$secondMessage,$credential,$credential,$dir,$storageKey));
        $s=$target->prepare('SELECT COUNT(*) FROM educ_recovery_quarantine WHERE subject_id=?');$s->execute([$subject]);
        ledgerCheck((int)$s->fetchColumn()===1);
        ledgerReject(DomainException::class,fn()=>$restore->restore($subject,'ledger-fixture',$archive,$archiveKey,$storageKey));
        echo "EDUC archive v2: document chain, exact request, quarantine and non-overwriting restore passed.\n";
    }finally{
        $target=null;
        $db->exec('DROP DATABASE `'.$name.'`');
        putenv('FISKAI_CIS_RECOVERY_MODE=0');
    }
}
