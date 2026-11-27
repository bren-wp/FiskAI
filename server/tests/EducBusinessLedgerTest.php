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
$third=$input;$third['documentId']='a2000000-0000-4000-8000-000000000003';$third['messageId']='a3000000-0000-4000-8000-000000000003';
$third['number']='4';
ledgerReject(DomainException::class,fn()=>$ledger->append($subject,$third,$storageKey));
unset($third['number']);
$invalid=$third;$invalid['rate']=17;
ledgerReject(InvalidArgumentException::class,fn()=>$ledger->issue($subject,$invalid,$storageKey));
$thirdEntry=$ledger->issue($subject,$third,$storageKey);
ledgerCheck($thirdEntry['document']['number']==='3');
ledgerCheck($ledger->issue($subject,$third,$storageKey)===$thirdEntry);
$changed=$third;$changed['netCents']=1001;
ledgerReject(DomainException::class,fn()=>$ledger->issue($subject,$changed,$storageKey));
$fourth=$third;$fourth['documentId']='a2000000-0000-4000-8000-000000000004';$fourth['messageId']='a3000000-0000-4000-8000-000000000004';$fourth['issuedAt']='01.01.2027 00:00:00';
ledgerCheck($ledger->issue($subject,$fourth,$storageKey)['document']['number']==='1');
$another=$fourth;$another['documentId']='a2000000-0000-4000-8000-000000000005';$another['messageId']='a3000000-0000-4000-8000-000000000005';$another['register']='2';
ledgerCheck($ledger->issue($subject,$another,$storageKey)['document']['number']==='1');
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
putenv('FISKAI_EDUC_ANCHOR_PUBLIC_KEY_SHA256');
ledgerReject(LogicException::class,fn()=>$dispatch->reserve($subject,$message,$storageKey,true));
ledgerCheck($dispatch->state($subject,$message)==='PREPARED');
$soap=$dispatch->open($subject,$message,$storageKey);
EducBusinessLedger::assertBound($entry['document'],$soap,$credential);
ledgerReject(DomainException::class,fn()=>EducBusinessLedger::assertBound($next['document'],$soap,$credential));
ledgerReject(DomainException::class,fn()=>EducBusinessLedger::assertBound($entry['document'],str_replace('12.50','12.51',$soap),$credential));
$s=$db->prepare('SELECT document_sha256 FROM cis_educ_response_evidence WHERE message_id=?');$s->execute([$message]);
ledgerCheck($s->fetchColumn()===$entry['digest']);
$multi=['schemaVersion'=>2,'documentId'=>'a2000000-0000-4000-8000-000000000006','messageId'=>'a3000000-0000-4000-8000-000000000006',
    'issuedAt'=>'27.11.2026 10:00:00','premise'=>'PP1','register'=>'1','operatorOib'=>'00000000001','payment'=>'G','kind'=>'INVOICE','correctionOf'=>null,
    'lines'=>[
        ['description'=>'Test 25','quantityMilli'=>2000,'unitCents'=>1000,'discountBps'=>1000,'rate'=>25],
        ['description'=>'Test 13','quantityMilli'=>1000,'unitCents'=>500,'discountBps'=>0,'rate'=>13]
    ]];
