<?php
declare(strict_types=1);

/** Offline verification against an independently provisioned latest approved checkpoint.
 * Never obtain the signing key or latest checkpoint from an application backup.
 * Does not authorize a fiscal transaction or discover legal changes. */
final class RegulatoryTrustAnchor {
    private const PREFIX="FISKAI-REGULATORY-HISTORY-V1\n";
    private const MAX=1048576;
    private static function digest(string $value):bool {
        return preg_match('/\A[0-9a-f]{64}\z/D',$value)===1;
    }
    /** Exact signed bytes: header, then sequence|previous-entry-sha256|catalog-sha256 LF.
     * Each revision's exact catalog bytes must be supplied in the same order. */
    public static function verify(string $history,string $signature,string $publicKey,string $publicPin,
        int $latestSequence,string $latestHead,array $catalogs):array {
        if(strlen($history)>self::MAX||!str_starts_with($history,self::PREFIX)||!str_ends_with($history,"\n")||
            strlen($signature)<8||strlen($signature)>128||strlen($publicKey)>8192||
            !self::digest($publicPin)||!hash_equals($publicPin,hash('sha256',$publicKey))||
            $latestSequence<1||$latestSequence>1000||!self::digest($latestHead)||
            !array_is_list($catalogs)||count($catalogs)!==$latestSequence)
            throw new InvalidArgumentException('Independent regulatory trust material invalid.');
        $key=openssl_pkey_get_public($publicKey);
        $details=$key===false?false:openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1'||
            openssl_verify($history,$signature,$key,OPENSSL_ALGO_SHA256)!==1)
            throw new DomainException('Regulatory history signature invalid.');
        $lines=explode("\n",substr($history,strlen(self::PREFIX),-1));
        if(count($lines)!==$latestSequence)throw new DomainException('Regulatory history rollback or incomplete history.');
        $head=str_repeat('0',64);$seen=[];$last=null;$total=0;
        foreach($lines as $index=>$line){
            $parts=explode('|',$line);
            if(count($parts)!==3||$parts[0]!==(string)($index+1)||!hash_equals($head,$parts[1])||
                !self::digest($parts[2])||isset($seen[$parts[2]]))
                throw new DomainException('Regulatory revision chain invalid.');
            $catalog=$catalogs[$index];
            if(!is_string($catalog)||$catalog===''||strlen($catalog)>self::MAX||
                ($total+=strlen($catalog))>16*self::MAX||!hash_equals($parts[2],hash('sha256',$catalog)))
                throw new DomainException('Regulatory catalog bytes missing or changed.');
            $seen[$parts[2]]=true;
            $head=hash('sha256',$line."\n");$last=$parts[2];
        }
        if(!hash_equals($latestHead,$head))throw new DomainException('Independent latest regulatory head mismatch.');
        $active=file_get_contents(__DIR__.'/../contracts/legal-rules.json',false,null,0,self::MAX+1);
        if(!is_string($active)||strlen($active)>self::MAX||!hash_equals($last,hash('sha256',$active)))
            throw new DomainException('Active regulatory catalog does not match approved history.');
        return ['sequence'=>$latestSequence,'head'=>$head,'catalogSha256'=>$last,'dispatchAuthorized'=>false];
    }
}
