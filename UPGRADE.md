# Nadogradnja FiskAI (tehničko testno izdanje)

Ne koristiti izdanje za stvarno fiskalno poslovanje. Prije nadogradnje
provjerite oznaku verzije, SHA256SUMS.txt i potpis instaliranog APK-a.
Ne uklanjajte postojeću aplikaciju radi zaobilaženja nepodudarnog potpisa.

## Android

1. U postojećoj aplikaciji izvezite šifriranu sigurnosnu kopiju radnog prostora.
   Čuvajte kopiju i lozinku odvojeno; ne pohranjujte certifikate u GitHub.
2. Provjerite dostupnost i čitljivost kopije u zasebnoj testnoj instalaciji iste
   varijante. Ne koristite obnovljeni radni prostor istodobno na više uređaja.
3. Nadogradite samo paket s istim applicationId i kompatibilnim potpisom.
   FiskAI i FiskAI DEMO imaju zasebne identitete i pohranu. Testni APK nije
   zamjena za produkcijski potpisani paket.
4. Nakon nadogradnje provjerite broj dokumenata, brojače, evidenciju primitaka,
   zalihe, ulazne račune i statuse outboxa. Ne šaljite ponovno dokument
   s nepoznatim ishodom kao novi račun.
5. Kod greške ne brišite bazu, ne vraćajte stariji APK preko novije baze i ne
   pokušavajte destruktivnu migraciju. Sačuvajte postojeću instalaciju i kopiju
   te provjerite mogućnost oporavka na izoliranom testnom uređaju.

Room v10 nije potpuna normalizacija svih poslovnih domena. Aktualnu shemu
i preostale migracijske uvjete potražite u docs/ROOM.md i docs/BACKUP.md.

## Web i server

1. Zabilježite verziju izvornog koda i konfiguracije te izradite konzistentnu
   kopiju MySQL/MariaDB baze i datoteka. Tajne čuvajte izvan javnog direktorija.
2. Provjerite restore na izoliranoj bazi prije nadogradnje produkcije.
3. Zaustavite upise tijekom nadogradnje, postavite odgovarajuće verzije
   direktorija web/ i server/ te zadržite web/public kao document root.
4. Pokrenite `php server/bin/migrate.php` s migracijskim korisnikom;
   ne mijenjajte već evidentirane migracije. Koristite ograničen runtime račun.
5. Provjerite aktualne i arhivske cjenike, CSV/XML poveznice i KPD pretragu
   prije ponovnog dopuštanja upisa.
6. Kod neuspjeha ne pokrećite improvizirani SQL rollback. MySQL/MariaDB DDL
   može implicitno potvrditi transakciju. Oporavak provodite iz verificirane
   kopije uz prethodno sačuvane zapise i koordinirani povrat verzije aplikacije.

Ove upute nisu dokaz uspješne nadogradnje svake povijesne verzije.
Fizička provjera migracije, oporavka i produkcijske fiskalizacije ostaje uvjet
za produkcijsku objavu.

## Room schema 11

Migracija 10 → 11 dodaje zasebnu šifriranu tablicu `legal_decision_ledger`. Postojeći poslovni redci ostaju netaknuti; legacy odluke ne dobivaju izmišljenu regulatornu povijest. Prije nadogradnje napravite šifriranu sigurnosnu kopiju. Povratak na stariji APK nakon migracije nije podržan.
