<?php
declare(strict_types=1);
require_once __DIR__.'/F2InvoicePreflight.php';
require_once __DIR__.'/F2ValidationEvidence.php';
require_once __DIR__.'/F2KpdRelease.php';
require_once __DIR__.'/F2ProviderGrant.php';

/** Durable, review-only F2 outbox. No provider call, fiscal acknowledgement or production route. */
final class F2EducIntake {
    public function __construct(private PDO $db,private string $key) {
        if(PHP_SAPI!=='cli'||strlen($key)!==32)throw new InvalidArgumentException('Independent CLI storage key required.');
    }
    private static function uuid(string $id):void {
        if(!preg_match('/\A[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\z/D',$id)||$id==='00000000-0000-0000-0000-000000000000')
            throw new InvalidArgumentException('Invalid identity.');
    }
    private static function aad(array $r):string {
        return implode("\0",['FISKAI-F2-EDUC-INTAKE-V1',$r['subject_id'],$r['document_id'],$r['direction'],
            $r['identity_sha256'],$r['document_sha256'],$r['review_sha256']]);
    }
    public function read(string $subject,string $id):array {
        self::uuid($subject);self::uuid($id);
        $s=$this->db->prepare('SELECT * FROM f2_educ_intake WHERE subject_id=? AND document_id=?');
        $s->execute([$subject,$id]);$r=$s->fetch(PDO::FETCH_ASSOC);
        if(!$r)throw new DomainException('Unknown F2 record.');
        $raw=base64_decode($r['sealed_evidence'],true);
        if($raw===false||strlen($raw)<29||strlen($raw)>4194332)throw new RuntimeException('Invalid F2 ciphertext.');
        $plain=openssl_decrypt(substr($raw,28),'aes-256-gcm',$this->key,OPENSSL_RAW_DATA,substr($raw,0,12),substr($raw,12,16),self::aad($r));
        if($plain===false||!hash_equals($r['review_sha256'],hash('sha256',$plain)))throw new RuntimeException('F2 evidence authentication failed.');
        $e=json_decode($plain,true,32,JSON_THROW_ON_ERROR);
        if(!hash_equals($r['document_sha256'],hash('sha256',$e['xml'])))throw new RuntimeException('F2 document mismatch.');
        return $e;
    }
    /** Trust configuration must be independently provisioned by an authorized operator.
     * Always remains REVIEW_REQUIRED even if all supplied evidence verifies.
     */
    public function prepare(string $subject,string $id,string $direction,string $date,string $xml,TaxStatusHistory $history,string $scope,
        string $schemaDir,array $schemaPins,string $entry,string $approvedSchemaDigest,
        string $kpdSourcePin,string $kpdCatalogPin,
        string $svrl,string $validationStatement,string $validationSignature,string $validatorPublic,string $validatorPin,string $validatorManifestPin,
        string $grantStatement,string $grantSignature,string $grantPublic,string $grantPin):array {
        self::uuid($subject);self::uuid($id);
        if($this->db->inTransaction())throw new LogicException('Own transaction required.');
        $this->db->beginTransaction();
        try{
            $s=$this->db->prepare('SELECT owner_oib FROM educ_business_subject WHERE subject_id=? FOR UPDATE');
            $s->execute([$subject]);$owner=$s->fetchColumn();
            if(!is_string($owner))throw new DomainException('Unknown EDUC subject.');
            $s=$this->db->prepare('SELECT subject_id FROM educ_recovery_quarantine WHERE subject_id=?');
            $s->execute([$subject]);if($s->fetchColumn()!==false)throw new DomainException('Recovered subject quarantined.');
            $review=F2InvoicePreflight::review($xml,$owner,$direction,$date,$history,$scope,$schemaDir,$schemaPins,$entry);
            if(!hash_equals($approvedSchemaDigest,$review['schemaManifestSha256']))throw new DomainException('Unapproved XSD manifest.');
            $release=F2KpdRelease::verified($this->db,$review['issueDate'],$kpdSourcePin,$kpdCatalogPin);
            $codes=F2KpdRelease::checkLines($xml,$release);unset($release['codes']);
            $validation=F2ValidationEvidence::verify($xml,$approvedSchemaDigest,$validatorManifestPin,$svrl,$validationStatement,$validationSignature,$validatorPublic,$validatorPin);
            $grant=F2ProviderGrant::verify($grantStatement,$grantSignature,$grantPublic,$grantPin,$subject,$owner,$direction,$date);
            $e=['xml'=>$xml,'review'=>$review,'kpd'=>$release,'codes'=>$codes,'validation'=>$validation,'svrl'=>$svrl,
                'validationStatement'=>$validationStatement,'validationSignature'=>base64_encode($validationSignature),
                'grant'=>$grant,'grantStatement'=>$grantStatement,'grantSignature'=>base64_encode($grantSignature)];
            $plain=json_encode($e,JSON_THROW_ON_ERROR);
            if(strlen($plain)>4194304)throw new InvalidArgumentException('F2 evidence capacity exceeded.');
            $r=['subject_id'=>$subject,'document_id'=>$id,'direction'=>$direction,
                'identity_sha256'=>hash('sha256',implode("\0",[$review['sellerOib'],$review['buyerOib'],$review['number']])),
                'document_sha256'=>$review['documentSha256'],'review_sha256'=>hash('sha256',$plain)];
            $nonce=random_bytes(12);$tag='';
            $cipher=openssl_encrypt($plain,'aes-256-gcm',$this->key,OPENSSL_RAW_DATA,$nonce,$tag,self::aad($r),16);
            if($cipher===false||strlen($tag)!==16)throw new RuntimeException('F2 encryption failed.');
            $s=$this->db->prepare('INSERT INTO f2_educ_intake(subject_id,document_id,direction,identity_sha256,document_sha256,review_sha256,sealed_evidence,created_at) VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE document_id=document_id');
            $s->execute([...array_values($r),base64_encode($nonce.$tag.$cipher)]);
            if($this->read($subject,$id)!==$e)throw new DomainException('Immutable F2 evidence conflict.');
            $this->db->commit();
            return ['state'=>'REVIEW_REQUIRED','documentId'=>$id,'documentSha256'=>$r['document_sha256'],'reviewSha256'=>$r['review_sha256'],'dispatchAuthorized'=>false];
        }catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
}
