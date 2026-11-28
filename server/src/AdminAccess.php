<?php
declare(strict_types=1);

/** Single-merchant administration; no default credentials and no trust in forwarding headers. */
final class AdminAccess {
    public static function authorize(array $server,string|false $enabled,string|false $username,string|false $passwordHash):int {
        if($enabled!=='1'||!is_string($username)||!preg_match('/\A[A-Za-z0-9_.-]{1,64}\z/D',$username)||
            !is_string($passwordHash)||strlen($passwordHash)>255||
            !in_array(password_get_info($passwordHash)['algoName'],['bcrypt','argon2id'],true))return 404;
        if(!in_array(strtolower((string)($server['HTTPS']??'')),['on','1'],true))return 403;
        $user=$server['PHP_AUTH_USER']??null;$password=$server['PHP_AUTH_PW']??null;
        if(!is_string($user)||!is_string($password)||strlen($user)>128||strlen($password)>256)return 401;
        $match=password_verify($password,$passwordHash);
        return hash_equals($username,$user)&&$match?200:401;
    }
    public static function csrf(mixed $expected,mixed $provided):bool {
        return is_string($expected)&&is_string($provided)&&preg_match('/\A[0-9a-f]{64}\z/D',$expected)===1&&hash_equals($expected,$provided);
    }
    public static function publication(string $json):array {
        if(strlen($json)>1000000||$json==='')throw new InvalidArgumentException('Invalid publication size.');
        $data=json_decode($json,true,16,JSON_THROW_ON_ERROR);
        if(!is_array($data)||array_is_list($data)||array_diff(array_keys($data),['site','kind','identifier','location','rows'])||
            !isset($data['site'],$data['kind'],$data['identifier'],$data['rows'])||
            !is_string($data['site'])||!is_string($data['kind'])||!is_string($data['identifier'])||
            (isset($data['location'])&&!is_string($data['location']))||!is_array($data['rows'])||!array_is_list($data['rows'])||!$data['rows'])
            throw new InvalidArgumentException('Invalid publication.');
        return $data;
    }
}
