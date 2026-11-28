<?php
declare(strict_types=1);
require_once __DIR__.'/RegulatoryTrustAnchor.php';
require_once __DIR__.'/LegalCompliance.php';

/** Read-only reconstruction for the supported narrow baseline. Never authorizes dispatch.
 * A stored revision identity, not the current date or latest revision, selects the catalog. */
final class HistoricalLegalDecisionResolver {
    private function __construct(private array $catalogs) {}
    public static function verified(string $history,string $signature,string $publicKey,string $pin,
        int $latestSequence,string $latestHead,array $catalogs):self {
        RegulatoryTrustAnchor::verify($history,$signature,$publicKey,$pin,$latestSequence,$latestHead,$catalogs);
        return new self($catalogs);
    }
    public function resolve(int $sequence,string $digest,string $date,string $subject,string $scope):array {
        LegalCompliance::validateDate($date);
        if($sequence<1||!isset($this->catalogs[$sequence-1])||
            !preg_match('/\A[0-9a-f]{64}\z/D',$digest))
            throw new InvalidArgumentException('Historical revision identity required.');
        $bytes=$this->catalogs[$sequence-1];
        if(!hash_equals($digest,hash('sha256',$bytes)))throw new DomainException('Historical catalog mismatch.');
        $catalog=json_decode($bytes,true,16,JSON_THROW_ON_ERROR);
        if(!is_array($catalog)||count($catalog)!==3||($catalog['scope']??null)!=='STANDARD_DOMESTIC_B2B'||
            ($catalog['source']??null)!=='https://narodne-novine.nn.hr/clanci/sluzbeni/2025_06_89_1233.html'||
            !is_array($catalog['rules']??null)||!array_is_list($catalog['rules'])||
            count($catalog['rules'])<1||count($catalog['rules'])>1000)
            throw new DomainException('Unsupported historical catalog schema.');
        $previous=null;$versions=[];$match=null;
        foreach($catalog['rules'] as $rule){
            if(!is_array($rule)||count($rule)!==5||array_diff(['from','until','version','vatIssue','nonVatIssue'],array_keys($rule))||
                !is_string($rule['from'])||!is_string($rule['until'])||!is_string($rule['version'])||
                !is_bool($rule['vatIssue'])||!is_bool($rule['nonVatIssue']))
                throw new DomainException('Invalid historical rule.');
            LegalCompliance::validateDate($rule['from']);LegalCompliance::validateDate($rule['until']);
            if($rule['from']<'2026-01-01'||$rule['from']>=$rule['until']||
                ($previous!==null&&$previous>$rule['from'])||
                !preg_match('/\AHR-F2-'.substr($rule['from'],0,4).'-v[1-9][0-9]*\z/D',$rule['version'])||
                isset($versions[$rule['version']]))
                throw new DomainException('Invalid historical interval or version.');
            $previous=$rule['until'];$versions[$rule['version']]=true;
            if($date>=$rule['from']&&$date<$rule['until'])$match=$rule;
        }
        return ['sequence'=>$sequence,'catalogSha256'=>$digest,'eventDate'=>$date,'subject'=>$subject,'scope'=>$scope,
            'rule'=>$match,'decision'=>LegalCompliance::evaluateResolved($match,$subject,$scope),'dispatchAuthorized'=>false];
    }
}
