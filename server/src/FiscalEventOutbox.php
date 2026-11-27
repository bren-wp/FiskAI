<?php
declare(strict_types=1);
require_once __DIR__.'/FiscalReportingRules.php';

/** Internal offline preparation ledger. No HTTP endpoint or fiscal transport.
 * Only EDUC can be claimed, and a claimed/uncertain event cannot be requeued.
 * IDs and evidence digest must be obtained from trusted persisted domain records.
 */
final class FiscalEventOutbox {
    public function __construct(private PDO $db) {}
    private static function uuid(string $id):void {
        if (!preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/D',$id)
            || $id==='00000000-0000-0000-0000-000000000000') throw new InvalidArgumentException('Invalid identity.');
    }
    private static function scope(string $environment,string $subject,string $event):void {
        if (!in_array($environment,['EDUC','PROD'],true)) throw new InvalidArgumentException('Invalid environment.');
        self::uuid($subject); self::uuid($event);
    }
    public function prepare(string $environment,string $subject,string $event,string $document,string $type,
        string $date,string $digest,bool $selfBilled=false):array {
        self::scope($environment,$subject,$event); self::uuid($document);
        if (!preg_match('/\A[0-9a-f]{64}\z/D',$digest)) throw new InvalidArgumentException('Invalid evidence digest.');
        $deadline=FiscalReportingRules::deadline($type,$date,$selfBilled);
        $rules=require __DIR__.'/LegalRuleCatalog.php';
        $rule=$rules[(int)substr($date,0,4)]??null;
        if ($rule===null || $deadline['kind']==='REVIEW_REQUIRED') throw new InvalidArgumentException('Unreviewed rule period.');
        $immutable=[
            'document_id'=>$document,'event_type'=>$type,'event_date'=>$date,'evidence_sha256'=>$digest,
            'rule_version'=>$rule['version'],'deadline_kind'=>$deadline['kind'],'deadline_date'=>$deadline['date'],
            'working_days'=>$deadline['workingDays'],
        ];
        // Idempotent insert; unique key serializes concurrent callers. Never overwrite evidence.
        $stmt=$this->db->prepare("INSERT INTO fiscal_event_outbox
            (environment,subject_id,event_id,document_id,event_type,event_date,evidence_sha256,rule_version,deadline_kind,deadline_date,working_days,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE event_id=event_id");
        $stmt->execute([$environment,$subject,$event,...array_values($immutable)]);
        $row=$this->read($environment,$subject,$event);
        if ($row===null) throw new RuntimeException('Outbox record missing.');
        foreach ($immutable as $key=>$value) {
            $actual=$row[$key];
            if ($actual!==null && $key==='working_days') $actual=(int)$actual;
            if ($actual!==$value) throw new DomainException('Conflicting fiscal event identity.');
        }
        return $row;
    }
    public function read(string $environment,string $subject,string $event):?array {
        self::scope($environment,$subject,$event);
        $stmt=$this->db->prepare('SELECT * FROM fiscal_event_outbox WHERE environment=? AND subject_id=? AND event_id=?');
        $stmt->execute([$environment,$subject,$event]);
        $row=$stmt->fetch(PDO::FETCH_ASSOC);
        return $row===false?null:$row;
    }
    /** Reserve once for controlled EDUC preparation. No network request is performed. */
    public function claim(string $environment,string $subject,string $event):?string {
        self::scope($environment,$subject,$event);
        if ($environment!=='EDUC') throw new LogicException('Production transport disabled.');
        $token=bin2hex(random_bytes(32));
        $stmt=$this->db->prepare("UPDATE fiscal_event_outbox SET state='CLAIMED',claim_token=?,claimed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
            WHERE environment=? AND subject_id=? AND event_id=? AND state='PREPARED'");
        $stmt->execute([$token,$environment,$subject,$event]);
        return $stmt->rowCount()===1?$token:null;
    }
    public function markUncertain(string $environment,string $subject,string $event,string $token):bool {
        self::scope($environment,$subject,$event);
        if ($environment!=='EDUC'||!preg_match('/\A[0-9a-f]{64}\z/D',$token)) throw new InvalidArgumentException('Invalid claim.');
        $stmt=$this->db->prepare("UPDATE fiscal_event_outbox SET state='UNCERTAIN',updated_at=UTC_TIMESTAMP(6)
            WHERE environment=? AND subject_id=? AND event_id=? AND state='CLAIMED' AND claim_token=?");
        $stmt->execute([$environment,$subject,$event,$token]);
        return $stmt->rowCount()===1;
    }
    /** Interrupted claims require manual reconciliation, not automatic resubmission. */
    public function recoverInterrupted(string $subject):int {
        self::uuid($subject);
        $stmt=$this->db->prepare("UPDATE fiscal_event_outbox SET state='UNCERTAIN',updated_at=UTC_TIMESTAMP(6)
            WHERE environment='EDUC' AND subject_id=? AND state='CLAIMED' AND claimed_at<=UTC_TIMESTAMP(6)-INTERVAL 5 MINUTE");
        $stmt->execute([$subject]);return $stmt->rowCount();
    }
}
