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

Accounting overview: local authenticated receipt and incoming-invoice projection, explicit period and business scope, exact cents and duplicate bank-link rejection. Excludes rejected invoices from document totals; incoming totals are not paid expenses. Not statutory books, a tax return or legal acceptance. Unit tests cover scope and double counting.

Single-merchant web administration: authenticated HTTPS screen for current price versions and explicit JSON publication; no default credentials, CSRF-protected session, existing transactional publication and no deletion. Full browser E2E and deployment acceptance still required.

Inventory: before reading balances and every movement, reconstruct the full local stock journal using authenticated payloads and exact arithmetic in the same SQL transaction. Reject altered balance metadata and inconsistent events instead of recording further movements. Retain the existing 20,000-event backup capacity as explicit operational bound; large-ledger performance and external rollback protection remain open. No schema change, no auto stock settlement for unissued POS drafts.
