# Ulazni eRačuni — lokalna obrada

Zaslon prema primarnoj Android referenci 07: ulazni/izlazni prikaz, stvarni pokazatelji, pretraživanje i statusni filtri, zbir iznosa, lokalni uvoz XML. Izlazni prikaz sadrži postojeće nacrte, ne poslane eRačune. Referenca je vizualni koncept, ne dokaz zakonske logike.

Prihvaća ograničeni podskup UBL Invoice u UTF-8 i EUR, hrvatski OIB dobavljača i primatelja, podudaranje OIB-a s aktivnim poslovnim subjektom, broj, datum, naziv, iznos iz LegalMonetaryTotal i barem jednu stavku. DTD i vanjski entiteti su zabranjeni. Najviše 512 KiB po dokumentu i 4 MiB ukupne JSON kolekcije, do 1000 dokumenata. Originalni bajtovi XML-a ostaju u šifriranoj kolekciji; SHA-256 i identitet dobavljač/primatelj/broj sprječavaju identične duplikate i odbijaju konfliktni sadržaj. Statusi su lokalni, ne pravno odbijanje ili potvrda posrednika. Nema automatskog knjiženja, plaćanja ili fiskalizacije.

Ovo nije EN16931/HR CIUS/Schematron validator, niti provjera XML potpisa ili poreznih izračuna. Potpuna normalizacija pojedinačnih računa u Room, kreditne note, više valuta, autorizirani posrednik, eIzvještavanje i vizualni regression test ostaju obvezni. Backup čuva šifriranu kolekciju; starije kopije bez nje se učitavaju s praznom kolekcijom.
