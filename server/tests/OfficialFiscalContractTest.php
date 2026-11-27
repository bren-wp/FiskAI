<?php
declare(strict_types=1);
require_once __DIR__.'/../src/FiscalContract.php';
require_once __DIR__.'/../src/FiscalSignature.php';
if($argc!==2)throw new RuntimeException('Expected inspected EDUC schema directory.');
$dir=$argv[1];
$pins=json_decode(file_get_contents(__DIR__.'/../contracts/cis-v1.10.sha256.json'),true,512,JSON_THROW_ON_ERROR)['Fiskalizacija-WSDL-EDUC_v1.10.zip'];
$manifest=['FiskalizacijaSchema.xsd'=>$pins['schema/FiskalizacijaSchema.xsd'],'xmldsig-core-schema.xsd'=>$pins['schema/xmldsig-core-schema.xsd']];
$xml='<f:RacunZahtjev xmlns:f="http://www.apis-it.hr/fin/2012/types/f73" Id="RacunZahtjev"><f:Zaglavlje><f:IdPoruke>12345678-1234-4234-8234-123456789abc</f:IdPoruke><f:DatumVrijeme>27.11.2026T09:00:00</f:DatumVrijeme></f:Zaglavlje><f:Racun><f:Oib>12345678903</f:Oib><f:USustPdv>true</f:USustPdv><f:DatVrijeme>27.11.2026T09:00:00</f:DatVrijeme><f:OznSlijed>N</f:OznSlijed><f:BrRac><f:BrOznRac>1</f:BrOznRac><f:OznPosPr>PP1</f:OznPosPr><f:OznNapUr>1</f:OznNapUr></f:BrRac><f:Pdv><f:Porez><f:Stopa>25.00</f:Stopa><f:Osnovica>10.00</f:Osnovica><f:Iznos>2.50</f:Iznos></f:Porez></f:Pdv><f:IznosUkupno>12.50</f:IznosUkupno><f:NacinPlac>G</f:NacinPlac><f:OibOper>00000000001</f:OibOper><f:ZastKod>00000000000000000000000000000000</f:ZastKod><f:NakDost>false</f:NakDost></f:Racun></f:RacunZahtjev>';
// Deliberately synthetic ZKI; schema acceptance does not verify its calculation.
FiscalContract::validate($xml,$dir,$manifest,'FiskalizacijaSchema.xsd');
$rejected=false;
try{FiscalContract::validate(str_replace('12.50','wrong',$xml),$dir,$manifest,'FiskalizacijaSchema.xsd');}
catch(InvalidArgumentException $e){$rejected=true;}
if(!$rejected)throw new RuntimeException('Invalid amount accepted.');
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_RSA,'private_key_bits'=>2048]);
if(!$key)throw new RuntimeException('Test key failed.');
$csr=openssl_csr_new(['commonName'=>'FiskAI offline XSD fixture'],$key,['digest_alg'=>'sha256']);
if(!$csr)throw new RuntimeException('Test CSR failed.');
$cert=openssl_csr_sign($csr,null,$key,1,['digest_alg'=>'sha256'],1);
if(!$cert)throw new RuntimeException('Test certificate failed.');
$signed=FiscalSignature::signRequest($xml,$key,$cert);
FiscalContract::validate($signed,$dir,$manifest,'FiskalizacijaSchema.xsd');
echo "Official EDUC schema accepted unsigned and signed offline fixtures; invalid amount rejected.\n";

require_once __DIR__.'/../src/CisEducRequestPreparation.php';
$csr=openssl_csr_new(['commonName'=>'FiskAI synthetic EDUC fixture','organizationIdentifier'=>'VATHR-12345678903'],$key,['digest_alg'=>'sha256']);
if(!$csr)throw new RuntimeException('Identity fixture unavailable.');
$credential=openssl_csr_sign($csr,null,$key,1,['digest_alg'=>'sha256'],1);
if(!$credential)throw new RuntimeException('Credential fixture unavailable.');
$pin=openssl_x509_fingerprint($credential,'sha256');
$zki=CisEducRequestPreparation::zki('12345678903','27.11.2026 09:00:00','1','PP1','1','12.50',$key);
if(!openssl_sign('12345678903'.'27.11.2026 09:00:00'.'1'.'PP1'.'1'.'12.50',$expectedZki,$key,OPENSSL_ALGO_SHA256)
    ||$zki!==md5($expectedZki))throw new RuntimeException('ZKI algorithm mismatch.');
$invoice=str_replace('00000000000000000000000000000000',$zki,$xml);
$prepare=fn($value,$oib='12345678903',$fingerprint=null)=>CisEducRequestPreparation::prepare($value,$oib,$key,$credential,$fingerprint??$pin,$dir);
$prepared=$prepare($invoice);
if($prepared['environment']!=='EDUC'||$prepared['messageId']!=='12345678-1234-4234-8234-123456789abc'||
    $prepared['zki']!==$zki||$prepared['requestSha256']!==hash('sha256',$prepared['soap'])||
    $prepared['signerSha256']!==$pin)throw new RuntimeException('Unexpected prepared request.');
$soap=new DOMDocument();$soap->loadXML($prepared['soap'],LIBXML_NONET);
$xp=new DOMXPath($soap);$xp->registerNamespace('f','http://www.apis-it.hr/fin/2012/types/f73');
$xp->registerNamespace('ds','http://www.w3.org/2000/09/xmldsig#');
$info=$xp->query('//ds:SignedInfo')->item(0);
$signature=$xp->query('//ds:SignatureValue')->item(0);
if(!$info||!$signature||openssl_verify($info->C14N(true,false),base64_decode($signature->textContent,true),openssl_pkey_get_public($credential),OPENSSL_ALGO_SHA256)!==1)
    throw new RuntimeException('SOAP wrapping invalidated the signature.');
foreach([fn()=>$prepare($xml),fn()=>$prepare($invoice,'00000000001'),fn()=>$prepare($invoice,'12345678903',str_repeat('0',64)),fn()=>$prepare(str_replace('12.50','12.51',$invoice))] as $reject){
    try{$reject();}catch(InvalidArgumentException $e){continue;}
    throw new RuntimeException('Invalid EDUC preparation accepted.');
}
echo "Offline EDUC preparation: correct ZKI, bound certificate, pinned XSD and signed SOAP verified.\n";
