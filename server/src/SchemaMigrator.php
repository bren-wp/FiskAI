<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';

/** Only retry-safe CREATE TABLE IF NOT EXISTS migrations are supported by this runner.
 * MySQL/MariaDB DDL implicitly commits; a failed step must never be treated as applied.
 * Future non-idempotent changes need a separately reviewed migration protocol. */
final class SchemaMigrator {
    public static function apply(PDO $db,string $directory): array {
        if ($db->inTransaction()) throw new LogicException('Migration requires its own connection.');
        $schema=$db->query('SELECT DATABASE()')->fetchColumn();
        if (!is_string($schema)||$schema==='') throw new RuntimeException('No selected database.');
        $lock='fiskai-schema-'.substr(hash('sha256',$schema),0,40);
        $stmt=$db->prepare('SELECT GET_LOCK(?, 15)');
        $stmt->execute([$lock]);
        if ((int)$stmt->fetchColumn()!==1) throw new RuntimeException('Migration lock unavailable.');
        try {
            $db->exec("CREATE TABLE IF NOT EXISTS fiskai_schema_migrations (
                migration VARCHAR(128) CHARACTER SET ascii PRIMARY KEY,
                checksum CHAR(64) CHARACTER SET ascii NOT NULL,
                state ENUM('applying','applied') NOT NULL,
                applied_at DATETIME(6) NULL
            ) ENGINE=InnoDB");
            $files=glob(rtrim($directory,'/').'/*.sql');
            if (!$files) throw new RuntimeException('No migration files.');
            sort($files,SORT_STRING);
            $plan=[];
            foreach ($files as $file) {
                $name=basename($file);
                if (!preg_match('/\A[0-9]{3}(?:_[a-z0-9_]+)?\.sql\z/D',$name)) throw new RuntimeException('Invalid migration filename.');
                $source=file_get_contents($file);
                if ($source===false||strlen($source)>1048576) throw new RuntimeException('Invalid migration size.');
                $statements=array_values(array_filter(array_map('trim',explode(';',$source)),static fn(string $s):bool=>$s!==''));
                if (!$statements) throw new RuntimeException('Empty migration.');
                foreach ($statements as $sql) {
                    if (!preg_match('/\ACREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+[a-z0-9_]+\s*\(/i',$sql)) throw new RuntimeException('Unsupported migration statement: '.$name);
                }
                $plan[$name]=[hash('sha256',$source),$statements];
            }
            $existing=$db->query('SELECT migration,checksum,state FROM fiskai_schema_migrations')->fetchAll(PDO::FETCH_ASSOC);
            $known=[];
            foreach ($existing as $row) {
                $name=$row['migration'];
                if (!isset($plan[$name])||!hash_equals($row['checksum'],$plan[$name][0])) throw new RuntimeException('Migration history mismatch: '.$name);
                $known[$name]=$row['state'];
            }
            $applied=[];
            foreach ($plan as $name=>[$checksum,$statements]) {
                if (($known[$name]??null)==='applied') continue;
                if (!isset($known[$name])) {
                    $stmt=$db->prepare("INSERT INTO fiskai_schema_migrations (migration,checksum,state) VALUES (?,?,'applying')");
                    $stmt->execute([$name,$checksum]);
                }
                foreach ($statements as $sql) $db->exec($sql);
                $stmt=$db->prepare("UPDATE fiskai_schema_migrations SET state='applied',applied_at=UTC_TIMESTAMP(6) WHERE migration=? AND checksum=? AND state='applying'");
                $stmt->execute([$name,$checksum]);
                if ($stmt->rowCount()!==1) throw new RuntimeException('Migration state mismatch.');
                $applied[]=$name;
            }
            return $applied;
        } finally {
            $stmt=$db->prepare('SELECT RELEASE_LOCK(?)');
            $stmt->execute([$lock]);
        }
    }
}
