<?php
declare(strict_types=1);
require_once __DIR__.'/RegulatoryFreshness.php';
require_once __DIR__.'/RecoveryKeyFile.php';

final class RegulatoryRemoteCheckpoint {
    private static function file(string $name,int $limit):string {
        $path=getenv($name);$real=is_string($path)?realpath($path):false;
        if($real===false||!is_file($real)||!is_readable($real))throw new LogicException('Witness trust material required.');
        $bytes=file_get_contents($real,false,null,0,$limit+1);
        if($bytes===false||$bytes===''||strlen($bytes)>$limit)throw new InvalidArgumentException('Invalid witness trust material.');
        return $bytes;
    }
    /** Only trusted operator configuration may select the independent witness host. */
    public static function requireFresh(array $current):void {
        $url=getenv('FISKAI_REGULATORY_WITNESS_URL');
        $pin=getenv('FISKAI_REGULATORY_PUBLIC_KEY_SHA256');
        $tlsPin=getenv('FISKAI_REGULATORY_WITNESS_TLS_SPKI');
        if(!is_string($url)||strlen($url)>512||!preg_match('~\Ahttps://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?/v1/latest\z~D',$url)||
            !is_string($pin)||!preg_match('/\A[0-9a-f]{64}\z/D',$pin)||
            !is_string($tlsPin)||!preg_match('~\Asha256//[A-Za-z0-9+/]{43}=\z~D',$tlsPin)||!extension_loaded('curl'))
            throw new LogicException('Independent HTTPS witness not configured.');
        $public=self::file('FISKAI_REGULATORY_PUBLIC_KEY_FILE',8192);
        if(!hash_equals($pin,hash('sha256',$public)))throw new LogicException('Witness public key mismatch.');
        $ca=self::file('FISKAI_REGULATORY_WITNESS_CA',1048576);
        $caPin=getenv('FISKAI_REGULATORY_WITNESS_CA_SHA256');
        if(!is_string($caPin)||!hash_equals($caPin,hash('sha256',$ca)))throw new LogicException('Witness CA mismatch.');
        $token=bin2hex(RecoveryKeyFile::read('FISKAI_REGULATORY_WITNESS_TOKEN_FILE'));
        $nonce=bin2hex(random_bytes(32));
        $payload=json_encode(['nonce'=>$nonce],JSON_THROW_ON_ERROR);
        $response='';$handle=curl_init($url);
        if($handle===false)throw new RuntimeException('Witness transport unavailable.');
        try {
            if(!curl_setopt_array($handle,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$payload,
                CURLOPT_HTTPHEADER=>['Content-Type: application/json','Accept: application/json','Authorization: Bearer '.$token],
                CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_PROXY=>'',
                CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_SSLVERSION=>CURL_SSLVERSION_TLSv1_2,
                CURLOPT_CAINFO_BLOB=>$ca,CURLOPT_PINNEDPUBLICKEY=>$tlsPin,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>12,
                CURLOPT_WRITEFUNCTION=>static function($h,string $bytes)use(&$response):int {
                    if(strlen($bytes)>4096-strlen($response))return 0;
                    $response.=$bytes;return strlen($bytes);
                }]))throw new RuntimeException('Witness TLS policy unavailable.');
            if(curl_exec($handle)!==true||curl_getinfo($handle,CURLINFO_RESPONSE_CODE)!==200)
                throw new RuntimeException('Fresh witness unavailable.');
        }finally{curl_close($handle);}
        $reply=json_decode($response,true,8,JSON_THROW_ON_ERROR);
        if(!is_array($reply))throw new InvalidArgumentException('Invalid witness response.');
        RegulatoryFreshness::verify($current,$nonce,$reply,$public,$pin);
    }
}
