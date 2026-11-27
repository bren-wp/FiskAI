# Google Play acceptance — 2026-11-27

Configured: compileSdk 37.1, targetSdk 36, minSdk 24, AGP 9.5.1, Gradle 9.9.2, Kotlin/Compose compiler 2.4.21, Compose BOM 2026.11.01, Activity 1.13.0. JUnit 4.13.2 retained for the existing JUnit 4 tests. Built-in Kotlin migration. No unused material-icons dependency. Weekly dependency proposals via Dependabot.

Verification required: dependency resolution, compilation, lint, test, API 24/36/37 device tests, edge-to-edge, 16 KB page size (including any transitive native libraries), signed AAB and Play Console pre-launch report. No claim of Play acceptance until verified. Account deletion, data safety, privacy policy, applicable billing requirements, store disclosures and permissions must reflect actual production features. No broad storage permissions; PDF uses Storage Access Framework. Alpha drafts are not legal invoices.

Official references:
- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/build/releases/agp-9-5-0-release-notes
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- https://developer.android.com/guide/practices/page-sizes
- https://kotlinlang.org/docs/releases.html
- https://docs.gradle.org/9.9.2/release-notes.html
