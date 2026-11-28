<?php
declare(strict_types=1);
require_once __DIR__.'/../src/HistoricalLegalDecisionResolver.php';
function check(bool $ok):void{if(!$ok)throw new RuntimeException('Historical resolution failed.');}
function reject(callable $fn):void{
    try{$fn();}catch(InvalidArgumentException|DomainException){return;}
    throw new RuntimeException('Invalid historical revision accepted.');
}
$active=file_get_contents(__DIR__.'/../contracts/legal-rules.json');
$old=json_decode($active,true,16,JSON_THROW_ON_ERROR);
$old['rules'][1]['nonVatIssue']=false; // synthetic regression fixture, NOT legal evidence
$old['rules'][1]['version']='HR-F2-2027-v99';
$old=json_encode($old,JSON_THROW_ON_ERROR);
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
if(!$key)throw new RuntimeException('Fixture key unavailable.');
$public=openssl_pkey_get_details($key)['key'];$pin=hash('sha256',$public);
$l1='1|'.str_repeat('0',64).'|'.hash('sha256',$old)."\n";
$l2='2|'.hash('sha256',$l1).'|'.hash('sha256',$active)."\n";
$history="FISKAI-REGULATORY-HISTORY-V1\n".$l1.$l2;
check(openssl_sign($history,$signature,$key,OPENSSL_ALGO_SHA256));
$resolver=HistoricalLegalDecisionResolver::verified($history,$signature,$public,$pin,2,hash('sha256',$l2),[$old,$active]);
$a=$resolver->resolve(1,hash('sha256',$old),'2027-01-01','NON_VAT_COVERED','STANDARD_DOMESTIC_B2B');
$b=$resolver->resolve(2,hash('sha256',$active),'2027-01-01','NON_VAT_COVERED','STANDARD_DOMESTIC_B2B');
check($a['decision']['issue']==='NOT_REQUIRED_BY_THIS_RULE'&&$b['decision']['issue']==='REQUIRED');
check($a['decision']['version']==='HR-F2-2027-v99'&&$b['dispatchAuthorized']===false);
check($resolver->resolve(2,hash('sha256',$active),'2028-01-01','VAT','STANDARD_DOMESTIC_B2B')['decision']['issue']==='REVIEW_REQUIRED');
reject(fn()=>$resolver->resolve(1,hash('sha256',$active),'2027-01-01','VAT','STANDARD_DOMESTIC_B2B'));
reject(fn()=>$resolver->resolve(3,hash('sha256',$active),'2027-01-01','VAT','STANDARD_DOMESTIC_B2B'));
echo "Verified historical revision resolution, explicit digest binding and fail-closed gaps passed.\n";
