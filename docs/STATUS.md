# Alpha10 acceptance status

Added local, explicitly non-fiscal DEMO receipt ledger with atomic sequence/snapshot commit, unique draft identity, immutable update/delete triggers, encrypted payload, schema v3 migration, and Croatian Compose screen. Scope is per-device per-year per-premise/register, not a production numbering design. Added standalone ZKI RSA-SHA256/MD5 implementation with unit tests and a ledger device test. No production issuance or real fiscal response handling.

Alpha08 CI run 16 succeeded. Alpha09 run 18 was in progress at last check. Alpha10 CI and instrumented tests not yet verified.

Official v2.7 specification and EDUC v1.10 archive identified; official ZIP retrieval was unsupported by web reader. No fabricated WSDL/XSD. See ACCEPTANCE.md for full remaining gates including F2, Room, backup, backend/web, visual and security audits, and release.