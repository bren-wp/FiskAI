# Objavljivanje testnog izdanja

CI na pull requestu provjerava Android, emulator, MySQL i MariaDB te priprema pakete kandidata. Kandidat nije objavljeno GitHub izdanje.

Nakon spajanja verificiranog PR-a, na grani main pokrenuti workflow FiskAI release candidate packages uz opciju publish. Workflow ponovno izvršava provjere i neće zamijeniti postojeće izdanje ili objaviti zastarjeli commit.

Obvezni uvjeti: svi paketi, provjereni SHA-256, čitljive ZIP/APK/AAB arhive, originali u brending/originals/01 i brending/originals/02, SHA-256 manifesti oba izvorna paketa (svi preneseni originali, uz evidentirani hash izvornog ZIP-a), te stvarne snimke Androida, weba i administracije. Web/admin snimke moraju biti trajno spremljene u screenshots/actual/web i screenshots/actual/admin. Nemojte koristiti dizajnerske koncepte kao snimke aplikacije. UPGRADE.md je obvezan prilog. Kontrola objave odbija ZIP zapise s nesigurnim putanjama, simboličkim poveznicama ili dupliciranim imenima te provjerava potpunost oba manifesta i otiske svih originala. Testovi koriste sintetičke arhive i ne potvrđuju izgled ni vjerodostojnost originala. Automatska provjera prisutnosti datoteka ne potvrđuje autentičnost ili pixel-perfect podudarnost; potrebno je pregledati stvarne slike.

Objava je isključivo tehničko testno izdanje (prerelease). Ne potvrđuje fiskalizacijsku autorizaciju, sigurnosni audit ni spremnost za poslovanje. Android SBOM pokriva runtime ovisnosti, ne potpuni serverski i build inventar.
