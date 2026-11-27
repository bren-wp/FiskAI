<?php
declare(strict_types=1);
require_once __DIR__.'/../src/FiscalSignature.php';
function check(bool $ok):void{if(!$ok)throw new RuntimeException('Signature test failed');}
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_RSA,'private_key_bits'=>2048]);
if(!$key)throw new RuntimeException('Key generation failed');
$csr=openssl_csr_new(['commonName'=>'FiskAI offline test fixture'],$key,['digest_alg'=>'sha256']);
if(!$csr)throw new RuntimeException('CSR generation failed');
$cert=openssl_csr_sign($csr,null,$key,1,['digest_alg'=>'sha256'],1);
if(!$cert)throw new RuntimeException('Test certificate failed');
$xml='<f:RacunZahtjev xmlns:f="http://www.apis-it.hr/fin/2012/types/f73" Id="RacunZahtjev"><f:Zaglavlje><f:IdPoruke>fixture</f:IdPoruke></f:Zaglavlje><f:Racun><f:IznosUkupno>12.50</f:IznosUkupno></f:Racun></f:RacunZahtjev>';
$signed=FiscalSignature::signRequest($xml,$key,$cert);
$doc=new DOMDocument();check($doc->loadXML($signed,LIBXML_NONET));
$ns='http://www.w3.org/2000/09/xmldsig#';
$si=$doc->getElementsByTagNameNS($ns,'SignedInfo')->item(0);
$value=$doc->getElementsByTagNameNS($ns,'SignatureValue')->item(0)->textContent;
check(openssl_verify($si->C14N(true,false),base64_decode($value,true),$cert,OPENSSL_ALGO_SHA256)===1);
foreach([$signed,str_replace('<f:Racun>','<f:Racun Id="RacunZahtjev">',$xml),'<!DOCTYPE x>'.$xml] as $bad){
    $rejected=false;try{FiscalSignature::signRequest($bad,$key,$cert);}catch(InvalidArgumentException){$rejected=true;}check($rejected);
}
if(isset($argv[1])){
    $dir=$argv[1];check(is_dir($dir));
    check(openssl_x509_export($cert,$pem));
    check(file_put_contents($dir.'/cert.pem',$pem)!==false);
    check(file_put_contents($dir.'/signed.xml',$signed)!==false);
    check(file_put_contents($dir.'/tampered.xml',str_replace('12.50','13.50',$signed))!==false);
}
echo "Offline XML signature tests passed\n";
