# Acceptance gates — alpha10

- Transactional numbering: DEMO only, device-scoped annual sequence per premise/register, unique draft, atomic counter and immutable encrypted snapshot. Production numbering rules, multi-device allocation and independent audit still pending.
- Official contracts: B2C v2.7 and EDUC v1.10 listed at https://porezna.gov.hr/fiskalizacija/gotovinski-racuni/tehnicki-podaci/o/tehnicki-podaci . Official archive URL https://porezna-uprava.gov.hr/UserDocsImages/Fiskalizacija/Tehni%C4%8Dke%20specifikacije/Fiskalizacija-WSDL-EDUC_v1.10.zip . Archive could not be retrieved by the web reader. Do not invent schemas. Obtain and pin checksums before validation.
- ZKI: RSA-SHA256 then MD5 representation per chapter 12; standalone primitive with generated-key unit tests, not connected to issuance. XMLDSig and signed JIR validation pending.
- Certificate trust, authorization and DEMO submission: pending authorized credential and official contract. No production transport.
- F2/UBL/eReporting: official docs https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/eracun and https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/fiskalizacija-bezgotovinskih-racuna ; validation, provider and reporting pending.
- Room normalization, recoverable backup, backend API/MySQL/MariaDB, public site and web app: pending.
- Original branding assets and visual regression, full security/dead-code/semantic-clone audits: pending.
- APK/AAB: previous alpha08 CI successful; current commit requires build and instrumentation. Stable release blocked.

## Alpha12
PHP web and MySQL/MariaDB price publishing/archive foundation, KPD import/search and integration tests added. Deployment, real DZS import, Android synchronization, scheduler and legal verification still pending. No change to production fiscal lock. CI must pass before any release.

## Alpha13
Offline request XMLDSig foundation, independent xmlsec verification in CI, Android HTTPS KPD lookup and local UBL draft export. No new external dependency. Certificate authorization, official schema validation, signed response verification, DEMO receipt submission, F2 transport, complete HR-CIUS, actual backend deployment and on-device tests remain pending. Original brand assets and full audits remain open.

## v0.1.0 packaging
Android remains at app/; public site at web/public; backend, SQL and CLI at server/; vector brand at brending/. Separate demo applicationId and label; standard APK is debug-signed, release AAB unsigned. Release is explicitly a testing prerelease until production gates pass. Original PNG reference transfer, actual screenshots and remaining business/fiscal gates are not complete.

## Current v0.1.1 development gates

Regulatory history, separate Android ledger and historical restore now have concrete implementations with synthetic regression tests. Operator-pinned archive import, separate witness quorum and NN/Cellar discovery are implemented but require current CI results and independently configured real infrastructure. Android runtime OSV lookup is a release candidate gate; complete build/server dependency inventory, manual security review, dead-code/semantic-clone audits, full legal 2027 review and production acceptance remain open. No original reference PNG or actual web/admin screenshots are fabricated. All 19 phases remain required.
