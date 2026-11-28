<?php
declare(strict_types=1);
require_once __DIR__.'/../src/RegulatoryTrustAnchor.php';
require_once __DIR__.'/../src/RegulatoryRemoteCheckpoint.php';

// Only independently provisioned operator material. No key generation or trust-on-first-use.
if(PHP_SAPI!=='cli'||!in_array($argc,[2,3],true)||($argc===3&&$argv[2]!=='--require-quorum'))throw new InvalidArgumentException('Usage: verify-regulatory-history.php HISTORY_DIRECTORY [--require-quorum]');
function bounded(string $file,int $limit):string {
    if(!is_file($file)||!is_readable($file))throw new RuntimeException('Trust material unavailable.');
    $data=file_get_contents($file,false,null,0,$limit+1);
    if(!is_string($data)||$data===''||strlen($data)>$limit)throw new RuntimeException('Invalid trust material size.');
    return $data;
}
$directory=rtrim($argv[1],DIRECTORY_SEPARATOR);
$publicFile=getenv('FISKAI_REGULATORY_PUBLIC_KEY_FILE');
$pin=getenv('FISKAI_REGULATORY_PUBLIC_KEY_SHA256');
$sequence=getenv('FISKAI_REGULATORY_LATEST_SEQUENCE');
$head=getenv('FISKAI_REGULATORY_LATEST_HEAD');
if(!is_string($publicFile)||!is_string($pin)||!is_string($sequence)||!preg_match('/\A[1-9][0-9]{0,3}\z/D',$sequence)||
    (int)$sequence>1000||!is_string($head))throw new LogicException('Independent latest regulatory trust configuration required.');
$catalogs=[];$total=0;
for($i=1;$i<=(int)$sequence;$i++){
    $bytes=bounded($directory.'/catalog-'.$i.'.json',1048576);
    if(($total+=strlen($bytes))>16777216)throw new RuntimeException('History capacity exceeded.');
    $catalogs[]=$bytes;
}
$result=RegulatoryTrustAnchor::verify(bounded($directory.'/history.txt',1048576),bounded($directory.'/history.sig',128),
    bounded($publicFile,8192),$pin,(int)$sequence,$head,$catalogs);
if($argc===3){
    RegulatoryRemoteCheckpoint::requireIndependentQuorum($result);
    $result['independentWitnessQuorumVerified']=true;
}
echo json_encode($result,JSON_THROW_ON_ERROR).PHP_EOL;