$multiEntry=$ledger->issue($subject,$multi,$storageKey);
ledgerCheck($multiEntry['document']['number']==='4'&&$multiEntry['document']['netCents']===2300&&$multiEntry['document']['taxCents']===515&&$multiEntry['document']['totalCents']===2815);
ledgerCheck($ledger->read($subject,$multi['documentId'],$storageKey)===$multiEntry);
ledgerCheck($ledger->issue($subject,$multi,$storageKey)===$multiEntry);
$multiXml=EducBusinessLedger::request($multiEntry['document'],$key);
ledgerCheck(substr_count($multiXml,'<f:Porez>')===2&&str_contains($multiXml,'<f:IznosUkupno>28.15</f:IznosUkupno>'));
$multiPrepared=CisEducRequestPreparation::prepare($multiXml,'12345678903',$key,$credential,$pin,$dir);
EducBusinessLedger::assertBound($multiEntry['document'],$multiPrepared['soap'],$credential);
$credit=$multi;$credit['kind']='CREDIT';$credit['correctionOf']=$multi['documentId'];
$credit['documentId']='a2000000-0000-4000-8000-000000000007';$credit['messageId']='a3000000-0000-4000-8000-000000000007';
$credit['issuedAt']='27.11.2026 11:00:00';
$badCredit=$credit;$badCredit['lines'][0]['unitCents']=999;
ledgerReject(DomainException::class,fn()=>$ledger->issue($subject,$badCredit,$storageKey));
$creditEntry=$ledger->issue($subject,$credit,$storageKey);
ledgerCheck($creditEntry['document']['number']==='5'&&$creditEntry['document']['totalCents']===-2815);
ledgerCheck($ledger->issue($subject,$credit,$storageKey)===$creditEntry);
ledgerCheck($ledger->read($subject,$multi['documentId'],$storageKey)===$multiEntry);
ledgerReject(LogicException::class,fn()=>EducBusinessLedger::request($creditEntry['document'],$key));
$duplicate=$credit;$duplicate['documentId']='a2000000-0000-4000-8000-000000000008';$duplicate['messageId']='a3000000-0000-4000-8000-000000000008';
ledgerReject(DomainException::class,fn()=>$ledger->issue($subject,$duplicate,$storageKey));
$partialOriginal=$multi;
$partialOriginal['documentId']='a2000000-0000-4000-8000-000000000009';$partialOriginal['messageId']='a3000000-0000-4000-8000-000000000009';
$partialOriginal['issuedAt']='27.11.2026 12:00:00';
$partialEntry=$ledger->issue($subject,$partialOriginal,$storageKey);
$partial=$partialOriginal;$partial['kind']='PARTIAL_CREDIT';$partial['correctionOf']=$partialOriginal['documentId'];
$partial['documentId']='a2000000-0000-4000-8000-000000000010';$partial['messageId']='a3000000-0000-4000-8000-000000000010';
$partial['issuedAt']='27.11.2026 13:00:00';$partial['lines']=[$partialOriginal['lines'][0]];$partial['lines'][0]['quantityMilli']=1000;
$p1=$ledger->issue($subject,$partial,$storageKey);
ledgerCheck($p1['document']['totalCents']===-1125);
ledgerCheck($ledger->issue($subject,$partial,$storageKey)===$p1);
$rest=$partial;$rest['documentId']='a2000000-0000-4000-8000-000000000011';$rest['messageId']='a3000000-0000-4000-8000-000000000011';
$rest['lines'][]=$partialOriginal['lines'][1];
$p2=$ledger->issue($subject,$rest,$storageKey);
ledgerCheck($p2['document']['totalCents']===-1690);
ledgerCheck($ledger->read($subject,$partialOriginal['documentId'],$storageKey)===$partialEntry);
$extra=$partial;$extra['documentId']='a2000000-0000-4000-8000-000000000012';$extra['messageId']='a3000000-0000-4000-8000-000000000012';
ledgerReject(DomainException::class,fn()=>$ledger->issue($subject,$extra,$storageKey));
ledgerReject(LogicException::class,fn()=>EducBusinessLedger::request($p1['document'],$key));
require_once __DIR__.'/../src/EducAccountingPreview.php';
$accounting=(new EducAccountingPreview($db))->period($subject,'2026-11-27','2026-11-27',$storageKey);
ledgerCheck($accounting['documents']===8&&$accounting['invoices']===5&&$accounting['credits']===3);
ledgerCheck($accounting['totalCents']===1250+126+1250);
ledgerCheck($accounting['checkpoint']['position']===10);
$empty=(new EducAccountingPreview($db))->period($subject,'2026-11-28','2026-11-30',$storageKey);
ledgerCheck($empty['documents']===0&&$empty['totalCents']===0);
$head=$ledger->checkpoint($subject,$storageKey);
ledgerCheck($head['position']===10&&$head['subject']===$subject);
$mergeKey=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
$mergePublic=openssl_pkey_get_details($mergeKey)['key'];$mergePin=hash('sha256',$mergePublic);
$import=$input;$import['documentId']='a2000000-0000-4000-8000-000000000013';$import['messageId']='a3000000-0000-4000-8000-000000000013';
$import['issuedAt']='27.11.2026 14:00:00';$import['number']='9';
$badImport=$import;$badImport['documentId']='a2000000-0000-4000-8000-000000000014';$badImport['messageId']='a3000000-0000-4000-8000-000000000014';$badImport['number']='11';
$merge=function(array $documents)use($ledger,$subject,$head,$mergeKey,$mergePublic,$mergePin,$storageKey):array {
    $statement=json_encode(['subject'=>$subject,'base'=>$head,'documents'=>$documents],JSON_THROW_ON_ERROR);
    if(!openssl_sign("FISKAI-EDUC-MERGE-V1\n".$statement,$sig,$mergeKey,OPENSSL_ALGO_SHA256))throw new RuntimeException('Fixture signature failed.');
    return $ledger->mergeSigned($subject,$statement,$sig,$mergePublic,$mergePin,$storageKey);
};
ledgerReject(DomainException::class,fn()=>$merge([$import,$badImport]));
ledgerCheck($ledger->checkpoint($subject,$storageKey)===$head);
ledgerReject(DomainException::class,fn()=>$ledger->read($subject,$import['documentId'],$storageKey));
$merged=$merge([$import]);
ledgerCheck($merged['checkpoint']['position']===11&&$merged['documents'][0]['document']['number']==='9');
ledgerReject(DomainException::class,fn()=>$merge([$import]));
echo "EDUC immutable business ledger, document chain, atomic signed merge and exact signed request binding passed.\n";

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
        ledgerCheck($restored->read($subject,$multi['documentId'],$storageKey)===$multiEntry);
        ledgerCheck($restored->read($subject,$credit['documentId'],$storageKey)===$creditEntry);
        ledgerCheck($restored->read($subject,$partial['documentId'],$storageKey)===$p1);
        ledgerCheck($restored->read($subject,$rest['documentId'],$storageKey)===$p2);
        ledgerReject(DomainException::class,fn()=>$restored->issue($subject,$third,$storageKey));
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
