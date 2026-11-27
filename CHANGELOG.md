# Promjene

## 0.1.1 — kandidat (nije objavljeno)
- Stroga provjera XML referenci i deklaracije prije DOM parsiranja; odbijanje nepoznatih entiteta, nedopuštenih Unicode referenci i processing instructions. Instrumentacijski testovi sada identificiraju pojedinačni neispravan ulaz; dodani JVM regresijski testovi.
- Ispravljen Android DOM parser koji ne podržava SAX external-general-entities; zajednički ograničeni UTF-8 parser za UBL i CIS Echo, zabrana DTD/vanjskih entiteta, testovi na emulatoru.
- Room v8: šifrirani bankovni izvodi i nepromjenjiva evidencija potvrđenih primitaka, CSV uvoz, deduplikacija, ručna klasifikacija, radni KPR pregled, backup/restore. Nije službeni porezni obrazac.
- Offline JIR verifier: službeni XSD, neovisni certifikat i SHA-256 pin, XMLDSig, korelacija ID-a poruke i negativni testovi. Bez stvarnog slanja.
- Room v7: individualni šifrirani ulazni računi, nedestruktivan cutover stare kolekcije, CAS promjena statusa, objedinjeni backup/restore i migracijski testovi.
- SHA-256 pinovi stvarno preuzetih službenih EDUC/PROD WSDL v1.10 arhiva i XSD-a; offline validacija potpisanog primjera prema stvarnom EDUC XSD-u u posebnom CI workflowu.
- Ulazni eRačuni: lokalni uvoz ograničenog podskupa UBL XML u EUR, provjera OIB primatelja, deduplikacija, šifrirani original, lokalna obrada, statusni pokazatelji i filtriranje prema referenci 07; uključeno u backup. Bez potvrde CIUS ili mrežne razmjene.
- Katalog proizvoda i usluga prema referenci 08: kategorije, pretraga, sortiranje, PDV filtar, kompaktni popis, izbornik stavke i jedinice mjere u katalogu, nacrtu i POS-u. Vizualna verifikacija još nije završena.
- Backup sadržaj v3 uključuje kanonske Room dokumente i outbox; transakcijski restore u objedinjenu bazu, SENDING → UNCERTAIN, test migracije i nastavka broja.
- Objedinjena fizička SQLite/Room baza v6, migracija DEMO dokumenata i prethodnog outboxa, Room autoritet numeriranja na aktivnom DEMO zaslonu.
- Offline XSD validator s provjerenim lokalnim SHA-256 manifestom i blokiranjem vanjskih ovisnosti; sintetički testovi, službeni ugovori još nisu uvezeni.
- Room DAO atomska lokalna alokacija broja, dokumenta i outboxa s migracijskim pragom, idempotencijom i testovima rollbacka. Aktivni cutover nije uključen.
- Prva Room integracija: lokalna priprema DEMO dokumenta i trajni outbox, transakcijski upis, jedinstveni identitet, CAS claim, UNCERTAIN oporavak i nepromjenjivost; bez mrežnog slanja.
- Nedestruktivna SQLite migracija v4→v5: zabrana INSERT OR REPLACE nad nepromjenjivim dnevnicima; broj i godina novog DEMO računa te novo stanje zaliha i vrijeme dio su autentificiranog šifriranog zapisa.
- Backup sadržaj v2: DEMO računi i sekvence, stanje i kretanje zaliha, CIS testni dnevnik, provjera kontinuiteta brojeva i salda, kompatibilnost uvoza v1.
- Prijenosni šifrirani backup radnog prostora (nacrti, katalog, profil i postavke); vraćanje samo u prazan radni prostor, bez izvoza certifikata i dnevnika. AES-256-GCM i PBKDF2-HMAC-SHA256, Android 8+, unit testovi.
- Kontrolirana ručna objava tehničkog testnog izdanja samo s glavne grane nakon prolaska CI-ja i provjere obveznih priloga, originalnog brendinga i snimki.
- Serverski CLI za nedestruktivne, idempotentne SQL migracije s evidencijom SHA-256, zaključavanjem i integracijskim testovima.
- CycloneDX 1.6 SBOM iz razriješenih Android runtime ovisnosti za debug, demo i release; uključen u kontrolne otiske kandidata.
- Zasebne akcije otvaranja i preuzimanja javnih cjenika, stabilne poveznice na prikazanu verziju.
- Stroga validacija parametara preuzimanja i JSON Content-Type; regresijski testovi.
- Automatizirano pakiranje izvornog koda, weba, servera, baze, brendinga i stvarnih snimki Android emulatora. Paketi kandidata ne objavljuju se automatski kao izdanje dok nisu provjereni svi obvezni prilozi.
- Evidencija SHA-256 dostavljenih vizualnih referenci. Originalni PNG još nisu u repozitoriju.

## 0.1.0 — 27.11.2026.
- Testni APK, DEMO APK, nepotpisani AAB, izvorni kod i snimke emulatora.
- Fiskalizacija i eRačuni nisu verificirani za stvarno poslovanje.
