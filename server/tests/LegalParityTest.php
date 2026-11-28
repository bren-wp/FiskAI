<?php
declare(strict_types=1);
require_once __DIR__.'/../src/FiscalReportingRules.php';
$hash=hash_init('sha256');$count=0;
$emit=function(array $fields)use($hash,&$count):void {
    hash_update($hash,implode('|',array_map(static fn($v)=>$v===null?'-':(string)$v,$fields))."\n");
    $count++;
};
for($d=new DateTimeImmutable('2025-12-30',new DateTimeZone('UTC'));$d<new DateTimeImmutable('2028-01-03',new DateTimeZone('UTC'));$d=$d->modify('+1 day')){
    $date=$d->format('Y-m-d');
    foreach(['VAT','NON_VAT_COVERED','UNKNOWN'] as $subject)foreach(['STANDARD_DOMESTIC_B2B','UNRESOLVED'] as $scope){
        $x=LegalCompliance::evaluate($date,$subject,$scope);
        $emit(['L',$date,$subject,$scope,$x['version'],$x['issue'],$x['receive'],$x['fiscalizeIssue'],$x['fiscalizeReceive']]);
    }
    foreach(['PAYMENT','REJECTION','ARTICLE_51_SUPPLY','ISSUED','SELF_BILLED','RECEIVED','TECHNICAL_INTERRUPTION'] as $event)
        foreach($event==='ARTICLE_51_SUPPLY'?[false,true]:[false] as $self){
            $x=FiscalReportingRules::deadline($event,$date,$self);
            $emit(['R',$date,$event,(int)$self,$x['kind'],$x['date'],$x['workingDays']]);
        }
}
$expected='4c42d68d72352bda13ca28503f33489a6d3b85e0c508bdb47a4c06f2b31fbb6a';
if($count!==10276||!hash_equals($expected,hash_final($hash)))throw new RuntimeException('Legal parity baseline mismatch.');
echo "Legal parity: $count decisions match fixed baseline.\n";
