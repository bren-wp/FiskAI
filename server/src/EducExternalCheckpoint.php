<?php
declare(strict_types=1);

/** Verifies the independently retained latest head, supplied by a trusted operator.
 * No signing key on application server. Not a monotonic external witness service.
 */
final class EducExternalCheckpoint {
    public static function bytes(array $head):string {
        if(array_keys($head)!==['protocol','subject','keyId','position','head']||
            $head['protocol']!=='FISKAI-EDUC-HEAD-V1'||!is_string($head['subject'])||
            !preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/D',$head['subject'])||
            !is_string($head['keyId'])||!preg_match('/\A[A-Za-z0-9_-]{1,64}\z/D',$head['keyId'])||
            !is_int($head['position'])||$head['position']<0||$head['position']>1000||
            !is_string($head['head'])||!preg_match('/\A[0-9a-f]{64}\z/D',$head['head']))
            throw new InvalidArgumentException('Invalid checkpoint.');
        return json_encode($head,JSON_THROW_ON_ERROR);
    }
    public static function verify(array $current,string $statement,string $signature,string $publicKey,string $pin):void {
        if(strlen($publicKey)>8192||strlen($statement)>2048||strlen($signature)>1024||
            !preg_match('/\A[0-9a-f]{64}\z/D',$pin)||!hash_equals($pin,hash('sha256',$publicKey)))
            throw new InvalidArgumentException('Invalid checkpoint trust configuration.');
        $key=openssl_pkey_get_public($publicKey);
        $details=$key===false?false:openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1')
            throw new InvalidArgumentException('Expected independently approved P-256 key.');
        if(openssl_verify($statement,$signature,$key,OPENSSL_ALGO_SHA256)!==1)
            throw new DomainException('Checkpoint signature invalid.');
        if(!hash_equals(self::bytes($current),$statement))
            throw new DomainException('External checkpoint does not match local ledger.');
    }
    private static function file(string $name,int $limit):string {
        $path=getenv($name);$real=is_string($path)?realpath($path):false;
        if($real===false||!is_file($real)||!is_readable($real))throw new LogicException('Independent checkpoint material required.');
        $bytes=file_get_contents($real,false,null,0,$limit+1);
        if($bytes===false||$bytes===''||strlen($bytes)>$limit)throw new InvalidArgumentException('Invalid checkpoint material.');
        return $bytes;
    }
    public static function requireLatest(array $current):void {
        $pin=getenv('FISKAI_EDUC_ANCHOR_PUBLIC_KEY_SHA256');
        if(!is_string($pin))throw new LogicException('Independent checkpoint trust not configured.');
        self::verify($current,self::file('FISKAI_EDUC_ANCHOR_STATEMENT',2048),
            self::file('FISKAI_EDUC_ANCHOR_SIGNATURE',1024),self::file('FISKAI_EDUC_ANCHOR_PUBLIC_KEY',8192),$pin);
    }
}
