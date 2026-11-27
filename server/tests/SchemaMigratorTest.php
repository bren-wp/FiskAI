<?php
declare(strict_types=1);
require_once __DIR__.'/../src/SchemaMigrator.php';
function check(bool $ok):void { if (!$ok) throw new RuntimeException('Migration test failed'); }
$db=db();
$dir=__DIR__.'/../database';
check(SchemaMigrator::apply($db,$dir)===[]);
$before=$db->query('SELECT COUNT(*) FROM price_lists')->fetchColumn();
$temp=sys_get_temp_dir().'/fiskai-migrations-'.bin2hex(random_bytes(8));
if (!mkdir($temp,0700)) throw new RuntimeException('Temporary directory unavailable.');
try {
    foreach(glob($dir.'/*.sql') as $file) check(copy($file,$temp.'/'.basename($file)));
    check(file_put_contents($temp.'/001.sql',PHP_EOL,FILE_APPEND)!==false);
    $rejected=false;
    try { SchemaMigrator::apply($db,$temp); } catch(RuntimeException $e) { $rejected=str_contains($e->getMessage(),'history mismatch'); }
    check($rejected);
    check((string)$db->query('SELECT COUNT(*) FROM price_lists')->fetchColumn()===(string)$before);
    check(SchemaMigrator::apply($db,$dir)===[]);
} finally {
    foreach(glob($temp.'/*') as $file) unlink($file);
    rmdir($temp);
}
echo "Migration integrity and data preservation tests passed\n";
