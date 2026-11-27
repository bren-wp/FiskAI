<?php
declare(strict_types=1);

/** Offline fail-closed trust verification. CA and CRL bundles are provisioned and
 * refreshed independently by an administrator. No AIA/OCSP/CDP network fetch. */
final class CisCertificateTrust {
    public static function approvedBundle(string $variable):string {
        $path=getenv($variable);
        $pin=getenv($variable.'_SHA256');
        $real=is_string($path)?realpath($path):false;
        if($real===false||!is_file($real)||!is_readable($real)||!is_string($pin)||!preg_match('/\A[0-9a-f]{64}\z/D',$pin))
            throw new InvalidArgumentException('Approved trust bundle required.');
        $data=file_get_contents($real,false,null,0,4194305);
        if($data===false||$data===''||strlen($data)>4194304||!hash_equals($pin,hash('sha256',$data)))
            throw new InvalidArgumentException('Trust bundle checksum mismatch.');
        return $data;
    }
    /** Dedicated root-only trust store, explicit intermediates, signed current CRLs for every issuer. */
    public static function verify(OpenSSLCertificate $cert,string $pin,string $ca,string $crl,?string $intermediates=null):void {
        if(PHP_SAPI!=='cli')throw new LogicException('CLI only.');
        if(!preg_match('/\A[0-9a-f]{64}\z/D',$pin)||!hash_equals($pin,openssl_x509_fingerprint($cert,'sha256')?:''))
            throw new InvalidArgumentException('Certificate pin mismatch.');
        $info=openssl_x509_parse($cert);
        if(!$info||time()<$info['validFrom_time_t']||time()>$info['validTo_time_t'])
            throw new InvalidArgumentException('Certificate expired or not yet valid.');
        $public=openssl_pkey_get_public($cert);
        $details=$public?openssl_pkey_get_details($public):false;
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_RSA||$details['bits']<2048)
            throw new InvalidArgumentException('Unsupported application certificate key.');
        if(!str_contains($info['extensions']['keyUsage']??'','Digital Signature'))
            throw new InvalidArgumentException('Signing key usage required.');
        if(!openssl_x509_export($cert,$pem))throw new RuntimeException('Certificate export failed.');
        foreach([$ca,$crl,$intermediates??$ca] as $bundle)
            if($bundle===''||strlen($bundle)>4194304)throw new InvalidArgumentException('Invalid bundle size.');
        $dir=sys_get_temp_dir().'/fiskai-trust-'.bin2hex(random_bytes(16));
        if(!mkdir($dir,0700))throw new RuntimeException('Trust workspace unavailable.');
        try {
            $files=['leaf.pem'=>$pem,'ca.pem'=>$ca,'crl.pem'=>$crl];
            if($intermediates!==null)$files['intermediates.pem']=$intermediates;
            foreach($files as $name=>$data){
                $path=$dir.'/'.$name;
                if(file_put_contents($path,$data,LOCK_EX)!==strlen($data)||!chmod($path,0600))throw new RuntimeException('Trust workspace write failed.');
            }
            $cmd=['/usr/bin/openssl','verify','-no-CAfile','-no-CApath','-no-CAstore','-CAfile',$dir.'/ca.pem',
                '-CRLfile',$dir.'/crl.pem','-crl_check_all','-x509_strict','-auth_level','2'];
            if($intermediates!==null)array_push($cmd,'-untrusted',$dir.'/intermediates.pem');
            $cmd[]=$dir.'/leaf.pem';
            // Fixed executable and argument array, no shell and no remotely supplied paths.
            $process=proc_open($cmd,[0=>['file','/dev/null','r'],1=>['file','/dev/null','w'],2=>['file','/dev/null','w']],$pipes,null,
                ['PATH'=>'/usr/bin:/bin','LC_ALL'=>'C','OPENSSL_CONF'=>'/dev/null']);
            if(!is_resource($process)||proc_close($process)!==0)
                throw new InvalidArgumentException('Certificate chain or revocation status not verified.');
        }finally{
            foreach(glob($dir.'/*')?:[] as $file)unlink($file);
            rmdir($dir);
        }
    }
}
