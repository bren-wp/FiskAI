<?php
declare(strict_types=1);
require_once __DIR__.'/../src/SchemaMigrator.php';
require_once __DIR__.'/../src/ServerRecovery.php';
require_once __DIR__.'/../src/EducBusinessLedger.php';
require_once __DIR__.'/../src/DemoSync.php';
require_once __DIR__.'/../src/PriceLists.php';
require_once __DIR__.'/../src/FiscalEventOutbox.php';
require_once __DIR__.'/../src/F2EducIntake.php';
function recoveryCheck(bool $ok):void {if(!$ok)throw new RuntimeException('Server recovery test failed.');}
function recoveryReject(string $type,callable $call):void {
    try{$call();}catch(Throwable $e){if($e instanceof $type)return;throw $e;}
    throw new RuntimeException('Expected recovery rejection missing.');
}
$admin=db();$dsn=getenv('FISKAI_DB_DSN');
if(!str_contains($dsn,'dbname=fiskai_test;'))throw new RuntimeException('CI database required.');
$names=[];$connections=[];
try{
    for($i=0;$i<2;$i++){
        $name='fiskai_recovery_test_'.bin2hex(random_bytes(6));
        $admin->exec('CREATE DATABASE `'.$name.'` CHARACTER SET utf8mb4');$names[]=$name;
        $conn=new PDO(str_replace('dbname=fiskai_test;','dbname='.$name.';',$dsn),getenv('FISKAI_DB_USER'),getenv('FISKAI_DB_PASSWORD'),
            [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_EMULATE_PREPARES=>false]);
        $conn->exec("SET time_zone='+00:00'");
        SchemaMigrator::apply($conn,__DIR__.'/../database');$connections[]=$conn;
    }
    [$source,$target]=$connections;
    $subject='b1000000-0000-4000-8000-000000000001';$document='b2000000-0000-4000-8000-000000000001';
    $message='b3000000-0000-4000-8000-000000000001';$storageKey=random_bytes(32);$archiveKey=random_bytes(32);
    $ledger=new EducBusinessLedger($source);$ledger->provision($subject,'12345678903','server-fixture');
    $entry=$ledger->append($subject,['documentId'=>$document,'messageId'=>$message,'issuedAt'=>'27.11.2026 09:00:00',
        'number'=>'1','premise'=>'PP1','register'=>'1','operatorOib'=>'00000000001','netCents'=>1000,'rate'=>25,'payment'=>'G'],$storageKey);
    $device=(new DemoSync($source))->provision($subject);
    $event='b4000000-0000-4000-8000-000000000001';
    (new FiscalEventOutbox($source))->prepare('EDUC',$subject,$event,$document,'PAYMENT','2026-11-27',hash('sha256','fixture'));
    $site='recovery_fixture';
    PriceLists::publish($source,['site'=>$site,'kind'=>'USLUGE','identifier'=>'12345678903','location'=>'Test',
        'rows'=>[['naziv'=>'Čišćenje','maloprodajna_cijena'=>'12.50','poseban_oblik_prodaje'=>false,'bazna_cijena'=>'10.00']]]);
    $price=PriceLists::download($source,$site,'USLUGE',null);
    // Synthetic authenticated evidence exercises both the F2 read guard and full restore.
    $f2id='b5000000-0000-4000-8000-000000000001';$f2key=random_bytes(32);
    $xml='<synthetic-fixture/>';$docHash=hash('sha256',$xml);
    $history=new TaxStatusHistory([['from'=>'2026-01-01','until'=>null,'subject'=>'VAT']]);
    $review=['documentSha256'=>$docHash,'assessment'=>$history->assess('2027-01-01','STANDARD_DOMESTIC_B2B'),
        'deadline'=>FiscalReportingRules::deadline('ISSUED','2027-01-01')];
    $evidence=['xml'=>$xml,'review'=>$review,'legalDecisionFormat'=>1];
    $plain=json_encode($evidence,JSON_THROW_ON_ERROR);
    $reviewHash=hash('sha256',$plain);$identity=hash('sha256','synthetic identity');
    $aad=implode("\0",['FISKAI-F2-EDUC-INTAKE-V1',$subject,$f2id,'ISSUED',$identity,$docHash,$reviewHash]);
    $nonce=random_bytes(12);$tag='';
    $cipher=openssl_encrypt($plain,'aes-256-gcm',$f2key,OPENSSL_RAW_DATA,$nonce,$tag,$aad,16);
    recoveryCheck(is_string($cipher)&&strlen($tag)===16);
    $source->beginTransaction();
    try{
        $s=$source->prepare("INSERT INTO f2_educ_intake(subject_id,document_id,direction,identity_sha256,document_sha256,review_sha256,sealed_evidence,created_at) VALUES (?,?,'ISSUED',?,?,?,?,UTC_TIMESTAMP(6))");
        $s->execute([$subject,$f2id,$identity,$docHash,$reviewHash,base64_encode($nonce.$tag.$cipher)]);
        $snapshot=(new LegalDecisionLedger($source,$f2key))->append($subject,$f2id,$review);
        $source->commit();
    }catch(Throwable $e){if($source->inTransaction())$source->rollBack();throw $e;}
    recoveryCheck((new F2EducIntake($source,$f2key))->read($subject,$f2id)===$evidence);
    $archive=(new ServerRecovery($source))->export($archiveKey);
    $restore=new ServerRecovery($target);
    putenv('FISKAI_SERVER_RECOVERY_MODE=0');
    recoveryReject(LogicException::class,fn()=>$restore->restore($archive,$archiveKey));
    putenv('FISKAI_SERVER_RECOVERY_MODE=1');putenv('FISKAI_CIS_RECOVERY_MODE=1');
    putenv('FISKAI_CIS_EDUC_ENABLED=0');putenv('FISKAI_DEMO_SYNC_ENABLED=0');
    $bad=$archive;$bad[strlen($bad)-1]=chr(ord($bad[strlen($bad)-1])^1);
    recoveryReject(InvalidArgumentException::class,fn()=>$restore->restore($bad,$archiveKey));
    recoveryReject(InvalidArgumentException::class,fn()=>$restore->restore($archive,random_bytes(32)));
    $counts=$restore->restore($archive,$archiveKey);
    recoveryCheck(count($counts)===22&&$counts['educ_business_document']===1&&$counts['price_lists']===1);
    recoveryCheck((new EducBusinessLedger($target))->read($subject,$document,$storageKey)===$entry);
    recoveryCheck($counts['legal_decision_ledger']===1&&$counts['f2_educ_intake']===1);
    recoveryCheck((new LegalDecisionLedger($target,$f2key))->read($subject,$f2id)===$snapshot);
    $intake=new F2EducIntake($target,$f2key);
    recoveryCheck($intake->read($subject,$f2id)===$evidence);
    $target->beginTransaction();
    try{
        $s=$target->prepare('DELETE FROM legal_decision_ledger WHERE subject_id=? AND document_id=?');$s->execute([$subject,$f2id]);
        recoveryReject(RuntimeException::class,fn()=>$intake->read($subject,$f2id));
    }finally{$target->rollBack();}
    recoveryCheck($intake->read($subject,$f2id)===$evidence);
    $restoredPrice=PriceLists::download($target,$site,'USLUGE',null);
    recoveryCheck($restoredPrice['csv_content']===$price['csv_content']&&$restoredPrice['xml_content']===$price['xml_content']);
    recoveryCheck((new FiscalEventOutbox($target))->read('EDUC',$subject,$event)['state']==='UNCERTAIN');
    $s=$target->prepare('SELECT revoked_at FROM demo_sync_device WHERE device_id=?');$s->execute([$device['deviceId']]);
    recoveryCheck(is_string($s->fetchColumn()));
    $s=$target->prepare('SELECT COUNT(*) FROM educ_recovery_quarantine WHERE subject_id=?');$s->execute([$subject]);
    recoveryCheck((int)$s->fetchColumn()===1);
    recoveryReject(DomainException::class,fn()=>$restore->restore($archive,$archiveKey));
    echo "Full application table archive: authenticated restore, binary data, credential invalidation and quarantine passed.\n";
}finally{
    $connections=[];$source=null;$target=null;$conn=null;
    foreach($names as $name)$admin->exec('DROP DATABASE `'.$name.'`');
}
