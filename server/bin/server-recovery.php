<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/RecoveryKeyFile.php';
require_once __DIR__.'/../src/ServerRecovery.php';
try{
    if($argc!==3||!in_array($argv[1],['export','restore'],true))throw new InvalidArgumentException('Usage: export|restore ARCHIVE_PATH');
    $key=RecoveryKeyFile::read('FISKAI_SERVER_ARCHIVE_KEY_FILE');
    $service=new ServerRecovery(db());
    if($argv[1]==='export'){
        $archive=$service->export($key);
        $old=umask(0077);
        try{$out=fopen($argv[2],'xb');}finally{umask($old);}
        if($out===false)throw new RuntimeException('Archive destination unavailable.');
        try{
            if(fwrite($out,$archive)!==strlen($archive)||!fflush($out)||!fsync($out))throw new RuntimeException('Incomplete write.');
        }catch(Throwable $e){fclose($out);unlink($argv[2]);throw $e;}
        fclose($out);echo "Server archive exported.\n";
    }else{
        $archive=file_get_contents($argv[2],false,null,0,16777300);
        if($archive===false)throw new RuntimeException('Archive unavailable.');
        echo json_encode($service->restore($archive,$key),JSON_THROW_ON_ERROR).PHP_EOL;
    }
}catch(Throwable $e){fwrite(STDERR,"Server recovery failed: ".get_class($e).".\n");exit(1);}
