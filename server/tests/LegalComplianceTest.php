<?php
declare(strict_types=1);
require_once __DIR__.'/../src/LegalCompliance.php';
$cases=[
    ['2026-01-01','VAT','STANDARD_DOMESTIC_B2B','HR-F2-2026-v1','REQUIRED','REQUIRED'],
    ['2026-12-31','NON_VAT_COVERED','STANDARD_DOMESTIC_B2B','HR-F2-2026-v1','NOT_REQUIRED_BY_THIS_RULE','REQUIRED'],
    ['2027-01-01','NON_VAT_COVERED','STANDARD_DOMESTIC_B2B','HR-F2-2027-v1','REQUIRED','REQUIRED'],
    ['2027-12-31','VAT','STANDARD_DOMESTIC_B2B','HR-F2-2027-v1','REQUIRED','REQUIRED'],
    ['2025-12-31','VAT','STANDARD_DOMESTIC_B2B',null,'REVIEW_REQUIRED','REVIEW_REQUIRED'],
    ['2028-01-01','VAT','STANDARD_DOMESTIC_B2B',null,'REVIEW_REQUIRED','REVIEW_REQUIRED'],
    ['2027-01-01','UNKNOWN','STANDARD_DOMESTIC_B2B','HR-F2-2027-v1','REVIEW_REQUIRED','REVIEW_REQUIRED'],
    ['2027-01-01','VAT','UNRESOLVED','HR-F2-2027-v1','REVIEW_REQUIRED','REVIEW_REQUIRED'],
    ['2026-06-01','NON_VAT_COVERED','STANDARD_DOMESTIC_B2B','HR-F2-2026-v1','NOT_REQUIRED_BY_THIS_RULE','REQUIRED'],
    ['2026-06-01','VAT','STANDARD_DOMESTIC_B2B','HR-F2-2026-v1','REQUIRED','REQUIRED'],
];
foreach($cases as [$date,$subject,$scope,$version,$issue,$receive]){
    $actual=LegalCompliance::evaluate($date,$subject,$scope);
    $expected=['version'=>$version,'issue'=>$issue,'receive'=>$receive,'fiscalizeIssue'=>$issue,'fiscalizeReceive'=>$receive];
    if($actual!==$expected)throw new RuntimeException('Unexpected decision: '.$date.' '.$subject);
}
foreach([['2027-02-29','VAT','STANDARD_DOMESTIC_B2B'],['2027-1-01','VAT','STANDARD_DOMESTIC_B2B'],['2027-01-01','OTHER','STANDARD_DOMESTIC_B2B'],['2027-01-01','VAT','B2C']] as $args){
    try { LegalCompliance::evaluate(...$args); }
    catch(InvalidArgumentException $e){continue;}
    throw new RuntimeException('Invalid context accepted.');
}
echo "Legal compliance: 14 cases passed\n";
