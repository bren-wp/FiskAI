<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/DemoSync.php';
try {
    if($argc!==3||!in_array($argv[1],['provision','revoke'],true))throw new InvalidArgumentException('Usage: provision SUBJECT_UUID or revoke DEVICE_UUID');
    $service=new DemoSync(db());
    if($argv[1]==='provision')echo json_encode($service->provision($argv[2]),JSON_THROW_ON_ERROR).PHP_EOL;
    else echo ($service->revoke($argv[2])?'Revoked':'Not found or already revoked').PHP_EOL;
}catch(Throwable $e){fwrite(STDERR,"Device administration failed. Check identity and database configuration.\n");exit(1);}
