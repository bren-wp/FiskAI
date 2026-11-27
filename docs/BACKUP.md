# Prijenosna sigurnosna kopija radnog prostora

Format FISKBAK1: osam ASCII bajtova oznake, 16 bajtova slučajne soli, 12 bajtova GCM noncea, AES-256-GCM ciphertext i 16 bajtova taga. Cijelo zaglavlje je AAD. Ključ se izvodi iz korisničke lozinke PBKDF2-HMAC-SHA256 (600.000 iteracija); ne prenosi se Android Keystore. Ograničenje 8 MiB nešifriranog sadržaja. Android 8+ zbog dostupnosti KDF providera.

Sadržaj v2 uključuje nacrte, kontakte, proizvode, profil, fiskalne postavke, DEMO račune i brojače, stanje i povijest zaliha te CIS testni dnevnik. V1 kopije ostaju čitljive. Prije izvoza i uvoza validira se kontinuitet numeriranja i stanje zaliha prema redoslijedu knjiženja; neizvršeni ili prekinuti CIS pokušaji vraćaju se kao UNCERTAIN. Ključevi i certifikati nisu uključeni. Ne koristiti isti obnovljeni radni prostor istodobno na više uređaja. Vraćanje je ograničeno na praznu instalaciju iste DEMO/redovne varijante. Postojeći zapisi se ne prepisuju, legacy migracije se izvršavaju prije provjere, upis se izvršava u jednoj SQL transakciji. Nakon vraćanja UI se ponovo učitava.

Podržan je format šifrirane ovojnice FISKBAK1 i sadržaj v1/v2. Maksimalno 20.000 redaka po dnevniku. Lozinka se ne sprema. Korisnik mora čuvati lozinku odvojeno od kopije. Integracijska validacija svih poslovnih relacija, potpuna Room migracija, budući produkcijski fiskalni dnevnici, serverski backup te fizički test prijenosa između uređaja ostaju obvezni prije produkcije.

## Sadržaj v3
Nakon fiskalnog cutovera izvoz uključuje kanonsku Room povijest, izvedene brojače i outbox metapodatke. Uvoz v3 puni legacy i Room tablice u istoj fizičkoj SQL transakciji; odbija postojeće fiskalne zapise. Status SENDING u obnovljenoj kopiji postaje UNCERTAIN. Stare v1/v2 kopije ostaju čitljive, a njihove povijesne snimke bez outboxa nakon cutovera dobivaju ARCHIVED. Ne prenosi se ključ Android Keystorea. Nema produkcijskog slanja. Potrebna je CI i fizička verifikacija prijenosa.
