<?php
declare(strict_types=1);
require_once __DIR__.'/../src/CisCertificateTrust.php';
if($argc!==5)throw new RuntimeException('Expected leaf, root, valid CRL and revoked CRL fixtures.');
$cert=openssl_x509_read(file_get_contents($argv[1]));
if($cert===false)throw new RuntimeException('Fixture unavailable.');
$pin=openssl_x509_fingerprint($cert,'sha256');
$ca=file_get_contents($argv[2]);$valid=file_get_contents($argv[3]);$revoked=file_get_contents($argv[4]);
CisCertificateTrust::verify($cert,$pin,$ca,$valid);
function rejectTrust(callable $f):void {
    try{$f();}catch(InvalidArgumentException){return;}
    throw new RuntimeException('Invalid certificate trust accepted.');
}
rejectTrust(fn()=>CisCertificateTrust::verify($cert,$pin,$ca,$revoked));
rejectTrust(fn()=>CisCertificateTrust::verify($cert,str_repeat('0',64),$ca,$valid));
rejectTrust(fn()=>CisCertificateTrust::verify($cert,$pin,$ca,'invalid CRL'));
rejectTrust(fn()=>CisCertificateTrust::verify($cert,$pin,'invalid root',$valid));
putenv('FISKAI_TEST_TRUST='.$argv[2]);
putenv('FISKAI_TEST_TRUST_SHA256='.hash('sha256',$ca));
if(CisCertificateTrust::approvedBundle('FISKAI_TEST_TRUST')!==$ca)throw new RuntimeException('Bundle differs.');
putenv('FISKAI_TEST_TRUST_SHA256='.str_repeat('0',64));
rejectTrust(fn()=>CisCertificateTrust::approvedBundle('FISKAI_TEST_TRUST'));
echo "Certificate trust: valid chain accepted; revoked, untrusted, missing CRL and changed bundle rejected.\n";
