<?php
declare(strict_types=1);
require_once __DIR__.'/LegalCompliance.php';

/** Local authorization evidence, not proof of provider certification or FiskAplikacija delegation. */
final class F2ProviderGrant {
    public static function verify(string $statement,string $signature,string $public,string $pin,string $subject,string $owner,string $direction,string $date):array {
        LegalCompliance::validateDate($date);
        if(strlen($statement)>4096||strlen($signature)>80||strlen($public)>8192||
            !preg_match('/\A[0-9a-f]{64}\z/D',$pin)||!hash_equals($pin,hash('sha256',$public)))
            throw new InvalidArgumentException('Invalid provider authorization evidence.');
        $key=openssl_pkey_get_public($public);$details=$key===false?false:openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1'||
            openssl_verify("FISKAI-F2-PROVIDER-GRANT-V1\n".$statement,$signature,$key,OPENSSL_ALGO_SHA256)!==1)
            throw new DomainException('Provider authorization signature invalid.');
        $g=json_decode($statement,true,8,JSON_THROW_ON_ERROR);
        if(!is_array($g)||array_keys($g)!==['environment','subject','ownerOib','providerOib','operation','validFrom','validUntil','authorizationSha256']||
            $g['environment']!=='EDUC'||$g['subject']!==$subject||$g['ownerOib']!==$owner||
            !in_array($direction,['ISSUED','RECEIVED'],true)||$g['operation']!==$direction||
            !is_string($g['providerOib'])||!preg_match('/\A[0-9]{11}\z/D',$g['providerOib'])||
            !is_string($g['authorizationSha256'])||!preg_match('/\A[0-9a-f]{64}\z/D',$g['authorizationSha256'])||
            !is_string($g['validFrom'])||!is_string($g['validUntil']))
            throw new DomainException('Provider grant scope mismatch.');
        $a=10;
        for($i=0;$i<10;$i++){$a=($a+(int)$g['providerOib'][$i])%10;if($a===0)$a=10;$a=($a*2)%11;}
        if((11-$a)%10!==(int)$g['providerOib'][10])throw new DomainException('Invalid provider OIB.');
        LegalCompliance::validateDate($g['validFrom']);LegalCompliance::validateDate($g['validUntil']);
        if($g['validFrom']>$date||$g['validUntil']<$date)throw new DomainException('Provider authorization outside validity.');
        return $g+['grantSha256'=>hash('sha256',$statement),'signerSha256'=>$pin,'externalAuthorizationVerified'=>false];
    }
}
