<?php
declare(strict_types=1);
require_once __DIR__.'/CisEducRequestPreparation.php';

/** Bounded, authenticated ledger for a narrow ordinary F1 EDUC invoice profile.
 * Not a production issuance, numbering, or tax determination engine.
 * Operator enters already allocated invoice identity and authoritative net/tax values.
 */
final class EducBusinessLedger {
    private const LIMIT=1000;
    private const ZERO='0000000000000000000000000000000000000000000000000000000000000000';
    private const NS='http://www.apis-it.hr/fin/2012/types/f73';
    public function __construct(private PDO $db) {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
    }
    private static function uuid(string $id):void {
        if(!preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/D',$id)||$id==='00000000-0000-0000-0000-000000000000')
            throw new InvalidArgumentException('Invalid identity.');
    }
    private static function oib(string $v):void {
        if(!preg_match('/\A[0-9]{11}\z/D',$v))throw new InvalidArgumentException('Invalid OIB.');
        $a=10;
        for($i=0;$i<10;$i++){$a=($a+(int)$v[$i])%10;if($a===0)$a=10;$a=($a*2)%11;}
        if((11-$a)%10!==(int)$v[10])throw new InvalidArgumentException('Invalid OIB checksum.');
    }
    private static function keys(string $key):array {
        if(strlen($key)!==32)throw new InvalidArgumentException('Invalid storage key.');
        return [hash_hkdf('sha256',$key,32,'FiskAI EDUC document encryption v1'),hash_hkdf('sha256',$key,32,'FiskAI EDUC document chain v1')];
    }
    public function provision(string $subject,string $oib,string $keyId):void {
        self::uuid($subject);self::oib($oib);
        if(!preg_match('/\A[A-Za-z0-9_-]{1,64}\z/D',$keyId))throw new InvalidArgumentException('Invalid key ID.');
        $s=$this->db->prepare('INSERT INTO educ_business_subject(subject_id,owner_oib,key_id) VALUES (?,?,?) ON DUPLICATE KEY UPDATE subject_id=subject_id');
        $s->execute([$subject,$oib,$keyId]);
        $row=$this->subject($subject,false);
        if($row['owner_oib']!==$oib||$row['key_id']!==$keyId)throw new DomainException('Subject identity conflict.');
    }
    private function subject(string $subject,bool $lock):array {
        self::uuid($subject);
        $s=$this->db->prepare('SELECT * FROM educ_business_subject WHERE subject_id=?'.($lock?' FOR UPDATE':''));
        $s->execute([$subject]);$row=$s->fetch(PDO::FETCH_ASSOC);
        if(!$row)throw new DomainException('Unknown EDUC business subject.');
        return $row;
    }
    private static function normalize(array $input,string $oib):array {
        $fields=['documentId','messageId','issuedAt','number','premise','register','operatorOib','netCents','rate','payment'];
        if(count($input)!==count($fields)||array_diff($fields,array_keys($input)))throw new InvalidArgumentException('Invalid document fields.');
        foreach($fields as $f)if(!in_array($f,['netCents','rate'],true)&&!is_string($input[$f]))throw new InvalidArgumentException('Invalid document value.');
        self::uuid($input['documentId']);self::uuid($input['messageId']);self::oib($input['operatorOib']);self::oib($oib);
        $date=DateTimeImmutable::createFromFormat('!d.m.Y H:i:s',$input['issuedAt'],new DateTimeZone('Europe/Zagreb'));
        if(!$date||$date->format('d.m.Y H:i:s')!==$input['issuedAt']||!in_array($date->format('Y'),['2026','2027'],true))
            throw new InvalidArgumentException('Unsupported issue date.');
        if(!preg_match('/\A[1-9][0-9]{0,19}\z/D',$input['number'])||!preg_match('/\A[A-Z0-9]{1,20}\z/D',$input['premise'])||
            !preg_match('/\A[1-9][0-9]{0,19}\z/D',$input['register'])||!in_array($input['payment'],['G','K','T','O'],true)||
            !is_int($input['netCents'])||$input['netCents']<1||$input['netCents']>99999999999||!in_array($input['rate'],[5,13,25],true))
            throw new InvalidArgumentException('Unsupported ordinary invoice profile.');
        $row=[];foreach($fields as $f)$row[$f]=$input[$f];
        $tax=intdiv($row['netCents']*$row['rate']+50,100);
        return $row+['ownerOib'=>$oib,'taxCents'=>$tax,'totalCents'=>$row['netCents']+$tax,'year'=>$date->format('Y')];
    }
    private static function aad(array $r,string $keyId):string {
        return implode("\0",['FiskAI:EDUC:document:v1',$r['subject_id'],$r['document_id'],$r['message_id'],(string)$r['chain_position'],
            (string)$r['fiscal_year'],$r['premise'],$r['register_no'],$r['invoice_no'],$r['document_sha256'],$r['previous_mac'],$keyId]);
    }
    private static function headMac(array $scope,int $position,string $last,string $mac):string {
        return hash_hmac('sha256',implode("\0",['FiskAI:EDUC:head:v1',$scope['subject_id'],$scope['owner_oib'],$scope['key_id'],(string)$position,$last]),$mac);
    }
    private function checkedRows(array $scope,string $key):array {
        [$enc,$mac]=self::keys($key);
        $s=$this->db->prepare('SELECT * FROM educ_business_document WHERE subject_id=? ORDER BY chain_position LIMIT 1001');
        $s->execute([$scope['subject_id']]);$result=[];$previous=self::ZERO;
        while($r=$s->fetch(PDO::FETCH_ASSOC)){
            if(count($result)>=self::LIMIT||(int)$r['chain_position']!==count($result)+1||$r['previous_mac']!==$previous)
                throw new RuntimeException('Document chain discontinuity.');
            $aad=self::aad($r,$scope['key_id']);
            if(!hash_equals($r['entry_mac'],hash_hmac('sha256',$aad,$mac)))throw new RuntimeException('Document chain authentication failed.');
            $blob=base64_decode($r['sealed_document'],true);
            if($blob===false||strlen($blob)<29||strlen($blob)>8192)throw new RuntimeException('Invalid sealed document.');
            $plain=openssl_decrypt(substr($blob,28),'aes-256-gcm',$enc,OPENSSL_RAW_DATA,substr($blob,0,12),substr($blob,12,16),$aad);
            if($plain===false||!hash_equals($r['document_sha256'],hash('sha256',$plain)))throw new RuntimeException('Document authentication failed.');
            $doc=json_decode($plain,true,16,JSON_THROW_ON_ERROR);
            $input=array_diff_key($doc,array_flip(['ownerOib','taxCents','totalCents','year']));
            if(self::normalize($input,$scope['owner_oib'])!==$doc||$doc['documentId']!==$r['document_id']||
                $doc['messageId']!==$r['message_id']||$doc['year']!==(string)$r['fiscal_year']||
                $doc['premise']!==$r['premise']||$doc['register']!==$r['register_no']||$doc['number']!==$r['invoice_no'])
                throw new RuntimeException('Document metadata mismatch.');
            $previous=$r['entry_mac'];
            $result[]=['document'=>$doc,'digest'=>$r['document_sha256'],'mac'=>$previous,'keyId'=>$scope['key_id']];
        }
        $s=$this->db->prepare('SELECT chain_position,entry_mac,head_mac FROM educ_business_chain_head WHERE subject_id=?');
        $s->execute([$scope['subject_id']]);$head=$s->fetch(PDO::FETCH_ASSOC);
        if($head===false){
            if($result)throw new RuntimeException('Missing chain head.');
        }elseif((int)$head['chain_position']!==count($result)||$head['entry_mac']!==$previous||
            !hash_equals($head['head_mac'],self::headMac($scope,count($result),$previous,$mac)))
            throw new RuntimeException('Chain head authentication failed.');
        return $result;
    }
    /** Consistent full-chain verification inside the caller's recovery transaction. */
    public function verifySubject(string $subject,string $storageKey):array {
        if(!$this->db->inTransaction())throw new LogicException('Recovery transaction required.');
        return $this->checkedRows($this->subject($subject,true),$storageKey);
    }
    public function read(string $subject,string $document,string $storageKey):array {
        self::uuid($document);
        $own=!$this->db->inTransaction();
        if($own)$this->db->beginTransaction();
        try{
            $rows=$this->checkedRows($this->subject($subject,true),$storageKey);
            foreach($rows as $row)if($row['document']['documentId']===$document){
                if($own)$this->db->commit();return $row;
            }
            throw new DomainException('Unknown document.');
        }catch(Throwable $e){if($own&&$this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    public function append(string $subject,array $input,string $storageKey):array {
        if($this->db->inTransaction())throw new LogicException('Own transaction required.');
        [$enc,$mac]=self::keys($storageKey);
        $this->db->beginTransaction();
        try {
            $scope=$this->subject($subject,true);
            $doc=self::normalize($input,$scope['owner_oib']);
            $rows=$this->checkedRows($scope,$storageKey);
            $plain=json_encode($doc,JSON_THROW_ON_ERROR);
            $digest=hash('sha256',$plain);
            foreach($rows as $existing){
                $d=$existing['document'];
                if($d['documentId']===$doc['documentId']){
                    if($existing['digest']!==$digest)throw new DomainException('Immutable document conflict.');
                    $this->db->commit();return $existing;
                }
                if($d['messageId']===$doc['messageId']||[$d['year'],$d['premise'],$d['register'],$d['number']]===[$doc['year'],$doc['premise'],$doc['register'],$doc['number']])
                    throw new DomainException('Duplicate fiscal identity.');
            }
            if(count($rows)>=self::LIMIT)throw new RuntimeException('Document ledger capacity exceeded.');
            $r=['subject_id'=>$subject,'document_id'=>$doc['documentId'],'message_id'=>$doc['messageId'],'chain_position'=>count($rows)+1,
                'fiscal_year'=>$doc['year'],'premise'=>$doc['premise'],'register_no'=>$doc['register'],'invoice_no'=>$doc['number'],
                'document_sha256'=>$digest,'previous_mac'=>$rows?end($rows)['mac']:self::ZERO];
            $aad=self::aad($r,$scope['key_id']);
            $r['entry_mac']=hash_hmac('sha256',$aad,$mac);
            $nonce=random_bytes(12);$tag='';
            $cipher=openssl_encrypt($plain,'aes-256-gcm',$enc,OPENSSL_RAW_DATA,$nonce,$tag,$aad,16);
            if($cipher===false||strlen($tag)!==16)throw new RuntimeException('Document encryption failed.');
            $r['sealed_document']=base64_encode($nonce.$tag.$cipher);
            $s=$this->db->prepare('INSERT INTO educ_business_document ('.implode(',',array_keys($r)).') VALUES ('.implode(',',array_fill(0,count($r),'?')).')');
            $s->execute(array_values($r));
            $head=self::headMac($scope,$r['chain_position'],$r['entry_mac'],$mac);
            $s=$this->db->prepare('INSERT INTO educ_business_chain_head(subject_id,chain_position,entry_mac,head_mac) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE chain_position=VALUES(chain_position),entry_mac=VALUES(entry_mac),head_mac=VALUES(head_mac)');
            $s->execute([$subject,$r['chain_position'],$r['entry_mac'],$head]);
            $this->db->commit();
            return ['document'=>$doc,'digest'=>$digest,'mac'=>$r['entry_mac'],'keyId'=>$scope['key_id']];
        }catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    private static function money(int $cents):string {return intdiv($cents,100).'.'.str_pad((string)($cents%100),2,'0',STR_PAD_LEFT);}
    public static function unsigned(array $d,string $zki):string {
        if(!preg_match('/\A[0-9a-f]{32}\z/D',$zki))throw new InvalidArgumentException('Invalid ZKI.');
        $date=str_replace(' ','T',$d['issuedAt']);
        // Every interpolated field has a restrictive ASCII grammar or is derived from integer cents.
        return '<f:RacunZahtjev xmlns:f="'.self::NS.'" Id="RacunZahtjev"><f:Zaglavlje><f:IdPoruke>'.$d['messageId'].'</f:IdPoruke><f:DatumVrijeme>'.$date.'</f:DatumVrijeme></f:Zaglavlje><f:Racun><f:Oib>'.$d['ownerOib'].'</f:Oib><f:USustPdv>true</f:USustPdv><f:DatVrijeme>'.$date.'</f:DatVrijeme><f:OznSlijed>N</f:OznSlijed><f:BrRac><f:BrOznRac>'.$d['number'].'</f:BrOznRac><f:OznPosPr>'.$d['premise'].'</f:OznPosPr><f:OznNapUr>'.$d['register'].'</f:OznNapUr></f:BrRac><f:Pdv><f:Porez><f:Stopa>'.$d['rate'].'.00</f:Stopa><f:Osnovica>'.self::money($d['netCents']).'</f:Osnovica><f:Iznos>'.self::money($d['taxCents']).'</f:Iznos></f:Porez></f:Pdv><f:IznosUkupno>'.self::money($d['totalCents']).'</f:IznosUkupno><f:NacinPlac>'.$d['payment'].'</f:NacinPlac><f:OibOper>'.$d['operatorOib'].'</f:OibOper><f:ZastKod>'.$zki.'</f:ZastKod><f:NakDost>false</f:NakDost></f:Racun></f:RacunZahtjev>';
    }
    public static function request(array $d,OpenSSLAsymmetricKey $key):string {
        return self::unsigned($d,CisEducRequestPreparation::zki($d['ownerOib'],$d['issuedAt'],$d['number'],$d['premise'],$d['register'],self::money($d['totalCents']),$key));
    }
    /** Check the entire unsigned request against the authoritative document and verify its XML signature. */
    public static function assertBound(array $d,string $soap,OpenSSLCertificate $cert):void {
        if(strlen($soap)>1048576||str_contains($soap,'<!')||str_contains($soap,"\0"))throw new InvalidArgumentException('Invalid SOAP.');
        $doc=new DOMDocument();if(!$doc->loadXML($soap,LIBXML_NONET)||$doc->doctype!==null)throw new InvalidArgumentException('Invalid SOAP.');
        $xp=new DOMXPath($doc);$xp->registerNamespace('s','http://schemas.xmlsoap.org/soap/envelope/');$xp->registerNamespace('f',self::NS);
        $xp->registerNamespace('ds','http://www.w3.org/2000/09/xmldsig#');
        $root=$xp->query('/s:Envelope/s:Body/f:RacunZahtjev');
        if($root->length!==1)throw new DomainException('Missing request.');
        $node=$root->item(0);
        $zki=$xp->query('f:Racun/f:ZastKod',$node);
        $sig=$xp->query('ds:Signature',$node);
        if($zki->length!==1||$sig->length!==1)throw new DomainException('Missing proof.');
        $signature=$sig->item(0);
        $si=$xp->query('ds:SignedInfo',$signature);$sv=$xp->query('ds:SignatureValue',$signature);
        $dig=$xp->query('ds:SignedInfo/ds:Reference/ds:DigestValue',$signature);
        if($si->length!==1||$sv->length!==1||$dig->length!==1)throw new DomainException('Invalid proof.');
        $public=openssl_pkey_get_public($cert);
        $bytes=base64_decode($sv->item(0)->textContent,true);
        $canonical=$si->item(0)->C14N(true,false);
        if(!$public||$bytes===false||$canonical===false||openssl_verify($canonical,$bytes,$public,OPENSSL_ALGO_SHA256)!==1)
            throw new DomainException('Stored request signature invalid.');
        $digest=$dig->item(0)->textContent;
        $node->removeChild($signature);
        $expected=new DOMDocument();$expected->loadXML(self::unsigned($d,$zki->item(0)->textContent),LIBXML_NONET);
        $actual=$node->C14N(true,false);
        if($actual===false||$expected->documentElement->C14N(true,false)!==$actual||
            !hash_equals(base64_encode(hash('sha256',$actual,true)),$digest))
            throw new DomainException('Request does not match stored document.');
    }
}
