<?php
declare(strict_types=1);
require_once __DIR__.'/DemoSync.php';
require_once __DIR__.'/DemoDocumentExchange.php';

/** Bounded encrypted DEMO document replica. Never writes EDUC/PROD business records. */
final class DemoDocumentReplica {
    private const LIMIT=1048576;
    public function __construct(private PDO $db,private string $key) {
        if(strlen($key)!==32)throw new InvalidArgumentException('Independent replica key required.');
    }
    public static function provision(PDO $db,string $subject,string $owner):void {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
        if(!preg_match('/\A[0-9]{11}\z/D',$owner))throw new InvalidArgumentException('Invalid owner.');
        $a=10;
        for($i=0;$i<10;$i++){$a=($a+(int)$owner[$i])%10;if($a===0)$a=10;$a=($a*2)%11;}
        if((11-$a)%10!==(int)$owner[10])throw new InvalidArgumentException('Invalid OIB checksum.');
        $s=$db->prepare('INSERT INTO demo_document_scope(subject_id,owner_oib) VALUES (?,?) ON DUPLICATE KEY UPDATE subject_id=subject_id');
        $s->execute([$subject,$owner]);
        $s=$db->prepare('SELECT owner_oib FROM demo_document_scope WHERE subject_id=?');$s->execute([$subject]);
        if($s->fetchColumn()!==$owner)throw new DomainException('Document scope already assigned.');
    }
    private static function aad(string $subject,string $owner,string $id,string $number,string $digest):string {
        return implode("\0",['FISKAI-DEMO-REPLICA-V1',$subject,$owner,$id,$number,$digest]);
    }
    private function open(array $row,string $owner):string {
        $raw=base64_decode($row['sealed_document'],true);
        if($raw===false||strlen($raw)<29||strlen($raw)>self::LIMIT+28)throw new RuntimeException('Invalid replica ciphertext.');
        $plain=openssl_decrypt(substr($raw,28),'aes-256-gcm',$this->key,OPENSSL_RAW_DATA,substr($raw,0,12),substr($raw,12,16),
            self::aad($row['subject_id'],$owner,$row['document_id'],$row['fiscal_identity'],$row['document_sha256']));
        if($plain===false||!hash_equals($row['document_sha256'],hash('sha256',$plain)))throw new RuntimeException('Replica authentication failed.');
        return $plain;
    }
    private function snapshot(string $subject,string $owner):array {
        $s=$this->db->prepare('SELECT COUNT(*) AS n,COALESCE(SUM(OCTET_LENGTH(sealed_document)),0) AS bytes FROM demo_sync_document WHERE subject_id=?');
        $s->execute([$subject]);$bounds=$s->fetch(PDO::FETCH_ASSOC);
        if((int)$bounds['n']>1000||(int)$bounds['bytes']>1500000)throw new DemoSyncConflict('Replica capacity exceeded.');
        $s=$this->db->prepare('SELECT * FROM demo_sync_document WHERE subject_id=? ORDER BY document_id');
        $s->execute([$subject]);$rows=[];$size=0;
        while($r=$s->fetch(PDO::FETCH_ASSOC)){
            $plain=$this->open($r,$owner);$size+=strlen($plain);
            if($size>self::LIMIT)throw new DemoSyncConflict('Replica capacity exceeded.');
            $rows[]=['documentId'=>$r['document_id'],'digest'=>$r['document_sha256'],'snapshot'=>$plain];
        }
        if(strlen(json_encode($rows,JSON_THROW_ON_ERROR))>1900000)throw new DemoSyncConflict('Replica response too large.');
        return $rows;
    }
    public function exchange(string $authorization,string $action,array $request,array $proof):array {
        if(!preg_match('/\ABearer ([0-9a-f]{64})\z/D',$authorization,$m))throw new DemoSyncAuthException('Unauthorized.');
        $push=$action==='demo-documents-push';
        if(!$push&&$action!=='demo-documents-pull')throw new InvalidArgumentException('Invalid action.');
        if(count($request)!==($push?3:2)||($request['protocol']??null)!==1||!is_string($request['subjectId']??null)||
            ($push&&!is_string($request['envelope']??null)))throw new InvalidArgumentException('Invalid document request.');
        $this->db->beginTransaction();
        try{
            $hash=hash('sha256',$m[1]);
            $s=$this->db->prepare('SELECT device_id,subject_id FROM demo_sync_device WHERE token_sha256=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE');
            $s->execute([$hash]);$device=$s->fetch(PDO::FETCH_ASSOC);
            if(!$device||$device['subject_id']!==$request['subjectId'])throw new DemoSyncAuthException('Unauthorized.');
            $subject=$device['subject_id'];
            (new DemoDeviceProof($this->db))->consume($device['device_id'],$subject,$hash,$action,$request,$proof);
            $s=$this->db->prepare('SELECT subject_id FROM demo_sync_subject WHERE subject_id=? FOR UPDATE');
            $s->execute([$subject]);if($s->fetchColumn()!==$subject)throw new DemoSyncAuthException('Unavailable subject.');
            $s=$this->db->prepare('SELECT owner_oib FROM demo_document_scope WHERE subject_id=? FOR UPDATE');
            $s->execute([$subject]);$owner=$s->fetchColumn();
            if(!is_string($owner))throw new DemoSyncAuthException('Document scope not enabled.');
            if(!$push){
                $rows=$this->snapshot($subject,$owner);
                $this->db->commit();
                return ['protocol'=>1,'subjectId'=>$subject,'ownerOib'=>$owner,'status'=>'DEMO_DOCUMENT_SNAPSHOT','documents'=>$rows];
            }
            $s=$this->db->prepare('SELECT public_key FROM demo_sync_device_proof WHERE device_id=?');
            $s->execute([$device['device_id']]);$public=$s->fetchColumn();
            if(!is_string($public))throw new DemoSyncAuthException('Device key unavailable.');
            try{$verified=DemoDocumentExchange::verify($request['envelope'],$subject,$owner,$public);}
            catch(DomainException $e){throw new DemoSyncConflict('Invalid signed documents.',0,$e);}
            $outer=json_decode($request['envelope'],true,8,JSON_THROW_ON_ERROR);
            $body=json_decode(base64_decode($outer['payload'],true),true,16,JSON_THROW_ON_ERROR);
            $accepted=[];
            foreach($body['documents'] as $i=>$row){
                $summary=$verified['documents'][$i];$id=$row['documentId'];$number=$summary['number'];$digest=$row['digest'];
                $s=$this->db->prepare('SELECT * FROM demo_sync_document WHERE subject_id=? AND (document_id=? OR fiscal_identity=?) FOR UPDATE');
                $s->execute([$subject,$id,$number]);$existing=$s->fetchAll(PDO::FETCH_ASSOC);
                if($existing){
                    if(count($existing)!==1||$existing[0]['document_id']!==$id||$existing[0]['fiscal_identity']!==$number||
                        $existing[0]['document_sha256']!==$digest||!hash_equals($this->open($existing[0],$owner),$row['snapshot']))
                        throw new DemoSyncConflict('Immutable document conflict.');
                }else{
                    $nonce=random_bytes(12);$tag='';
                    $cipher=openssl_encrypt($row['snapshot'],'aes-256-gcm',$this->key,OPENSSL_RAW_DATA,$nonce,$tag,self::aad($subject,$owner,$id,$number,$digest),16);
                    if($cipher===false||strlen($tag)!==16)throw new RuntimeException('Replica encryption failed.');
                    $s=$this->db->prepare('INSERT INTO demo_sync_document(subject_id,document_id,fiscal_identity,document_sha256,sealed_document,received_at) VALUES (?,?,?,?,?,UTC_TIMESTAMP(6))');
                    $s->execute([$subject,$id,$number,$digest,base64_encode($nonce.$tag.$cipher)]);
                }
                $accepted[]=$id;
            }
            $this->snapshot($subject,$owner);
            $this->db->commit();
            return ['protocol'=>1,'subjectId'=>$subject,'status'=>'DEMO_DOCUMENTS_STORED','accepted'=>$accepted];
        }catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
}
