<?php
declare(strict_types=1);
require_once __DIR__.'/../src/FiscalReportingRules.php';
function equal(mixed $actual,mixed $expected):void {
    if($actual!==$expected)throw new RuntimeException('Unexpected deadline: '.json_encode($actual));
}
foreach(['PAYMENT','REJECTION'] as $event){
    foreach(['2026-12-31'=>'2027-01-20','2027-02-01'=>'2027-03-20','2027-12-31'=>'2028-01-20'] as $date=>$expected)
        equal(FiscalReportingRules::deadline($event,$date),['kind'=>'MONTHLY_STATUTORY_DATE','date'=>$expected,'workingDays'=>null]);
}
foreach(['ISSUED','ARTICLE_51_SUPPLY'] as $event)
    equal(FiscalReportingRules::deadline($event,'2027-01-01'),['kind'=>'AT_ISSUE','date'=>'2027-01-01','workingDays'=>null]);
foreach(['SELF_BILLED','RECEIVED','TECHNICAL_INTERRUPTION'] as $event)
    equal(FiscalReportingRules::deadline($event,'2027-01-01'),['kind'=>'WORKING_DAYS_REVIEW','date'=>null,'workingDays'=>5]);
equal(FiscalReportingRules::deadline('ARTICLE_51_SUPPLY','2027-01-01',true),['kind'=>'WORKING_DAYS_REVIEW','date'=>null,'workingDays'=>5]);
equal(FiscalReportingRules::deadline('PAYMENT','2028-01-01'),['kind'=>'REVIEW_REQUIRED','date'=>null,'workingDays'=>null]);
foreach([['PAYMENT','2027-02-29'],['UNKNOWN','2027-01-01'],['REJECTION','2027-01-01',true]] as $args){
    try{FiscalReportingRules::deadline(...$args);}catch(InvalidArgumentException $e){continue;}
    throw new RuntimeException('Invalid event accepted.');
}
echo "Fiscal reporting deadline tests passed\n";
