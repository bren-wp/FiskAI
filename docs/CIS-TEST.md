# CIS test connectivity

Specification v2.7, section 2.4 (page 65):
https://porezna.gov.hr/fiskalizacija/gotovinski-racuni/tehnicki-podaci/o/tehnicki-podaci

Echo is a real test-endpoint SOAP 1.1 request, not invoice submission. Random UUID only, no taxpayer information. Fixed test URL, standard Android TLS certificate and host validation, no redirects, 15-second connect/read timeout, capped response, strict SOAP response namespace and exact token match. SOAPAction empty is provisional until confirmed against EDUC WSDL v1.10 and live service. No live result claimed yet. Do not weaken trust on TLS failure.

SQLite schema 2 adds diagnostic journal, explicit atomic claim and terminal state. Interrupted jobs become UNCERTAIN; no auto resend. Diagnostic data (random ID, time, state, result code) is not encrypted because no business payload is stored. Only 50 recent records displayed; older rows retained. No receipt-number allocation or actual invoice outbox yet. Production endpoint intentionally absent. Before test RacunZahtjev: official WSDL/XSD, signing, ZKI, trust and signed response checks, durable issuance/sequence and authorized DEMO credentials.
