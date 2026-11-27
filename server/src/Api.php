<?php
declare(strict_types=1);
require_once __DIR__.'/PriceLists.php';
require_once __DIR__.'/KpdCatalog.php';
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
try{
    $action=$_GET['action']??'';
    $method=$_SERVER['REQUEST_METHOD'];
    if($action==='publish'&&$method==='POST'){
        requirePublisher();
        if(!str_starts_with(strtolower($_SERVER['CONTENT_TYPE']??''),'application/json'))jsonResponse(['error'=>'Očekuje se JSON.'],415);
        $body=file_get_contents('php://input',false,null,0,5000001);
        if($body===false||strlen($body)>5000000)jsonResponse(['error'=>'Zahtjev je prevelik.'],413);
        $data=json_decode($body,true,512,JSON_THROW_ON_ERROR);
        if(!is_array($data))throw new InvalidArgumentException('Neispravan JSON.');
        jsonResponse(PriceLists::publish(db(),$data),201);
    }
    if($action==='download'&&$method==='GET'){
        $format=$_GET['format']??'csv';
        if(!in_array($format,['csv','xml'],true))throw new InvalidArgumentException('Neispravan format.');
        $version=null;
        if(isset($_GET['version'])){$version=filter_var($_GET['version'],FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);if($version===false)throw new InvalidArgumentException('Neispravna verzija.');}
        $row=PriceLists::download(db(),(string)($_GET['site']??''),(string)($_GET['kind']??''),$version);
        if(!$row)jsonResponse(['error'=>'Cjenik nije dostupan.'],404);
        header('Content-Type: '.($format==='csv'?'text/csv':'application/xml').'; charset=utf-8');
        header('Cache-Control: public, max-age=60, must-revalidate');
        header("Content-Disposition: inline; filename*=UTF-8''".rawurlencode($row['filename'].'.'.$format));
        echo $row[$format.'_content'];exit;
    }
    if($action==='kpd'&&$method==='GET'){
        $date=(string)($_GET['date']??(new DateTimeImmutable('now',new DateTimeZone('Europe/Zagreb')))->format('Y-m-d'));
        $source=db()->query('SELECT source_url,sha256,imported_at,record_count FROM kpd_source WHERE id=1')->fetch();
        if(!$source)jsonResponse(['error'=>'KPD katalog još nije uvezen.'],503);
        jsonResponse(['source'=>$source,'items'=>KpdCatalog::search(db(),(string)($_GET['q']??''),$date)]);
    }
    jsonResponse(['error'=>'Nepoznat zahtjev.'],404);
}catch(InvalidArgumentException|JsonException|TypeError $e){jsonResponse(['error'=>'Provjerite unesene podatke.'],400);}
catch(Throwable $e){error_log('FiskAI request failed: '.get_class($e));jsonResponse(['error'=>'Usluga trenutačno nije dostupna.'],503);}
