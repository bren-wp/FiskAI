<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
final class PriceDownload {
    public static function parse(array $query): array {
        foreach (['site','kind','format','mode','version'] as $key) {
            if (array_key_exists($key,$query) && !is_string($query[$key])) {
                throw new InvalidArgumentException('Neispravan parametar.');
            }
        }
        $format=$query['format']??'csv';
        $mode=$query['mode']??'inline';
        if (!in_array($format,['csv','xml'],true) || !in_array($mode,['inline','attachment'],true)) {
            throw new InvalidArgumentException('Neispravan format ili način preuzimanja.');
        }
        $version=null;
        if (isset($query['version'])) {
            $version=filter_var($query['version'],FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
            if ($version===false) throw new InvalidArgumentException('Neispravna verzija.');
        }
        return ['site'=>siteKey($query['site']??''),'kind'=>kind($query['kind']??''),'format'=>$format,'mode'=>$mode,'version'=>$version];
    }
    public static function disposition(string $filename,array $options): string {
        return $options['mode']."; filename*=UTF-8''".rawurlencode($filename.'.'.$options['format']);
    }
}
