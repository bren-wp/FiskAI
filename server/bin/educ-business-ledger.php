<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/RecoveryKeyFile.php';
require_once __DIR__.'/../src/EducBusinessLedger.php';
try{
    $service=new EducBusinessLedger(db());
    if($argc===5&&$argv[1]==='provision'){
        $service->provision($argv[2],$argv[3],$argv[4]);echo "EDUC subject registered.\n";
    }elseif($argc===4&&$argv[1]==='append'){
        $json=file_get_contents($argv[3],false,null,0,16385);
        if($json===false||strlen($json)>16384)throw new InvalidArgumentException('Invalid document file.');
        $input=json_decode($json,true,16,JSON_THROW_ON_ERROR);
        if(!is_array($input))throw new InvalidArgumentException('Invalid document.');
        $entry=$service->append($argv[2],$input,RecoveryKeyFile::read('FISKAI_CIS_STORAGE_KEY_FILE'));
        echo json_encode(['documentId'=>$entry['document']['documentId'],'digest'=>$entry['digest'],'status'=>'EDUC_RECORDED'],JSON_THROW_ON_ERROR).PHP_EOL;
    }else throw new InvalidArgumentException('Usage: provision SUBJECT_UUID OIB KEY_ID | append SUBJECT_UUID DOCUMENT_JSON');
}catch(Throwable $e){fwrite(STDERR,"EDUC ledger operation failed: ".get_class($e).".\n");exit(1);}
