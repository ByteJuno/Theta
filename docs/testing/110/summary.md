# Issue 110 — Saved provider settings and compatibility controls

Writer implementation: `661955d17858501514a67851499ae88d699fa55a`, based on main
`029adaf75c8118e27a33cbe6aed6057f3b587b0b`. Initial PR head
`d86d29afb3e2c6422105528ba8ba903c3e8e12ec` changes only this summary; its 350 tracked
non-document source digests match the writer's tested source manifest. The
reviewer recovery fix below changes the editor and has separate targeted results.

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

## Writer checks (before reviewer recovery fix)

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
- Writer Debug APK SHA-256 (does not include the reviewer recovery fix):
  `70e23c0a79e2ca39c817d8c4889720e378fca5923c48487805fd0f39397ea0e0`.

Initial resource-guard failures (protocol literals in a composable file) and a
JUnit initialization failure (non-Unit coroutine test return) were fixed without
removing assertions or extending timeouts. Raw attempts are preserved outside
Git under `validation-issue11/issue110-9ea8b30c-{initial,r2,r3,r4,final}`.
Source manifests, API report and both APK identities are retained in
`event-runs/9ea8b30c-94db-4fbf-9fc7-dc4bc368d448`; only this summary is committed.

## Remaining acceptance

**Visual and device behavior are unverified.** No dedicated writer rendering
lease was confirmed; no phone or shared emulator was operated. Final APK
acceptance still includes the open/load/edit/save/reopen path, profile
switch during load/discovery, empty/error/retry/re-entry states, changed-endpoint prompt,
advanced options and keyboard scrolling, Chinese/English, narrow screens/large
fonts and light/dark themes. Real endpoints, Android Keystore on-device, API33
execution, Release/R8 and physical power interruption were not tested locally.
Final-head CI and prescribed emulator regression remain outer-script gates; the
writer did not merge.

Profile lifetime follows the inspected AndroidX
[LaunchedEffect/rememberCoroutineScope implementation](https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Effects.kt):
leaving keyed composition cancels the old effects/scope. This is source evidence,
not a rendered interaction result.

## Reviewer recovery fix

An uncommitted `ProviderRecoveryProbe` confirmed a recovery dead end after the
UI's existing load/error transitions were moved unchanged into
`ProviderSettingsEditor`: truncated ciphertext, invalid decrypted payloads and
missing encryption keys each kept both profiles uneditable after three retries.
The six combinations preserved disk bytes, and explicit replacement recovered
each one. The original dialog had no way to perform that replacement.

The dialog now offers an explicit bilingual re-entry action alongside retry.
Choosing it creates a blank form with no saved-key reuse and performs no storage
or key access. The user must enter the endpoint, model and a new key and press
Save; until a successful replacement, the previous disk record is preserved.
Transient failures can still recover through retry. This restores the existing
replacement capability; it does not change Keystore alias handling.

Reviewer GPU command:
`:app:testDebugUnitTest --tests '*ProviderSettingsEditorTest*' --tests '*ProviderSettingsFormTest*' --tests '*ProviderRecoveryProbe*'`
returned `GRADLE_EXIT=0`, `TESTS total=6 failed=0`: two editor regression tests
(including the six recovery combinations), three form tests and one uncommitted
probe. Regression assertions cover no key access or disk writes on re-entry,
mandatory new keys, failed-write preservation, cold reopen with the replacement
key and successful retry after a temporary failure. No assertions or timeouts
were weakened. Store/client code is unchanged from the writer's tested source.
Only this summary is committed; the probe and raw output are not Git artifacts.
The reviewer did not rebuild an APK or claim rendered UI validation; final-source
CI and emulator acceptance must use an artifact containing this fix.
