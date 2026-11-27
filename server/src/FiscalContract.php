<?php
declare(strict_types=1);

/** Offline validation against an operator-reviewed, hash-pinned local contract.
 * No download, no implicit trust in an XML document's schemaLocation. */
final class FiscalContract {
    private const XSD='http://www.w3.org/2001/XMLSchema';
    private const MAX=1048576;
    private static function parse(string $xml,bool $schema=false): DOMDocument {
        if ($xml==='' || strlen($xml)>self::MAX || str_contains($xml,"\0") ||
            ($schema ? preg_match('/<!\s*(?:DOCTYPE|ENTITY)\b/i',$xml) : str_contains($xml,'<!')) || !preg_match('//u',$xml)) throw new InvalidArgumentException('Unsupported XML.');
        $doc=new DOMDocument();
        if (!$doc->loadXML($xml,LIBXML_NONET) || $doc->doctype!==null) throw new InvalidArgumentException('Malformed XML.');
        return $doc;
    }
    /** Manifest is trusted configuration, not supplied by an invoice or HTTP request.
     * Each schema must be a flat local filename; imported schemas must also be pinned. */
    public static function validate(string $xml,string $directory,array $sha256,string $entry): void {
        if(PHP_SAPI!=='cli')throw new LogicException('Offline validation only.');
        $base=realpath($directory);
        if($base===false || !is_dir($base) || count($sha256)<1 || count($sha256)>64 || !isset($sha256[$entry]))
            throw new InvalidArgumentException('Missing contract bundle.');
        $files=[];
        $previous=libxml_use_internal_errors(true);
        $loader=libxml_get_external_entity_loader();
        try {
            foreach($sha256 as $name=>$digest) {
                if(!is_string($name)||!preg_match('/\A[A-Za-z0-9][A-Za-z0-9_.-]*\.xsd\z/D',$name)||
                    !is_string($digest)||!preg_match('/\A[0-9a-f]{64}\z/D',$digest))
                    throw new InvalidArgumentException('Invalid contract manifest.');
                $path=$base.DIRECTORY_SEPARATOR.$name;
                if(is_link($path)||realpath($path)!==$path||!is_file($path)||filesize($path)>self::MAX)
                    throw new InvalidArgumentException('Missing schema file.');
                $content=file_get_contents($path);
                if($content===false||!hash_equals($digest,hash('sha256',$content)))
                    throw new InvalidArgumentException('Schema checksum mismatch.');
                $schema=self::parse($content,true);
                if($schema->documentElement?->namespaceURI!==self::XSD||$schema->documentElement?->localName!=='schema')
                    throw new InvalidArgumentException('Not an XML schema.');
                $xpath=new DOMXPath($schema);
                if($xpath->query('//@*[namespace-uri()="http://www.w3.org/XML/1998/namespace" and local-name()="base"]')->length)
                    throw new InvalidArgumentException('xml:base forbidden.');
                foreach(['import','include','redefine','override'] as $tag) {
                    foreach($schema->getElementsByTagNameNS(self::XSD,$tag) as $node) {
                        $location=$node->getAttribute('schemaLocation');
                        if(!isset($sha256[$location]))
                            throw new InvalidArgumentException('Unpinned schema dependency.');
                    }
                }
                $files[$path]=$content;
            }
            // Serve only the already verified bytes, avoiding file replacement and network loads.
            libxml_set_external_entity_loader(static function($public,$system,$context) use ($files,$base) {
                if(!is_string($system))return null;
                if(str_starts_with($system,'file://'))$system=substr($system,7);
                if(!str_contains($system,'/')&&!str_contains($system,'\\'))$system=$base.DIRECTORY_SEPARATOR.$system;
                if(!isset($files[$system]))return null;
                $stream=fopen('php://temp','w+b');
                if($stream===false)return null;
                fwrite($stream,$files[$system]);rewind($stream);
                return $stream;
            });
            $document=self::parse($xml);
            if(!$document->schemaValidate($base.DIRECTORY_SEPARATOR.$entry))
                throw new InvalidArgumentException('XML failed pinned schema validation.');
        } finally {
            libxml_set_external_entity_loader($loader);
            libxml_clear_errors();libxml_use_internal_errors($previous);
        }
    }
}
