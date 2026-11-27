# FiskAI

**Računi, ponude i poslovanje na jednom mjestu.**

FiskAI je hrvatska poslovna platforma u razvoju. Nativna Android aplikacija omogućuje lokalno sastavljanje nacrta računa i ponuda, lokalni imenik kupaca i dobavljača te katalog proizvoda i usluga te pregled poslovnih dokumenata.

PDF nacrte moguće je izvesti u odabranu mapu uređaja. Lokalni zapisi šifrirani su putem Android Keystorea. Postavke poslovnog subjekta, poslovnih prostora, naplatnih uređaja i operatera spremaju se na uređaju.

Lokalna provjera PKCS#12 certifikata (.p12/.pfx) prikazuje valjanost, izdavatelja i otisak. Opcionalni lokalni sef testnih certifikata sprema šifriranu PKCS#12 kopiju; lozinka se nikada ne sprema. Sef nije produkcijski fiskalni potpisnik.

## Dostupnost

Trenutačno: razvojna verzija. Nacrti nisu izdani porezni dokumenti. Fiskalizacija, eRačuni, eIzvještavanje i sinkronizacija još nisu aktivirani.

## Izgradnja

Android Studio ili Gradle 9.9.2, JDK 17 i Android SDK 37.1. Iz korijena: gradle :app:testDebugUnitTest :app:assembleDebug :app:bundleDebug.

MIT — vidi LICENSE.
