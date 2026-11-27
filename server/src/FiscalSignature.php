<?php
declare(strict_types=1);

/** Offline DEMO request signing only. Not a trust validator, transport or authorization service. */
final class FiscalSignature {
    private const F='http://www.apis-it.hr/fin/2012/types/f73';
    private const DS='http://www.w3.org/2000/09/xmldsig#';
    private const EXC='http://www.w3.org/2001/10/xml-exc-c14n#';
    private const RSA='http://www.w3.org/2001/04/xmldsig-more#rsa-sha256';
    private const SHA='http://www.w3.org/2001/04/xmlenc#sha256';
    private static function parse(string $xml):DOMDocument {
        if(strlen($xml)>1048576||str_contains($xml,"\0")||str_contains($xml,'<!')||!preg_match('//u',$xml))throw new InvalidArgumentException('Unsupported XML');
        $doc=new DOMDocument('1.0','UTF-8');$doc->preserveWhiteSpace=true;$doc->formatOutput=false;
        $prev=libxml_use_internal_errors(true);
        try{
            if(!$doc->loadXML($xml,LIBXML_NONET)||$doc->doctype!==null)throw new InvalidArgumentException('Invalid XML');
        }finally{libxml_clear_errors();libxml_use_internal_errors($prev);}
        return $doc;
    }
    private static function append(DOMElement $parent,string $name,?string $text=null):DOMElement {
        $doc=$parent->ownerDocument;
        $node=$doc->createElementNS(self::DS,'ds:'.$name);
        if($text!==null)$node->appendChild($doc->createTextNode($text));
        $parent->appendChild($node);return $node;
    }
    private static function algorithm(DOMElement $parent,string $name,string $uri):DOMElement{
        $node=self::append($parent,$name);$node->setAttribute('Algorithm',$uri);return $node;
    }
    public static function signRequest(string $xml,OpenSSLAsymmetricKey $key,OpenSSLCertificate $cert):string {
        if(PHP_SAPI!=='cli')throw new LogicException('Offline signing only');
        $details=openssl_pkey_get_details($key);
        $info=openssl_x509_parse($cert);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_RSA||$details['bits']<2048||!$info||time()<$info['validFrom_time_t']||time()>$info['validTo_time_t']||!openssl_x509_check_private_key($cert,$key))throw new InvalidArgumentException('Invalid signing credential');
        $doc=self::parse($xml);$root=$doc->documentElement;
        if(!$root||$root->namespaceURI!==self::F||$root->localName!=='RacunZahtjev'||$root->getAttribute('Id')!=='RacunZahtjev')throw new InvalidArgumentException('Unexpected root');
        $xpath=new DOMXPath($doc);
        $ids=$xpath->query('//@*[local-name()="Id" or local-name()="ID" or local-name()="id"]');
        if($ids===false||$ids->length!==1||$doc->getElementsByTagNameNS(self::DS,'*')->length!==0)throw new InvalidArgumentException('Ambiguous ID or preexisting signature');
        $children=[];foreach($root->childNodes as $child){if($child instanceof DOMElement){if($child->namespaceURI!==self::F)throw new InvalidArgumentException('Unexpected namespace');$children[]=$child->localName;}}
        if($children!==['Zaglavlje','Racun'])throw new InvalidArgumentException('Unexpected request structure');
        $canonical=$root->C14N(true,false);
        if($canonical===false)throw new RuntimeException('Canonicalization failed');
        $sig=self::append($root,'Signature');
        $si=self::append($sig,'SignedInfo');
        self::algorithm($si,'CanonicalizationMethod',self::EXC);
        self::algorithm($si,'SignatureMethod',self::RSA);
        $ref=self::append($si,'Reference');$ref->setAttribute('URI','#RacunZahtjev');
        $trans=self::append($ref,'Transforms');
        self::algorithm($trans,'Transform',self::DS.'enveloped-signature');self::algorithm($trans,'Transform',self::EXC);
        self::algorithm($ref,'DigestMethod',self::SHA);
        self::append($ref,'DigestValue',base64_encode(hash('sha256',$canonical,true)));
        $data=$si->C14N(true,false);
        if($data===false||!openssl_sign($data,$signed,$key,OPENSSL_ALGO_SHA256))throw new RuntimeException('Signing failed');
        self::append($sig,'SignatureValue',base64_encode($signed));
        $ki=self::append($sig,'KeyInfo');$xd=self::append($ki,'X509Data');
        if(!openssl_x509_export($cert,$pem))throw new RuntimeException('Certificate export failed');
        $encoded=preg_replace('/-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----|\s+/','',$pem);
        if(!$encoded||base64_decode($encoded,true)===false)throw new RuntimeException('Certificate export failed');
        self::append($xd,'X509Certificate',$encoded);
        $result=$doc->saveXML();
        if($result===false)throw new RuntimeException('XML serialization failed');
        return $result;
    }
}
