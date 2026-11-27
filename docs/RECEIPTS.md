# Evidencija naplate, bankovni izvodi i radni KPR

Room v8, tablice bank_transaction i receipt_event, pojedinačno AES-GCM šifrirani sadržaj, stabilan identitet po OIB-u, računu i referenci. Jedinstvena veza bankovnog primitka i transakcije. SQL zabrana izmjene, brisanja i REPLACE. Migracija v7→v8 dodaje tablice bez brisanja starih podataka.

CSV je izričito ograničen na UTF-8 i točno zaglavlje id;datum;iban;iznos;valuta;opis, bez navodnika i višerednih polja. Datum ISO, EUR, iznos s decimalnom točkom ili zarezom. IBAN mora odgovarati poslovnom subjektu. Identitet koristi stabilni ID transakcije koji mora biti jednak u ponovljenim izvodima. Konfliktni duplikat odbija cijeli uvoz, identični se preskaču. Odljevi ne mogu postati primici. Pozitivni priljev korisnik mora izričito klasificirati; gotovina se evidentira uz jedinstvenu oznaku dokaza. Ne postoji automatska veza s nacrtom, DEMO računom, JIR-om ili platnim kodom.

Radni KPR prikazuje potvrđene primitke po datumu i vrsti uplate; nije službeni KPR, PO-SD ni porezni izračun. Storno/korekcije, plaćanje karticama, uparivanje s izdanim dokumentima, djelomične uplate, camt.053 i bankovno-specifični formati nisu još podržani. Ne koristiti kao jedinu zakonsku evidenciju. Backup uključuje obje zbirke, restore provjerava identitete, veze i prazan cilj. Starije kopije nemaju ove zbirke i učitavaju se s praznim zbirkama.
