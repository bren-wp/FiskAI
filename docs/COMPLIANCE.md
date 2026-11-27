# Regulatory acceptance gates

- B2C: official CIS v2.7, WSDL/XSD, real certificate and test endpoint, signed XML, response validation, unique issuance, offline recovery.
- eRačuni: UBL 2.1, Croatian EN 16931 extensions, six-digit KPD, official validation, provider sandbox and authorization.
- eIzvještavanje: payment, rejection and supply when eRačun could not be issued. Separate from KPR and PO-SD.
- KPR is Knjiga prometa, not a profit-and-loss calculation. PO-SD is for applicable flat-rate income tax.
- Do not display JIR/ZKI as eRačun fiscalization confirmation. Keep transport, fiscal and payment status distinct.
- No production issuance until legal and technical acceptance is complete.

Official documentation:
https://porezna.gov.hr/fiskalizacija/gotovinski-racuni/tehnicki-podaci/o/tehnicki-podaci
https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/eracun
https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/fiskalizacija-bezgotovinskih-racuna
