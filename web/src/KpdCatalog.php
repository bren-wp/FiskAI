<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
final class KpdCatalog {
    public const SOURCE='https://web.dzs.hr/App/klasus/Download/GenericFillerFrm.aspx?action=downloadJson&content=ClassificationItems&id=17294173&parent=ClassificationVersion';
    private static function date(string $value):?string{
        $value=trim($value);if($value==='')return null;
        $d=DateTimeImmutable::createFromFormat('!d.m.Y',$value);
        if(!$d||$d->format('d.m.Y')!==$value)throw new InvalidArgumentException('Neispravan datum KPD.');
        return $d->format('Y-m-d');
    }
    public static function decode(string $json):array{
        if(strlen($json)>20000000)throw new InvalidArgumentException('Prevelik katalog.');
        $data=json_decode($json,true,512,JSON_THROW_ON_ERROR);
        if(!isset($data['DTable'])||!is_array($data['DTable']))throw new InvalidArgumentException('Neispravan katalog.');
        $rows=[];
        foreach($data['DTable'] as $r){
            if((string)($r['LevelNumber']??'')!=='6')continue;
            $code=$r['ItemCode']??'';$name=trim($r['OfficialTitle_HR']??'');
            if(!preg_match('/\A[0-9]{2}\.[0-9]{2}\.[0-9]{2}\z/D',$code)||$name===''||strlen($name)>4000)throw new InvalidArgumentException('Neispravna potkategorija.');
            $from=self::date($r['StartingDate']??'');$to=self::date($r['EndingDate']??'');
            if($from===null||($to!==null&&$to<$from))throw new InvalidArgumentException('Neispravno razdoblje.');
            $key=$code.'|'.$from;
            if(isset($rows[$key]))throw new InvalidArgumentException('Duplicirana šifra i datum.');
            $rows[$key]=[$code,$from,$to,$name];
        }
        // Reject partial/truncated exports rather than replacing a complete catalogue.
        if(count($rows)<3000||count($rows)>20000)throw new InvalidArgumentException('Katalog nije cjelovit.');
        return array_values($rows);
    }
    public static function import(PDO $db,string $json):int{
        $rows=self::decode($json);$db->beginTransaction();
        try{
            $db->exec('DELETE FROM kpd_catalog');
            $stmt=$db->prepare('INSERT INTO kpd_catalog(code,valid_from,valid_to,name_hr) VALUES (?,?,?,?)');
            foreach($rows as $row)$stmt->execute($row);
            $stmt=$db->prepare('INSERT INTO kpd_source(id,source_url,sha256,imported_at,record_count) VALUES (1,?,?,UTC_TIMESTAMP(),?) ON DUPLICATE KEY UPDATE source_url=VALUES(source_url),sha256=VALUES(sha256),imported_at=VALUES(imported_at),record_count=VALUES(record_count)');
            $stmt->execute([self::SOURCE,hash('sha256',$json),count($rows)]);$db->commit();return count($rows);
        }catch(Throwable $e){if($db->inTransaction())$db->rollBack();throw $e;}
    }
    public static function search(PDO $db,string $q,string $date):array{
        if(strlen($q)>160||!preg_match('/\A[0-9]{4}-[0-9]{2}-[0-9]{2}\z/D',$date))throw new InvalidArgumentException('Neispravan upit.');
        $like='%'.str_replace(['!','%','_'],['!!','!%','!_'],$q).'%';
        $stmt=$db->prepare("SELECT code,name_hr,valid_from,valid_to FROM kpd_catalog WHERE valid_from<=? AND (valid_to IS NULL OR valid_to>=?) AND (code LIKE ? ESCAPE '!' OR name_hr LIKE ? ESCAPE '!') ORDER BY code LIMIT 100");
        $stmt->execute([$date,$date,$like,$like]);return $stmt->fetchAll();
    }
}
