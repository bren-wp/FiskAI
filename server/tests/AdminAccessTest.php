<?php
declare(strict_types=1);
require_once __DIR__.'/../src/AdminAccess.php';
function check(bool $ok):void{if(!$ok)throw new RuntimeException('Admin test failed.');}
$password='synthetic-test-secret';$hash=password_hash($password,PASSWORD_BCRYPT);
$server=['HTTPS'=>'on','PHP_AUTH_USER'=>'operator','PHP_AUTH_PW'=>$password];
check(AdminAccess::authorize($server,'1','operator',$hash)===200);
check(AdminAccess::authorize($server,'0','operator',$hash)===404);
check(AdminAccess::authorize($server,'1','operator','plaintext')===404);
check(AdminAccess::authorize(array_replace($server,['HTTPS'=>'off','HTTP_X_FORWARDED_PROTO'=>'https']),'1','operator',$hash)===403);
check(AdminAccess::authorize(array_replace($server,['PHP_AUTH_PW'=>'wrong']),'1','operator',$hash)===401);
check(AdminAccess::authorize(array_replace($server,['PHP_AUTH_USER'=>'wrong']),'1','operator',$hash)===401);
check(!AdminAccess::csrf(null,null)&&!AdminAccess::csrf('x','x'));
$token=bin2hex(random_bytes(32));check(AdminAccess::csrf($token,$token)&&!AdminAccess::csrf($token,str_repeat('0',64)));
$data=['site'=>'TEST','kind'=>'USLUGE','identifier'=>'12345678903','rows'=>[['naziv'=>'Test','maloprodajna_cijena'=>'12.50','bazna_cijena'=>'10.00','poseban_oblik_prodaje'=>false]]];
check(AdminAccess::publication(json_encode($data,JSON_THROW_ON_ERROR))===$data);
foreach(['{}','[]','{"site":"a","kind":"USLUGE","identifier":"1","rows":[]}',str_repeat('x',1000001)] as $bad){
    try{AdminAccess::publication($bad);throw new RuntimeException('Invalid publication accepted.');}
    catch(InvalidArgumentException|JsonException){}
}
echo "Admin access, HTTPS, CSRF and publication validation passed.\n";
