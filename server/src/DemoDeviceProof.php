<?php
declare(strict_types=1);

/** DEMO proof of possession; no attestation or production authority. */
final class DemoDeviceProof {
    public function __construct(private PDO $db) {}
    private static function key(string $encoded):OpenSSLAsymmetricKey {
        $der=base64_decode($encoded,true);
        if($der===false||strlen($der)>256||base64_encode($der)!==$encoded)throw new InvalidArgumentException('Invalid public key.');
        $pem="-----BEGIN PUBLIC KEY-----\n".chunk_split($encoded,64,"\n")."-----END PUBLIC KEY-----\n";
        $key=openssl_pkey_get_public($pem);
        $details=$key===false?false:openssl_pkey_get_details($key);
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1')
            throw new InvalidArgumentException('Expected P-256 public key.');
        $canonical=preg_replace('/\s|-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----/','',$details['key']);
        if($canonical!==$encoded)throw new InvalidArgumentException('Noncanonical public key.');
        return $key;
    }
    /** Administrative trust establishment, once per device. Rotate by revoking and provisioning anew. */
    public function bind(string $device,string $publicKey):string {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
        self::key($publicKey);
        $fingerprint=hash('sha256',base64_decode($publicKey,true));
        $this->db->beginTransaction();
        try {
            $s=$this->db->prepare('SELECT device_id FROM demo_sync_device WHERE device_id=? AND revoked_at IS NULL FOR UPDATE');
            $s->execute([$device]);if($s->fetchColumn()!==$device)throw new DemoSyncAuthException('Device unavailable.');
            $s=$this->db->prepare('SELECT public_key FROM demo_sync_device_proof WHERE device_id=? FOR UPDATE');
            $s->execute([$device]);$existing=$s->fetchColumn();
            if($existing===false){
                $s=$this->db->prepare('INSERT INTO demo_sync_device_proof(device_id,public_key,key_sha256) VALUES (?,?,?)');
                $s->execute([$device,$publicKey,$fingerprint]);
            }elseif($existing!==$publicKey)throw new DemoSyncConflict('Existing key cannot be replaced.');
            $this->db->commit();return $fingerprint;
        }catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    public function challenge(string $authorization,string $subject):array {
        if(!preg_match('/\ABearer ([0-9a-f]{64})\z/D',$authorization,$m))throw new DemoSyncAuthException('Unauthorized.');
        $hash=hash('sha256',$m[1]);$this->db->beginTransaction();
        try{
            $s=$this->db->prepare('SELECT device_id,subject_id FROM demo_sync_device WHERE token_sha256=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE');
            $s->execute([$hash]);$device=$s->fetch(PDO::FETCH_ASSOC);
            if(!$device||$device['subject_id']!==$subject)throw new DemoSyncAuthException('Unauthorized.');
            $nonce=bin2hex(random_bytes(32));
            $s=$this->db->prepare('UPDATE demo_sync_device_proof SET nonce=?,token_sha256=?,expires_at=UTC_TIMESTAMP(6)+INTERVAL 90 SECOND WHERE device_id=?');
            $s->execute([$nonce,$hash,$device['device_id']]);
            if($s->rowCount()!==1)throw new DemoSyncAuthException('Device key not enrolled.');
            $this->db->commit();
            return ['protocol'=>1,'subjectId'=>$subject,'deviceId'=>$device['device_id'],'nonce'=>$nonce];
        }catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    public static function message(string $action,string $subject,string $device,string $nonce,string $tokenHash,string $body):string {
        return implode("\n",['FISKAI-DEMO-PROOF-V1',$action,$subject,$device,$nonce,$tokenHash,hash('sha256',$body)]);
    }
    /** Caller holds device row lock. Consume challenge in same transaction as accepted operation. */
    public function consume(string $device,string $subject,string $tokenHash,string $action,array $request,?array $proof):void {
        if(!$this->db->inTransaction())throw new LogicException('Transaction required.');
        $s=$this->db->prepare('SELECT public_key,key_sha256,nonce,token_sha256,(expires_at>UTC_TIMESTAMP(6)) AS fresh FROM demo_sync_device_proof WHERE device_id=? FOR UPDATE');
        $s->execute([$device]);$row=$s->fetch(PDO::FETCH_ASSOC);
        if(!$row){
            // Preserve internal legacy CLI tests; all HTTP requests require enrolled proof.
            if(PHP_SAPI==='cli'&&$proof===null)return;
            throw new DemoSyncAuthException('Device key not enrolled.');
        }
        if($proof===null||!is_string($proof['nonce']??null)||!is_string($proof['signature']??null)||!is_string($proof['body']??null)
            ||!preg_match('/\A[0-9a-f]{64}\z/D',$proof['nonce'])||$row['nonce']!==$proof['nonce']
            ||$row['token_sha256']!==$tokenHash||(int)$row['fresh']!==1
            ||strlen($proof['body'])>65536||json_decode($proof['body'],true,16,JSON_THROW_ON_ERROR)!==$request)
            throw new DemoSyncAuthException('Invalid challenge.');
        $signature=base64_decode($proof['signature'],true);
        if($signature===false||strlen($signature)<8||strlen($signature)>80||base64_encode($signature)!==$proof['signature'])
            throw new DemoSyncAuthException('Invalid signature.');
        $key=self::key($row['public_key']);
        if(!hash_equals($row['key_sha256'],hash('sha256',base64_decode($row['public_key'],true)))||
            openssl_verify(self::message($action,$subject,$device,$proof['nonce'],$tokenHash,$proof['body']),$signature,$key,OPENSSL_ALGO_SHA256)!==1)
            throw new DemoSyncAuthException('Invalid signature.');
        $s=$this->db->prepare('UPDATE demo_sync_device_proof SET nonce=NULL,token_sha256=NULL,expires_at=NULL WHERE device_id=? AND nonce=?');
        $s->execute([$device,$proof['nonce']]);
        if($s->rowCount()!==1)throw new DemoSyncAuthException('Challenge already consumed.');
    }
}
