<?php
declare(strict_types=1);

/** Verify separately executed validator evidence. No claim that a validator ran locally.
 * An independently authorized validator signs exact document, contract and SVRL bytes.
 */
final class F2ValidationEvidence {
    public static function verify(string $xml,string $schemaDigest,string $manifestPin,string $svrl,string $statement,
        string $signature,string $public,string $pin):array {
        if(strlen($svrl)>1048576||$svrl===''||str_contains($svrl,'<!')||str_contains($svrl,"\0")||
            !preg_match('//u',$svrl)||strlen($statement)>2048||strlen($signature)>80||strlen($public)>8192)
            throw new InvalidArgumentException('Invalid validation evidence.');
        foreach([$schemaDigest,$manifestPin,$pin] as $hash)if(!preg_match('/\A[0-9a-f]{64}\z/D',$hash))throw new InvalidArgumentException('Invalid pin.');
        if(!hash_equals($pin,hash('sha256',$public)))throw new DomainException('Validator key mismatch.');
        $expected=['protocol'=>'FISKAI-F2-SVRL-V1','documentSha256'=>hash('sha256',$xml),'schemaManifestSha256'=>$schemaDigest,
            'validatorManifestSha256'=>$manifestPin,'svrlSha256'=>hash('sha256',$svrl)];
        if(!hash_equals(json_encode($expected,JSON_THROW_ON_ERROR),$statement))throw new DomainException('Validator evidence does not bind document and packages.');
        $key=openssl_pkey_get_public($public);$details=$key===false?false:openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1'||
            openssl_verify("FISKAI-F2-VALIDATION-V1\n".$statement,$signature,$key,OPENSSL_ALGO_SHA256)!==1)
            throw new DomainException('Validator evidence signature invalid.');
        $doc=new DOMDocument();
        if(!$doc->loadXML($svrl,LIBXML_NONET)||$doc->doctype!==null||$doc->documentElement?->namespaceURI!=='http://purl.oclc.org/dsdl/svrl'||
            $doc->documentElement?->localName!=='schematron-output')throw new InvalidArgumentException('Invalid SVRL output.');
        $xp=new DOMXPath($doc);$xp->registerNamespace('s','http://purl.oclc.org/dsdl/svrl');
        if($xp->query('/s:schematron-output/s:active-pattern')->length<1||$xp->query('/s:schematron-output/s:fired-rule')->length<1||
            $xp->query('//s:failed-assert | //s:successful-report')->length!==0)
            throw new DomainException('Validator findings or missing execution evidence require review.');
        return $expected+['validatorKeySha256'=>$pin,'status'=>'SIGNED_SVRL_REVIEWED'];
    }
}
