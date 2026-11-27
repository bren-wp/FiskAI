<?php
declare(strict_types=1);

/** Bounded logical archive of all application data tables in migrations 001–011.
 * No SQL from archives is executed. Restore only to a freshly migrated isolated database.
 * Database accounts, external storage, keys, grants and physical binlogs are not included.
 */
final class ServerRecovery {
    private const MAX=16777216;
    private const ROWS=50000;
    private const MAGIC="FISKAI-SERVER-RECOVERY-V1\0";
    private const TABLES=[
        'price_lists'=>['id'],'price_heads'=>['site_key','kind'],
        'kpd_catalog'=>['code','valid_from'],'kpd_source'=>['id'],
        'fiscal_event_outbox'=>['environment','subject_id','event_id'],
        'demo_sync_subject'=>['subject_id'],'demo_sync_device'=>['device_id'],
        'demo_sync_payment'=>['subject_id','event_id'],'demo_sync_device_proof'=>['device_id'],
        'demo_document_scope'=>['subject_id'],'demo_sync_document'=>['subject_id','document_id'],
        'educ_business_subject'=>['subject_id'],'educ_business_document'=>['subject_id','document_id'],
        'educ_business_chain_head'=>['subject_id'],'educ_recovery_quarantine'=>['subject_id'],
        'cis_educ_response_evidence'=>['message_id'],'cis_educ_dispatch'=>['message_id'],
        'cis_educ_dispatch_audit'=>['message_id','event_type']
    ];
    public function __construct(private PDO $db) {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
    }
    private static function key(string $key):void {
        if(strlen($key)!==32)throw new InvalidArgumentException('Independent archive key required.');
    }
    private function transaction(callable $work):mixed {
        if($this->db->inTransaction())throw new LogicException('Own transaction required.');
        $this->db->exec('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ');
        $this->db->beginTransaction();
        try{$result=$work();$this->db->commit();return $result;}
        catch(Throwable $e){if($this->db->inTransaction())$this->db->rollBack();throw $e;}
    }
    private function manifest():array {
        $manifest=[];
        $files=glob(__DIR__.'/../database/*.sql');sort($files,SORT_STRING);
        foreach($files as $file)$manifest[basename($file)]=hash_file('sha256',$file);
        $rows=$this->db->query('SELECT migration,checksum,state FROM fiskai_schema_migrations ORDER BY migration')->fetchAll(PDO::FETCH_ASSOC);
        $actual=[];
        foreach($rows as $row){
            if($row['state']!=='applied')throw new RuntimeException('Incomplete migration.');
            $actual[$row['migration']]=$row['checksum'];
        }
        if($manifest!==$actual)throw new RuntimeException('Schema history mismatch.');
        return $manifest;
    }
    private function columns():array {
        $rows=$this->db->query("SELECT TABLE_NAME,ENGINE,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")->fetchAll(PDO::FETCH_ASSOC);
        $expected=array_keys(self::TABLES);$expected[]='fiskai_schema_migrations';sort($expected);
        $actual=[];
        foreach($rows as $row){
            if($row['ENGINE']!=='InnoDB'||$row['TABLE_TYPE']!=='BASE TABLE')throw new RuntimeException('Unsupported table engine or object.');
            $actual[]=$row['TABLE_NAME'];
        }
        sort($actual);if($expected!==$actual)throw new RuntimeException('Unexpected or missing application table.');
        $result=[];
        $s=$this->db->prepare('SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION');
        foreach(self::TABLES as $table=>$pk){
            $s->execute([$table]);$cols=$s->fetchAll(PDO::FETCH_COLUMN);
            if(!$cols||array_diff($pk,$cols))throw new RuntimeException('Invalid table definition.');
            foreach($cols as $col)if(!preg_match('/\A[a-z][a-z0-9_]*\z/D',$col))throw new RuntimeException('Invalid column.');
            $result[$table]=$cols;
        }
        return $result;
    }
    private function snapshot(array $columns):array {
        $tables=[];$bytes=0;$count=0;
        foreach(self::TABLES as $table=>$pk){
            $cols=$columns[$table];$select=implode(',',$cols);
            $sizeExpr=implode('+',array_map(fn($c)=>"COALESCE(OCTET_LENGTH(CAST($c AS BINARY)),0)",$cols));
            $bounds=$this->db->query("SELECT COUNT(*) AS n,COALESCE(SUM($sizeExpr),0) AS bytes FROM $table")->fetch(PDO::FETCH_ASSOC);
            if((int)$bounds['n']>self::ROWS-$count||(int)$bounds['bytes']>self::MAX-$bytes)
                throw new RuntimeException('Archive capacity exceeded.');
            $rows=[];
            $stmt=$this->db->query("SELECT $select FROM $table ORDER BY ".implode(',',$pk));
            while($row=$stmt->fetch(PDO::FETCH_NUM)){
                $encoded=array_map(static fn($v)=>$v===null?null:base64_encode((string)$v),$row);
                $bytes+=strlen(json_encode($encoded,JSON_THROW_ON_ERROR));$count++;
                if($bytes>self::MAX||$count>self::ROWS)throw new RuntimeException('Archive capacity exceeded.');
                $rows[]=$encoded;
            }
            $tables[$table]=$rows;
        }
        return $tables;
    }
    public function export(string $key):string {
        self::key($key);
        $payload=$this->transaction(function(){
            $manifest=$this->manifest();$columns=$this->columns();
            return json_encode(['version'=>1,'migrations'=>$manifest,'columns'=>$columns,'tables'=>$this->snapshot($columns)],JSON_THROW_ON_ERROR);
        });
        if(strlen($payload)>self::MAX)throw new RuntimeException('Archive capacity exceeded.');
        $nonce=random_bytes(12);$tag='';
        $cipher=openssl_encrypt($payload,'aes-256-gcm',$key,OPENSSL_RAW_DATA,$nonce,$tag,self::MAGIC,16);
        if($cipher===false||strlen($tag)!==16)throw new RuntimeException('Archive encryption failed.');
        return self::MAGIC.$nonce.$tag.$cipher;
    }
    public function restore(string $archive,string $key):array {
        self::key($key);
        if(getenv('FISKAI_SERVER_RECOVERY_MODE')!=='1'||getenv('FISKAI_CIS_RECOVERY_MODE')!=='1'||
            getenv('FISKAI_CIS_EDUC_ENABLED')==='1'||getenv('FISKAI_DEMO_SYNC_ENABLED')==='1'||getenv('FISKAI_DEMO_DOCUMENTS_ENABLED')==='1')
            throw new LogicException('Isolated server recovery mode required.');
        $offset=strlen(self::MAGIC);
        if(strlen($archive)<$offset+29||strlen($archive)>self::MAX+$offset+28||!str_starts_with($archive,self::MAGIC))
            throw new InvalidArgumentException('Invalid archive format.');
        $plain=openssl_decrypt(substr($archive,$offset+28),'aes-256-gcm',$key,OPENSSL_RAW_DATA,
            substr($archive,$offset,12),substr($archive,$offset+12,16),self::MAGIC);
        if($plain===false)throw new InvalidArgumentException('Archive authentication failed.');
        $root=json_decode($plain,true,16,JSON_THROW_ON_ERROR);
        if(!is_array($root)||array_keys($root)!==['version','migrations','columns','tables']||$root['version']!==1||
            !is_array($root['tables'])||array_keys($root['tables'])!==array_keys(self::TABLES))
            throw new InvalidArgumentException('Invalid archive structure.');
        return $this->transaction(function()use($root){
            $columns=$this->columns();
            if($root['columns']!==$columns||$root['migrations']!==$this->manifest())throw new InvalidArgumentException('Archive schema mismatch.');
            foreach(self::TABLES as $table=>$pk)
                if($this->db->query("SELECT 1 FROM $table LIMIT 1 FOR UPDATE")->fetchColumn()!==false)
                    throw new DomainException('Recovery target must be empty.');
            $total=0;$counts=[];
            foreach(self::TABLES as $table=>$pk){
                $rows=$root['tables'][$table];$cols=$columns[$table];
                if(!is_array($rows)||!array_is_list($rows)||count($rows)>self::ROWS-$total)throw new InvalidArgumentException('Invalid row count.');
                $insert=$this->db->prepare("INSERT INTO $table (".implode(',',$cols).') VALUES ('.implode(',',array_fill(0,count($cols),'?')).')');
                foreach($rows as $row){
                    if(!is_array($row)||!array_is_list($row)||count($row)!==count($cols))throw new InvalidArgumentException('Invalid row shape.');
                    $values=[];
                    foreach($row as $value){
                        if($value===null){$values[]=null;continue;}
                        if(!is_string($value)||($decoded=base64_decode($value,true))===false||base64_encode($decoded)!==$value)
                            throw new InvalidArgumentException('Invalid cell encoding.');
                        $values[]=$decoded;
                    }
                    $insert->execute($values);
                }
                $counts[$table]=count($rows);$total+=count($rows);
            }
            if($this->snapshot($columns)!==$root['tables'])throw new RuntimeException('Restored data differs from authenticated snapshot.');
            // Credentials and in-flight requests from an old snapshot must never be reused.
            $this->db->exec("UPDATE demo_sync_device SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(6))");
            $this->db->exec("UPDATE demo_sync_device_proof SET nonce=NULL,token_sha256=NULL,expires_at=NULL");
            $this->db->exec("UPDATE fiscal_event_outbox SET state='UNCERTAIN',updated_at=UTC_TIMESTAMP(6)");
            $this->db->exec("INSERT INTO educ_recovery_quarantine(subject_id,restored_at)
                SELECT subject_id,UTC_TIMESTAMP(6) FROM educ_business_subject ON DUPLICATE KEY UPDATE subject_id=educ_recovery_quarantine.subject_id");
            $this->db->exec("INSERT INTO educ_recovery_quarantine(subject_id,restored_at)
                SELECT DISTINCT subject_id,UTC_TIMESTAMP(6) FROM cis_educ_response_evidence ON DUPLICATE KEY UPDATE subject_id=educ_recovery_quarantine.subject_id");
            $this->db->exec("INSERT INTO cis_educ_dispatch_audit(message_id,event_type,actor,recorded_at)
                SELECT message_id,'RESTORED_QUARANTINE','recovery-cli',UTC_TIMESTAMP(6) FROM cis_educ_dispatch WHERE state<>'VERIFIED'
                ON DUPLICATE KEY UPDATE message_id=cis_educ_dispatch_audit.message_id");
            $this->db->exec("UPDATE cis_educ_dispatch SET state='UNCERTAIN',updated_at=UTC_TIMESTAMP(6) WHERE state<>'VERIFIED'");
            return $counts;
        });
    }
}
