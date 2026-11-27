<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/DemoSync.php';
try {
    if($argc===4&&$argv[1]==='bind'){
        echo (new DemoDeviceProof(db()))->bind($argv[2],$argv[3]).PHP_EOL;exit;
    }
    if($argc!==3||!in_array($argv[1],['provision','revoke','rotate'],true))throw new InvalidArgumentException('Usage: provision SUBJECT_UUID, rotate DEVICE_UUID or revoke DEVICE_UUID; bind DEVICE_UUID PUBLIC_KEY_BASE64');
    $service=new DemoSync(db());
    if($argv[1]==='provision')echo json_encode($service->provision($argv[2]),JSON_THROW_ON_ERROR).PHP_EOL;
    elseif($argv[1]==='rotate')echo json_encode($service->rotate($argv[2]),JSON_THROW_ON_ERROR).PHP_EOL;
    else echo ($service->revoke($argv[2])?'Revoked':'Not found or already revoked').PHP_EOL;
}catch(Throwable $e){fwrite(STDERR,"Device administration failed. Check identity and database configuration.\n");exit(1);}
