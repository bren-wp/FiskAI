<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../src/F2EducIntake.php';
require_once __DIR__.'/../src/RecoveryKeyFile.php';

function f2File(string $path,int $limit):string {
    if(!is_file($path)||!is_readable($path))throw new RuntimeException('Input unavailable.');
    $bytes=file_get_contents($path,false,null,0,$limit+1);
    if($bytes===false||$bytes===''||strlen($bytes)>$limit)throw new InvalidArgumentException('Invalid input size.');
    return $bytes;
}
try{
    if($argc!==9)throw new InvalidArgumentException('Usage: DOCUMENT_UUID DIRECTION EVENT_DATE UBL_XML SVRL VALIDATION_JSON VALIDATION_DER EDUC');
    // Trust configuration is selected only by an authorized operator via the environment.
    $configPath=getenv('FISKAI_F2_EDUC_CONFIG');
    if(!is_string($configPath)||$argv[8]!=='EDUC')throw new LogicException('Explicit EDUC operator configuration required.');
    $c=json_decode(f2File($configPath,262144),true,32,JSON_THROW_ON_ERROR);
    if(!is_array($c)||!is_string($c['subject']??null)||!is_array($c['history']??null)||!is_array($c['schemaPins']??null)||
        !is_array($c['kpd']??null))throw new InvalidArgumentException('Invalid operator configuration.');
    $xml=f2File($argv[4],1048576);
    // Select the approved KPD edition from the invoice issue date (not receipt date).
    $db=db();
    $s=$db->prepare('SELECT owner_oib FROM educ_business_subject WHERE subject_id=?');$s->execute([$c['subject']]);
    $owner=$s->fetchColumn();if(!is_string($owner))throw new DomainException('Unknown subject.');
    $history=new TaxStatusHistory($c['history']);
    $pre=F2InvoicePreflight::review($xml,$owner,$argv[2],$argv[3],$history,$c['scope'],$c['schemaDir'],$c['schemaPins'],$c['entry']);
    $kpd=$c['kpd'][F2KpdRelease::revision($pre['issueDate'])]??null;
    if(!is_array($kpd))throw new DomainException('No approved KPD edition configured.');
    $service=new F2EducIntake($db,RecoveryKeyFile::read('FISKAI_F2_STORAGE_KEY_FILE'));
    $result=$service->prepare($c['subject'],$argv[1],$argv[2],$argv[3],$xml,$history,$c['scope'],$c['schemaDir'],$c['schemaPins'],$c['entry'],$c['schemaDigest'],
        $kpd['sourceSha256'],$kpd['catalogSha256'],f2File($argv[5],1048576),f2File($argv[6],2048),f2File($argv[7],80),
        f2File($c['validatorPublicKey'],8192),$c['validatorKeyPin'],$c['validatorManifestPin'],
        f2File($c['grantStatement'],4096),f2File($c['grantSignature'],80),f2File($c['grantPublicKey'],8192),$c['grantKeyPin']);
    echo json_encode($result,JSON_THROW_ON_ERROR).PHP_EOL;
}catch(Throwable $e){fwrite(STDERR,"F2 EDUC intake failed: ".get_class($e).".\n");exit(1);}
