# FiskAI F2 supplied-contract diagnostic acceptance

Reviewed on 2026-11-27. The four ZIP files were supplied by the project owner. Their hashes identify the received bytes, not independent official approval. None of the following tests sends a fiscalization request or authorizes dispatch.

## Input filenames

Copy the supplied archives without recompressing or modifying their bytes into a local directory using these names:

| Supplied archive | Diagnostic filename | SHA-256 |
| --- | --- | --- |
| HRUBLSchematron_13032026-2.zip | validator-2026-03-13.zip | d6250b00b1cd385c835e3dd4c3abc71a683e9b890f04f41c0ae1e04f3c3e790c |
| UBL2.1 eRačun.zip | ubl-2026-04-15.zip | 549ced211d2b463db0e5821ed9e604580140aa1870ee8636d207206d872c8388 |
| eFiskalizacijaSchema.zip | efiskalizacija-2026-02-12.zip | 7ca59ff821069acee929fc53f9872670206b831d034f70c27960cb1d2346eebd |
| eIzvjestavanjeSchema.zip | eizvjestavanje-2026-02-12.zip | a559b9ef7dc413ada4b81cc6ce56030f1efc1e0abf8e7f2712d834080ae79103 |

Original bytes are not committed by this change. Retain them independently. The acquisition workflow compares the downloaded originals to these diagnostic pins and retains unreviewed candidates before diagnostics. A mismatch fails diagnostics rather than silently accepting a new release. Independent eight-file signed acceptance remains separate.

## Real local checks

Python/lxml 6.1.1 (libxml2 2.14.6) successfully compiled both complete original UBL 2.1 Invoice/CreditNote trees. Minimal synthetic positive documents passed XSD; documents missing ID failed. Malformed input, DOCTYPE, changed archive and outside schema reference were rejected. Both supplied service XSDs compiled diagnostically using an explicit alias of the UBL XMLDSig schema (SHA-256 92fcf626208d0641846fea1e65c49e4e756cba6c61c77841da3492d30bc2b0cb). This is not an approval of that dependency for the service contracts. Both WSDL SOAP address locations are empty; no endpoints may be inferred.

- eFiskalizacija: EvidentirajERacun.
- eIzvjestavanje: EvidentirajOdbijanje, EvidentirajNaplatu, EvidentirajIsporukuZaKojuNijeIzdanERacun, OvlastenjaFiskalizacije.

The supplied HR-BR-5 rule specifies the literal CustomizationID ending in urn:mfin.gov.hr:ext-2025:1.0; the previous incorrect ext[1]2025 spelling has been corrected in PHP preflight and its regression fixture. That PHP test has not yet been executed against a DOM/SQL-enabled runtime. The existing PHP flat XSD loader cannot consume the complete original nested UBL tree directly; the new Python adapter is a separate offline diagnostic, not yet integrated with F2EducIntake.

Run:

```sh
python3 scripts/test-f2-uploaded.py /path/to/archives
python3 scripts/validate-f2-uploaded.py /path/to/archives
python3 scripts/validate-f2-uploaded.py /path/to/archives --document /path/to/synthetic.xml
python3 scripts/prepare-f2-hr-schematron.py /path/to/archives /path/to/hr-expanded.sch
```

Actual HR source expansion was executed locally: one code-list include, 71 original assertions plus two code-list assertions, custom XSLT functions retained, XSLT 2 binding. The expanded source is not compiled XSLT and not an execution result.

## Executable Schematron adapter — still requires verification

The embedded HR instructions require EN16931 first, then Croatian CIUS/extension rules. The separately published EN16931 v1.3.17 source is https://github.com/ConnectingEurope/eInvoicing-EN16931/releases/tag/validation-1.3.17, EUPL-1.2. The release commit candidate is 6e35e0ab8b09eb183f7d84b282e24ef95f9f5715 and its fetched UBL XSLT blob is e2849ece2a6b9d5cdd163f2e7a89a6dc3d43dc8a. A fetch via the tag returned a different blob (16298a68dde999dde512cf514b79c839b1ac8040); do not treat these as equivalent. CI acquisition checks the immutable commit, exact file and license blob; independent review of actual downloaded bytes and SHA-256 is mandatory. It does not execute the candidate.

The local environment lacks SaxonC and cannot download external dependencies (DNS). No actual EN or HR transform has been executed. The optional compile path accepts independently reviewed ISO XSLT2 skeleton files iso_svrl_for_xslt2.xsl and iso_schematron_skeleton_for_saxon.xsl and a JSON map of their exact filenames to approved SHA-256 values. Compile in an isolated offline environment with no secrets and resource/time limits; compilation permits file access for the reviewed import, so the operator must enforce OS isolation. The output is an unreviewed compiled candidate. Example:

