<?php
declare(strict_types=1);
require_once __DIR__.'/FiscalContract.php';
require_once __DIR__.'/FiscalSignature.php';

/** Offline preparation for ordinary F1 EDUC invoice requests, specification 2.7 sections 9 and 12.
 * Does not establish legal authority or send a request. Only an independently approved
 * legal-person EDUC certificate profile (organizationIdentifier) is supported here.
 */
final class CisEducRequestPreparation {
    private const NS='http://www.apis-it.hr/fin/2012/types/f73';
    private const SOAP='http://schemas.xmlsoap.org/soap/envelope/';
    private static function oib(string $value):void {
        if(!preg_match('/\A[0-9]{11}\z/D',$value))throw new InvalidArgumentException('Invalid OIB.');
        $a=10;
        for($i=0;$i<10;$i++){
            $a=($a+(int)$value[$i])%10;if($a===0)$a=10;$a=($a*2)%11;
        }
        if((11-$a)%10!==(int)$value[10])throw new InvalidArgumentException('Invalid OIB checksum.');
    }
    private static function text(DOMXPath $xp,string $path):string {
        $nodes=$xp->query($path);
        if($nodes===false||$nodes->length!==1)throw new InvalidArgumentException('Missing or ambiguous fiscal field.');
        $node=$nodes->item(0);
        foreach($node->childNodes as $child)if($child instanceof DOMElement)throw new InvalidArgumentException('Unexpected field structure.');
        return $node->textContent;
    }
    /** ZKI uses the exact invoice fields, not XML canonicalization or the transport signature. */
    public static function zki(string $oib,string $localDateTime,string $number,string $premise,string $register,
        string $total,OpenSSLAsymmetricKey $key):string {
        self::oib($oib);
        $date=DateTimeImmutable::createFromFormat('!d.m.Y H:i:s',$localDateTime,new DateTimeZone('Europe/Zagreb'));
        if(!$date||$date->format('d.m.Y H:i:s')!==$localDateTime||
            !preg_match('/\A[0-9]{1,20}\z/D',$number)||!preg_match('/\A[A-Za-z0-9]{1,20}\z/D',$premise)||
            !preg_match('/\A[0-9]{1,20}\z/D',$register)||!preg_match('/\A[0-9]{1,13}\.[0-9]{2}\z/D',$total))
            throw new InvalidArgumentException('Unsupported ordinary invoice fields.');
        $details=openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_RSA||$details['bits']<2048)throw new InvalidArgumentException('RSA signing key required.');
        if(!openssl_sign($oib.$localDateTime.$number.$premise.$register.$total,$signed,$key,OPENSSL_ALGO_SHA256))
            throw new RuntimeException('ZKI signing failed.');
        // Required legacy output format of the fiscal specification, not a security checksum.
        return md5($signed);
    }
    /** Pin is independently approved for EDUC. No automatic trust in XML KeyInfo or subject text. */
    public static function credential(string $oib,OpenSSLAsymmetricKey $key,OpenSSLCertificate $cert,string $pin):array {
        self::oib($oib);
        $info=openssl_x509_parse($cert,false);
        $details=openssl_pkey_get_details($key);
        if(!$info||!$details||$details['type']!==OPENSSL_KEYTYPE_RSA||$details['bits']<2048||
            !preg_match('/\A[0-9a-f]{64}\z/D',$pin)||!hash_equals($pin,openssl_x509_fingerprint($cert,'sha256')?:'')||
            !openssl_x509_check_private_key($cert,$key)||time()<$info['validFrom_time_t']||time()>$info['validTo_time_t'])
            throw new InvalidArgumentException('Invalid or unapproved signing credential.');
        $subject=$info['subject']??[];
        $identity=$subject['organizationIdentifier']??$subject['2.5.4.97']??null;
        if(!is_string($identity)||!hash_equals('VATHR-'.$oib,$identity))
            throw new InvalidArgumentException('Certificate identity requires operator review.');
        if(isset($info['extensions']['keyUsage'])&&!str_contains($info['extensions']['keyUsage'],'Digital Signature'))
            throw new InvalidArgumentException('Certificate key usage disallows signing.');
        return ['sha256'=>$pin,'validUntil'=>(int)$info['validTo_time_t'],'oib'=>$oib];
    }
    public static function prepare(string $unsigned,string $expectedOib,OpenSSLAsymmetricKey $key,
        OpenSSLCertificate $cert,string $signerPin,string $schemaDir):array {
        if(PHP_SAPI!=='cli')throw new LogicException('Offline preparation only.');
        self::credential($expectedOib,$key,$cert,$signerPin);
        $manifest=json_decode(file_get_contents(__DIR__.'/../contracts/cis-v1.10.sha256.json'),true,512,JSON_THROW_ON_ERROR);
        $pins=$manifest['Fiskalizacija-WSDL-EDUC_v1.10.zip'];
        $schemas=['FiskalizacijaSchema.xsd'=>$pins['schema/FiskalizacijaSchema.xsd'],
            'xmldsig-core-schema.xsd'=>$pins['schema/xmldsig-core-schema.xsd']];
        FiscalContract::validate($unsigned,$schemaDir,$schemas,'FiskalizacijaSchema.xsd');
        $doc=new DOMDocument();$doc->preserveWhiteSpace=true;
        if(!$doc->loadXML($unsigned,LIBXML_NONET)||$doc->doctype!==null||
            $doc->documentElement?->namespaceURI!==self::NS||$doc->documentElement?->localName!=='RacunZahtjev')
            throw new InvalidArgumentException('Unexpected fiscal request.');
        $xp=new DOMXPath($doc);$xp->registerNamespace('f',self::NS);
        $root='/f:RacunZahtjev';$invoice=$root.'/f:Racun';
        $message=self::text($xp,$root.'/f:Zaglavlje/f:IdPoruke');
        if(!preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/D',$message)||
            $message==='00000000-0000-0000-0000-000000000000')
            throw new InvalidArgumentException('Invalid message identity.');
        $oib=self::text($xp,$invoice.'/f:Oib');
        if(!hash_equals($expectedOib,$oib))throw new InvalidArgumentException('Invoice owner mismatch.');
        $date=self::text($xp,$invoice.'/f:DatVrijeme');
        if(!preg_match('/\A[0-9]{2}\.[0-9]{2}\.[0-9]{4}T[0-9]{2}:[0-9]{2}:[0-9]{2}\z/D',$date))
            throw new InvalidArgumentException('Invalid fiscal timestamp.');
        $zki=self::zki($oib,str_replace('T',' ',$date),self::text($xp,$invoice.'/f:BrRac/f:BrOznRac'),
            self::text($xp,$invoice.'/f:BrRac/f:OznPosPr'),self::text($xp,$invoice.'/f:BrRac/f:OznNapUr'),
            self::text($xp,$invoice.'/f:IznosUkupno'),$key);
        if(!hash_equals($zki,self::text($xp,$invoice.'/f:ZastKod')))
            throw new InvalidArgumentException('ZKI does not match the invoice and signing key.');
        $signed=FiscalSignature::signRequest($unsigned,$key,$cert);
        FiscalContract::validate($signed,$schemaDir,$schemas,'FiskalizacijaSchema.xsd');
        $signedDoc=new DOMDocument();
        if(!$signedDoc->loadXML($signed,LIBXML_NONET))throw new RuntimeException('Signed XML unavailable.');
        $envelope=new DOMDocument('1.0','UTF-8');
        $root=$envelope->createElementNS(self::SOAP,'s:Envelope');$envelope->appendChild($root);
        $body=$envelope->createElementNS(self::SOAP,'s:Body');$root->appendChild($body);
        $body->appendChild($envelope->importNode($signedDoc->documentElement,true));
        $soap=$envelope->saveXML();
        if($soap===false||strlen($soap)>1048576)throw new RuntimeException('Invalid SOAP size.');
        return ['environment'=>'EDUC','messageId'=>$message,'zki'=>$zki,'signerSha256'=>$signerPin,
            'requestSha256'=>hash('sha256',$soap),'soap'=>$soap];
    }
}
