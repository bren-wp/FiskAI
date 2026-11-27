<?php
declare(strict_types=1);
// Reuse the official schema and synthetic signing credential fixture; no network.
require __DIR__.'/OfficialFiscalContractTest.php';
require_once __DIR__.'/../src/SchemaMigrator.php';
require_once __DIR__.'/../src/CisEducDispatch.php';
function dispatchCheck(bool $ok):void {if(!$ok)throw new RuntimeException('Dispatch test failed.');}
function dispatchReject(string $type,callable $call):void {
    try{$call();}catch(Throwable $e){if($e instanceof $type)return;throw $e;}
    throw new RuntimeException('Expected dispatch rejection missing.');
}
$db=db();SchemaMigrator::apply($db,__DIR__.'/../database');
$dispatch=new CisEducDispatch($db);
$subject='91000000-0000-4000-8000-000000000001';
$document='92000000-0000-4000-8000-000000000001';
$message='93000000-0000-4000-8000-000000000001';
$invoice=str_replace('12345678-1234-4234-8234-123456789abc',$message,$invoice);
$documentHash=hash('sha256','synthetic immutable dispatch document');
$storageKey=random_bytes(32);
$prepareDispatch=fn($value,$doc=null)=>$dispatch->prepare($subject,$doc??$document,$documentHash,$value,'12345678903',$key,$credential,$pin,$pin,$dir,'dispatch-fixture',$storageKey);
dispatchCheck($prepareDispatch($invoice)===$message);
dispatchCheck($prepareDispatch($invoice)===$message);
dispatchCheck($dispatch->state($subject,$message)==='PREPARED');
$original=$dispatch->open($subject,$message,$storageKey);
dispatchReject(RuntimeException::class,fn()=>$dispatch->open($subject,$message,random_bytes(32)));
dispatchReject(DomainException::class,fn()=>$dispatch->open('91000000-0000-4000-8000-000000000002',$message,$storageKey));
$newMessage='93000000-0000-4000-8000-000000000002';
dispatchReject(DomainException::class,fn()=>$prepareDispatch(str_replace($message,$newMessage,$invoice)));
$s=$db->prepare('SELECT COUNT(*) FROM cis_educ_response_evidence WHERE message_id=?');
$s->execute([$newMessage]);dispatchCheck((int)$s->fetchColumn()===0);
$s=$db->prepare('SELECT sealed_request FROM cis_educ_dispatch WHERE message_id=?');
$s->execute([$message]);dispatchCheck(!str_contains($s->fetchColumn(),'RacunZahtjev'));
$reserved=$dispatch->reserve($subject,$message,$storageKey);
dispatchCheck($reserved['soap']===$original&&$reserved['requestSha256']===hash('sha256',$original));
dispatchReject(DomainException::class,fn()=>$dispatch->reserve($subject,$message,$storageKey));
dispatchCheck(!$dispatch->uncertain($subject,$message,str_repeat('0',64)));
dispatchCheck($dispatch->recoverInterrupted($subject)===0);
$s=$db->prepare("UPDATE cis_educ_dispatch SET reserved_at=UTC_TIMESTAMP(6)-INTERVAL 6 MINUTE WHERE message_id=?");$s->execute([$message]);
dispatchCheck($dispatch->recoverInterrupted($subject)===1);
dispatchCheck($dispatch->state($subject,$message)==='UNCERTAIN');
dispatchReject(DomainException::class,fn()=>$dispatch->reserve($subject,$message,$storageKey));
// A signed synthetic response is not a real CIS receipt.
$jir='94000000-0000-4000-8000-000000000001';
$xmlResponse='<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:f="http://www.apis-it.hr/fin/2012/types/f73"><s:Body><f:RacunOdgovor Id="response1"><f:Zaglavlje><f:IdPoruke>'.$message.'</f:IdPoruke><f:DatumVrijeme>27.11.2026T09:00:00</f:DatumVrijeme></f:Zaglavlje><f:Jir>'.$jir.'</f:Jir></f:RacunOdgovor></s:Body></s:Envelope>';
$d=new DOMDocument();$d->loadXML($xmlResponse,LIBXML_NONET);
$root=$d->getElementsByTagNameNS('http://www.apis-it.hr/fin/2012/types/f73','RacunOdgovor')->item(0);
$hash=base64_encode(hash('sha256',$root->C14N(false,false),true));
$ns='http://www.w3.org/2000/09/xmldsig#';$canonical='http://www.w3.org/TR/2001/REC-xml-c14n-20010315';
$sig=$d->createElementNS($ns,'ds:Signature');$root->appendChild($sig);
$fragment=$d->createDocumentFragment();
$fragment->appendXML('<ds:SignedInfo xmlns:ds="'.$ns.'"><ds:CanonicalizationMethod Algorithm="'.$canonical.'"/><ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/><ds:Reference URI="#response1"><ds:Transforms><ds:Transform Algorithm="'.$ns.'enveloped-signature"/><ds:Transform Algorithm="'.$canonical.'"/></ds:Transforms><ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/><ds:DigestValue>'.$hash.'</ds:DigestValue></ds:Reference></ds:SignedInfo>');
$sig->appendChild($fragment);
if(!openssl_sign($sig->firstChild->C14N(false,false),$signedResponse,$key,OPENSSL_ALGO_SHA256))throw new RuntimeException('Fixture signing failed.');
$sig->appendChild($d->createElementNS($ns,'ds:SignatureValue',base64_encode($signedResponse)));
$response=$d->saveXML();
$verifyDispatch=fn($value,$reservation=null)=>$dispatch->verifyResponse($subject,$message,$reservation??$reserved['reservation'],$value,$credential,$dir,$storageKey);
dispatchReject(DomainException::class,fn()=>$verifyDispatch($response,str_repeat('0',64)));
dispatchReject(InvalidArgumentException::class,fn()=>$verifyDispatch($xmlResponse));
dispatchReject(InvalidArgumentException::class,fn()=>$verifyDispatch(str_replace($jir,'94000000-0000-4000-8000-000000000002',$response)));
dispatchCheck($dispatch->state($subject,$message)==='UNCERTAIN');
dispatchCheck($verifyDispatch($response)===$jir);
dispatchCheck($dispatch->state($subject,$message)==='VERIFIED');
dispatchCheck($verifyDispatch($response)===$jir);
dispatchCheck((new CisEducResponseEvidence($db))->open($subject,$message,$storageKey)===$response);
dispatchReject(DomainException::class,fn()=>$dispatch->reserve($subject,$message,$storageKey));
echo "Offline EDUC dispatch: encrypted request, single reservation, crash recovery, authenticated response and rollback passed.\n";
