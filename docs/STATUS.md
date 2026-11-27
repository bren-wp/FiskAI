# Alpha06 acceptance status

Added local PKCS#12 credential inspection: bounded file input, password handling, RSA key size and key/certificate match challenge, X.509 metadata, date validity and SHA-256 fingerprint. No private-key or password storage; no activation, no trust/issuer authorization or revocation checking yet. No production certificates should be used before acceptance.

CI run 10 installed API 37.1 successfully but failed resolving Kotlin Compose plugin marker 2.4.21. Map the plugin ID directly to the versioned Maven Central compose-compiler-gradle-plugin module; keep stable versions and fail on resolution errors. New CI pending.

Remaining: actual device tests, real test certificate fixtures, trust and OIB validation, certificate vault, Room transactional storage, test CIS and eInvoice integrations, backup, web, real branding assets and visual acceptance. No production issuance or stable release.
