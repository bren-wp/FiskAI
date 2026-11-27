<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/RecoveryKeyFile.php';
require_once __DIR__.'/../src/EducBusinessLedger.php';
require_once __DIR__.'/../src/EducExternalCheckpoint.php';
try{
    $service=new EducBusinessLedger(db());
    if($argc===5&&$argv[1]==='provision'){
        $service->provision($argv[2],$argv[3],$argv[4]);echo "EDUC subject registered.\n";
    }elseif($argc===3&&$argv[1]==='history'){
        echo json_encode($service->checkpointHistory($argv[2],RecoveryKeyFile::read('FISKAI_CIS_STORAGE_KEY_FILE')),JSON_THROW_ON_ERROR);
    }elseif($argc===3&&$argv[1]==='checkpoint'){
        echo EducExternalCheckpoint::bytes($service->checkpoint($argv[2],RecoveryKeyFile::read('FISKAI_CIS_STORAGE_KEY_FILE')));
    }elseif($argc===4&&in_array($argv[1],['append','issue'],true)){
        $json=file_get_contents($argv[3],false,null,0,16385);
        if($json===false||strlen($json)>16384)throw new InvalidArgumentException('Invalid document file.');
        $input=json_decode($json,true,16,JSON_THROW_ON_ERROR);
        if(!is_array($input))throw new InvalidArgumentException('Invalid document.');
        $key=RecoveryKeyFile::read('FISKAI_CIS_STORAGE_KEY_FILE');
        $entry=$argv[1]==='issue'?$service->issue($argv[2],$input,$key):$service->append($argv[2],$input,$key);
        echo json_encode(['documentId'=>$entry['document']['documentId'],'number'=>$entry['document']['number'],'digest'=>$entry['digest'],'status'=>'EDUC_RECORDED'],JSON_THROW_ON_ERROR).PHP_EOL;
    }else throw new InvalidArgumentException('Usage: provision SUBJECT_UUID OIB KEY_ID | append|issue SUBJECT_UUID DOCUMENT_JSON | checkpoint|history SUBJECT_UUID');
}catch(Throwable $e){fwrite(STDERR,"EDUC ledger operation failed: ".get_class($e).".\n");exit(1);}
