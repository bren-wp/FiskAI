<?php
declare(strict_types=1);
function db(): PDO {
    static $db;
    if ($db instanceof PDO) return $db;
    $dsn=getenv('FISKAI_DB_DSN');
    $user=getenv('FISKAI_DB_USER');
    $pass=getenv('FISKAI_DB_PASSWORD');
    if (!$dsn || !str_starts_with($dsn,'mysql:') || !$user || $pass===false) throw new RuntimeException('Database configuration missing');
    $db=new PDO($dsn,$user,$pass,[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_EMULATE_PREPARES=>false,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC]);
    $db->exec("SET time_zone = '+00:00'");
    return $db;
}
function jsonResponse(array $data,int $status=200): never {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data,JSON_THROW_ON_ERROR|JSON_UNESCAPED_UNICODE);
    exit;
}
function h(string $v): string {return htmlspecialchars($v,ENT_QUOTES|ENT_SUBSTITUTE,'UTF-8');}
function siteKey(string $v): string {
    if (!preg_match('/\A[A-Za-z0-9_-]{1,64}\z/D',$v)) throw new InvalidArgumentException('Neispravna oznaka cjenika.');
    return $v;
}
function kind(string $v): string {
    if (!in_array($v,['ROBA','USLUGE'],true)) throw new InvalidArgumentException('Neispravna vrsta cjenika.');
    return $v;
}
function requirePublisher(): void {
    $expected=getenv('FISKAI_PUBLISH_TOKEN');
    $supplied=$_SERVER['HTTP_AUTHORIZATION']??'';
    if (!is_string($expected)||strlen($expected)<32||!hash_equals('Bearer '.$expected,$supplied)) jsonResponse(['error'=>'Pristup nije odobren.'],401);
}
