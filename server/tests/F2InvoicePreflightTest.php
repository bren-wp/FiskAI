<?php
declare(strict_types=1);
require_once __DIR__.'/../src/F2InvoicePreflight.php';
function f2Reject(string $type,callable $f):void {
    try{$f();}catch(Throwable $e){if($e instanceof $type)return;throw $e;}
    throw new RuntimeException('Invalid preflight accepted.');
}
// Synthetic schema checks the integration path, not official UBL/CIUS compliance.
$dir=sys_get_temp_dir().'/fiskai-f2-fixture-'.bin2hex(random_bytes(12));
if(!mkdir($dir,0700))throw new RuntimeException('Fixture unavailable.');
$schema='<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" elementFormDefault="qualified"><xs:element name="Invoice"><xs:complexType><xs:sequence><xs:any namespace="##other" processContents="skip" minOccurs="1" maxOccurs="unbounded"/></xs:sequence></xs:complexType></xs:element></xs:schema>';
file_put_contents($dir.'/Invoice.xsd',$schema);
try{
    $pins=['Invoice.xsd'=>hash('sha256',$schema)];
    $xml='<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"><cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:mfin.gov.hr:cius-2025:1.0#conformant#urn:mfin.gov.hr:ext[1]2025:1.0</cbc:CustomizationID><cbc:ID>TEST-1</cbc:ID><cbc:IssueDate>2026-11-27</cbc:IssueDate><cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode><cac:AccountingSupplierParty><cac:Party><cac:PartyLegalEntity><cbc:CompanyID>12345678903</cbc:CompanyID></cac:PartyLegalEntity></cac:Party></cac:AccountingSupplierParty><cac:AccountingCustomerParty><cac:Party><cac:PartyLegalEntity><cbc:CompanyID>00000000001</cbc:CompanyID></cac:PartyLegalEntity></cac:Party></cac:AccountingCustomerParty><cac:LegalMonetaryTotal><cbc:PayableAmount currencyID="EUR">12.50</cbc:PayableAmount></cac:LegalMonetaryTotal><cac:InvoiceLine/></Invoice>';
    $history=new TaxStatusHistory([['from'=>'2026-01-01','until'=>'2027-01-01','subject'=>'VAT']]);
    $review=fn($value,$owner='12345678903',$direction='ISSUED',$date='2026-11-27',$scope='STANDARD_DOMESTIC_B2B',$manifest=null)=>F2InvoicePreflight::review($value,$owner,$direction,$date,$history,$scope,$dir,$manifest??$pins,'Invoice.xsd');
    $r=$review($xml);
    if($r['status']!=='XSD_PREFLIGHT_ONLY'||$r['documentSha256']!==hash('sha256',$xml)||$r['dispatchAuthorized']!==false||
        $r['schematronVerified']!==false||$r['deadline']['date']!=='2026-11-27')throw new RuntimeException('Unexpected preflight.');
    $received=$review($xml,'00000000001','RECEIVED','2026-11-28');
    if($received['deadline']['kind']!=='WORKING_DAYS_REVIEW')throw new RuntimeException('Unexpected receipt review.');
    if($review($xml,'12345678903','ISSUED','2026-11-27','UNRESOLVED')['deadline']!==null)throw new RuntimeException('Unknown scope inferred.');
    f2Reject(DomainException::class,fn()=>$review($xml,'00000000001'));
    f2Reject(InvalidArgumentException::class,fn()=>$review($xml,'12345678903','ISSUED','2026-11-28'));
    f2Reject(InvalidArgumentException::class,fn()=>$review(str_replace('cius-2025','unknown',$xml)));
    f2Reject(InvalidArgumentException::class,fn()=>$review(str_replace('<cbc:ID>TEST-1</cbc:ID>','<cbc:ID>TEST-1</cbc:ID><cbc:ID>OTHER</cbc:ID>',$xml)));
    f2Reject(InvalidArgumentException::class,fn()=>$review($xml,'12345678903','ISSUED','2026-11-27','STANDARD_DOMESTIC_B2B',['Invoice.xsd'=>str_repeat('0',64)]));
    f2Reject(InvalidArgumentException::class,fn()=>$review('<!DOCTYPE Invoice>'.$xml));
    echo "F2 preflight with synthetic schema: identity, pinned XSD, historical rules and blocked dispatch passed.\n";
}finally{unlink($dir.'/Invoice.xsd');rmdir($dir);}
