<?php
declare(strict_types=1);

final class RecoveryKeyFile {
    public static function read(string $env):string {
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
}
