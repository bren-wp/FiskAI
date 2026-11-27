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
        self::checkReport($svrl);
        return $expected+['validatorKeySha256'=>$pin,'status'=>'SIGNED_SVRL_REVIEWED'];
    }
    /** Two named rule sets, one signature over the exact invoice and both reports.
     * Evidence of an authorized review, never a dispatch permission.
     */
    public static function verifyDual(string $xml,string $schemaDigest,string $enManifestPin,string $hrManifestPin,
        string $enSvrl,string $hrSvrl,string $statement,string $signature,string $public,string $pin):array {
        foreach([$schemaDigest,$enManifestPin,$hrManifestPin,$pin] as $hash)
            if(!preg_match('/\A[0-9a-f]{64}\z/D',$hash))throw new InvalidArgumentException('Invalid pin.');
        if(strlen($statement)>2048||strlen($signature)>80||strlen($public)>8192||
            !hash_equals($pin,hash('sha256',$public))||hash_equals($enManifestPin,$hrManifestPin)||
            hash_equals(hash('sha256',$enSvrl),hash('sha256',$hrSvrl)))
            throw new InvalidArgumentException('Distinct dual validation evidence required.');
        $expected=['protocol'=>'FISKAI-F2-DUAL-SVRL-V2','documentSha256'=>hash('sha256',$xml),
            'schemaManifestSha256'=>$schemaDigest,'enManifestSha256'=>$enManifestPin,'hrManifestSha256'=>$hrManifestPin,
            'enSvrlSha256'=>hash('sha256',$enSvrl),'hrSvrlSha256'=>hash('sha256',$hrSvrl)];
        if(!hash_equals(json_encode($expected,JSON_THROW_ON_ERROR),$statement))
            throw new DomainException('Dual evidence binding mismatch.');
        $key=openssl_pkey_get_public($public);$details=$key===false?false:openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1'||
            openssl_verify("FISKAI-F2-DUAL-VALIDATION-V2\n".$statement,$signature,$key,OPENSSL_ALGO_SHA256)!==1)
            throw new DomainException('Dual validation signature invalid.');
        $warnings=self::checkReport($enSvrl,true);self::checkReport($hrSvrl);
        return $expected+['validatorKeySha256'=>$pin,'status'=>$warnings===[]?'SIGNED_DUAL_SVRL_REVIEWED':'SIGNED_DUAL_SVRL_REVIEW_REQUIRED',
            'enWarnings'=>$warnings,'dispatchAuthorized'=>false];
    }
    private static function checkReport(string $svrl,bool $nationalProfileWarnings=false):array {
        if(strlen($svrl)>1048576||$svrl===''||preg_match('/<!\s*(?:DOCTYPE|ENTITY)\b/i',$svrl)||str_contains($svrl,"\0")||!preg_match('//u',$svrl))
            throw new InvalidArgumentException('Invalid SVRL bytes.');
        $doc=new DOMDocument();
        if(!$doc->loadXML($svrl,LIBXML_NONET)||$doc->doctype!==null||$doc->documentElement?->namespaceURI!=='http://purl.oclc.org/dsdl/svrl'||
            $doc->documentElement?->localName!=='schematron-output')throw new InvalidArgumentException('Invalid SVRL output.');
        $xp=new DOMXPath($doc);$xp->registerNamespace('s','http://purl.oclc.org/dsdl/svrl');
        if($xp->query('/s:schematron-output/s:active-pattern')->length<1||$xp->query('/s:schematron-output/s:fired-rule')->length<1)
            throw new DomainException('Missing rule execution evidence.');
        $warnings=[];
        foreach($xp->query('//s:failed-assert | //s:successful-report') as $finding){
            $id=$finding->getAttribute('id');
            // These exact EN warnings conflict with mandatory Croatian fields.
            // Retain them only in signed dual REVIEW_REQUIRED evidence; no waiver.
            if(!$nationalProfileWarnings||$finding->localName!=='failed-assert'||$finding->getAttribute('flag')!=='warning'||
                !in_array($id,['UBL-CR-006','UBL-CR-200'],true)||in_array($id,$warnings,true))
                throw new DomainException('Unresolved validator findings.');
            $warnings[]=$id;
        }
        sort($warnings,SORT_STRING);return $warnings;
    }
}
