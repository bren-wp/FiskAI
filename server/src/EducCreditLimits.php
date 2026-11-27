<?php
declare(strict_types=1);

/** Offline correction limits for the existing v2 rounding model.
 * Never invent a balancing cent or change historical invoice amounts.
 */
final class EducCreditLimits {
    private static function quantities(array $lines):array {
        $totals=[];
        foreach($lines as $line){
            $key=json_encode([$line['description'],$line['unitCents'],$line['discountBps'],$line['rate']],JSON_THROW_ON_ERROR);
            $totals[$key]=($totals[$key]??0)+$line['quantityMilli'];
        }
        return $totals;
    }
    public static function check(array $original,array $credits):void {
        $remaining=self::quantities($original['lines']);
        $groups=[];
        foreach($original['vatBreakdown'] as $g)$groups[$g['rate']]=[$g['netCents'],$g['taxCents']];
        $discount=$original['discountCents'];
        foreach($credits as $credit){
            foreach(self::quantities($credit['lines']) as $key=>$q){
                if(!isset($remaining[$key])||$q>$remaining[$key])throw new DomainException('Credit quantity exceeds original.');
                $remaining[$key]-=$q;
            }
            foreach($credit['vatBreakdown'] as $g){
                $rate=$g['rate'];
                if(!isset($groups[$rate])||$g['netCents']>0||$g['taxCents']>0)
                    throw new DomainException('Credit tax group mismatch.');
                $groups[$rate][0]+=$g['netCents'];
                $groups[$rate][1]+=$g['taxCents'];
                if(min($groups[$rate])<0)throw new DomainException('Credit exceeds original tax amounts.');
            }
            $discount+=$credit['discountCents'];
            if($discount<0)throw new DomainException('Credit exceeds original discount.');
        }
        // Independent rounding of partial quantities can produce a cent difference.
        // Fail closed rather than silently discarding the residual.
        if(array_sum($remaining)===0){
            foreach($groups as $g)if($g!==[0,0])throw new DomainException('Full quantity reversal requires rounding review.');
            if($discount!==0)throw new DomainException('Full discount reversal requires rounding review.');
        }
    }
}
