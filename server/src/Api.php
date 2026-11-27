<?php
declare(strict_types=1);
require_once __DIR__.'/PriceLists.php';
require_once __DIR__.'/KpdCatalog.php';
require_once __DIR__.'/PriceDownload.php';
require_once __DIR__.'/DemoSync.php';
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
try{
    $action=$_GET['action']??'';
    $method=$_SERVER['REQUEST_METHOD'];
    if(in_array($action,['demo-sync','demo-sync-pull','demo-sync-challenge'],true)&&$method==='POST'){
        header('Cache-Control: no-store');
        // Do not trust caller-supplied forwarding headers. TLS must be terminated
        // locally or the trusted server configuration must establish HTTPS.
        if(getenv('FISKAI_DEMO_SYNC_ENABLED')!=='1')jsonResponse(['error'=>'Usluga nije dostupna.'],404);
        if(!in_array(strtolower((string)($_SERVER['HTTPS']??'')),['on','1'],true))jsonResponse(['error'=>'Potreban je HTTPS.'],403);
        if(strtolower(trim(explode(';',$_SERVER['CONTENT_TYPE']??'',2)[0]))!=='application/json')jsonResponse(['error'=>'Očekuje se JSON.'],415);
        $auth=$_SERVER['HTTP_AUTHORIZATION']??'';
        if(!is_string($auth)||!preg_match('/\ABearer [0-9a-f]{64}\z/D',$auth))jsonResponse(['error'=>'Pristup nije odobren.'],401);
        $body=file_get_contents('php://input',false,null,0,65537);
        if($body===false||strlen($body)>65536)jsonResponse(['error'=>'Zahtjev je prevelik.'],413);
        $data=json_decode($body,true,16,JSON_THROW_ON_ERROR);
        if(!is_array($data))throw new InvalidArgumentException('Neispravan JSON.');
        try{
            if($action==='demo-sync-challenge'){
                if(count($data)!==2||($data['protocol']??null)!==1||!is_string($data['subjectId']??null))throw new InvalidArgumentException('Invalid challenge request.');
                jsonResponse((new DemoDeviceProof(db()))->challenge($auth,$data['subjectId']));
            }
            $proof=['nonce'=>$_SERVER['HTTP_X_FISKAI_NONCE']??null,'signature'=>$_SERVER['HTTP_X_FISKAI_SIGNATURE']??null,'body'=>$body];
            $sync=new DemoSync(db());
            jsonResponse($action==='demo-sync-pull'?$sync->pull($auth,$data,$proof):$sync->push($auth,$data,$proof));
        }
        catch(DemoSyncAuthException $e){jsonResponse(['error'=>'Pristup nije odobren.'],401);}
        catch(DemoSyncConflict $e){jsonResponse(['error'=>'Sukob zapisa. Lokalni zapisi nisu izmijenjeni.'],409);}
    }
    if($action==='publish'&&$method==='POST'){
        requirePublisher();
        if(strtolower(trim(explode(';',$_SERVER['CONTENT_TYPE']??'',2)[0]))!=='application/json')jsonResponse(['error'=>'Očekuje se JSON.'],415);
        $body=file_get_contents('php://input',false,null,0,5000001);
        if($body===false||strlen($body)>5000000)jsonResponse(['error'=>'Zahtjev je prevelik.'],413);
        $data=json_decode($body,true,512,JSON_THROW_ON_ERROR);
        if(!is_array($data))throw new InvalidArgumentException('Neispravan JSON.');
        jsonResponse(PriceLists::publish(db(),$data),201);
    }
    if($action==='download'&&$method==='GET'){
        $options=PriceDownload::parse($_GET);
        $format=$options['format'];
        $row=PriceLists::download(db(),$options['site'],$options['kind'],$options['version']);
        if(!$row)jsonResponse(['error'=>'Cjenik nije dostupan.'],404);
        header('Content-Type: '.($format==='csv'?'text/csv':'application/xml').'; charset=utf-8');
        header('Cache-Control: public, max-age=60, must-revalidate');
        header('Content-Disposition: '.PriceDownload::disposition($row['filename'],$options));
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
