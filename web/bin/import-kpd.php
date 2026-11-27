<?php
declare(strict_types=1);
require_once __DIR__.'/../src/KpdCatalog.php';
if(PHP_SAPI!=='cli')exit(1);
try{
    $ch=curl_init(KpdCatalog::SOURCE);$json='';
    curl_setopt_array($ch,[CURLOPT_FOLLOWLOCATION=>false,CURLOPT_CONNECTTIMEOUT=>15,CURLOPT_TIMEOUT=>90,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($ch,string $chunk)use(&$json):int{if(strlen($json)+strlen($chunk)>20000000)return 0;$json.=$chunk;return strlen($chunk);}]);
    $ok=curl_exec($ch);$status=curl_getinfo($ch,CURLINFO_RESPONSE_CODE);curl_close($ch);
    if($ok===false||$status!==200)throw new RuntimeException('DZS download failed');
    echo 'Uvezeno potkategorija: '.KpdCatalog::import(db(),$json).PHP_EOL;
}catch(Throwable $e){fwrite(STDERR,"Uvoz nije uspio. Postojeći katalog nije zamijenjen.\n");exit(1);}
