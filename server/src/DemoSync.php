<?php
declare(strict_types=1);
require_once __DIR__.'/FiscalReportingRules.php';

/** Unverified DEMO metadata replication only. Never queues fiscal transport. */
final class DemoSyncAuthException extends RuntimeException {}
final class DemoSyncConflict extends RuntimeException {}
final class DemoSync {
    public function __construct(private PDO $db) {}
    private static function uuid(string $id):void {
        if (!preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/D',$id)
            || $id==='00000000-0000-0000-0000-000000000000') throw new InvalidArgumentException('Invalid identity.');
    }
    private static function uuidRandom():string {
        $b=random_bytes(16);$b[6]=chr((ord($b[6])&15)|64);$b[8]=chr((ord($b[8])&63)|128);
        $h=bin2hex($b);return substr($h,0,8).'-'.substr($h,8,4).'-'.substr($h,12,4).'-'.substr($h,16,4).'-'.substr($h,20);
    }
    /** Administrative CLI only; token is returned once and never stored in cleartext. */
    public function provision(string $subject):array {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
        self::uuid($subject);
        $device=self::uuidRandom();$token=bin2hex(random_bytes(32));
        $this->db->beginTransaction();
        try {
            $s=$this->db->prepare('INSERT INTO demo_sync_subject(subject_id,created_at) VALUES (?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE subject_id=subject_id');
            $s->execute([$subject]);
            $s=$this->db->prepare('INSERT INTO demo_sync_device(device_id,subject_id,token_sha256,expires_at) VALUES (?,?,?,UTC_TIMESTAMP(6)+INTERVAL 30 DAY)');
            $s->execute([$device,$subject,hash('sha256',$token)]);
            $this->db->commit();
            return ['subjectId'=>$subject,'deviceId'=>$device,'token'=>$token];
        } catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    public function revoke(string $device):bool {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
        self::uuid($device);
        $s=$this->db->prepare('UPDATE demo_sync_device SET revoked_at=UTC_TIMESTAMP(6) WHERE device_id=? AND revoked_at IS NULL');
        $s->execute([$device]);return $s->rowCount()===1;
    }
    private static function fields(array $row):array {
        $keys=['eventId','receiptId','documentId','evidenceSha256','eventDate','amountCents','ruleVersion','deadlineDate'];
        if(count($row)!==count($keys)||array_diff($keys,array_keys($row)))throw new InvalidArgumentException('Invalid event fields.');
        foreach($keys as $k)if($k!=='amountCents'&&!is_string($row[$k]))throw new InvalidArgumentException('Invalid event value.');
        self::uuid($row['eventId']);self::uuid($row['documentId']);
        foreach(['receiptId','evidenceSha256'] as $k)if(!preg_match('/\A[0-9a-f]{64}\z/D',$row[$k]))throw new InvalidArgumentException('Invalid digest.');
        if(!is_int($row['amountCents'])||$row['amountCents']<1||$row['amountCents']>999999999999)throw new InvalidArgumentException('Invalid amount.');
        $deadline=FiscalReportingRules::deadline('PAYMENT',$row['eventDate']);
        $rules=require __DIR__.'/LegalRuleCatalog.php';
        $rule=$rules[(int)substr($row['eventDate'],0,4)]??null;
        if($rule===null||$row['ruleVersion']!==$rule['version']||$row['deadlineDate']!==$deadline['date'])
            throw new InvalidArgumentException('Unreviewed rule or deadline.');
        $ordered=[];foreach($keys as $k)$ordered[$k]=$row[$k];
        return $ordered;
    }
    /** Complete bounded immutable metadata snapshot, scoped by the device credential.
     * Same lock order as push. A single transaction avoids pagination omissions.
     */
    public function pull(string $authorization,array $request):array {
        if(!preg_match('/\ABearer ([0-9a-f]{64})\z/D',$authorization,$m))throw new DemoSyncAuthException('Unauthorized.');
        if(count($request)!==2||($request['protocol']??null)!==1||!is_string($request['subjectId']??null))
            throw new InvalidArgumentException('Invalid snapshot request.');
        self::uuid($request['subjectId']);
        $this->db->beginTransaction();
        try {
            $s=$this->db->prepare('SELECT subject_id FROM demo_sync_device WHERE token_sha256=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE');
            $s->execute([hash('sha256',$m[1])]);$subject=$s->fetchColumn();
            if(!is_string($subject)||!hash_equals($subject,$request['subjectId']))throw new DemoSyncAuthException('Unauthorized.');
            $s=$this->db->prepare('SELECT subject_id FROM demo_sync_subject WHERE subject_id=? FOR UPDATE');
            $s->execute([$subject]);if($s->fetchColumn()!==$subject)throw new RuntimeException('Scope unavailable.');
            $s=$this->db->prepare('SELECT event_id,receipt_id,document_id,evidence_sha256,event_date,amount_cents,rule_version,deadline_date,payload_sha256 FROM demo_sync_payment WHERE subject_id=? ORDER BY event_id LIMIT 2001 FOR UPDATE');
            $s->execute([$subject]);$events=[];
            while($row=$s->fetch(PDO::FETCH_ASSOC)){
                if(count($events)>=2000)throw new RuntimeException('Scope capacity exceeded.');
                $event=self::fields(['eventId'=>$row['event_id'],'receiptId'=>$row['receipt_id'],
                    'documentId'=>$row['document_id'],'evidenceSha256'=>$row['evidence_sha256'],
                    'eventDate'=>$row['event_date'],'amountCents'=>(int)$row['amount_cents'],
                    'ruleVersion'=>$row['rule_version'],'deadlineDate'=>$row['deadline_date']]);
                if(!hash_equals($row['payload_sha256'],hash('sha256',json_encode($event,JSON_THROW_ON_ERROR))))
                    throw new RuntimeException('Stored metadata integrity failure.');
                $events[]=$event;
            }
            $this->db->commit();
            return ['protocol'=>1,'subjectId'=>$subject,'status'=>'DEMO_METADATA_SNAPSHOT','events'=>$events];
        }catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    /** Authenticate inside the transaction. Every batch either commits in full or rolls back. */
    public function push(string $authorization,array $request):array {
        if(!preg_match('/\ABearer ([0-9a-f]{64})\z/D',$authorization,$m))throw new DemoSyncAuthException('Unauthorized.');
        if(count($request)!==3||($request['protocol']??null)!==1||!is_string($request['subjectId']??null)
            ||!is_array($request['events']??null)||!array_is_list($request['events'])||count($request['events'])<1||count($request['events'])>50)
            throw new InvalidArgumentException('Invalid sync batch.');
        self::uuid($request['subjectId']);
        $this->db->beginTransaction();
        try {
            $s=$this->db->prepare('SELECT subject_id FROM demo_sync_device WHERE token_sha256=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE');
            $s->execute([hash('sha256',$m[1])]);$subject=$s->fetchColumn();
            if(!is_string($subject)||!hash_equals($subject,$request['subjectId']))throw new DemoSyncAuthException('Unauthorized.');
            $s=$this->db->prepare('SELECT subject_id FROM demo_sync_subject WHERE subject_id=? FOR UPDATE');
            $s->execute([$subject]);if($s->fetchColumn()!==$subject)throw new RuntimeException('Scope unavailable.');
            $s=$this->db->prepare('SELECT COUNT(*) FROM demo_sync_payment WHERE subject_id=?');
            $s->execute([$subject]);$count=(int)$s->fetchColumn();
            $accepted=[];$seen=[];
            foreach($request['events'] as $raw) {
                if(!is_array($raw))throw new InvalidArgumentException('Invalid event.');
                $row=self::fields($raw);$id=$row['eventId'];
                if(isset($seen[$id]))throw new InvalidArgumentException('Duplicate batch identity.');
                $seen[$id]=true;
                $hash=hash('sha256',json_encode($row,JSON_THROW_ON_ERROR));
                $s=$this->db->prepare('SELECT event_id,payload_sha256 FROM demo_sync_payment WHERE subject_id=? AND (event_id=? OR receipt_id=?) FOR UPDATE');
                $s->execute([$subject,$id,$row['receiptId']]);$existing=$s->fetchAll(PDO::FETCH_ASSOC);
                if($existing) {
                    if(count($existing)!==1||$existing[0]['event_id']!==$id||!hash_equals($existing[0]['payload_sha256'],$hash))
                        throw new DemoSyncConflict('Conflicting immutable event.');
                } else {
                    if($count>=2000)throw new DemoSyncConflict('Scope capacity reached.');
                    $s=$this->db->prepare('INSERT INTO demo_sync_payment(subject_id,event_id,receipt_id,document_id,evidence_sha256,event_date,amount_cents,rule_version,deadline_date,payload_sha256,received_at) VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))');
                    $s->execute([$subject,...array_values($row),$hash]);$count++;
                }
                $accepted[]=$id;
            }
            $this->db->commit();
            return ['protocol'=>1,'subjectId'=>$subject,'status'=>'DEMO_METADATA_STORED','accepted'=>$accepted];
        }catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
}
