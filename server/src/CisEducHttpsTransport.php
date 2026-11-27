<?php
declare(strict_types=1);
require_once __DIR__.'/CisCertificateTrust.php';

/** Opt-in F1 EDUC transport. Configuration must be provided by an authorized operator.
 * No production URL, public API, redirect, automatic retry or proxy.
 */
final class CisEducHttpsTransport {
    public const ENDPOINT='https://cistest.apis-it.hr:8449/FiskalizacijaServiceTest';
    private const LIMIT=1048576;
    public static function authorize(string $subject,OpenSSLCertificate $signer,OpenSSLCertificate $response,
        string $signerPin,string $responsePin):array {
        if(PHP_SAPI!=='cli'||getenv('FISKAI_CIS_EDUC_ENABLED')!=='1'||
            getenv('FISKAI_CIS_EDUC_AUTHORIZED_SUBJECT')!==$subject)
            throw new LogicException('EDUC transport is not authorized by local configuration.');
        if(!extension_loaded('curl'))throw new RuntimeException('cURL unavailable.');
        $tlsCa=CisCertificateTrust::approvedBundle('FISKAI_CIS_EDUC_TLS_CA');
        $tlsCrl=CisCertificateTrust::approvedBundle('FISKAI_CIS_EDUC_TLS_CRL');
        $appCa=CisCertificateTrust::approvedBundle('FISKAI_CIS_EDUC_APPLICATION_CA');
        $appCrl=CisCertificateTrust::approvedBundle('FISKAI_CIS_EDUC_APPLICATION_CRL');
        $intermediates=getenv('FISKAI_CIS_EDUC_APPLICATION_INTERMEDIATES')===false?null:
            CisCertificateTrust::approvedBundle('FISKAI_CIS_EDUC_APPLICATION_INTERMEDIATES');
        $tlsPin=getenv('FISKAI_CIS_EDUC_TLS_SPKI');
        if(!is_string($tlsPin)||!preg_match('~\Asha256//[A-Za-z0-9+/]{43}=\z~D',$tlsPin))
            throw new InvalidArgumentException('Independently approved TLS SPKI pin required.');
        foreach([[$signer,$signerPin],[$response,$responsePin]] as [$cert,$pin])
            CisCertificateTrust::verify($cert,$pin,$appCa,$appCrl,$intermediates);
        return ['ca'=>$tlsCa,'crl'=>$tlsCrl,'pin'=>$tlsPin];
    }
    public static function post(string $subject,string $soap,OpenSSLCertificate $signer,OpenSSLCertificate $response,
        string $signerPin,string $responsePin):string {
        $policy=self::authorize($subject,$signer,$response,$signerPin,$responsePin);
        if($soap===''||strlen($soap)>self::LIMIT)throw new InvalidArgumentException('Invalid SOAP size.');
        $dir=sys_get_temp_dir().'/fiskai-tls-'.bin2hex(random_bytes(16));
        if(!mkdir($dir,0700))throw new RuntimeException('TLS workspace unavailable.');
        $handle=null;$body='';
        try{
            foreach(['ca.pem'=>$policy['ca'],'crl.pem'=>$policy['crl']] as $name=>$data){
                $path=$dir.'/'.$name;
                if(file_put_contents($path,$data,LOCK_EX)!==strlen($data)||!chmod($path,0600))throw new RuntimeException('TLS workspace write failed.');
            }
            $handle=curl_init(self::ENDPOINT);
            if($handle===false)throw new RuntimeException('Transport unavailable.');
            if(!curl_setopt_array($handle,[
                CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$soap,
                CURLOPT_HTTPHEADER=>['Content-Type: text/xml; charset=utf-8','SOAPAction: ""','Accept: text/xml'],
                CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_REDIR_PROTOCOLS=>CURLPROTO_HTTPS,
                CURLOPT_FOLLOWLOCATION=>false,CURLOPT_MAXREDIRS=>0,CURLOPT_PROXY=>'',
                CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,
                CURLOPT_SSLVERSION=>CURL_SSLVERSION_TLSv1_2,CURLOPT_CAINFO=>$dir.'/ca.pem',CURLOPT_CRLFILE=>$dir.'/crl.pem',
                CURLOPT_PINNEDPUBLICKEY=>$policy['pin'],CURLOPT_CONNECTTIMEOUT=>10,CURLOPT_TIMEOUT=>30,
                CURLOPT_WRITEFUNCTION=>static function($h,string $chunk)use(&$body):int{
                    if(strlen($chunk)>self::LIMIT-strlen($body))return 0;
                    $body.=$chunk;return strlen($chunk);
                }
            ]))throw new RuntimeException('Transport options unavailable.');
            if(curl_exec($handle)!==true||curl_getinfo($handle,CURLINFO_RESPONSE_CODE)!==200||$body==='')
                throw new RuntimeException('EDUC response not confirmed.');
            return $body;
        }finally{
            if($handle instanceof CurlHandle)curl_close($handle);
            foreach(glob($dir.'/*')?:[] as $file)unlink($file);
            rmdir($dir);
        }
    }
}
