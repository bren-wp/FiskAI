# Prijenosna sigurnosna kopija radnog prostora

Format FISKBAK1: osam ASCII bajtova oznake, 16 bajtova slučajne soli, 12 bajtova GCM noncea, AES-256-GCM ciphertext i 16 bajtova taga. Cijelo zaglavlje je AAD. Ključ se izvodi iz korisničke lozinke PBKDF2-HMAC-SHA256 (600.000 iteracija); ne prenosi se Android Keystore. Ograničenje 8 MiB nešifriranog sadržaja. Android 8+ zbog dostupnosti KDF providera.

Izvoz obuhvaća samo nacrte, kontakte, proizvode, poslovni profil i fiskalne postavke. Ne izvozi certifikate, ključeve, DEMO račune, brojače, stanje i kretanje zaliha ni CIS dnevnik. Nije potpuni disaster recovery. Vraćanje je ograničeno na praznu instalaciju iste DEMO/redovne varijante. Postojeći zapisi se ne prepisuju, legacy migracije se izvršavaju prije provjere, upis se izvršava u jednoj SQL transakciji. Nakon vraćanja UI se ponovo učitava.

Podržan je samo vlastiti format v1. Lozinka se ne sprema. Korisnik mora čuvati lozinku odvojeno od kopije. Integracijska validacija svih poslovnih relacija, potpuna Room migracija, svi dnevnici i brojači, serverski backup te fizički test prijenosa između uređaja ostaju obvezni prije produkcije.
