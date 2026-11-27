<?php
declare(strict_types=1);
require_once __DIR__.'/../src/FiscalResponse.php';
if($argc!==2)throw new RuntimeException('Expected EDUC schema directory.');
$dir=$argv[1];
$pins=json_decode(file_get_contents(__DIR__.'/../contracts/cis-v1.10.sha256.json'),true,512,JSON_THROW_ON_ERROR)['Fiskalizacija-WSDL-EDUC_v1.10.zip'];
$manifest=['FiskalizacijaSchema.xsd'=>$pins['schema/FiskalizacijaSchema.xsd'],'xmldsig-core-schema.xsd'=>$pins['schema/xmldsig-core-schema.xsd']];
function credential():array{
    $key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_RSA,'private_key_bits'=>2048]);
    $csr=openssl_csr_new(['commonName'=>'FiskAI offline response fixture'],$key,['digest_alg'=>'sha256']);
    return [$key,openssl_csr_sign($csr,null,$key,1,['digest_alg'=>'sha256'],1)];
}
function rejected(callable $f):void{
    try{$f();}catch(InvalidArgumentException){return;}
    throw new RuntimeException('Invalid response accepted.');
}
[$key,$cert]=credential();[, $other]=credential();
$message='12345678-1234-4234-8234-123456789abc';
$jir='87654321-4321-4321-8321-cba987654321';
$xml='<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:f="http://www.apis-it.hr/fin/2012/types/f73"><s:Body><f:RacunOdgovor Id="response1"><f:Zaglavlje><f:IdPoruke>'.$message.'</f:IdPoruke><f:DatumVrijeme>27.11.2026T09:00:00</f:DatumVrijeme></f:Zaglavlje><f:Jir>'.$jir.'</f:Jir></f:RacunOdgovor></s:Body></s:Envelope>';
$d=new DOMDocument();$d->loadXML($xml,LIBXML_NONET);
$root=$d->getElementsByTagNameNS('http://www.apis-it.hr/fin/2012/types/f73','RacunOdgovor')->item(0);
$hash=base64_encode(hash('sha256',$root->C14N(false,false),true));
$ns='http://www.w3.org/2000/09/xmldsig#';
$canonical='http://www.w3.org/TR/2001/REC-xml-c14n-20010315';
$signature=$d->createElementNS($ns,'ds:Signature');$root->appendChild($signature);
$fragment=$d->createDocumentFragment();
$fragment->appendXML('<ds:SignedInfo xmlns:ds="'.$ns.'"><ds:CanonicalizationMethod Algorithm="'.$canonical.'"/><ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/><ds:Reference URI="#response1"><ds:Transforms><ds:Transform Algorithm="'.$ns.'enveloped-signature"/><ds:Transform Algorithm="'.$canonical.'"/></ds:Transforms><ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/><ds:DigestValue>'.$hash.'</ds:DigestValue></ds:Reference></ds:SignedInfo>');
$signature->appendChild($fragment);
$si=$signature->firstChild;
if(!openssl_sign($si->C14N(false,false),$signed,$key,OPENSSL_ALGO_SHA256))throw new RuntimeException('Test signing failed.');
$signature->appendChild($d->createElementNS($ns,'ds:SignatureValue',base64_encode($signed)));
$soap=$d->saveXML();$pin=openssl_x509_fingerprint($cert,'sha256');
$verify=fn($value,$id=null)=>FiscalResponse::verifyJir($value,$id??$message,$cert,$pin,$dir,$manifest);
if($verify($soap)!==$jir)throw new RuntimeException('Valid signed fixture rejected.');
rejected(fn()=>$verify($xml));
rejected(fn()=>$verify(str_replace($jir,'11111111-1111-4111-8111-111111111111',$soap)));
rejected(fn()=>$verify($soap,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'));
rejected(fn()=>$verify(str_replace('URI="#response1"','URI="#other"',$soap)));
rejected(fn()=>$verify(str_replace('<s:Body>','<s:Body Id="response1">',$soap)));
rejected(fn()=>FiscalResponse::verifyJir($soap,$message,$other,openssl_x509_fingerprint($other,'sha256'),$dir,$manifest));
rejected(fn()=>FiscalResponse::verifyJir($soap,$message,$cert,str_repeat('0',64),$dir,$manifest));
echo "Offline signed JIR fixture verified; tamper, wrapping, correlation and untrusted key cases rejected.\n";

if(getenv('FISKAI_DB_DSN')!==false){
    require_once __DIR__.'/../src/SchemaMigrator.php';
    require_once __DIR__.'/../src/CisEducResponseEvidence.php';
    $db=db();
    SchemaMigrator::apply($db,__DIR__.'/../database');
    $ledger=new CisEducResponseEvidence($db);
    $subject='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    $document='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
    $otherSubject='cccccccc-cccc-4ccc-8ccc-cccccccccccc';
    $storageKey=random_bytes(32);
    $documentHash=hash('sha256','synthetic immutable EDUC document');
    $requestHash=hash('sha256','synthetic request fixture');
    $ledger->register($subject,$message,$document,$documentHash,$requestHash,$pin,'ci-test-key');
    $ledger->register($subject,$message,$document,$documentHash,$requestHash,$pin,'ci-test-key');
    rejected(fn()=>$ledger->register($otherSubject,$message,$document,$documentHash,$requestHash,$pin,'ci-test-key'));
    try{$ledger->register($subject,$message,$document,$documentHash,hash('sha256','changed'),$pin,'ci-test-key');throw new RuntimeException('Request conflict accepted');}catch(DomainException){}
    rejected(fn()=>$ledger->verifyAndStore($subject,$message,$xml,$cert,$dir,$storageKey));
    rejected(fn()=>$ledger->verifyAndStore($subject,$message,$soap,$other,$dir,$storageKey));
    try{$ledger->open($subject,$message,$storageKey);throw new RuntimeException('Unverified response persisted');}catch(LogicException){}
    if($ledger->verifyAndStore($subject,$message,$soap,$cert,$dir,$storageKey)!==$jir)throw new RuntimeException('Valid fixture was not stored.');
    if($ledger->verifyAndStore($subject,$message,$soap,$cert,$dir,$storageKey)!==$jir)throw new RuntimeException('Replay failed.');
    if($ledger->open($subject,$message,$storageKey)!==$soap)throw new RuntimeException('Signed evidence changed.');
    rejected(fn()=>$ledger->open($otherSubject,$message,$storageKey));
    try{$ledger->open($subject,$message,random_bytes(32));throw new LogicException('Wrong key accepted');}catch(RuntimeException){}
    $row=$db->query('SELECT sealed_response FROM cis_educ_response_evidence')->fetchColumn();
    if(str_contains($row,'RacunOdgovor'))throw new RuntimeException('Plaintext evidence persisted.');
    // Simulate unauthorized modification by a privileged test DB user.
    $db->exec("UPDATE cis_educ_response_evidence SET request_sha256='".str_repeat('0',64)."'");
    try{$ledger->open($subject,$message,$storageKey);throw new LogicException('Changed metadata accepted');}catch(RuntimeException){}
    echo "EDUC evidence persistence, exact replay, isolation and authenticated recovery passed.\n";
}
