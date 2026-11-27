# v0.1.0 — verification pending

Public web in web/, server SQL/API/CLI/tests in server/, vector assets in brending/. Separate DEMO app ID, debug-signed APKs, unsigned release AAB, source and screenshot release workflow gated by Android/device and web jobs; release is a prerelease, not production stable. New transactional local inventory (SQLite schema 4) and POS draft cart. Stock quantities, product IDs and timestamps are database metadata; movement descriptions/reasons are AES-GCM encrypted. No automatic fiscal stock settlement, payment collection or production receipt. MySQL and MariaDB CI matrix added. New tests not yet verified.

Remaining gates: authorized live fiscal receipt, WSDL/XSD and JIR validation, provider/eInvoice/eReporting, normalized Room, recovery backup, HUB3/EPC, KPR/PO-SD, automatic price publication, full administration, original PNG assets and visual/security/dead code/semantic clone audits. No claim of production stability.
