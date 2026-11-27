<?php
declare(strict_types=1);

/** Versioned EDUC arithmetic, not a tax classification or legal rounding policy. */
final class EducInvoiceMath {
    public static function calculate(array $lines):array {
        if(!array_is_list($lines)||count($lines)<1||count($lines)>30)throw new InvalidArgumentException('Invalid invoice lines.');
        $normalized=[];$groups=[];$net=0;$discount=0;
        foreach($lines as $line){
            if(!is_array($line)||count($line)!==5||array_diff(['description','quantityMilli','unitCents','discountBps','rate'],array_keys($line))||
                !is_string($line['description'])||strlen($line['description'])<1||strlen($line['description'])>120||
                !preg_match('//u',$line['description'])||preg_match('/[\x00-\x1f\x7f]/',$line['description']))
                throw new InvalidArgumentException('Invalid line description or fields.');
            foreach(['quantityMilli','unitCents','discountBps','rate'] as $field)
                if(!is_int($line[$field]))throw new InvalidArgumentException('Integer arithmetic required.');
            $q=$line['quantityMilli'];$u=$line['unitCents'];$d=$line['discountBps'];$rate=$line['rate'];
            if($q<1||$q>1000000||$u<1||$u>100000000||$d<0||$d>10000||!in_array($rate,[5,13,25],true))
                throw new InvalidArgumentException('Unsupported line values.');
            $gross=intdiv($q*$u+500,1000);
            $rebate=intdiv($gross*$d+5000,10000);
            $base=$gross-$rebate;
            $net+=$base;$discount+=$rebate;
            if($net>99999999999)throw new InvalidArgumentException('Invoice amount too large.');
            $groups[$rate]=($groups[$rate]??0)+$base;
            $normalized[]=['description'=>$line['description'],'quantityMilli'=>$q,'unitCents'=>$u,'discountBps'=>$d,'rate'=>$rate];
        }
        if($net<1)throw new InvalidArgumentException('Positive invoice amount required.');
        ksort($groups,SORT_NUMERIC);$tax=0;$breakdown=[];
        foreach($groups as $rate=>$base){
            $amount=intdiv($base*$rate+50,100);$tax+=$amount;
            $breakdown[]=['rate'=>$rate,'netCents'=>$base,'taxCents'=>$amount];
        }
        return ['lines'=>$normalized,'netCents'=>$net,'discountCents'=>$discount,'taxCents'=>$tax,'totalCents'=>$net+$tax,'vatBreakdown'=>$breakdown];
    }
}
