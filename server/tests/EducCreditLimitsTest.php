<?php
declare(strict_types=1);
require_once __DIR__.'/../src/EducInvoiceMath.php';
require_once __DIR__.'/../src/EducCreditLimits.php';
function creditReject(callable $f):void {
    try{$f();}catch(DomainException){return;}
    throw new RuntimeException('Invalid credit accepted.');
}
function line(int $quantity,int $price=1000,int $rate=25,int $discount=1000):array {
    return ['description'=>'Fixture','quantityMilli'=>$quantity,'unitCents'=>$price,'discountBps'=>$discount,'rate'=>$rate];
}
function credit(array $lines):array {
    $result=EducInvoiceMath::calculate($lines);
    foreach(['netCents','taxCents','totalCents','discountCents'] as $k)$result[$k]*=-1;
    foreach($result['vatBreakdown'] as &$g){$g['netCents']*=-1;$g['taxCents']*=-1;}
    unset($g);return $result;
}
$original=EducInvoiceMath::calculate([line(2000),line(1000,500,13,0)]);
$a=credit([line(1000)]);$b=credit([line(1000),line(1000,500,13,0)]);
EducCreditLimits::check($original,[$a]);
EducCreditLimits::check($original,[$a,$b]);
creditReject(fn()=>EducCreditLimits::check($original,[$a,$b,$a]));
creditReject(fn()=>EducCreditLimits::check($original,[credit([line(3000)])]));
creditReject(fn()=>EducCreditLimits::check($original,[credit([line(1000,1001)])]));
creditReject(fn()=>EducCreditLimits::check($original,[credit([line(1000,1000,13)])]));
// Original 3 cents at 25% -> 1 cent VAT; independently rounded 2 + 1 cents -> 1 + 0.
$small=EducInvoiceMath::calculate([line(3000,1,25,0)]);
EducCreditLimits::check($small,[credit([line(2000,1,25,0)]),credit([line(1000,1,25,0)])]);
// Three individually rounded 1-cent credits leave an unreversed VAT cent.
creditReject(fn()=>EducCreditLimits::check($small,array_fill(0,3,credit([line(1000,1,25,0)]))));
$over=EducInvoiceMath::calculate([line(4000,1,25,0)]);
creditReject(fn()=>EducCreditLimits::check($over,[credit([line(2000,1,25,0)]),credit([line(2000,1,25,0)])]));
echo "Partial credit quantities, rate matching, cumulative caps and rounding residual checks passed.\n";
