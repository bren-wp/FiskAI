<?php
declare(strict_types=1);
require_once __DIR__.'/../src/PriceLists.php';
function check(bool $ok):void{if(!$ok)throw new RuntimeException('Test failed');}
$row=['naziv'=>'Usluga čitanja & pisanja','maloprodajna_cijena'=>'12.50','poseban_oblik_prodaje'=>false,'bazna_cijena'=>'10.00'];
[$csv,$xml]=PriceLists::encode('USLUGE',[$row]);
check(str_contains($csv,'naziv;maloprodajna_cijena;poseban_oblik_prodaje;bazna_cijena'));
check(str_contains($xml,'&amp;'));
check(str_contains($csv,'12.50;NE;10.00'));
foreach([['bazna_cijena'=>''],['naziv'=>'=1+1'],['maloprodajna_cijena'=>12.5]] as $bad){
    $rejected=false;try{PriceLists::encode('USLUGE',[array_replace($row,$bad)]);}catch(InvalidArgumentException){$rejected=true;}check($rejected);
}
echo "Price-list tests passed\n";
