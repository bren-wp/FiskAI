# Room — objedinjeni lokalni fiskalni sloj v6

AndroidX Room 2.8.4. Od v6 fiskalni dokumenti i outbox fizički su u postojećoj bazi fiskai_records.db. Legacy SQLiteOpenHelper i Room dijele datoteku; ostale poslovne tablice još se pristupaju starim slojem. Room shema pokriva dvije fiskalne tablice. Nema destruktivnog fallbacka.

Migracija v1–v5 dodaje tablice i indekse. Prije uporabe fiskalnog zaslona ili izvoza kopije, FiscalCutover u jednoj SQL transakciji provjerava kontinuitet svih povijesnih brojeva, dešifrira i provjerava snimke, kopira postojeći šifrat u Room tablicu i prenosi statuse iz stare zasebne baze v1 samo ako se svaki dokument točno podudara s izvorom. Stara baza se ne briše. Povijesni dokumenti bez lokalnog outboxa dobivaju ARCHIVED i nisu kandidati za slanje. SENDING se pretvara u UNCERTAIN. Marker cutovera upisuje se tek nakon uspjeha; nakon toga stari DemoLedger.freeze odbija izdavanje. Aktivni DEMO zaslon dodjeljuje broj, šifrira snimku i upisuje dokument i outbox u jednoj Room transakciji. Brojevi počinju nakon najvećeg postojećeg broja u opsegu. Nema stvarnog slanja i nema lažnog statusa prihvaćanja.

Backup v3 uključuje kanonske Room dokumente, brojače i statuse. Pri restoreu u prazan radni prostor, stariji dnevnici i Room tablice pune se u istoj fizičkoj SQL transakciji, a SENDING prelazi u UNCERTAIN. V1/V2 kopije ostaju podržane; nakon cutovera povijesne snimke bez outboxa arhiviraju se. Certifikati i privatni ključevi nisu uključeni.

Preostalo: potpuna Room normalizacija drugih domena, uklanjanje paralelnih pristupnih slojeva, izvoz i trajna pohrana provjerenih shema, fizička verifikacija migracije i backupa, autentificirani produkcijski fiskalni protokol i autorizirani DEMO test. Dok CI i integracijski testovi nisu završeni, promjene nisu produkcijski verificirane.

## Room v9 — DEMO priprema naplate

Nova tablica `demo_payment_preparation` referencira `receipt_event` i `fiscal_document` stranim ključevima, uz jedinstveni dokument i identitet događaja. Migracija 8→9 dodaje tablicu i ne mijenja postojeće retke. Triggers blokiraju UPDATE, DELETE i REPLACE. Zajednička SQLite transakcija i autentificirani šifrat čuvaju vezu. `ReceiptStore.prepareDemoPayment` dopušten je samo u izoliranom DEMO paketu; provjerava vlasnika, cijeli iznos, datum, sažetke i nominalni rok. Ne podržava djelomičnu naplatu, storno, više naplata, izlazni eRačun ni slanje na server. Room compiler provjerava v9 za sve varijante. Potrebna je potvrda CI i migracijskih testova.
