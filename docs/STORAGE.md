# Local storage v1

The app now uses SQLiteOpenHelper as an intermediate transactional storage layer, not Room yet. Each record is encrypted with existing AES-256-GCM and Android Keystore before persistence. The database structure, keys/namespaces and payload lengths are not encrypted; exported PDFs are not encrypted.

A migration marker and encrypted record are committed together; legacy SharedPreferences values are removed only after commit. If interrupted after commit, a retry ignores the stale legacy copy. Invalid legacy or tampered ciphertext causes a read failure, and writes refuse to replace it. Migration is per record, not a single transaction across all business modules. Downgrading to previous versions is unsupported. No destructive schema fallback. Existing alpha records are JSON payloads; normalized entities, SQL foreign keys, Room integration and multi-document atomic transactions remain pending.

Instrumented tests cover plaintext migration, stale records, malformed records, authenticated decryption failure and retry. Run on a test device: gradle :app:connectedDebugAndroidTest. Unit-test/build CI does not execute these tests. Backup/restore and device-loss recovery remain mandatory before production.
