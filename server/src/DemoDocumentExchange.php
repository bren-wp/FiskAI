<?php
declare(strict_types=1);

/** Offline DEMO review. Never imports into EDUC/PROD or assigns a fiscal number. */
final class DemoDocumentExchange {
    private static function uuid(string $id):void {
        if(!preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/D',$id)||$id==='00000000-0000-0000-0000-000000000000')
            throw new InvalidArgumentException('Invalid identity.');
    }
    private static function decode(string $value,int $limit):string {
        if(strlen($value)>4*intdiv($limit+2,3))throw new InvalidArgumentException('Envelope size exceeded.');
        $data=base64_decode($value,true);
        if($data===false||strlen($data)>$limit||base64_encode($data)!==$value)throw new InvalidArgumentException('Invalid envelope encoding.');
        return $data;
    }
    public static function verify(string $envelope,string $subject,string $owner,string $publicKey):array {
        self::uuid($subject);
        if(strlen($envelope)>1500000||!preg_match('/\A[0-9]{11}\z/D',$owner)||strlen($publicKey)>512)
            throw new InvalidArgumentException('Invalid review scope.');
        $outer=json_decode($envelope,true,8,JSON_THROW_ON_ERROR);
        if(!is_array($outer)||array_keys($outer)!==['payload','signature']||!is_string($outer['payload'])||!is_string($outer['signature']))
            throw new InvalidArgumentException('Invalid envelope.');
        $body=self::decode($outer['payload'],1048576);$signature=self::decode($outer['signature'],80);
        $der=self::decode($publicKey,256);
        $key=openssl_pkey_get_public("-----BEGIN PUBLIC KEY-----\n".chunk_split($publicKey,64,"\n")."-----END PUBLIC KEY-----\n");
        $details=$key===false?false:openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1'||
            openssl_verify($body,$signature,$key,OPENSSL_ALGO_SHA256)!==1)
            throw new DomainException('Document envelope signature invalid.');
        $data=json_decode($body,true,16,JSON_THROW_ON_ERROR);
        if(!is_array($data)||array_keys($data)!==['protocol','subjectId','ownerOib','keySha256','documents']||
            $data['protocol']!=='FISKAI-DEMO-DOCUMENTS-V1'||$data['subjectId']!==$subject||$data['ownerOib']!==$owner||
            $data['keySha256']!==hash('sha256',$der)||!is_array($data['documents'])||!array_is_list($data['documents'])||
            count($data['documents'])<1||count($data['documents'])>1000)
            throw new DomainException('Document envelope scope mismatch.');
        $ids=[];$numbers=[];$results=[];
        foreach($data['documents'] as $row){
            if(!is_array($row)||array_keys($row)!==['documentId','digest','snapshot']||
                !is_string($row['documentId'])||!is_string($row['digest'])||!is_string($row['snapshot']))
                throw new InvalidArgumentException('Invalid document row.');
            self::uuid($row['documentId']);
            if(isset($ids[$row['documentId']])||!hash_equals(hash('sha256',$row['snapshot']),$row['digest']))
                throw new DomainException('Duplicate or changed document.');
            $ids[$row['documentId']]=true;
            $p=json_decode($row['snapshot'],true,16,JSON_THROW_ON_ERROR);
            if(!is_array($p)||($p['environment']??null)!=='DEMO'||($p['sellerOib']??null)!==$owner||
                !is_int($p['year']??null)||$p['year']<2000||$p['year']>9999||
                !is_int($p['number']??null)||$p['number']<1||$p['number']>999999999||
                !is_string($p['premise']??null)||!is_string($p['register']??null)||
                !preg_match('/\A[A-Za-z0-9]{1,20}\z/D',$p['premise'])||!preg_match('/\A[0-9]{1,20}\z/D',$p['register'])||
                !is_int($p['created']??null)||$p['created']<1||!is_string($p['draft']??null))
                throw new DomainException('Invalid DEMO snapshot identity.');
            self::uuid($p['draft']);
            $number=implode('/',[$p['year'],$p['premise'],$p['register'],$p['number']]);
            if(isset($numbers[$number]))throw new DomainException('Duplicate fiscal number.');
            $numbers[$number]=true;
            $results[]=['documentId'=>$row['documentId'],'digest'=>$row['digest'],'number'=>$number];
        }
        return ['status'=>'DEMO_REVIEW_ONLY','subjectId'=>$subject,'payloadSha256'=>hash('sha256',$body),'documents'=>$results];
    }
    /** Administrator supplies the expected seller OIB and a device identity out of band. */
    public static function verifyEnrolled(PDO $db,string $envelope,string $subject,string $owner,string $device):array {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
        self::uuid($subject);self::uuid($device);
        $s=$db->prepare('SELECT p.public_key,p.key_sha256 FROM demo_sync_device d JOIN demo_sync_device_proof p ON p.device_id=d.device_id WHERE d.subject_id=? AND d.device_id=? AND d.revoked_at IS NULL AND d.expires_at>UTC_TIMESTAMP(6)');
        $s->execute([$subject,$device]);$row=$s->fetch(PDO::FETCH_ASSOC);
        if(!$row||!hash_equals($row['key_sha256'],hash('sha256',self::decode($row['public_key'],256))))
            throw new DomainException('Approved device unavailable.');
        return self::verify($envelope,$subject,$owner,$row['public_key']);
    }
}
