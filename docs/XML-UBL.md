# XML and UBL — test acceptance

B2C v2.7 chapter 7 (pages 108–110): request signature uses exclusive C14N, enveloped transform and SHA-256 digest / RSA-SHA256. PHP CLI-only signing foundation uses DOM and OpenSSL, rejects DTD, nested/duplicate IDs, preexisting signatures and invalid/mismatched RSA credentials. CI generates a self-signed fixture and independently verifies its signature with xmlsec1, then rejects a modified amount. Fixture deliberately does not pretend to be a schema-valid invoice. X509IssuerSerial, WSDL/XSD validation, authorized DEMO trust/OIB/revocation, actual ZKI insertion, signed response verification (inclusive C14N), correlation and JIR storage still require implementation/acceptance. No public signing endpoint or private-key storage added.

Android: product editor can query a user-entered HTTPS api.php KPD endpoint, with platform TLS trust, no redirects and capped JSON. The returned source metadata is informational; the merchant must use a trusted backend and verify the classification. No API credentials, automatic sync, or live hosted server provisioned.

UBL export: local Invoice 2.1 structural test draft with KPD listID CG, explicit units, standard positive VAT rates only, group reconciliation and amounts. Not a compliant issued eInvoice: buyer address, endpoint IDs, HR-CIUS profile, extensions, official XSD/Schematron, payment terms and receipt transport still pending. No production issuance or provider transmission. Old drafts remain readable; units default absent.

Sources:
- https://porezna.gov.hr/fiskalizacija/gotovinski-racuni/tehnicki-podaci/o/tehnicki-podaci
- https://porezna.gov.hr/fiskalizacija/bezgotovinski-racuni/eracun
- https://porezna.gov.hr/fiskalizacija/api/dokumenti/149
