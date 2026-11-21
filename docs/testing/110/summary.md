# Issue 110 — Saved provider settings and compatibility controls

Implementation: `661955d17858501514a67851499ae88d699fa55a`, based on main
`029adaf75c8118e27a33cbe6aed6057f3b587b0b`. The subsequent documentation commit
changes only this summary; all 350 tracked non-document source digests match
the tested source manifest.

Provider settings now load non-secret endpoint/model/compatibility fields on IO.
The secret field stays empty and never receives the saved key. Missing settings
use defaults; read/decrypt failures preserve disk data, show an error and offer
reload. Profile-keyed composition cancels loads and model discovery and discards
the previous form. Explicit save closes the dialog after success.

Saved-key updates decrypt, check the exact saved endpoint, re-encrypt and replace
under the existing process-wide lock. Discovery checks the current record in IO,
then checks cancellation before starting the client. Only `endpoint.trim() ==
savedEndpoint` allows an absent new key; changed hosts, paths, schemes or trailing
slashes require a newly entered key. Invalid whitespace keys are rejected. No
key is returned by the settings-read, update or model-discovery API. Temporary
plaintext byte arrays are wiped on success/failure; immutable JVM strings cannot
be guaranteed erased.

Chat-only advanced controls include explicit cache control (off by default),
max-token field, stream usage, reasoning field and reasoning replay. Clearing
the reasoning field disables replay. Existing parameters and schema profile are
preserved. Model discovery remains explicit/read-only and manual entry remains
available. Both locales provide loading, error, saved-key and compatibility text.
Storage format, build/workflow/protected files and run-admission behavior are unchanged.

## Executed checks

- **92 applicable targeted cases passed**, no remaining failures/errors/skips:
  64 unchanged core cases (`OpenAiChatTest` 51, `OpenAiCredentialsTest` 5,
  `OpenAiModelsClientTest` 8) from r2; 8 unchanged model-state/form cases from r3;
  20 final-source `OpenAiSettingsStoreTest` cases. This is an aggregate of valid
  source-bound results, not one 92-case all-green invocation.
- Real encrypted cold reopen verifies model/compatibility/defaults/parameters
  and original key retention. Tests cover absent/corrupt/unreadable credentials,
  failed encryption preserving ciphertext, byte-array wiping, exact endpoint
  rejection, stale form/current-record rejection, and concurrent replacement.
- Real loopback HTTP through the production discovery client verifies
  `GET /v1/models`, distinct chat/image Bearer keys and unchanged disk records.
  Synthetic test keys only; no real provider credentials were accessed.
- Cancellation while credential IO is blocked starts no discovery call. Existing
  revision/cancellation tests reject late results after profile/endpoint changes.
- Debug APK, bilingual resource guard and Lint pass. Lint: **0 errors, 28 warnings,
  1 hint**. Strict `--configuration-cache --configuration-cache-problems=fail`;
  final storage/build/lint invocation reused cache and took **51.88 s**.
- Exact-APK-bound direct-JDK API33 scan passes: **401 classes, 0 violations**.
  This scan does not certify whole-app/API33 device execution.
- Final Debug APK SHA-256:
  `70e23c0a79e2ca39c817d8c4889720e378fca5923c48487805fd0f39397ea0e0`.

Initial resource-guard failures (protocol literals in a composable file) and a
JUnit initialization failure (non-Unit coroutine test return) were fixed without
removing assertions or extending timeouts. Raw attempts are preserved outside
Git under `validation-issue11/issue110-9ea8b30c-{initial,r2,r3,r4,final}`.
Source manifests, API report and both APK identities are retained in
`event-runs/9ea8b30c-94db-4fbf-9fc7-dc4bc368d448`; only this summary is committed.

## Remaining acceptance

**Visual and device behavior are unverified.** No dedicated writer rendering
lease was confirmed; no phone or shared emulator was operated. The independent
reviewer must inspect the final APK's open/load/edit/save/reopen path, profile
switch during load/discovery, empty/error/retry states, changed-endpoint prompt,
advanced options and keyboard scrolling, Chinese/English, narrow screens/large
fonts and light/dark themes. Real endpoints, Android Keystore on-device, API33
execution, Release/R8 and physical power interruption were not tested locally.
Final-head CI and prescribed emulator regression remain reviewer gates; the
writer did not merge.

Profile lifetime follows the inspected AndroidX
[LaunchedEffect/rememberCoroutineScope implementation](https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Effects.kt):
leaving keyed composition cancels the old effects/scope. This is source evidence,
not a rendered interaction result.
