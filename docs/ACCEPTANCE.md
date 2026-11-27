# Acceptance gates — alpha10

- Transactional numbering: DEMO only, device-scoped annual sequence per premise/register, unique draft, atomic counter and immutable encrypted snapshot. Production numbering rules, multi-device allocation and independent audit still pending.
- Official contracts: B2C v2.7 and EDUC v1.10 listed at https://porezna.gov.hr/fiskalizacija/gotovinski-racuni/tehnicki-podaci/o/tehnicki-podaci . Official archive URL https://porezna-uprava.gov.hr/UserDocsImages/Fiskalizacija/Tehni%C4%8Dke%20specifikacije/Fiskalizacija-WSDL-EDUC_v1.10.zip . Archive could not be retrieved by the web reader. Do not invent schemas. Obtain and pin checksums before validation.
- ZKI: RSA-SHA256 then MD5 representation per chapter 12; standalone primitive with generated-key unit tests, not connected to issuance. XMLDSig and signed JIR validation pending.
- Certificate trust, authorization and DEMO submission: pending authorized credential and official contract. No production transport.
- F2/UBL/eReporting: official docs https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/eracun and https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/fiskalizacija-bezgotovinskih-racuna ; validation, provider and reporting pending.
- Room normalization, recoverable backup, backend API/MySQL/MariaDB, public site and web app: pending.
- Original branding assets and visual regression, full security/dead-code/semantic-clone audits: pending.
- APK/AAB: previous alpha08 CI successful; current commit requires build and instrumentation. Stable release blocked.
