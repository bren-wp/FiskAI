# Promjene

## 0.1.1 — kandidat (nije objavljeno)
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
