<?php
declare(strict_types=1);
require_once __DIR__.'/../src/PriceDownload.php';
function check(bool $ok): void { if (!$ok) throw new RuntimeException('Download test failed'); }
$base=['site'=>'RIJEKA_1','kind'=>'USLUGE'];
$a=PriceDownload::parse($base);
check($a['version']===null && $a['format']==='csv' && $a['mode']==='inline');
$b=PriceDownload::parse($base+['version'=>'7','format'=>'xml','mode'=>'attachment']);
check($b['version']===7);
check(PriceDownload::disposition('Čišćenje',$b)==="attachment; filename*=UTF-8''%C4%8Ci%C5%A1%C4%87enje.xml");
check(PriceDownload::disposition("a\r\nb",$a)==="inline; filename*=UTF-8''a%0D%0Ab.csv");
foreach ([['site'=>['x']],['kind'=>'x'],['format'=>'html'],['mode'=>'bad'],['version'=>'0'],['version'=>'-1'],['version'=>['1']],['site'=>'../x']] as $bad) {
    $rejected=false;
    try { PriceDownload::parse(array_replace($base,$bad)); } catch (InvalidArgumentException) { $rejected=true; }
    check($rejected);
}
echo "Price download tests passed\n";
