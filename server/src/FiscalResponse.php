<?php
declare(strict_types=1);
require_once __DIR__.'/FiscalContract.php';

/** Offline verifier for the v2.7 response profile, section 9.4.
 * Trust must be provisioned independently; a certificate in KeyInfo is never a trust anchor. */
final class FiscalResponse {
    private const F='http://www.apis-it.hr/fin/2012/types/f73';
    private const SOAP='http://schemas.xmlsoap.org/soap/envelope/';
    private const DS='http://www.w3.org/2000/09/xmldsig#';
    private const C14N='http://www.w3.org/TR/2001/REC-xml-c14n-20010315';
    private const RSA='http://www.w3.org/2001/04/xmldsig-more#rsa-sha256';
    private const SHA='http://www.w3.org/2001/04/xmlenc#sha256';
    private static function children(DOMElement $node):array {
        return array_values(array_filter(iterator_to_array($node->childNodes),static fn($n)=>$n instanceof DOMElement));
    }
    private static function one(DOMElement $node,string $ns,string $name):DOMElement {
        $matches=array_values(array_filter(self::children($node),static fn($n)=>$n->namespaceURI===$ns&&$n->localName===$name));
        if(count($matches)!==1)throw new InvalidArgumentException('Unexpected element: '.$name);
        return $matches[0];
    }
    private static function shape(DOMElement $node,array $expected):void {
        $actual=array_map(static fn($n)=>[$n->namespaceURI,$n->localName],self::children($node));
        if($actual!==$expected)throw new InvalidArgumentException('Unexpected XML structure.');
    }
    private static function value(DOMElement $node):string {
        if(self::children($node)!==[])throw new InvalidArgumentException('Unexpected nested content.');
        return trim($node->textContent);
    }
    private static function alg(DOMElement $node,string $algorithm):void {
        if($node->getAttribute('Algorithm')!==$algorithm||self::children($node)!==[])
            throw new InvalidArgumentException('Unsupported signature algorithm.');
    }
    private static function decode(string $value):string {
        $clean=preg_replace('/[\r\n\t ]/','',$value);
        $bytes=is_string($clean)?base64_decode($clean,true):false;
        if($bytes===false||$bytes==='')throw new InvalidArgumentException('Invalid base64.');
        return $bytes;
    }
    private static function uuid(string $id):bool {
        return preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/iD',$id)===1;
    }
    /** Returns a verified JIR for a single expected message, not a production submission receipt.
     * The caller must enforce trusted environment-specific certificate provisioning and durable replay protection. */
    public static function verifyJir(string $soap,string $expectedMessage,OpenSSLCertificate $trusted,
        string $trustedSha256,string $schemaDir,array $schemaHashes):string {
        if(PHP_SAPI!=='cli')throw new LogicException('Offline verifier only.');
        if(!self::uuid($expectedMessage)||!preg_match('/\A[0-9a-f]{64}\z/D',$trustedSha256)||
            !hash_equals($trustedSha256,openssl_x509_fingerprint($trusted,'sha256')?:''))
            throw new InvalidArgumentException('Untrusted certificate.');
        $info=openssl_x509_parse($trusted);
        $public=openssl_pkey_get_public($trusted);
        $details=$public?openssl_pkey_get_details($public):false;
        if(!$info||time()<$info['validFrom_time_t']||time()>$info['validTo_time_t']||
            !$details||$details['type']!==OPENSSL_KEYTYPE_RSA||$details['bits']<2048)
            throw new InvalidArgumentException('Invalid verification key.');
        if($soap===''||strlen($soap)>1048576||str_contains($soap,"\0")||str_contains($soap,'<!')||!preg_match('//u',$soap))
            throw new InvalidArgumentException('Unsupported XML.');
        $doc=new DOMDocument();$doc->preserveWhiteSpace=true;
        $prev=libxml_use_internal_errors(true);
        try {
            if(!$doc->loadXML($soap,LIBXML_NONET)||$doc->doctype!==null)throw new InvalidArgumentException('Malformed XML.');
            $env=$doc->documentElement;
            if(!$env||$env->namespaceURI!==self::SOAP||$env->localName!=='Envelope')throw new InvalidArgumentException('Not SOAP 1.1.');
            self::shape($env,[[self::SOAP,'Body']]);
            $body=self::one($env,self::SOAP,'Body');
            self::shape($body,[[self::F,'RacunOdgovor']]);
            $root=self::one($body,self::F,'RacunOdgovor');
            self::shape($root,[[self::F,'Zaglavlje'],[self::F,'Jir'],[self::DS,'Signature']]);
            $id=$root->getAttribute('Id');
            $xp=new DOMXPath($doc);
            $ids=$xp->query('//@*[local-name()="Id" or local-name()="ID" or local-name()="id"]');
            if(!preg_match('/\A[A-Za-z_][A-Za-z0-9_.-]{0,127}\z/D',$id)||$ids===false||$ids->length!==1||
                $doc->getElementsByTagNameNS(self::DS,'Signature')->length!==1)
                throw new InvalidArgumentException('Ambiguous signed target.');
            $header=self::one($root,self::F,'Zaglavlje');
            self::shape($header,[[self::F,'IdPoruke'],[self::F,'DatumVrijeme']]);
            $message=self::value(self::one($header,self::F,'IdPoruke'));
            $jir=self::value(self::one($root,self::F,'Jir'));
            if(!hash_equals($expectedMessage,$message)||!self::uuid($jir))throw new InvalidArgumentException('Unmatched response.');
            $serialized=$root->C14N(false,false);
            if($serialized===false)throw new InvalidArgumentException('Canonicalization failed.');
            FiscalContract::validate($serialized,$schemaDir,$schemaHashes,'FiskalizacijaSchema.xsd');
            $signature=self::one($root,self::DS,'Signature');
            $signatureShape=array_map(static fn($n)=>[$n->namespaceURI,$n->localName],self::children($signature));
            if(!in_array($signatureShape,[[[self::DS,'SignedInfo'],[self::DS,'SignatureValue']],[[self::DS,'SignedInfo'],[self::DS,'SignatureValue'],[self::DS,'KeyInfo']]],true))
                throw new InvalidArgumentException('Unsupported signature structure.');
            $si=self::one($signature,self::DS,'SignedInfo');
            self::shape($si,[[self::DS,'CanonicalizationMethod'],[self::DS,'SignatureMethod'],[self::DS,'Reference']]);
            self::alg(self::one($si,self::DS,'CanonicalizationMethod'),self::C14N);
            self::alg(self::one($si,self::DS,'SignatureMethod'),self::RSA);
            $ref=self::one($si,self::DS,'Reference');
            if($ref->getAttribute('URI')!=='#'.$id)throw new InvalidArgumentException('Wrong signature reference.');
            self::shape($ref,[[self::DS,'Transforms'],[self::DS,'DigestMethod'],[self::DS,'DigestValue']]);
            $transforms=self::one($ref,self::DS,'Transforms');
            self::shape($transforms,[[self::DS,'Transform'],[self::DS,'Transform']]);
            [$first,$second]=self::children($transforms);
            self::alg($first,self::DS.'enveloped-signature');self::alg($second,self::C14N);
            self::alg(self::one($ref,self::DS,'DigestMethod'),self::SHA);
            $digest=self::decode(self::value(self::one($ref,self::DS,'DigestValue')));
            $sig=self::decode(self::value(self::one($signature,self::DS,'SignatureValue')));
            $signedInfo=$si->C14N(false,false);
            if($signedInfo===false||openssl_verify($signedInfo,$sig,$public,OPENSSL_ALGO_SHA256)!==1)
                throw new InvalidArgumentException('Invalid response signature.');
            $root->removeChild($signature);
            $canonical=$root->C14N(false,false);
            if($canonical===false||!hash_equals(hash('sha256',$canonical,true),$digest))
                throw new InvalidArgumentException('Response digest mismatch.');
            return strtolower($jir);
        }finally{libxml_clear_errors();libxml_use_internal_errors($prev);}
    }
}
