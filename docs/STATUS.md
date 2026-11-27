# Alpha05 acceptance status

Source additions: local fiscal setup, premises, registers and operators; reference integrity and duplicate checks; encrypted persistence; local create/list/delete screens and domain tests. These settings are NOT registered with the tax authority and are not tied to issued invoice sequences yet. Validation is intentionally a conservative local subset, not legal certification.

CI run 8 reached Gradle after SDK setup succeeded; AAR metadata requires compile SDK 37.1. Correct package platforms;android-37.1 and AGP minorApiLevel DSL now configured. targetSdk remains 36. No downgrade or AAR check bypass. Pending build verification.

Remaining: Room transactional DB, migrations and device tests, edit/retire workflow, certificates, test CIS and eInvoice integrations, backup, web, real branding assets and visual acceptance. No production issuance or stable release.
