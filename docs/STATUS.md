# Alpha07 acceptance status

CI run 12: SDK setup successful; configuration failed with Required value was null. The previous resolution rule incorrectly required a version on all Compose plugin requests, including the versionless app request inherited from root. Now only explicit version requests are mapped; stack traces enabled for build diagnosis.

New source: optional encrypted local PKCS#12 test credential copy, password re-entry for inspection, fingerprint/expiry display, explicit replacement and logical deletion. The test label is a user instruction, not verified by certificate policy. No password saved; no trust/issuer/OIB/revocation verification, no production activation, no transport, no hardware-backed guarantee. No secure physical erasure guarantee. Keep original test certificate separately. Do not use production certificates yet.

Pending: CI, instrumentation tests for vault, certificate fixtures, transactional database, device testing, real regulatory integration, backups, branding and web. No production issuance or stable release.
