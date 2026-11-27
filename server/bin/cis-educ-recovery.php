<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/CisEducRecovery.php';

require_once __DIR__.'/../src/RecoveryKeyFile.php';

try{
    if($argc!==5||!in_array($argv[1],['export','restore'],true))throw new InvalidArgumentException('Usage: export|restore SUBJECT_UUID KEY_ID ARCHIVE_PATH');
    $archiveKey=RecoveryKeyFile::read('FISKAI_CIS_ARCHIVE_KEY_FILE');
    $storageKey=RecoveryKeyFile::read('FISKAI_CIS_STORAGE_KEY_FILE');
    $service=new CisEducRecovery(db());
    if($argv[1]==='export'){
        $archive=$service->export($argv[2],$argv[3],$archiveKey,$storageKey);
        $old=umask(0077);
        try{$out=fopen($argv[4],'xb');}finally{umask($old);}
        if($out===false)throw new RuntimeException('Archive destination unavailable.');
        try{
            if(fwrite($out,$archive)!==strlen($archive)||!fflush($out)||!fsync($out))
                throw new RuntimeException('Archive write incomplete.');
        }catch(Throwable $e){fclose($out);unlink($argv[4]);throw $e;}
        fclose($out);echo "EDUC archive exported.\n";
    }else{
        $archive=file_get_contents($argv[4],false,null,0,16777300);
        if($archive===false)throw new RuntimeException('Archive unavailable.');
        $count=$service->restore($argv[2],$argv[3],$archive,$archiveKey,$storageKey);
        echo "EDUC records restored into quarantine: ".$count.PHP_EOL;
    }
}catch(Throwable $e){fwrite(STDERR,"EDUC recovery failed: ".get_class($e).". No automatic resend.\n");exit(1);}
