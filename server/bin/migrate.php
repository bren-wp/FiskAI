<?php
declare(strict_types=1);
if (PHP_SAPI!=='cli') { http_response_code(404); exit; }
require_once __DIR__.'/../src/SchemaMigrator.php';
try {
    $applied=SchemaMigrator::apply(db(),__DIR__.'/../database');
    echo $applied ? 'Primijenjene migracije: '.implode(', ',$applied).PHP_EOL : 'Sve migracije već su primijenjene.'.PHP_EOL;
} catch (Throwable $e) {
    fwrite(STDERR,"Migracija nije uspjela. Provjerite konfiguraciju, ovlasti i povijest migracija prije ponovnog pokušaja.".PHP_EOL);
    exit(1);
}
