<?php
declare(strict_types=1);
require_once __DIR__.'/CisEducRequestPreparation.php';
require_once __DIR__.'/CisEducResponseEvidence.php';
require_once __DIR__.'/CisEducHttpsTransport.php';

/** EDUC lifecycle. External transport disabled unless explicitly authorized; no PROD support.
 * Caller must provide trusted immutable domain document and independently approved pins.
 * No automatic re-reservation after an uncertain outcome.
 */
final class CisEducDispatch {
    public function __construct(private PDO $db) {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
    }
    private function transaction(callable $work):mixed {
        if($this->db->inTransaction())throw new LogicException('Own transaction required.');
        $this->db->beginTransaction();
        try{$result=$work();$this->db->commit();return $result;}
        catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    private function row(string $subject,string $message,bool $lock=false):array {
        $s=$this->db->prepare('SELECT d.*,e.document_sha256,e.trust_sha256,e.key_id,e.request_sha256 AS evidence_request FROM cis_educ_dispatch d JOIN cis_educ_response_evidence e ON e.message_id=d.message_id AND e.subject_id=d.subject_id AND e.document_id=d.document_id WHERE d.subject_id=? AND d.message_id=?'.($lock?' FOR UPDATE':''));
        $s->execute([$subject,$message]);$row=$s->fetch(PDO::FETCH_ASSOC);
        if(!$row||$row['request_sha256']!==$row['evidence_request'])throw new DomainException('Unknown or inconsistent dispatch.');
        return $row;
    }
    private static function aad(array $r):string {
        return implode("\0",['FiskAI:CIS:EDUC:dispatch:v1',$r['subject_id'],$r['message_id'],$r['document_id'],
            $r['document_sha256'],$r['request_sha256'],$r['signer_sha256'],$r['trust_sha256'],$r['key_id']]);
    }
    private static function decrypt(array $r,string $key):string {
        if(strlen($key)!==32)throw new InvalidArgumentException('Invalid storage key.');
        $raw=base64_decode($r['sealed_request'],true);
        if($raw===false||strlen($raw)<29||strlen($raw)>1048604)throw new RuntimeException('Invalid sealed request.');
        $soap=openssl_decrypt(substr($raw,28),'aes-256-gcm',$key,OPENSSL_RAW_DATA,substr($raw,0,12),substr($raw,12,16),self::aad($r));
        if($soap===false||!hash_equals($r['request_sha256'],hash('sha256',$soap)))throw new RuntimeException('Request authentication failed.');
        return $soap;
    }
    /** Prepare from unsigned XML; never accept an arbitrary caller-supplied signed blob. */
    public function prepare(string $subject,string $document,string $documentHash,string $unsigned,string $oib,
        OpenSSLAsymmetricKey $signingKey,OpenSSLCertificate $cert,string $signerPin,string $responsePin,
        string $schemaDir,string $keyId,string $storageKey):string {
        if(strlen($storageKey)!==32)throw new InvalidArgumentException('Invalid storage key.');
        $p=CisEducRequestPreparation::prepare($unsigned,$oib,$signingKey,$cert,$signerPin,$schemaDir);
        $message=$p['messageId'];
        return $this->transaction(function()use($subject,$document,$documentHash,$p,$message,$signerPin,$responsePin,$keyId,$storageKey){
            $ledger=new CisEducResponseEvidence($this->db);
            $ledger->register($subject,$message,$document,$documentHash,$p['requestSha256'],$responsePin,$keyId);
            $r=['subject_id'=>$subject,'message_id'=>$message,'document_id'=>$document,'document_sha256'=>$documentHash,
                'request_sha256'=>$p['requestSha256'],'signer_sha256'=>$signerPin,'trust_sha256'=>$responsePin,'key_id'=>$keyId];
            $nonce=random_bytes(12);$tag='';
            $cipher=openssl_encrypt($p['soap'],'aes-256-gcm',$storageKey,OPENSSL_RAW_DATA,$nonce,$tag,self::aad($r),16);
            if($cipher===false||strlen($tag)!==16)throw new RuntimeException('Request encryption failed.');
            $s=$this->db->prepare("INSERT INTO cis_educ_dispatch(message_id,subject_id,document_id,signer_sha256,request_sha256,sealed_request,state,updated_at)
                VALUES (?,?,?,?,?,?,'PREPARED',UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE message_id=message_id");
            $s->execute([$message,$subject,$document,$signerPin,$p['requestSha256'],base64_encode($nonce.$tag.$cipher)]);
            $stored=$this->row($subject,$message,true);
            if(!hash_equals($p['soap'],self::decrypt($stored,$storageKey)))throw new DomainException('Conflicting request.');
            return $message;
        });
    }
    public function open(string $subject,string $message,string $key):string {
        return self::decrypt($this->row($subject,$message),$key);
    }
    public function state(string $subject,string $message):string {
        return $this->row($subject,$message)['state'];
    }
    /** Reserve for a controlled offline exercise only. No network operation is performed. */
    public function reserve(string $subject,string $message,string $storageKey):array {
        return $this->transaction(function()use($subject,$message,$storageKey){
            $r=$this->row($subject,$message,true);
            if($r['state']!=='PREPARED')throw new DomainException('Already reserved or resolved.');
            $soap=self::decrypt($r,$storageKey);$token=bin2hex(random_bytes(32));
            $s=$this->db->prepare("UPDATE cis_educ_dispatch SET state='RESERVED',reservation_sha256=?,reserved_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE message_id=? AND subject_id=? AND state='PREPARED'");
            $s->execute([hash('sha256',$token),$message,$subject]);
            if($s->rowCount()!==1)throw new RuntimeException('Reservation lost.');
            return ['reservation'=>$token,'soap'=>$soap,'requestSha256'=>$r['request_sha256']];
        });
    }
    /** Explicit recovery. A previously reserved request can never automatically be sent again. */
    public function recoverInterrupted(string $subject):int {
        $s=$this->db->prepare("UPDATE cis_educ_dispatch SET state='UNCERTAIN',updated_at=UTC_TIMESTAMP(6) WHERE subject_id=? AND state='RESERVED' AND reserved_at<=UTC_TIMESTAMP(6)-INTERVAL 5 MINUTE");
        $s->execute([$subject]);return $s->rowCount();
    }
    public function uncertain(string $subject,string $message,string $reservation):bool {
        $s=$this->db->prepare("UPDATE cis_educ_dispatch SET state='UNCERTAIN',updated_at=UTC_TIMESTAMP(6) WHERE subject_id=? AND message_id=? AND state='RESERVED' AND reservation_sha256=?");
        $s->execute([$subject,$message,hash('sha256',$reservation)]);return $s->rowCount()===1;
    }
    /** Operator-controlled EDUC send. Failure after reservation is always ambiguous. */
    public function dispatchAuthorized(string $subject,string $message,OpenSSLCertificate $signer,
        OpenSSLCertificate $trusted,string $schemaDir,string $storageKey):string {
        $r=$this->row($subject,$message);
        CisEducHttpsTransport::authorize($subject,$signer,$trusted,$r['signer_sha256'],$r['trust_sha256']);
        $reserved=$this->reserve($subject,$message,$storageKey);
        try{
            $soap=CisEducHttpsTransport::post($subject,$reserved['soap'],$signer,$trusted,$r['signer_sha256'],$r['trust_sha256']);
            return $this->verifyResponse($subject,$message,$reserved['reservation'],$soap,$trusted,$schemaDir,$storageKey);
        }catch(Throwable $e){
            // Preserve uncertainty even when the network succeeded but verification or persistence failed.
            $this->uncertain($subject,$message,$reserved['reservation']);
            throw $e;
        }
    }
    /** CLI operator recovery after process loss; only a pinned, signed response can resolve uncertainty.
     * Does not perform a query to CIS or authorize a new submission.
     */
    public function recoverVerifiedResponse(string $subject,string $message,string $soap,
        OpenSSLCertificate $trusted,string $schemaDir,string $storageKey):string {
        return $this->transaction(function()use($subject,$message,$soap,$trusted,$schemaDir,$storageKey){
            $r=$this->row($subject,$message,true);
            if($r['state']!=='UNCERTAIN')throw new DomainException('Only uncertain dispatch may be reconciled.');
            return $this->persistVerified($r,$soap,$trusted,$schemaDir,$storageKey);
        });
    }
    /** Accept independently supplied signed EDUC evidence, not an assertion from the client.
     * Synthetic fixtures prove cryptographic behavior only, never actual CIS submission.
     */
    public function verifyResponse(string $subject,string $message,string $reservation,string $soap,
        OpenSSLCertificate $trusted,string $schemaDir,string $storageKey):string {
        return $this->transaction(function()use($subject,$message,$reservation,$soap,$trusted,$schemaDir,$storageKey){
            $r=$this->row($subject,$message,true);
            if(!in_array($r['state'],['RESERVED','UNCERTAIN','VERIFIED'],true)||!is_string($r['reservation_sha256'])||
                !hash_equals($r['reservation_sha256'],hash('sha256',$reservation)))throw new DomainException('Unknown reservation.');
            return $this->persistVerified($r,$soap,$trusted,$schemaDir,$storageKey);
        });
    }
    private function persistVerified(array $r,string $soap,OpenSSLCertificate $trusted,string $schemaDir,string $storageKey):string {
        self::decrypt($r,$storageKey);
        $ledger=new CisEducResponseEvidence($this->db);
        $jir=$ledger->verifyAndStore($r['subject_id'],$r['message_id'],$soap,$trusted,$schemaDir,$storageKey);
        if($r['state']!=='VERIFIED'){
            $s=$this->db->prepare("UPDATE cis_educ_dispatch SET state='VERIFIED',updated_at=UTC_TIMESTAMP(6) WHERE subject_id=? AND message_id=? AND state IN ('RESERVED','UNCERTAIN')");
            $s->execute([$r['subject_id'],$r['message_id']]);
            if($s->rowCount()!==1)throw new RuntimeException('Verification transition lost.');
        }
        return $jir;
    }
}
