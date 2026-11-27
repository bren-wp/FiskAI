<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
final class PriceLists {
    private const GOODS=['naziv','sifra','marka','neto_kolicina','jedinica_mjere','maloprodajna_cijena','poseban_oblik_prodaje','cijena_za_jedinicu_mjere','barkod','ean','bazna_cijena'];
    private const SERVICES=['naziv','maloprodajna_cijena','poseban_oblik_prodaje','bazna_cijena'];
    private static function money(mixed $v): string {
        if (!is_string($v)||!preg_match('/\A(?:0|[1-9][0-9]{0,9})\.[0-9]{2}\z/D',$v)) throw new InvalidArgumentException('Cijena mora biti decimalni niz s dvije decimale.');
        return $v;
    }
    private static function field(mixed $v): string {
        if (!is_string($v)||strlen($v)>2000||!preg_match('//u',$v)||preg_match('/[\x00-\x1f\x7f]/',$v)) throw new InvalidArgumentException('Neispravno tekstualno polje.');
        // Reject spreadsheet formulas rather than silently altering official field values.
        if (preg_match('/\A\s*[=+@-]/u',$v)) throw new InvalidArgumentException('Nedopušten početak tekstualnog polja.');
        return $v;
    }
    public static function encode(string $kind,array $rows): array {
        $columns=kind($kind)==='ROBA'?self::GOODS:self::SERVICES;
        if(!array_is_list($rows)||count($rows)>10000) throw new InvalidArgumentException('Neispravan popis artikala.');
        $csv=fopen('php://temp','w+b');
        if($csv===false) throw new RuntimeException('CSV stream');
        $xml=new XMLWriter();$xml->openMemory();$xml->startDocument('1.0','UTF-8');$xml->startElement('cjenik');$xml->writeAttribute('vrsta',$kind);
        try {
            fputcsv($csv,$columns,';','"','',"\r\n");
            foreach($rows as $row){
                if(!is_array($row)||array_diff(array_keys($row),$columns)) throw new InvalidArgumentException('Nepoznata polja cjenika.');
                $line=[];
                $xml->startElement('stavka');
                foreach($columns as $key){
                    $value=$row[$key]??'';
                    if(in_array($key,['maloprodajna_cijena','bazna_cijena'],true)) $value=self::money($value);
                    elseif($key==='cijena_za_jedinicu_mjere'&&$value!=='') $value=self::money($value);
                    elseif($key==='poseban_oblik_prodaje'){
                        if(!is_bool($value)) throw new InvalidArgumentException('Oznaka posebne prodaje mora biti logička vrijednost.');
                        $value=$value?'DA':'NE';
                    }else $value=self::field($value);
                    if($key==='naziv'&&trim($value)==='') throw new InvalidArgumentException('Nedostaje naziv.');
                    $line[]=$value;$xml->writeElement($key,$value);
                }
                $xml->endElement();fputcsv($csv,$line,';','"','',"\r\n");
            }
            $xml->endElement();$xml->endDocument();rewind($csv);
            return [stream_get_contents($csv),$xml->outputMemory()];
        }finally{fclose($csv);}
    }
    public static function publish(PDO $db,array $data): array {
        $site=siteKey((string)($data['site']??''));
        $kind=kind((string)($data['kind']??''));
        $identifier=(string)($data['identifier']??'');
        if(!preg_match('/\A[0-9]{8,13}\z/D',$identifier)) throw new InvalidArgumentException('Unesite OIB ili matični broj.');
        $location=$site==='ZA_SVA_PRODAJNA_MJESTA'?$site:($data['location']??'');
        if(!is_string($location)||!preg_match('/\A[\p{L}\p{N}_ -]{1,100}\z/u',$location)) throw new InvalidArgumentException('Unesite vrstu, adresu i oznaku prodajnog mjesta.');
        [$csv,$xml]=self::encode($kind,$data['rows']??[]);
        $db->beginTransaction();
        try {
            $stmt=$db->prepare('INSERT INTO price_heads(site_key,kind) VALUES (?,?) ON DUPLICATE KEY UPDATE site_key=site_key');$stmt->execute([$site,$kind]);
            $stmt=$db->prepare('SELECT current_id,last_version FROM price_heads WHERE site_key=? AND kind=? FOR UPDATE');$stmt->execute([$site,$kind]);$head=$stmt->fetch();
            if($head['current_id']!==null){
                $stmt=$db->prepare('SELECT filename,csv_content,xml_content,version_no FROM price_lists WHERE id=?');$stmt->execute([$head['current_id']]);$old=$stmt->fetch();
                if($old&&$old['csv_content']===$csv&&$old['xml_content']===$xml){$db->commit();return ['id'=>(int)$head['current_id'],'version'=>(int)$old['version_no'],'unchanged'=>true];}
            }
            $version=(int)$head['last_version']+1;
            $now=new DateTimeImmutable('now',new DateTimeZone('Europe/Zagreb'));
            $stamp=$now->format('Ymd_Hi');$utc=$now->setTimezone(new DateTimeZone('UTC'))->format('Y-m-d H:i:s.u');
            $filename=$identifier.'_'.str_replace(' ','_',$location).'_'.$kind.'_'.$version.'_'.$stamp;
            $stmt=$db->prepare('INSERT INTO price_lists(site_key,kind,version_no,filename,published_at,csv_content,xml_content) VALUES (?,?,?,?,?,?,?)');
            $stmt->execute([$site,$kind,$version,$filename,$utc,$csv,$xml]);$id=(int)$db->lastInsertId();
            if($head['current_id']!==null){$stmt=$db->prepare('UPDATE price_lists SET replaced_at=? WHERE id=? AND replaced_at IS NULL');$stmt->execute([$utc,$head['current_id']]);}
            $stmt=$db->prepare('UPDATE price_heads SET current_id=?,last_version=? WHERE site_key=? AND kind=?');$stmt->execute([$id,$version,$site,$kind]);
            $db->commit();return ['id'=>$id,'version'=>$version,'unchanged'=>false];
        }catch(Throwable $e){if($db->inTransaction())$db->rollBack();throw $e;}
    }
    public static function download(PDO $db,string $site,string $kind,?int $version): ?array {
        $args=[siteKey($site),kind($kind)];
        if($version===null)$sql='SELECT p.* FROM price_heads h JOIN price_lists p ON h.current_id=p.id WHERE h.site_key=? AND h.kind=?';
        else{$sql='SELECT * FROM price_lists WHERE site_key=? AND kind=? AND version_no=? AND (replaced_at IS NULL OR replaced_at>=UTC_TIMESTAMP()-INTERVAL 15 DAY)';$args[]=$version;}
        $stmt=$db->prepare($sql);$stmt->execute($args);return $stmt->fetch()?:null;
    }
}
