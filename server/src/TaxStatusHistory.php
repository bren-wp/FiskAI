<?php
declare(strict_types=1);
require_once __DIR__.'/LegalCompliance.php';

/** Timeline belongs to one business subject. No inference from current tax status. */
final class TaxStatusHistory {
    private array $timeline;
    public function __construct(array $periods) {
        if (!array_is_list($periods) || count($periods)>1000) throw new InvalidArgumentException('Invalid timeline.');
        foreach ($periods as $row) {
            if (!is_array($row) || count($row)!==3 || !isset($row['from'],$row['subject']) || !array_key_exists('until',$row)
                || !is_string($row['from']) || ($row['until']!==null && !is_string($row['until']))
                || !in_array($row['subject'],['VAT','NON_VAT_COVERED','UNKNOWN'],true)) {
                throw new InvalidArgumentException('Invalid tax period.');
            }
            LegalCompliance::validateDate($row['from']);
            if ($row['until']!==null) {
                LegalCompliance::validateDate($row['until']);
                if ($row['until']<=$row['from']) throw new InvalidArgumentException('Empty tax period.');
            }
        }
        usort($periods,static fn(array $a,array $b):int => strcmp($a['from'],$b['from']));
        for ($i=1;$i<count($periods);$i++) {
            $end=$periods[$i-1]['until'];
            if ($end===null || $end>$periods[$i]['from']) throw new InvalidArgumentException('Overlapping tax status periods.');
        }
        $this->timeline=$periods;
    }
    /** from inclusive, until exclusive. Assessment is not issuance authorization. */
    public function assess(string $date,string $scope):array {
        LegalCompliance::validateDate($date);
        $period=null;
        foreach ($this->timeline as $row) {
            if ($row['from']<=$date && ($row['until']===null || $date<$row['until'])) {
                $period=$row; break;
            }
        }
        $subject=$period['subject']??'UNKNOWN';
        return ['eventDate'=>$date,'subject'=>$subject,'scope'=>$scope,
            'effectivePeriod'=>$period,'decision'=>LegalCompliance::evaluate($date,$subject,$scope)];
    }
}
