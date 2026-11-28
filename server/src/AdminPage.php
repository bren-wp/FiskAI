<?php
declare(strict_types=1);
require_once __DIR__.'/AdminAccess.php';
require_once __DIR__.'/PriceLists.php';

header("Content-Security-Policy: default-src 'self'; style-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
header('Cache-Control: no-store');
header('X-Robots-Tag: noindex, nofollow');
header('Content-Type: text/html; charset=utf-8');
$status=AdminAccess::authorize($_SERVER,getenv('FISKAI_ADMIN_ENABLED'),getenv('FISKAI_ADMIN_USERNAME'),getenv('FISKAI_ADMIN_PASSWORD_HASH'));
if($status!==200){
    http_response_code($status);
    if($status===401)header('WWW-Authenticate: Basic realm="FiskAI Administration", charset="UTF-8"');
    echo 'Administracija nije dostupna ili pristup nije odobren.';exit;
}
if(!in_array($_SERVER['REQUEST_METHOD']??'',['GET','POST'],true)){http_response_code(405);header('Allow: GET, POST');exit;}
if(getenv('FISKAI_SERVER_RECOVERY_MODE')==='1'){http_response_code(503);echo 'Administracija privremeno nije dostupna.';exit;}
ini_set('session.use_strict_mode','1');
ini_set('session.use_only_cookies','1');
session_name('FISKAIADMIN');
session_set_cookie_params(['lifetime'=>0,'path'=>'/','secure'=>true,'httponly'=>true,'samesite'=>'Strict']);
session_cache_limiter('');
if(!session_start()){http_response_code(503);echo 'Sesija nije dostupna.';exit;}
$identity=hash('sha256',(string)getenv('FISKAI_ADMIN_USERNAME')."\0".(string)getenv('FISKAI_ADMIN_PASSWORD_HASH'));
if(($_SESSION['identity']??null)!==$identity){
    $_SESSION=[];
    if(!session_regenerate_id(true)){session_write_close();http_response_code(503);exit;}
    $_SESSION['identity']=$identity;
    $_SESSION['csrf']=bin2hex(random_bytes(32));
}
$csrf=$_SESSION['csrf']??null;
$message='';
if($_SERVER['REQUEST_METHOD']==='POST'){
    if(!AdminAccess::csrf($csrf,$_POST['csrf']??null)){session_write_close();http_response_code(403);echo 'Zahtjev nije potvrđen.';exit;}
    $raw=$_POST['publication']??null;
    try{
        if(!is_string($raw))throw new InvalidArgumentException('Missing publication.');
        $data=AdminAccess::publication($raw);
        $result=PriceLists::publish(db(),$data);
        $_SESSION['notice']=$result['unchanged']?'Cjenik je već objavljen.':'Objavljena je verzija '.$result['version'].'.';
        $_SESSION['csrf']=bin2hex(random_bytes(32));
        session_write_close();header('Location: admin.php',true,303);exit;
    }catch(InvalidArgumentException|JsonException|TypeError){$message='Provjerite sadržaj cjenika. Objava nije izvršena.';http_response_code(400);}
    catch(Throwable $e){error_log('FiskAI admin publication: '.get_class($e));$message='Objava nije potvrđena. Provjerite trenutno stanje prije ponavljanja.';http_response_code(503);}
}
if(isset($_SESSION['notice'])&&is_string($_SESSION['notice'])){$message=$_SESSION['notice'];unset($_SESSION['notice']);}
session_write_close();
$rows=[];$unavailable=false;
try{
    $rows=db()->query('SELECT p.site_key,p.kind,p.version_no,p.filename,p.published_at FROM price_heads h JOIN price_lists p ON h.current_id=p.id ORDER BY p.site_key,p.kind LIMIT 501')->fetchAll();
    if(count($rows)>500)throw new RuntimeException('Administration capacity exceeded.');
}catch(Throwable){$rows=[];$unavailable=true;http_response_code(503);}
?><!doctype html><html lang="hr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FiskAI | Administracija</title><link rel="stylesheet" href="style.css"></head><body>
<header><a class="brand" href="./">Fisk<span>AI</span></a><nav><a href="./">Javni cjenici</a></nav></header>
<main><section><p class="eyebrow">UPRAVLJANJE POSLOVANJEM</p><h1>Administracija cjenika</h1><p>Pregled trenutačno objavljenih cjenika i objava nove verzije.</p>
<?php if($message!==''):?><p role="status"><?=h($message)?></p><?php endif;?>
<?php if($unavailable):?><p>Pregled trenutačno nije dostupan.</p><?php else:?>
<h2>Aktualne verzije</h2><p>Broj cjenika: <?=count($rows)?></p>
<div class="grid"><?php foreach($rows as $row):$query=['action'=>'download','site'=>$row['site_key'],'kind'=>$row['kind'],'version'=>$row['version_no']];?>
<article><h3><?=h($row['site_key'])?> · <?=h($row['kind'])?></h3><p>Verzija <?=h((string)$row['version_no'])?> · <?=h($row['published_at'])?> UTC</p><p><?=h($row['filename'])?></p><a href="api.php?<?=h(http_build_query($query+['format'=>'csv']))?>">CSV</a> · <a href="api.php?<?=h(http_build_query($query+['format'=>'xml']))?>">XML</a></article>
<?php endforeach;?></div>
<h2>Objava nove verzije</h2><p>Unesite pripremljeni JSON cjenika s oznakom prodajnog mjesta, vrstom, identifikatorom i stavkama. Provjerite podatke prije objave. Prethodna verzija ostaje u arhivi.</p>
<form action="admin.php" method="post" class="admin-form"><input type="hidden" name="csrf" value="<?=h((string)$csrf)?>"><label for="publication">Sadržaj cjenika (JSON)</label><textarea id="publication" name="publication" rows="12" maxlength="1000000" required spellcheck="false"></textarea><button type="submit">Objavi cjenik</button></form>
<?php endif;?></section></main><footer>FiskAI · Administracija</footer></body></html>
