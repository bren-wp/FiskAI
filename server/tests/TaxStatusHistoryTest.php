<?php
declare(strict_types=1);
require_once __DIR__.'/../src/TaxStatusHistory.php';
function period(string $from,?string $until,string $subject):array {
    return compact('from','until','subject');
}
function verify(bool $ok):void { if (!$ok) throw new RuntimeException('Unexpected tax history result.'); }
$b2b='STANDARD_DOMESTIC_B2B';
$rows=[
    period('2027-01-01',null,'NON_VAT_COVERED'),
    period('2026-07-01','2026-12-01','VAT'),
    period('2026-01-01','2026-07-01','NON_VAT_COVERED'),
];
$history=new TaxStatusHistory($rows);
foreach ([
    ['2025-12-31','UNKNOWN','REVIEW_REQUIRED'],
    ['2026-06-30','NON_VAT_COVERED','NOT_REQUIRED_BY_THIS_RULE'],
    ['2026-07-01','VAT','REQUIRED'],
    ['2026-12-01','UNKNOWN','REVIEW_REQUIRED'],
    ['2027-01-01','NON_VAT_COVERED','REQUIRED'],
] as [$date,$subject,$issue]) {
    $result=$history->assess($date,$b2b);
    verify($result['eventDate']===$date && $result['subject']===$subject && $result['decision']['issue']===$issue);
}
verify($history->assess('2026-12-31',$b2b)['effectivePeriod']===null);
verify($history->assess('2027-01-01','UNRESOLVED')['decision']['issue']==='REVIEW_REQUIRED');
verify($history->assess('2027-01-01',$b2b)['decision']['version']==='HR-F2-2027-v1');
foreach ([
    [period('2026-02-29',null,'VAT')],
    [period('2026-01-01','2026-01-01','VAT')],
    [period('2026-01-01',null,'VAT'),period('2027-01-01',null,'NON_VAT_COVERED')],
    [period('2026-01-01','2026-08-01','VAT'),period('2026-07-01',null,'VAT')],
    [period('2026-01-01',null,'UNVERIFIED')],
] as $invalid) {
    try { new TaxStatusHistory($invalid); }
    catch (InvalidArgumentException $e) { continue; }
    throw new RuntimeException('Invalid timeline accepted.');
}
$previous=$history->assess('2026-06-30',$b2b);
$rows[2]['subject']='VAT';
verify($history->assess('2026-06-30',$b2b)===$previous);
verify((new TaxStatusHistory($rows))->assess('2026-06-30',$b2b)['decision']['issue']==='REQUIRED');
echo "Tax status history tests passed\n";
