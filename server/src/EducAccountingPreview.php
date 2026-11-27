<?php
declare(strict_types=1);
require_once __DIR__.'/EducBusinessLedger.php';
require_once __DIR__.'/LegalCompliance.php';

/** EDUC analytical output over authenticated immutable invoice records.
 * Not a statutory VAT return, general ledger or tax liability calculation.
 */
final class EducAccountingPreview {
    public function __construct(private PDO $db){}
    public function period(string $subject,string $from,string $until,string $key):array {
        LegalCompliance::validateDate($from);LegalCompliance::validateDate($until);
        if($from>$until)throw new InvalidArgumentException('Invalid period.');
        if($this->db->inTransaction())throw new LogicException('Own transaction required.');
        $this->db->beginTransaction();
        try{
            $ledger=new EducBusinessLedger($this->db);
            $entries=$ledger->verifySubject($subject,$key);
            $result=self::summarize($entries,$from,$until);
            $result['checkpoint']=$ledger->checkpoint($subject,$key);
            $this->db->commit();return $result;
        }catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    /** Pure projection of already authenticated documents. No posting or legal classification. */
    public static function summarize(array $entries,string $from,string $until):array {
        LegalCompliance::validateDate($from);LegalCompliance::validateDate($until);
        if($from>$until)throw new InvalidArgumentException('Invalid period.');
        $groups=[];$count=0;$invoices=0;$credits=0;$net=0;$tax=0;$gross=0;
        foreach($entries as $entry){
            $d=$entry['document'];
            $date=substr($d['issuedAt'],6,4).'-'.substr($d['issuedAt'],3,2).'-'.substr($d['issuedAt'],0,2);
            LegalCompliance::validateDate($date);
            if($date<$from||$date>$until)continue;
            $credit=in_array($d['kind']??null,['CREDIT','PARTIAL_CREDIT'],true);
            if($credit)$credits++;else $invoices++;
            $count++;
            $rows=$d['vatBreakdown']??[['rate'=>$d['rate'],'netCents'=>$d['netCents'],'taxCents'=>$d['taxCents']]];
            $base=0;$vat=0;
            foreach($rows as $g){
                $r=$g['rate'];
                if(!isset($groups[$r]))$groups[$r]=['rate'=>$r,'invoiceNetCents'=>0,'invoiceTaxCents'=>0,'creditNetCents'=>0,'creditTaxCents'=>0,'netCents'=>0,'taxCents'=>0];
                $prefix=$credit?'credit':'invoice';
                $groups[$r][$prefix.'NetCents']+=$g['netCents'];
                $groups[$r][$prefix.'TaxCents']+=$g['taxCents'];
                $groups[$r]['netCents']+=$g['netCents'];$groups[$r]['taxCents']+=$g['taxCents'];
                $base+=$g['netCents'];$vat+=$g['taxCents'];
            }
            if($base!==$d['netCents']||$vat!==$d['taxCents']||$base+$vat!==$d['totalCents'])
                throw new RuntimeException('Document totals do not reconcile.');
            $net+=$base;$tax+=$vat;$gross+=$d['totalCents'];
        }
        ksort($groups,SORT_NUMERIC);
        return ['status'=>'EDUC_ACCOUNTING_PREVIEW','from'=>$from,'until'=>$until,'documents'=>$count,'invoices'=>$invoices,'credits'=>$credits,
            'netCents'=>$net,'taxCents'=>$tax,'totalCents'=>$gross,'vatBreakdown'=>array_values($groups)];
    }
}
