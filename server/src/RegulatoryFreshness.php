<?php
declare(strict_types=1);

/** Bind the offline approved history to an independent fresh witness statement.
 * A caller creates a new random challenge for each verification, never from a saved reply.
 * No fiscal authorization is conferred. */
final class RegulatoryFreshness {
    public static function verify(array $approved,string $nonce,array $reply,string $public,string $pin):void {
        if(!preg_match('/\A[0-9a-f]{64}\z/D',$nonce)||!preg_match('/\A[0-9a-f]{64}\z/D',$pin)||
            strlen($public)>8192||!hash_equals($pin,hash('sha256',$public))||
            count($reply)!==4||array_diff(['statement','signature','publicKeySha256','nonce'],array_keys($reply))||
            ($reply['nonce']??null)!==$nonce||($reply['publicKeySha256']??null)!==$pin||
            !is_string($reply['statement']??null)||strlen($reply['statement'])>256||
            !is_string($reply['signature']??null)||strlen($reply['signature'])>172)
            throw new InvalidArgumentException('Invalid regulatory freshness response.');
        $seq=$approved['sequence']??null;$head=$approved['head']??null;$digest=$approved['catalogSha256']??null;
        if(!is_int($seq)||$seq<1||$seq>1000||!is_string($head)||!is_string($digest)||
            !preg_match('/\A[0-9a-f]{64}\z/D',$head)||!preg_match('/\A[0-9a-f]{64}\z/D',$digest))
            throw new InvalidArgumentException('Invalid approved checkpoint.');
        $statement=base64_decode($reply['statement'],true);$signature=base64_decode($reply['signature'],true);
        if($statement===false||$signature===false||base64_encode($statement)!==$reply['statement']||
            base64_encode($signature)!==$reply['signature']||!hash_equals("$seq|$head|$digest\n",$statement))
            throw new DomainException('Witness differs from approved regulatory history.');
        $key=openssl_pkey_get_public($public);$details=$key===false?false:openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1'||
            openssl_verify("FISKAI-REGULATORY-FRESH-V1\n".$nonce."\n".$statement,$signature,$key,OPENSSL_ALGO_SHA256)!==1)
            throw new DomainException('Regulatory freshness signature invalid.');
    }
}
