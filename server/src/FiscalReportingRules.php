<?php
declare(strict_types=1);
require_once __DIR__.'/LegalCompliance.php';

/** Baseline for already classified events. No implied submission or acceptance. */
final class FiscalReportingRules {
    public static function deadline(string $event,string $date,bool $selfBilled=false):array {
        LegalCompliance::validateDate($date);
        if (!in_array($event,['PAYMENT','REJECTION','ARTICLE_51_SUPPLY','ISSUED','SELF_BILLED','RECEIVED','TECHNICAL_INTERRUPTION'],true)
            || ($selfBilled && $event!=='ARTICLE_51_SUPPLY')) throw new InvalidArgumentException('Invalid reporting event.');
        $year=(int)substr($date,0,4);
        $rules=require __DIR__.'/LegalRuleCatalog.php';
        if (!isset($rules[$year])) return self::result('REVIEW_REQUIRED');
        if (in_array($event,['PAYMENT','REJECTION'],true)) {
            $month=(int)substr($date,5,2);
            return self::result('MONTHLY_STATUTORY_DATE',sprintf('%04d-%02d-20',$month===12?$year+1:$year,$month===12?1:$month+1));
        }
        if ($event==='ISSUED' || ($event==='ARTICLE_51_SUPPLY' && !$selfBilled)) {
            return self::result('AT_ISSUE',$date);
        }
        return self::result('WORKING_DAYS_REVIEW',null,5);
    }
    private static function result(string $kind,?string $date=null,?int $workingDays=null):array {
        return compact('kind','date','workingDays');
    }
}
