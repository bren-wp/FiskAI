<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/DemoDocumentExchange.php';
try {
    if($argc!==5)throw new InvalidArgumentException('Usage: SUBJECT_UUID OWNER_OIB DEVICE_UUID ENVELOPE_FILE');
    $envelope=file_get_contents($argv[4],false,null,0,1500001);
    if($envelope===false||strlen($envelope)>1500000)throw new InvalidArgumentException('Invalid envelope file.');
    echo json_encode(DemoDocumentExchange::verifyEnrolled(db(),$envelope,$argv[1],$argv[2],$argv[3]),JSON_THROW_ON_ERROR).PHP_EOL;
}catch(Throwable $e){fwrite(STDERR,"DEMO document review failed: ".get_class($e).".\n");exit(1);}
