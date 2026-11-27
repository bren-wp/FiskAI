<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/bootstrap.php';
require_once __DIR__.'/../src/CisEducRecovery.php';

function recoveryKey(string $env):string {
    $path=getenv($env);
    if(!is_string($path)||is_link($path))throw new RuntimeException('Key file required.');
    $stream=fopen($path,'rb');
    if($stream===false)throw new RuntimeException('Key unavailable.');
    try{
        $stat=fstat($stream);
        if(!$stat||($stat['mode']&0170000)!==0100000||($stat['mode']&0077)!==0||$stat['size']!==32||
            (function_exists('posix_geteuid')&&$stat['uid']!==posix_geteuid()))
            throw new RuntimeException('Key file must be owner-only, regular and exactly 32 raw bytes.');
        $key=stream_get_contents($stream,33);
        if($key===false||strlen($key)!==32)throw new RuntimeException('Invalid key file.');
        return $key;
    }finally{fclose($stream);}
}
try{
    if($argc!==5||!in_array($argv[1],['export','restore'],true))throw new InvalidArgumentException('Usage: export|restore SUBJECT_UUID KEY_ID ARCHIVE_PATH');
    $archiveKey=recoveryKey('FISKAI_CIS_ARCHIVE_KEY_FILE');
    $storageKey=recoveryKey('FISKAI_CIS_STORAGE_KEY_FILE');
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
