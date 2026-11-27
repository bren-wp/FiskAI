<?php
declare(strict_types=1);
require_once __DIR__.'/CisEducDispatch.php';

/** Bounded, authenticated EDUC dispatch archive. Not a complete database backup.
 * Restore only into an isolated recovery installation. Never requeue archived requests.
 */
final class CisEducRecovery {
    private const MAX=16777216;
    private const MAGIC="FISKAI-EDUC-RECOVERY-V1\0";
    private const TABLES=[
        'cis_educ_response_evidence'=>['message_id','subject_id','document_id','document_sha256','request_sha256','trust_sha256','key_id','response_sha256','jir','sealed_response','registered_at','verified_at'],
        'cis_educ_dispatch'=>['message_id','subject_id','document_id','signer_sha256','request_sha256','sealed_request','state','reservation_sha256','reserved_at','updated_at'],
        'cis_educ_dispatch_audit'=>['message_id','event_type','actor','recorded_at']
    ];
    public function __construct(private PDO $db) {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
    }
    private static function uuid(string $id):void {
        if(!preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/D',$id)||$id==='00000000-0000-0000-0000-000000000000')
            throw new InvalidArgumentException('Invalid scope.');
    }
    private static function keys(string $archiveKey,string $storageKey):void {
        if(strlen($archiveKey)!==32||strlen($storageKey)!==32||hash_equals($archiveKey,$storageKey))
            throw new InvalidArgumentException('Independent 256-bit archive and storage keys required.');
    }
    private function transaction(callable $work):mixed {
        if($this->db->inTransaction())throw new LogicException('Own transaction required.');
        $this->db->exec('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ');
        $this->db->beginTransaction();
        try{$result=$work();$this->db->commit();return $result;}
        catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    private function snapshot(string $subject):array {
        $tables=[];$bytes=0;
        foreach(self::TABLES as $table=>$columns){
            $select=implode(',',array_map(fn($c)=>'t.'.$c,$columns));
            $sql="SELECT $select FROM $table t JOIN cis_educ_dispatch d ON d.message_id=t.message_id WHERE d.subject_id=? ORDER BY t.message_id";
            if($table==='cis_educ_dispatch_audit')$sql.=',t.event_type';
            $s=$this->db->prepare($sql.' LIMIT 5001');$s->execute([$subject]);
            $rows=[];
            while($row=$s->fetch(PDO::FETCH_ASSOC)){
                $bytes+=strlen(json_encode($row,JSON_THROW_ON_ERROR));
                if($bytes>self::MAX||count($rows)>=5000)throw new RuntimeException('Archive capacity exceeded.');
                $rows[]=$row;
            }
            $tables[$table]=$rows;
        }
        return $tables;
    }
    private function validateStored(string $subject,array $tables,string $keyId,string $storageKey):void {
        $dispatch=new CisEducDispatch($this->db);$evidence=new CisEducResponseEvidence($this->db);
        $requests=$tables['cis_educ_dispatch'];$responses=$tables['cis_educ_response_evidence'];
        if(count($requests)<1||count($requests)>1000||count($requests)!==count($responses))throw new InvalidArgumentException('Invalid archive scope size.');
        $byId=array_column($responses,null,'message_id');
        if(count($byId)!==count($responses))throw new InvalidArgumentException('Duplicate response identity.');
        foreach($requests as $row){
            self::uuid($row['message_id']);self::uuid($row['document_id']);
            $response=$byId[$row['message_id']]??null;
            if(!$response||$row['subject_id']!==$subject||$response['subject_id']!==$subject||$row['document_id']!==$response['document_id']||
                $row['request_sha256']!==$response['request_sha256']||$response['key_id']!==$keyId)
                throw new InvalidArgumentException('Archive identity or key mismatch.');
            $dispatch->open($subject,$row['message_id'],$storageKey);
            $complete=$response['response_sha256']!==null&&$response['jir']!==null&&$response['sealed_response']!==null&&$response['verified_at']!==null;
            $empty=$response['response_sha256']===null&&$response['jir']===null&&$response['sealed_response']===null&&$response['verified_at']===null;
            if($row['state']==='VERIFIED'){
                if(!$complete)throw new RuntimeException('Missing verified response.');
                $evidence->open($subject,$row['message_id'],$storageKey);
            }elseif(!in_array($row['state'],['PREPARED','RESERVED','UNCERTAIN'],true)||!$empty)
                throw new RuntimeException('Inconsistent dispatch state.');
        }
    }
    public function export(string $subject,string $keyId,string $archiveKey,string $storageKey):string {
        self::uuid($subject);self::keys($archiveKey,$storageKey);
        $payload=$this->transaction(function()use($subject,$keyId,$storageKey){
            $tables=$this->snapshot($subject);
            $this->validateStored($subject,$tables,$keyId,$storageKey);
            return json_encode(['version'=>1,'subject'=>$subject,'keyId'=>$keyId,'tables'=>$tables],JSON_THROW_ON_ERROR);
        });
        if(strlen($payload)>self::MAX)throw new RuntimeException('Archive too large.');
        $nonce=random_bytes(12);$tag='';
        $cipher=openssl_encrypt($payload,'aes-256-gcm',$archiveKey,OPENSSL_RAW_DATA,$nonce,$tag,self::MAGIC.$subject,16);
        if($cipher===false||strlen($tag)!==16)throw new RuntimeException('Archive encryption failed.');
        return self::MAGIC.$nonce.$tag.$cipher;
    }
    public function restore(string $subject,string $keyId,string $archive,string $archiveKey,string $storageKey):int {
        self::uuid($subject);self::keys($archiveKey,$storageKey);
        if(getenv('FISKAI_CIS_RECOVERY_MODE')!=='1'||getenv('FISKAI_CIS_EDUC_ENABLED')==='1')
            throw new LogicException('Isolated recovery mode required.');
        $offset=strlen(self::MAGIC);
        if(strlen($archive)<$offset+29||strlen($archive)>self::MAX+$offset+28||!str_starts_with($archive,self::MAGIC))
            throw new InvalidArgumentException('Invalid archive format.');
        $plain=openssl_decrypt(substr($archive,$offset+28),'aes-256-gcm',$archiveKey,OPENSSL_RAW_DATA,
            substr($archive,$offset,12),substr($archive,$offset+12,16),self::MAGIC.$subject);
        if($plain===false)throw new InvalidArgumentException('Archive authentication failed.');
        $root=json_decode($plain,true,16,JSON_THROW_ON_ERROR);
        if(!is_array($root)||array_keys($root)!==['version','subject','keyId','tables']||$root['version']!==1||$root['subject']!==$subject||
            $root['keyId']!==$keyId||!is_array($root['tables'])||array_keys($root['tables'])!==array_keys(self::TABLES))
            throw new InvalidArgumentException('Archive metadata mismatch.');
        $tables=$root['tables'];
        $ids=[];
        foreach(self::TABLES as $table=>$columns){
            $rows=$tables[$table];
            if(!is_array($rows)||!array_is_list($rows)||count($rows)>5000)throw new InvalidArgumentException('Invalid archive rows.');
            foreach($rows as $row){
                if(!is_array($row)||array_keys($row)!==$columns)throw new InvalidArgumentException('Invalid archive columns.');
                foreach($row as $value)if($value!==null&&!is_string($value))throw new InvalidArgumentException('Invalid archive value.');
                self::uuid($row['message_id']);
                if($table==='cis_educ_response_evidence'){
                    if($row['subject_id']!==$subject||isset($ids[$row['message_id']]))throw new InvalidArgumentException('Invalid archive scope.');
                    $ids[$row['message_id']]=true;
                }elseif(!isset($ids[$row['message_id']])||($table==='cis_educ_dispatch'&&$row['subject_id']!==$subject))
                    throw new InvalidArgumentException('Invalid archive reference.');
            }
        }
        return $this->transaction(function()use($subject,$keyId,$tables,$storageKey){
            // Deliberately refuse an existing subject; no destructive overwrite or merge.
            $s=$this->db->prepare('SELECT message_id FROM cis_educ_response_evidence WHERE subject_id=? FOR UPDATE');
            $s->execute([$subject]);if($s->fetchColumn()!==false)throw new DomainException('Recovery target already contains subject records.');
            foreach(self::TABLES as $table=>$columns){
                $insert=$this->db->prepare("INSERT INTO $table (".implode(',',$columns).') VALUES ('.implode(',',array_fill(0,count($columns),'?')).')');
                foreach($tables[$table] as $row)$insert->execute(array_values($row));
            }
            $this->validateStored($subject,$tables,$keyId,$storageKey);
            $audit=$this->db->prepare("INSERT INTO cis_educ_dispatch_audit(message_id,event_type,actor,recorded_at) VALUES (?,'RESTORED_QUARANTINE','recovery-cli',UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE message_id=message_id");
            $update=$this->db->prepare("UPDATE cis_educ_dispatch SET state='UNCERTAIN',updated_at=UTC_TIMESTAMP(6) WHERE message_id=? AND subject_id=? AND state<>'VERIFIED'");
            foreach($tables['cis_educ_dispatch'] as $row){
                if($row['state']==='VERIFIED')continue;
                $update->execute([$row['message_id'],$subject]);$audit->execute([$row['message_id']]);
            }
            return count($tables['cis_educ_dispatch']);
        });
    }
}
