<?php
declare(strict_types=1);
require_once __DIR__.'/KpdCatalog.php';
require_once __DIR__.'/LegalCompliance.php';

/** Separately reviewed snapshots; never infer 2027 validity from the mutable public search index. */
final class F2KpdRelease {
    public static function revision(string $date):string {
        LegalCompliance::validateDate($date);
        return match(substr($date,0,4)){
            '2026'=>'KPD2025-2026','2027'=>'KPD2025-NN119-2027',
            default=>throw new InvalidArgumentException('Unreviewed KPD period.')
        };
    }
    private static function active(string $json,string $date):array {
        $rows=[];
        foreach(KpdCatalog::decode($json) as [$code,$from,$until,$name]){
            if($from>$date||($until!==null&&$until<$date))continue;
            if(isset($rows[$code]))throw new DomainException('Overlapping KPD code periods.');
            $rows[$code]=$name;
        }
        if(count($rows)<3000||count($rows)>20000)throw new DomainException('Incomplete KPD release.');
        ksort($rows,SORT_STRING);return $rows;
    }
    public static function import(PDO $db,string $date,string $json,string $approvedSha):string {
        if(PHP_SAPI!=='cli'||$db->inTransaction())throw new LogicException('Own CLI transaction required.');
        if(!in_array($date,['2026-01-01','2027-01-01'],true)||!preg_match('/\A[0-9a-f]{64}\z/D',$approvedSha)||
            !hash_equals($approvedSha,hash('sha256',$json)))throw new InvalidArgumentException('Approved release checksum required.');
        $revision=self::revision($date);$rows=self::active($json,$date);
        $digest=hash('sha256',json_encode($rows,JSON_THROW_ON_ERROR));
        $db->beginTransaction();
        try{
            $s=$db->prepare('INSERT INTO f2_kpd_release(revision,source_sha256,catalog_sha256,imported_at) VALUES (?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE revision=revision');
            $s->execute([$revision,$approvedSha,$digest]);
            $s=$db->prepare('SELECT source_sha256,catalog_sha256 FROM f2_kpd_release WHERE revision=? FOR UPDATE');
            $s->execute([$revision]);$stored=$s->fetch(PDO::FETCH_ASSOC);
            if(!$stored||$stored['source_sha256']!==$approvedSha||$stored['catalog_sha256']!==$digest)
                throw new DomainException('Immutable KPD release conflict.');
            $s=$db->prepare('SELECT COUNT(*) FROM f2_kpd_code WHERE revision=?');$s->execute([$revision]);
            if((int)$s->fetchColumn()===0){
                $insert=$db->prepare('INSERT INTO f2_kpd_code(revision,code,name_hr) VALUES (?,?,?)');
                foreach($rows as $code=>$name)$insert->execute([$revision,$code,$name]);
            }
            self::verified($db,$date,$approvedSha);
            $db->commit();return $revision;
        }catch(Throwable $e){if($db->inTransaction())$db->rollBack();throw $e;}
    }
    /** External approved source hash is independent of database contents. */
    public static function verified(PDO $db,string $date,string $approvedSha):array {
        $revision=self::revision($date);
        $s=$db->prepare('SELECT source_sha256,catalog_sha256 FROM f2_kpd_release WHERE revision=?');
        $s->execute([$revision]);$stored=$s->fetch(PDO::FETCH_ASSOC);
        if(!$stored||!hash_equals($approvedSha,$stored['source_sha256']))throw new DomainException('Approved KPD release unavailable.');
        $s=$db->prepare('SELECT code,name_hr FROM f2_kpd_code WHERE revision=? ORDER BY code LIMIT 20001');
        $s->execute([$revision]);$rows=[];
        while($r=$s->fetch(PDO::FETCH_ASSOC))$rows[$r['code']]=$r['name_hr'];
        if(count($rows)<3000||count($rows)>20000||!hash_equals($stored['catalog_sha256'],hash('sha256',json_encode($rows,JSON_THROW_ON_ERROR))))
            throw new DomainException('KPD catalogue integrity mismatch.');
        return ['revision'=>$revision,'sourceSha256'=>$approvedSha,'catalogSha256'=>$stored['catalog_sha256'],'codes'=>$rows];
    }
}
