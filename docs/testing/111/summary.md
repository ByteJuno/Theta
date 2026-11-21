# Issue 111 — bounded chat diagnostics

Implementation commit: `6c35b72d370f227ed4b1b05a21cba86027dd8814`. Base: `763da3f6d62a06aa0ec8361298653b57f44b9536`.
The following documentation commit changes only this summary; all 346 tracked non-document source digests match the tested tree. Debug APK SHA-256: `49fa464d0d63815903639579f64243ff9bbeda7e0cf702eabfc3b6310e8fb34b`.

## Delivered behavior

Failed and paused chat results now offer a collapsed **Diagnostic details** section below the result notice in the scrollable message area. The selectable bilingual summary reports state, recognized reason/model error code, steps, retries and the server-execution-unknown flag. It hides while loading/running and after success. Expansion is scoped to the session, accepted input and result, and is not saved across recreation. Existing recovery-detail reasons pass through the same sanitizer.

Reasons over 512 UTF-16 code units are discarded before scanning or copying. Shorter reasons are matched to fixed resource text and exact `ModelErrorCode` values; arbitrary bodies, headers, exception suffixes and checkpoint IDs are never echoed by the diagnostic formatter. Unknown reasons receive an explicit privacy-omission label. This deliberately avoids relying on regex token detection: secrets need not use a known prefix. Output remains bounded even for a 2-million-character input; test summaries in both locales remain below 600 characters. A false unknown flag is labelled “not reported”, not proof of absent remote effects.

`RunResult` currently retains the model error category but not HTTP status or rejected parameter names. This UI displays the available category; it does not invent or recover absent fields from raw logs. No run/storage schema, credentials, retry policy, approval or execution ownership changes.

## Verification

Final command (strict configuration cache): `run-validation.py issue111-4b6decd9-final :app:testDebugUnitTest --tests '*ChatDiagnosticsTest' --tests '*ChatSessionOwnerTest.diagnosticsFollowFailureColdOpenSessionSwitchAndLaterSuccess' --tests '*ChatRecoveryTest' :app:assembleDebug :app:lintDebug`.

- **9 tests / 9 passed / 0 failures / 0 errors / 0 skipped**, in one final invocation; 48.22 seconds, configuration cache reused. Five formatter/projection cases include every model error code, both locales, bounded output and hostile key/header/body/stack inputs. Three existing recovery cases pass.
- One real SQLite/owner regression covers failure, cold reopen without a new request, switching to empty and failed sessions, hiding diagnostics during a blocked new request, later successful completion and reselecting the session. Provider responses are synthetic; this is not real-provider acceptance.
- Debug APK built; bilingual Kotlin/XML guard passed for 369 keys. Lint: **0 errors / 28 warnings**.
- Existing API33 direct-JDK scanner: **392 agent-core classes, 95 JDK classes, 246 members, 0 violations**. Compiler-linkage exclusions bound to this exact APK and compiled-class hashes. The agent-core sources are unchanged; this is neither API33 device execution nor whole-app certification. App API checks are included in Lint.
- Initial failures preserved outside Git: the UI guard rejected protocol literals colocated with a composable; pure conversion was separated without weakening the guard. The next run exposed checkpoint-delimiter normalization returning the omission label; the mapping was corrected with the original assertion retained. Final checks pass.

Raw logs, XML, source/class manifests and APK are under `event-runs/4b6decd9-6ca2-4bbf-87ce-3cd8f6f7dadf` and `validation-issue11/issue111-4b6decd9-*`, outside Git. No test deadline or assertion was weakened. No protected/build/workflow file changed.

## Remaining acceptance

**Visual verification was not performed.** The android-lab README and process inventory provide no available dedicated writer rendering lease; no phone or shared emulator was operated. Independent review should inspect final-APK expansion/collapse and text selection/copy, failure/pause/unknown states, success and session changes, narrow screens/large font, both languages and light/dark themes. Resource and JVM tests are not rendered UI evidence. The section uses existing Material typography, a minimum 48 dp toggle and scrolling content.

Real endpoint compatibility, API33 execution, Release/R8, final-head CI and prescribed emulator regression remain pending. The independent reviewer owns final acceptance and merge.

Compose state reference: AndroidX `Composables.kt` (`remember` overloads compare keys and recreate values when keys change), inspected from https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composables.kt; local reference SHA-256 `6eb495f178bc0214af2d7fa3328c693a41abf442083e0e98a3780f1f813a8b07`. Applied to per-session/result expansion only.
