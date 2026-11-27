# Third-party review

Candidates only; no source code copied into this initial implementation:
- https://github.com/NathanelMa/InvoiceApp — MIT, Android invoices and PDF.
- https://github.com/l-d-t/fiskalhrgo — MIT, CIS B2C; not full Fiskalizacija 2.0.
- https://github.com/sanket1035/GSTbillingApp — MIT, Android Compose patterns; Indian tax logic not applicable.
- https://github.com/youcef-debbah/Reporter — GPL-3.0; do not copy into an MIT-only distribution without license review.

Pin source versions, preserve notices, verify dependency licenses and run security checks before integration.

## Integrated dependency
ZXing core 3.5.4 — Apache-2.0, https://github.com/zxing/zxing . No copied source files; used as a Maven library for QR and PDF417 encoding/decoding. License information is also included in the distributable library metadata; retain upstream notices in distribution. See https://github.com/zxing/zxing/blob/master/LICENSE .
