<?php
declare(strict_types=1);
require_once __DIR__.'/LegalCompliance.php';

/** Internal F2 EDUC evidence; not authorization. Reads never recompute historical decisions. */
final class LegalDecisionLedger {
    private string $key;
    public function __construct(private PDO $db,string $storageKey) {
        if(PHP_SAPI!=='cli'||strlen($storageKey)!==32)throw new InvalidArgumentException('CLI storage key required.');
        $this->key=hash_hkdf('sha256',$storageKey,32,'FISKAI-LEGAL-DECISION-MAC-V1');
    }
    private function mac(string $subject,string $id,string $documentHash,string $reviewHash,string $payload):string {
        return hash_hmac('sha256',implode("\0",['FISKAI-LEGAL-DECISION-V1',$subject,$id,$documentHash,$reviewHash,$payload]),$this->key);
    }
    public function read(string $subject,string $id):?array {
        $s=$this->db->prepare('SELECT l.*,i.document_sha256 AS current_document,i.review_sha256 AS current_review FROM legal_decision_ledger l JOIN f2_educ_intake i ON i.subject_id=l.subject_id AND i.document_id=l.document_id WHERE l.subject_id=? AND l.document_id=?');
        $s->execute([$subject,$id]);$r=$s->fetch(PDO::FETCH_ASSOC);
        if(!$r)return null;
        if(!hash_equals($r['document_sha256'],$r['current_document'])||!hash_equals($r['review_sha256'],$r['current_review'])||
            !hash_equals($r['decision_mac'],$this->mac($subject,$id,$r['document_sha256'],$r['review_sha256'],$r['decision_json'])))
            throw new RuntimeException('Legal decision authentication failed.');
        return json_decode($r['decision_json'],true,16,JSON_THROW_ON_ERROR);
    }
    /** Caller holds the subject lock and owns the F2 intake transaction. */
    public function append(string $subject,string $id,array $review):array {
        if(!$this->db->inTransaction())throw new LogicException('Intake transaction required.');
        $s=$this->db->prepare('SELECT document_sha256,review_sha256 FROM f2_educ_intake WHERE subject_id=? AND document_id=? FOR UPDATE');
        $s->execute([$subject,$id]);$r=$s->fetch(PDO::FETCH_ASSOC);
        if(!$r||!hash_equals($r['document_sha256'],$review['documentSha256']))throw new DomainException('Document evidence mismatch.');
        $assessment=$review['assessment'];
        $rule=LegalCompliance::ruleForDate($assessment['eventDate']);
        if(LegalCompliance::evaluate($assessment['eventDate'],$assessment['subject'],$assessment['scope'])!==$assessment['decision'])
            throw new DomainException('Legal decision mismatch.');
        $snapshot=['format'=>1,'source'=>'https://narodne-novine.nn.hr/clanci/sluzbeni/2025_06_89_1233.html',
            'assessment'=>$assessment,'rule'=>$rule,'deadline'=>$review['deadline'],'dispatchAuthorized'=>false];
        $payload=json_encode($snapshot,JSON_THROW_ON_ERROR);
        if(strlen($payload)>16384)throw new InvalidArgumentException('Legal decision capacity exceeded.');
        $mac=$this->mac($subject,$id,$r['document_sha256'],$r['review_sha256'],$payload);
        $s=$this->db->prepare('INSERT INTO legal_decision_ledger(subject_id,document_id,document_sha256,review_sha256,decision_json,decision_mac) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE document_id=document_id');
        $s->execute([$subject,$id,$r['document_sha256'],$r['review_sha256'],$payload,$mac]);
        if($this->read($subject,$id)!==$snapshot)throw new DomainException('Immutable legal decision conflict.');
        return $snapshot;
    }
}
