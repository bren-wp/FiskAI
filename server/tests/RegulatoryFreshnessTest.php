<?php
declare(strict_types=1);
require_once __DIR__.'/../src/RegulatoryFreshness.php';
if($argc!==5)throw new InvalidArgumentException('Expected approved JSON, challenge, reply JSON, public key.');
$approved=json_decode(file_get_contents($argv[1]),true,8,JSON_THROW_ON_ERROR);
$nonce=$argv[2];$reply=json_decode(file_get_contents($argv[3]),true,8,JSON_THROW_ON_ERROR);
$public=file_get_contents($argv[4]);$pin=hash('sha256',$public);
RegulatoryFreshness::verify($approved,$nonce,$reply,$public,$pin);
function reject(callable $fn):void {
    try{$fn();}catch(InvalidArgumentException|DomainException){return;}
    throw new RuntimeException('Invalid regulatory freshness accepted.');
}
$other=$nonce===str_repeat('a',64)?str_repeat('b',64):str_repeat('a',64);
reject(fn()=>RegulatoryFreshness::verify($approved,$other,$reply,$public,$pin));
$replay=$reply;$replay['nonce']=$other;
reject(fn()=>RegulatoryFreshness::verify($approved,$other,$replay,$public,$pin));
$old=$approved;$old['sequence']--;
reject(fn()=>RegulatoryFreshness::verify($old,$nonce,$reply,$public,$pin));
echo "Cross-runtime nonce, signature and latest-head verification passed.\n";
