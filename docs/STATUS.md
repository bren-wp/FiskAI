# Alpha04 acceptance status

Implemented in source: draft and directory CRUD, PDF export, local company profile, OIB and Croatian IBAN checksum validation, AES-256-GCM record encryption with Android Keystore and legacy migration. SDK 36 compilation and target configured because runner could not resolve platforms;android-37. No dependencies downgraded in this change.

Still required: successful CI and device tests, migration and tamper instrumentation tests, background write handling and transactional database (Room), recoverable encrypted backup, company premises/devices/operators, regulatory integration, actual screenshots and pixel comparison. PDF export produces unencrypted documents at user-selected destination. No issued invoices, fiscalization, signed XML, eInvoice transport, server or release yet. Do not enter real taxpayer data until acceptance.
