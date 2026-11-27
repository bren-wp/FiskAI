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

Sljedeći koraci: jedinstveni strojno čitljivi katalog pravila, automatski cross-platform parity test, povijest poreznog statusa, trajna pohrana verzije pravila uz dokument, integracija s izdavanjem i zaprimanjem, stručna provjera odredbi i izuzeća. Postojeći dokumenti se ne mijenjaju. Produkcijska blokada ostaje na snazi. Ostala poglavlja master prompta, uključujući NN 97/2026, NN 119/2026 i NN 132/2026, nisu zatvorena ovom izmjenom.
