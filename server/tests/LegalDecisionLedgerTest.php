<?php
declare(strict_types=1);
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/LegalDecisionLedger.php';
require_once __DIR__.'/../src/TaxStatusHistory.php';
function verify(bool $ok):void {if(!$ok)throw new RuntimeException('Legal decision ledger test failed.');}
function reject(string $type,callable $fn):void {try{$fn();}catch(Throwable $e){if($e instanceof $type)return;throw $e;}throw new RuntimeException('Expected rejection missing.');}
$db=db();$key=random_bytes(32);$ledger=new LegalDecisionLedger($db,$key);
$subject='c1000000-0000-4000-8000-000000000001';$id='c2000000-0000-4000-8000-000000000001';
$document=hash('sha256','fixture');$reviewHash=hash('sha256','review fixture');
$history=new TaxStatusHistory([['from'=>'2026-01-01','until'=>null,'subject'=>'NON_VAT_COVERED']]);
$review=['documentSha256'=>$document,'assessment'=>$history->assess('2027-01-01','STANDARD_DOMESTIC_B2B'),'deadline'=>null];
reject(LogicException::class,fn()=>$ledger->append($subject,$id,$review));
$db->beginTransaction();
try{
    $s=$db->prepare('INSERT INTO educ_business_subject(subject_id,owner_oib,key_id) VALUES (?,?,?)');
    $s->execute([$subject,'98765432109','legal-fixture']);
    $s=$db->prepare("INSERT INTO f2_educ_intake(subject_id,document_id,direction,identity_sha256,document_sha256,review_sha256,sealed_evidence,created_at) VALUES (?,?,'ISSUED',?,?,?,?,UTC_TIMESTAMP(6))");
    $s->execute([$subject,$id,hash('sha256','identity'),$document,$reviewHash,'synthetic']);
    $a=$ledger->append($subject,$id,$review);
    verify($a['rule']['version']==='HR-F2-2027-v1'&&$a['dispatchAuthorized']===false);
    verify($ledger->append($subject,$id,$review)===$a);
    verify($ledger->read($subject,$id)===$a);
    verify($a['catalogSha256']===hash_file('sha256',__DIR__.'/../contracts/legal-rules.json'));
    $catalog=file_get_contents(__DIR__.'/../contracts/legal-rules.json');
    $line='1|'.str_repeat('0',64).'|'.$a['catalogSha256']."\n";
    $history="FISKAI-REGULATORY-HISTORY-V1\n".$line;
    $signing=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
    verify(openssl_sign($history,$signature,$signing,OPENSSL_ALGO_SHA256));
    $public=openssl_pkey_get_details($signing)['key'];
    $resolver=HistoricalLegalDecisionResolver::verified($history,$signature,$public,hash('sha256',$public),1,hash('sha256',$line),[$catalog]);
    $audit=$ledger->auditRevision($subject,$id,$resolver);
    verify($audit['sequence']===1&&$audit['decision']===$a['assessment']['decision']&&$audit['dispatchAuthorized']===false);
    verify($ledger->read('c1000000-0000-4000-8000-000000000002',$id)===null);
    reject(RuntimeException::class,fn()=>(new LegalDecisionLedger($db,random_bytes(32)))->read($subject,$id));
    $changed=$review;$changed['deadline']=['kind'=>'changed'];
    reject(DomainException::class,fn()=>$ledger->append($subject,$id,$changed));
    verify($ledger->read($subject,$id)===$a);
    $s=$db->prepare("UPDATE legal_decision_ledger SET decision_json='{}' WHERE subject_id=? AND document_id=?");
    $s->execute([$subject,$id]);
    reject(RuntimeException::class,fn()=>$ledger->read($subject,$id));
}finally{$db->rollBack();}
verify($ledger->read($subject,$id)===null);
echo "Legal ledger transaction, idempotency, scope and tamper checks passed.\n";
