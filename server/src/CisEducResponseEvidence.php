<?php
declare(strict_types=1);
require_once __DIR__.'/FiscalResponse.php';

/** Internal offline EDUC evidence only, not eInvoice reporting or proof of production issuance.
 * Trusted caller registers the actual immutable document and outbound request digest before
 * accepting a response. Certificate pin and random 256-bit storage key are provisioned out of band.
 * Never expose registration or verification directly through the public HTTP API.
 */
final class CisEducResponseEvidence {
    public function __construct(private PDO $db) {
        if (PHP_SAPI!=='cli') throw new LogicException('Offline EDUC evidence only.');
    }
    private static function uuid(string $id):void {
        if (!preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/D',$id)
            || $id==='00000000-0000-0000-0000-000000000000') throw new InvalidArgumentException('Invalid identity.');
    }
    private static function digest(string $value):void {
        if (!preg_match('/\A[0-9a-f]{64}\z/D',$value)) throw new InvalidArgumentException('Invalid digest.');
    }
    private static function key(string $key):void {
        if (strlen($key)!==32) throw new InvalidArgumentException('Expected independently provisioned 256-bit key.');
    }
    private function row(string $subject,string $message):array {
        self::uuid($subject);self::uuid($message);
        $stmt=$this->db->prepare('SELECT * FROM cis_educ_response_evidence WHERE subject_id=? AND message_id=?');
        $stmt->execute([$subject,$message]);
        $row=$stmt->fetch(PDO::FETCH_ASSOC);
        if ($row===false) throw new InvalidArgumentException('Unknown EDUC request.');
        return $row;
    }
    public function register(string $subject,string $message,string $document,string $documentHash,
        string $requestHash,string $trustPin,string $keyId):void {
        self::uuid($subject);self::uuid($message);self::uuid($document);
        foreach ([$documentHash,$requestHash,$trustPin] as $hash) self::digest($hash);
        if (!preg_match('/\A[A-Za-z0-9_-]{1,64}\z/D',$keyId)) throw new InvalidArgumentException('Invalid key identity.');
        $fields=['subject_id'=>$subject,'document_id'=>$document,'document_sha256'=>$documentHash,
            'request_sha256'=>$requestHash,'trust_sha256'=>$trustPin,'key_id'=>$keyId];
        $stmt=$this->db->prepare('INSERT INTO cis_educ_response_evidence
            (message_id,subject_id,document_id,document_sha256,request_sha256,trust_sha256,key_id,registered_at)
            VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE message_id=message_id');
        $stmt->execute([$message,...array_values($fields)]);
        $row=$this->row($subject,$message);
        foreach ($fields as $name=>$value) if ($row[$name]!==$value) throw new DomainException('Conflicting request identity.');
    }
    private static function aad(array $row,string $hash,string $jir):string {
        return implode("\0",['FiskAI:CIS:EDUC:v1',$row['message_id'],$row['subject_id'],$row['document_id'],
            $row['document_sha256'],$row['request_sha256'],$row['trust_sha256'],$row['key_id'],$hash,$jir]);
    }
    private static function schemaHashes():array {
        $manifest=json_decode(file_get_contents(__DIR__.'/../contracts/cis-v1.10.sha256.json'),true,512,JSON_THROW_ON_ERROR);
        $pins=$manifest['Fiskalizacija-WSDL-EDUC_v1.10.zip'];
        return ['FiskalizacijaSchema.xsd'=>$pins['schema/FiskalizacijaSchema.xsd'],
            'xmldsig-core-schema.xsd'=>$pins['schema/xmldsig-core-schema.xsd']];
    }
    /** Verifies correlation, official pinned XSD and signature before any response is persisted. */
    public function verifyAndStore(string $subject,string $message,string $soap,OpenSSLCertificate $trusted,
        string $schemaDir,string $storageKey):string {
        self::key($storageKey);
        $row=$this->row($subject,$message);
        $jir=FiscalResponse::verifyJir($soap,$message,$trusted,$row['trust_sha256'],$schemaDir,self::schemaHashes());
        $hash=hash('sha256',$soap);
        if ($row['response_sha256']===null) {
            $nonce=random_bytes(12);
            $cipher=openssl_encrypt($soap,'aes-256-gcm',$storageKey,OPENSSL_RAW_DATA,$nonce,$tag,self::aad($row,$hash,$jir),16);
            if ($cipher===false || strlen($tag)!==16) throw new RuntimeException('Evidence encryption failed.');
            $stmt=$this->db->prepare('UPDATE cis_educ_response_evidence
                SET response_sha256=?,jir=?,sealed_response=?,verified_at=UTC_TIMESTAMP(6)
                WHERE subject_id=? AND message_id=? AND response_sha256 IS NULL');
            $stmt->execute([$hash,$jir,base64_encode($nonce.$tag.$cipher),$subject,$message]);
            $row=$this->row($subject,$message);
        }
        if ($row['response_sha256']!==$hash || $row['jir']!==$jir) throw new DomainException('Conflicting signed response.');
        if (!hash_equals($soap,$this->open($subject,$message,$storageKey))) throw new RuntimeException('Stored response mismatch.');
        return $jir;
    }
    /** Recover original signed bytes; does not establish current certificate trust or production acceptance. */
    public function open(string $subject,string $message,string $storageKey):string {
        self::key($storageKey);
        $row=$this->row($subject,$message);
        if ($row['response_sha256']===null || $row['jir']===null || $row['verified_at']===null || !is_string($row['sealed_response']))
            throw new LogicException('No verified EDUC evidence.');
        $bytes=base64_decode($row['sealed_response'],true);
        if ($bytes===false || strlen($bytes)<29 || strlen($bytes)>1048604) throw new RuntimeException('Invalid sealed evidence.');
        $plain=openssl_decrypt(substr($bytes,28),'aes-256-gcm',$storageKey,OPENSSL_RAW_DATA,substr($bytes,0,12),
            substr($bytes,12,16),self::aad($row,$row['response_sha256'],$row['jir']));
        if ($plain===false || !hash_equals($row['response_sha256'],hash('sha256',$plain))) throw new RuntimeException('Evidence authentication failed.');
        return $plain;
    }
}
