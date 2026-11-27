<?php
declare(strict_types=1);
require_once __DIR__.'/FiscalContract.php';
require_once __DIR__.'/TaxStatusHistory.php';
require_once __DIR__.'/FiscalReportingRules.php';

/** Narrow offline F2 intake preflight. XSD alone is not EN16931/CIUS compliance.
 * No dispatch, acceptance, KPD inference, or tax classification.
 */
final class F2InvoicePreflight {
    private const INV='urn:oasis:names:specification:ubl:schema:xsd:Invoice-2';
    private const CBC='urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2';
    private const CAC='urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2';
    private const PROFILE='urn:cen.eu:en16931:2017#compliant#urn:mfin.gov.hr:cius-2025:1.0#conformant#urn:mfin.gov.hr:ext[1]2025:1.0';
    private static function text(DOMXPath $xp,string $path,int $limit):string {
        $nodes=$xp->query($path);
        if($nodes===false||$nodes->length!==1)throw new InvalidArgumentException('Ambiguous invoice identity.');
        $node=$nodes->item(0);
        foreach($node->childNodes as $child)if($child instanceof DOMElement)throw new InvalidArgumentException('Invalid scalar field.');
        $value=trim($node->textContent);
        if($value===''||strlen($value)>$limit||preg_match('/[\x00-\x1f\x7f]/',$value))throw new InvalidArgumentException('Invalid invoice field.');
        return $value;
    }
    private static function oib(string $v):string {
        if(str_starts_with($v,'HR'))$v=substr($v,2);
        if(!preg_match('/\A[0-9]{11}\z/D',$v))throw new InvalidArgumentException('Invalid OIB.');
        $a=10;
        for($i=0;$i<10;$i++){$a=($a+(int)$v[$i])%10;if($a===0)$a=10;$a=($a*2)%11;}
        if((11-$a)%10!==(int)$v[10])throw new InvalidArgumentException('Invalid OIB checksum.');
        return $v;
    }
    /** A reviewed, hash-pinned flattened schema bundle is supplied by the operator.
     * Only the ordinary UBL Invoice profile with unambiguous legal entity OIBs is supported.
     */
    public static function review(string $xml,string $owner,string $direction,string $eventDate,
        TaxStatusHistory $history,string $scope,string $schemaDir,array $pins,string $entry):array {
        if(!in_array($direction,['ISSUED','RECEIVED'],true))throw new InvalidArgumentException('Unknown direction.');
        $owner=self::oib($owner);LegalCompliance::validateDate($eventDate);
        FiscalContract::validate($xml,$schemaDir,$pins,$entry);
        $doc=new DOMDocument();
        if(!$doc->loadXML($xml,LIBXML_NONET)||$doc->doctype!==null||$doc->documentElement?->namespaceURI!==self::INV||
            $doc->documentElement?->localName!=='Invoice')throw new InvalidArgumentException('Only ordinary UBL Invoice supported.');
        $xp=new DOMXPath($doc);$xp->registerNamespace('i',self::INV);$xp->registerNamespace('cbc',self::CBC);$xp->registerNamespace('cac',self::CAC);
        $root='/i:Invoice';
        if(self::text($xp,$root.'/cbc:CustomizationID',256)!==self::PROFILE||self::text($xp,$root.'/cbc:DocumentCurrencyCode',3)!=='EUR')
            throw new InvalidArgumentException('Unreviewed invoice profile.');
        $number=self::text($xp,$root.'/cbc:ID',128);$date=self::text($xp,$root.'/cbc:IssueDate',10);
        LegalCompliance::validateDate($date);
        if($eventDate<$date||($direction==='ISSUED'&&$eventDate!==$date))throw new InvalidArgumentException('Event date mismatch.');
        $seller=self::oib(self::text($xp,$root.'/cac:AccountingSupplierParty/cac:Party/cac:PartyLegalEntity/cbc:CompanyID',32));
        $buyer=self::oib(self::text($xp,$root.'/cac:AccountingCustomerParty/cac:Party/cac:PartyLegalEntity/cbc:CompanyID',32));
        if($seller===$buyer||$owner!==($direction==='ISSUED'?$seller:$buyer))throw new DomainException('Invoice owner mismatch.');
        $payable=self::text($xp,$root.'/cac:LegalMonetaryTotal/cbc:PayableAmount',32);
        if(!preg_match('/\A[0-9]{1,12}(?:\.[0-9]{1,2})?\z/D',$payable)||
            $xp->evaluate('string('.$root.'/cac:LegalMonetaryTotal/cbc:PayableAmount/@currencyID)')!=='EUR')
            throw new InvalidArgumentException('Unsupported payable amount.');
        if($xp->query($root.'/cac:InvoiceLine')->length<1)throw new InvalidArgumentException('Invoice lines missing.');
        $assessment=$history->assess($eventDate,$scope);
        $required=$assessment['decision'][$direction==='ISSUED'?'fiscalizeIssue':'fiscalizeReceive'];
        $deadline=$required==='REQUIRED'?FiscalReportingRules::deadline($direction,$eventDate):null;
        ksort($pins,SORT_STRING);
        return ['status'=>'XSD_PREFLIGHT_ONLY','documentSha256'=>hash('sha256',$xml),
            'schemaManifestSha256'=>hash('sha256',json_encode(['entry'=>$entry,'pins'=>$pins],JSON_THROW_ON_ERROR)),
            'direction'=>$direction,'sellerOib'=>$seller,'buyerOib'=>$buyer,'number'=>$number,'issueDate'=>$date,'eventDate'=>$eventDate,
            'payable'=>$payable,'assessment'=>$assessment,'deadline'=>$deadline,
            'schematronVerified'=>false,'dispatchAuthorized'=>false];
    }
}
