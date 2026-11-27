# Zakonska matrica — početna verifikacija eRačuna

Provjera izvora: 27.11.2026. Ovo nije potvrda potpune zakonske sukladnosti.

Izvori: [Zakon o fiskalizaciji, NN 89/2025](https://narodne-novine.nn.hr/clanci/sluzbeni/full/2025_06_89_1233.html), [Porezna uprava — eRačun](https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/eracun), [obveze javnih tijela](https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/bezgotovinski-racuni-novosti/o/obveze-drzavnih-tijela-jls).

| Propis | Odredba | Početak primjene | Obveznici | Implementacija | Test | Status |
|---|---|---|---|---|---|---|
| NN 89/2025 | čl. 38., 80. | 1.1.2026. | obuhvaćeni PDV obveznici | HR-F2-2026-v1 | LegalComplianceTest (Kotlin/PHP) | osnovno pravilo, CI provjera potrebna |
| NN 89/2025 | čl. 38. st. 1. t. 2. i 3., čl. 80. | 1.1.2027. | obuhvaćeni subjekti izvan PDV-a | HR-F2-2027-v1 | prijelaz godine | osnovno pravilo, CI provjera potrebna |
| NN 89/2025 | čl. 38., 80.; odredbe o zaprimanju i fiskalizaciji | 1.1.2026. | obuhvaćeni primatelji | zaprimanje odvojeno od izdavanja | oba režima | potrebno dovršiti članak-po-članak verifikaciju |

Zakon je općenito stupio na snagu 1.9.2025.; za navedene obveze vrijede prijelazni datumi iz čl. 80.

## Granice implementacije

LegalCompliance u PHP-u i Kotlinu implementira samo osnovne obveze za prethodno utvrđene standardne tuzemne B2B transakcije bez izuzeća. Povijesni status subjekta i datum relevantnog događaja moraju doći od pozivatelja. UNKNOWN, UNRESOLVED i godine izvan verificiranog raspona vraćaju REVIEW_REQUIRED. NOT_REQUIRED_BY_THIS_RULE ne znači da je eRačun zabranjen niti da ne postoji obveza prema drugom propisu. B2G, inozemstvo, zakonska izuzeća, posebni slučajevi i buduće izmjene zahtijevaju dodatnu klasifikaciju. Rezultati ne potvrđuju izvršenu fiskalizaciju ni dopuštenje produkcijskog slanja.

Sljedeći koraci: automatski cross-platform parity test svih odluka, povijest poreznog statusa, trajna pohrana verzije pravila uz dokument, integracija s izdavanjem i zaprimanjem, stručna provjera odredbi i izuzeća. Postojeći dokumenti se ne mijenjaju. Produkcijska blokada ostaje na snazi. Ostala poglavlja master prompta, uključujući NN 97/2026, NN 119/2026 i NN 132/2026, nisu zatvorena ovom izmjenom.

## Jedinstveni katalog pravila

`server/contracts/legal-rules.json` je izvor za generirane Kotlin i PHP tablice, koje oba enginea koriste. `python3 scripts/check-legal-catalog.py --write` reproducira tablice; bez `--write` CI odbija odstupanje. Katalog trenutno podržava cijele kalendarske godine, ne unutar-godišnje promjene ili arhivu više revizija iste godine. Te mogućnosti i trajna pohrana odluka uz izdane dokumente ostaju otvorene. Testovi enginea i dalje su potrebni za provjeru ponašanja izvan same tablice.

## Povijest poreznog statusa

`TaxStatusHistory` (Kotlin/PHP) prihvaća vremenske intervale jednog poslovnog subjekta: početak uključiv, završetak isključiv; `null` označava otvoren interval. Intervali se sortiraju, provjeravaju i odbijaju ako se preklapaju. Nedostajući interval znači UNKNOWN, nikad pretpostavljeni aktualni status. Procjena uključuje datum događaja, efektivni interval, status, opseg i verzioniranu odluku. Ulazni popis kopira se; izmjene vanjskog popisa ne mijenjaju prethodno izračunate procjene.

Ovo je domenska jezgra, ne trajna evidencija: spremanje uz poslovni subjekt, ovlašteno dokazivanje statusa, trajna pohrana uz nepromjenjiv dokument, Room/SQL migracije, backup i UI još nisu povezani. Ne koristi se za produkcijsko izdavanje. Regresijski testovi `TaxStatusHistoryTest` provjeravaju promjenu statusa, praznine, granice intervala, preklapanje i neovisnost procjene od izmjene ulaza.

## Fiskalizacija eRačuna i eIzvještavanje — rokovi

Izvor: [NN 89/2025, čl. 47–53 i 80](https://narodne-novine.nn.hr/clanci/sluzbeni/2025_06_89_1233.html), primjena od 1.1.2026. i prijelazne kategorije iz čl. 80. `FiscalReportingRules` u Kotlinu i PHP-u razlikuje izdavanje, samoizdavanje, primitak, tehnički prekid, isporuke iz čl. 51, odbijanje i naplatu. Čl. 52 i 53: nominalni 20. dan sljedećeg mjeseca prema datumu stvarnog događaja. Čl. 48 st. 3–4 i čl. 49: odvojeni režimi pri izdavanju i pet radnih dana. Čl. 51 st. 3 upućuje na čl. 48 st. 3, ne na mjesečni rok.

Model vraća nominalni zakonski datum ili broj radnih dana uz oznaku WORKING_DAYS_REVIEW; ne računa konačne rokove bez provjere pravila računanja rokova, blagdana i eventualnih pomicanja. Potrebna je prethodna klasifikacija stvarnog događaja i obveznika; ne potvrđuje naplatu, odbijanje, fiskalizaciju ni dostavu. Razdoblja izvan kataloga traže dodatnu provjeru. Trajni outbox, XSD/Schematron, autorizacija, stvarni odgovor, korekcije i UI ostaju otvoreni. Testovi: FiscalReportingRulesTest (Kotlin/PHP); CI provjera potrebna.

## Trajna evidencija pripreme fiskalnih događaja

Migracija `002_fiscal_event_outbox.sql` dodaje zasebnu SQL tablicu bez promjene povijesne migracije 001. `FiscalEventOutbox` čuva identitet događaja, dokument, sažetak dokaza, verziju pravila i rok. Idempotentna priprema ne prepisuje postojeće podatke; promijenjeni sadržaj pod istim identitetom se odbija. EDUC rezervacija koristi atomsku promjenu stanja; prekinuta rezervacija prelazi u UNCERTAIN nakon pet minuta i nikad se automatski ne vraća u PREPARED. PROD rezervacija je blokirana. Nema stanja potvrđene dostave ni slanja prema CIS-u. Testovi koriste sintetičke identifikatore.

Ovo je interna infrastruktura, ne javni API: identiteti poslovnog subjekta, dokumenta i događaja moraju doći iz autoriziranog poslovnog sloja. Još nedostaju Room ekvivalent, sinkronizacija, transakcijsko povezivanje s naplatom/dokumentom, dokaz autorizacije, šifrirani sadržaj poruke, backup/restore, revizijski dnevnik, verifikacija odgovora i kontrolirano razrješenje nepoznatog ishoda. Jedinstvenost vrijedi za isti ID događaja, ne dokazuje da dvije različite identifikacijske oznake nisu ista poslovna radnja. Produkcijska blokada ostaje.

## Trajna kriptografska evidencija CIS EDUC odgovora

Migracija 003 dodaje izoliranu tablicu `cis_educ_response_evidence`. Interni CLI sloj registrira identitet poruke, poslovnog subjekta, dokumenta, sažetke dokumenta i zahtjeva te izvan sustava provisioniran pin certifikata i identitet ključa. `CisEducResponseEvidence` provjerava potpis, korelaciju i službenu pinanu EDUC XSD shemu prije upisa. Izvorni SOAP pohranjuje se AES-256-GCM uz autentificirane identitete i sažetke; identičan odgovor je idempotentan, konfliktni se odbija. JIR i sažetak odgovora jedinstveni su unutar EDUC tablice. Oporavak vraća točne potpisane bajtove, ali nije nova verifikacija aktualnog povjerenja certifikatu. Testovi koriste sintetički potpis i ne potvrđuju stvarni CIS odgovor. Nema javnog API-ja, transporta, provjere autorizacije poslovnog subjekta, produkcijske pohrane ni veze s eRačun eIzvještavanjem. Potrebni su siguran vanjski key management, politika rotacije, revizijski trag, serverski backup i verifikacija stvarnog transporta; privilegirani SQL pristup nije ograničen ovom klasom.
