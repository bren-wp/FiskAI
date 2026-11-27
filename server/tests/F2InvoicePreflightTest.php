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
    require_once __DIR__.'/../src/F2EducIntake.php';
    require_once __DIR__.'/../src/EducBusinessLedger.php';
    $db=db();
    $subject='c1000000-0000-4000-8000-000000000001';$id='c2000000-0000-4000-8000-000000000001';
    (new EducBusinessLedger($db))->provision($subject,'12345678903','f2-fixture');
    $items=[];$catalog2026=[];$catalog2027=[];
    for($i=0;$i<3000;$i++){
        $code=sprintf('10.%02d.%02d',intdiv($i,100),$i%100);
        $items[]=['LevelNumber'=>6,'ItemCode'=>$code,'OfficialTitle_HR'=>'Synthetic '.$i,'StartingDate'=>'01.01.2025','EndingDate'=>$i===0?'31.12.2026':''];
        $catalog2026[$code]='Synthetic '.$i;
        if($i!==0)$catalog2027[$code]='Synthetic '.$i;
    }
    $items[]=['LevelNumber'=>6,'ItemCode'=>'99.99.99','OfficialTitle_HR'=>'Synthetic replacement','StartingDate'=>'01.01.2027','EndingDate'=>''];
    $catalog2027['99.99.99']='Synthetic replacement';
    $json=json_encode(['DTable'=>$items],JSON_THROW_ON_ERROR);
    $sourcePin=hash('sha256',$json);$pin26=hash('sha256',json_encode($catalog2026,JSON_THROW_ON_ERROR));$pin27=hash('sha256',json_encode($catalog2027,JSON_THROW_ON_ERROR));
    F2KpdRelease::import($db,'2026-01-01',$json,$sourcePin);
    F2KpdRelease::import($db,'2027-01-01',$json,$sourcePin);
    F2KpdRelease::import($db,'2027-01-01',$json,$sourcePin);
    $release26=F2KpdRelease::verified($db,'2026-12-31',$sourcePin,$pin26);
    $release27=F2KpdRelease::verified($db,'2027-01-01',$sourcePin,$pin27);
    if(!isset($release26['codes']['10.00.00'])||isset($release27['codes']['10.00.00'])||!isset($release27['codes']['99.99.99']))
        throw new RuntimeException('KPD transition mismatch.');
    f2Reject(DomainException::class,fn()=>F2KpdRelease::verified($db,'2027-01-01',$sourcePin,$pin26));
    $xml=str_replace('<cac:InvoiceLine/>','<cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode><cac:InvoiceLine><cac:Item><cac:CommodityClassification><cbc:ItemClassificationCode listID="CG">10.00.00</cbc:ItemClassificationCode></cac:CommodityClassification></cac:Item></cac:InvoiceLine>',$xml);
    if(F2KpdRelease::checkLines($xml,$release26)!==['10.00.00'])throw new RuntimeException('KPD line check mismatch.');
    f2Reject(DomainException::class,fn()=>F2KpdRelease::checkLines($xml,$release27));
    f2Reject(DomainException::class,fn()=>F2KpdRelease::checkLines(str_replace('listID="CG"','listID="XX"',$xml),$release26));
    $r=$review($xml);$schemaDigest=$r['schemaManifestSha256'];
    $validatorKey=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
    $grantKey=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
    $validatorPublic=openssl_pkey_get_details($validatorKey)['key'];$grantPublic=openssl_pkey_get_details($grantKey)['key'];
    $validatorPin=hash('sha256',$validatorPublic);$grantPin=hash('sha256',$grantPublic);
    $manifest=hash('sha256','synthetic validator package');
    $svrl='<svrl:schematron-output xmlns:svrl="http://purl.oclc.org/dsdl/svrl"><svrl:active-pattern/><svrl:fired-rule context="Invoice"/></svrl:schematron-output>';
    $statement=json_encode(['protocol'=>'FISKAI-F2-SVRL-V1','documentSha256'=>hash('sha256',$xml),'schemaManifestSha256'=>$schemaDigest,
        'validatorManifestSha256'=>$manifest,'svrlSha256'=>hash('sha256',$svrl)],JSON_THROW_ON_ERROR);
    openssl_sign("FISKAI-F2-VALIDATION-V1\n".$statement,$signature,$validatorKey,OPENSSL_ALGO_SHA256);
    $grant=json_encode(['environment'=>'EDUC','subject'=>$subject,'ownerOib'=>'12345678903','providerOib'=>'00000000001','operation'=>'ISSUED',
        'validFrom'=>'2026-01-01','validUntil'=>'2026-12-31','authorizationSha256'=>hash('sha256','synthetic grant')],JSON_THROW_ON_ERROR);
    openssl_sign("FISKAI-F2-PROVIDER-GRANT-V1\n".$grant,$grantSig,$grantKey,OPENSSL_ALGO_SHA256);
    f2Reject(DomainException::class,fn()=>F2ValidationEvidence::verify($xml.' ',$schemaDigest,$manifest,$svrl,$statement,$signature,$validatorPublic,$validatorPin));
    f2Reject(DomainException::class,fn()=>F2ProviderGrant::verify($grant,$grantSig,$grantPublic,$grantPin,$subject,'12345678903','ISSUED','2027-01-01'));
    $storageKey=random_bytes(32);$intake=new F2EducIntake($db,$storageKey);
    $prepare=fn($docId,$value)=>$intake->prepare($subject,$docId,'ISSUED','2026-11-27',$value,$history,'STANDARD_DOMESTIC_B2B',
        $dir,$pins,'Invoice.xsd',$schemaDigest,$sourcePin,$pin26,$svrl,$statement,$signature,$validatorPublic,$validatorPin,$manifest,$grant,$grantSig,$grantPublic,$grantPin);
    $record=$prepare($id,$xml);
    if($record['state']!=='REVIEW_REQUIRED'||$record['dispatchAuthorized']!==false||$prepare($id,$xml)!==$record||
        $intake->read($subject,$id)['xml']!==$xml)throw new RuntimeException('Durable F2 intake mismatch.');
    f2Reject(DomainException::class,fn()=>$prepare('c2000000-0000-4000-8000-000000000002',$xml));
    f2Reject(RuntimeException::class,fn()=>(new F2EducIntake($db,random_bytes(32)))->read($subject,$id));
    $s=$db->prepare('SELECT sealed_evidence FROM f2_educ_intake WHERE subject_id=?');$s->execute([$subject]);
    if(str_contains($s->fetchColumn(),'TEST-1'))throw new RuntimeException('Unencrypted intake.');
    echo "F2 intake synthetic evidence, date-versioned KPD, duplicate and encrypted recovery checks passed.\n";
    echo "F2 preflight with synthetic schema: identity, pinned XSD, historical rules and blocked dispatch passed.\n";
}finally{unlink($dir.'/Invoice.xsd');rmdir($dir);}
