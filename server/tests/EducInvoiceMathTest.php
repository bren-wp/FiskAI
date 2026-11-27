<?php
declare(strict_types=1);
require_once __DIR__.'/../src/EducInvoiceMath.php';
function mathCheck(bool $ok):void {if(!$ok)throw new RuntimeException('Invoice calculation mismatch.');}
function mathReject(array $lines):void {
    try{EducInvoiceMath::calculate($lines);}catch(InvalidArgumentException){return;}
    throw new RuntimeException('Invalid invoice accepted.');
}
$line=fn($q,$u,$d,$r)=>['description'=>'Test','quantityMilli'=>$q,'unitCents'=>$u,'discountBps'=>$d,'rate'=>$r];
$lines=[$line(2000,1000,1000,25),$line(1500,200,0,13),$line(1000,100,0,5),$line(1000,200,0,25)];
$m=EducInvoiceMath::calculate($lines);
mathCheck($m['netCents']===2400&&$m['discountCents']===200&&$m['taxCents']===544&&$m['totalCents']===2944);
mathCheck($m['vatBreakdown']===[['rate'=>5,'netCents'=>100,'taxCents'=>5],['rate'=>13,'netCents'=>300,'taxCents'=>39],['rate'=>25,'netCents'=>2000,'taxCents'=>500]]);
$m=EducInvoiceMath::calculate([$line(1500,1,0,25),$line(1000,1,0,25)]);
mathCheck($m['netCents']===3&&$m['taxCents']===1);
$m=EducInvoiceMath::calculate([$line(1000,101,5000,13)]);
mathCheck($m['netCents']===50&&$m['discountCents']===51&&$m['taxCents']===7);
foreach([[],[$line(0,100,0,25)],[$line(1000,-100,0,25)],[$line(1000,100,10001,25)],[$line(1000,100,0,17)],[$line(1000,100,10000,25)],[$line(1.5,100,0,25)],array_fill(0,31,$line(1000,100,0,25))] as $bad)mathReject($bad);
echo "Multi-rate integer invoice arithmetic and invalid input checks passed.\n";
