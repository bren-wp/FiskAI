<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/F2KpdRelease.php';
try{
    if($argc!==3||!in_array($argv[1],['2026-01-01','2027-01-01'],true))
        throw new InvalidArgumentException('Usage: EFFECTIVE_DATE REVIEWED_DZS_JSON');
    $year=substr($argv[1],0,4);
    $pin=getenv('FISKAI_KPD_'.$year.'_SOURCE_SHA256');
    if(!is_string($pin))throw new LogicException('Reviewed source pin required.');
    $json=file_get_contents($argv[2],false,null,0,20000001);
    if($json===false||strlen($json)>20000000)throw new InvalidArgumentException('Invalid catalogue file.');
    $db=db();
    $revision=F2KpdRelease::import($db,$argv[1],$json,$pin);
    $s=$db->prepare('SELECT source_sha256,catalog_sha256 FROM f2_kpd_release WHERE revision=?');$s->execute([$revision]);
    echo json_encode(['revision'=>$revision]+$s->fetch(PDO::FETCH_ASSOC),JSON_THROW_ON_ERROR).PHP_EOL;
}catch(Throwable $e){fwrite(STDERR,"F2 KPD import failed: ".get_class($e).".\n");exit(1);}
