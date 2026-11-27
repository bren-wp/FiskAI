# Alpha03 acceptance status

Implemented: local directory CRUD, catalog CRUD, duplicate OIB/code checks, draft selection, PDF draft export through SAF, updated stable Android toolchain, dependency update configuration.

Previous CI failure: sdkmanager legacy tools package. Updated SDK setup action and explicit package selection.

Not yet verified: new Android compilation, device layout, persistent storage integration tests, PDF output on device, 16 KB compatibility and pixel matching.

Not production-ready: plaintext app-private preferences; no issued invoices, fiscalization, signed XML, eInvoice transport, backup or server sync. No actual user certificates or external source code imported. Keep real taxpayer data out of alpha.
