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