```sh
python3 scripts/prepare-f2-hr-schematron.py /path/to/archives /path/to/hr-candidate.xsl --compiler-dir /path/to/compiler --compiler-pins /path/to/approved-compiler-pins.json
python3 scripts/run-f2-schematron.py /path/to/archives /path/to/synthetic.xml /path/to/EN16931-UBL-validation.xslt APPROVED_EN_SHA256 /path/to/hr-reviewed.xsl APPROVED_HR_SHA256 /path/to/new-report-directory
```

The runner uses SaxonC, disables external protocols/functions, checks compiled stylesheet pins and the EN commit blob, executes both stylesheets on the XSD-validated document and separately inventories findings. This runtime path requires independent review and E2E negative tests before any use beyond synthetic offline diagnostics. The HR compiled XSLT must be independently reviewed against the expanded HR source; the runner does not prove that relationship. The report directory contains document-derived SVRL; use synthetic documents and do not publish personal/business data. No result grants external authorization, updates F2 outbox state or substitutes for independently signed contract approval.

## Remaining acceptance

- Complete CI on the current commit.
- Match actual official package bytes and review the service XMLDSig dependency.
- Review EN source/compiled artifact, SaxonC distribution, compiler pins and HR compiled output.
- Execute both Schematron layers with real approved positive and negative test cases, including KPD 2027 effective rules.
- Integrate reviewed XSD and dual SVRL evidence into the durable F2 intake; implement authorized eReporting and AS4/AMS/MPS only after verified contracts.
- All existing security gates, release assets, UI/QA and the 31-part project acceptance remain mandatory. No production dispatch or release is approved by this diagnostic work.


## Actual dual execution — 2026-11-27

Run 46039705497, artifact 15218883233 (downloaded and inspected): actual SaxonC 12.9.0 compiled the Croatian rules and generated both SVRL reports. All three incomplete/negative diagnostic cases executed; the wrong customization ID produced HR-BR-5 and the invalid KPD produced HR-BR-CL-2. The complete synthetic invoice in scripts/fixtures/f2-positive-invoice.xml was independently XSD validated locally (SHA-256 7cc28d63faa1cf20d27658ad12c76b77d9af1c519a06161e514506f235dfe25f). Its actual HR SVRL has zero findings; EN SVRL has exactly two warnings, no fatal findings: UBL-CR-006 for IssueTime and UBL-CR-200 for SellerContact. HR-BR-2, HR-BR-37 and HR-BR-9 require these fields. They must not be deleted to force an artificial clean report. The initial CI intentionally failed the strict zero-findings criterion. The subsequent diagnostic regression requires the exact two warnings and zero HR findings, retaining both raw SVRL files, plus a monetary negative fixture requiring BR-CO-16. This is diagnostic acceptance only, not a waiver or legal approval. The next actual CI remains to be verified.

F2 download run 46039078490 failed with curl exit 56 (connection reset by peer); added bounded retries to public GET only, without modifying TLS verification or archive pins. Subsequent run 46039705497 successfully acquired contracts and generated SVRL. No retries for fiscal requests are introduced.

### Dual review-only intake protocol

V2 signature covers the ASCII domain FISKAI-F2-DUAL-VALIDATION-V2, LF and exact JSON bytes with ordered keys: protocol=FISKAI-F2-DUAL-SVRL-V2, documentSha256, schemaManifestSha256, enManifestSha256, hrManifestSha256, enSvrlSha256, hrSvrlSha256. Distinct EN and HR manifest pins and reports required, one independently authorized P-256 signer. Existing V1 remains unchanged. V2 stores both raw reports atomically in the existing AES-GCM intake, with no change to migration 012 or historical rows. Zero findings remain SIGNED_DUAL_SVRL_REVIEWED; the two specifically identified EN warnings may be retained in SIGNED_DUAL_SVRL_REVIEW_REQUIRED, not treated as an unconditional pass. All other findings, duplicate warnings, unexpected flags, any HR findings, missing execution evidence and invalid signatures are rejected. Harmless XML comments in real SVRL are permitted for V2, DTD/entity declarations remain prohibited. The outbox remains REVIEW_REQUIRED and dispatchAuthorized=false in all cases. Configuration of hrValidatorManifestPin requires both reports; no fallback to V1 in this mode.

CLI: php server/bin/f2-intake.php DOCUMENT_UUID ISSUED EVENT_DATE UBL_XML EN_SVRL VALIDATION_JSON VALIDATION_DER HR_SVRL EDUC. Set validatorManifestPin for EN and hrValidatorManifestPin for HR in the independently reviewed operator configuration. The actual contract manifests, signer and nested UBL integration still require independent review. The SQL integration test uses synthetic signed reports; the new F2 CI step additionally exercises PHP signature/evidence checking over actual generated SVRL files with an ephemeral CI key and synthetic schema-manifest pin. It does not assert official approval, actual production signer identity, full real-XSD-to-SQL integration, or transport authorization. The current head's tests are not yet confirmed.
