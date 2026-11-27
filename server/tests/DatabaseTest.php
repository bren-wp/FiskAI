<?php
declare(strict_types=1);
require_once __DIR__.'/../src/PriceLists.php';
require_once __DIR__.'/../src/KpdCatalog.php';
require_once __DIR__.'/../src/SchemaMigrator.php';
function check(bool $ok):void{if(!$ok)throw new RuntimeException('Database test failed');}
$db=db();
$sql=file_get_contents(__DIR__.'/../database/001.sql');
foreach(explode(';',$sql) as $statement){if(trim($statement)!=='')$db->exec($statement);}
// Exercise adoption of an existing installation without recreating existing tables.
$existingSite='existing_'.bin2hex(random_bytes(6));
$stmt=$db->prepare("INSERT INTO price_heads(site_key,kind) VALUES (?,'USLUGE')");$stmt->execute([$existingSite]);
check(SchemaMigrator::apply($db,__DIR__.'/../database')===['001.sql','002_fiscal_event_outbox.sql','003_cis_educ_response_evidence.sql','004_demo_sync.sql','005_demo_device_proof.sql']);
$stmt=$db->prepare("SELECT COUNT(*) FROM price_heads WHERE site_key=?");$stmt->execute([$existingSite]);check((int)$stmt->fetchColumn()===1);
check(SchemaMigrator::apply($db,__DIR__.'/../database')===[]);
$site='test_'.bin2hex(random_bytes(6));
$data=['site'=>$site,'kind'=>'USLUGE','identifier'=>'12345678903','location'=>'ured Test 1','rows'=>[['naziv'=>'Usluga','maloprodajna_cijena'=>'12.50','poseban_oblik_prodaje'=>false,'bazna_cijena'=>'10.00']]];
$a=PriceLists::publish($db,$data);
$b=PriceLists::publish($db,$data);
check($a['version']===1&&$b['unchanged']&&$b['id']===$a['id']);
$data['rows'][0]['maloprodajna_cijena']='13.50';
$c=PriceLists::publish($db,$data);
check($c['version']===2&&!$c['unchanged']);
$current=PriceLists::download($db,$site,'USLUGE',null);
$old=PriceLists::download($db,$site,'USLUGE',1);
check(str_contains($current['csv_content'],'13.50'));
check(str_contains($old['csv_content'],'12.50')&&$old['replaced_at']!==null);
$stmt=$db->prepare('UPDATE price_lists SET replaced_at=UTC_TIMESTAMP()-INTERVAL 16 DAY WHERE id=?');$stmt->execute([$a['id']]);
check(PriceLists::download($db,$site,'USLUGE',1)===null);
$bad=$data;unset($bad['rows']);
$rejected=false;try{PriceLists::publish($db,$bad);}catch(InvalidArgumentException){$rejected=true;}check($rejected);
check(PriceLists::download($db,$site,'USLUGE',null)['version_no']==2);
$rejected=false;try{KpdCatalog::import($db,'{"DTable":[]}');}catch(InvalidArgumentException){$rejected=true;}check($rejected);
echo "MySQL integration tests passed\n";
