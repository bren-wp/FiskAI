<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/DemoDocumentReplica.php';
try{
    if($argc!==3)throw new InvalidArgumentException('Usage: SUBJECT_UUID OWNER_OIB');
    DemoDocumentReplica::provision(db(),$argv[1],$argv[2]);
    echo "DEMO document scope registered.\n";
}catch(Throwable $e){fwrite(STDERR,"DEMO document scope failed: ".get_class($e).".\n");exit(1);}
