# Issue 111 — bounded chat diagnostics

Validated merge implementation: `7d59f6fd82ba0fa037baa197611a3f3573b6bdf3`, integrating main `029adaf75c8118e27a33cbe6aed6057f3b587b0b` into the original diagnostic implementation.
The following documentation commit changes only this summary; all 350 tracked non-document source digests match the tested tree. Debug APK SHA-256: `a64166e18b8ccce1db4bf5c131b5f7b05f668a81acaf7cfd232ee65cbfbcab39`.

## Delivered behavior

Failed and paused chat results now offer a collapsed **Diagnostic details** section below the result notice in the scrollable message area. The selectable bilingual summary reports state, recognized reason/model error code, steps, retries and the server-execution-unknown flag. It hides while loading/running and after success. Expansion is scoped to the session, accepted input and result, and is not saved across recreation. Existing recovery-detail reasons pass through the same sanitizer.

Reasons over 512 UTF-16 code units are discarded before scanning or copying. Shorter reasons are matched to fixed resource text and exact `ModelErrorCode` values; arbitrary bodies, headers, exception suffixes and checkpoint IDs are never echoed by the diagnostic formatter. Unknown reasons receive an explicit privacy-omission label. This deliberately avoids relying on regex token detection: secrets need not use a known prefix. Output remains bounded even for a 2-million-character input; test summaries in both locales remain below 600 characters. A false unknown flag is labelled “not reported”, not proof of absent remote effects.

`RunResult` currently retains the model error category but not HTTP status or rejected parameter names. This UI displays the available category; it does not invent or recover absent fields from raw logs. No run/storage schema, credentials, retry policy, approval or execution ownership changes.

## Verification

Final command (strict configuration cache): `run-validation.py issue111-b155fe97-merge :app:testDebugUnitTest --tests '*ChatDiagnosticsTest' --tests '*ChatSessionOwnerTest.diagnosticsFollowFailureColdOpenSessionSwitchAndLaterSuccess' --tests '*ChatRecoveryTest' --tests '*ProviderModelsTest' :app:assembleDebug :app:lintDebug`.

- **13 tests / 13 passed / 0 failures / 0 errors / 0 skipped**, in one final invocation; 76.83 seconds, strict configuration cache stored. Five formatter/projection cases include every model error code, both locales, bounded output and hostile key/header/body/stack inputs. Three recovery and four provider model-list state cases pass.
- One real SQLite/owner regression covers failure, cold reopen without a new request, switching to empty and failed sessions, hiding diagnostics during a blocked new request, later successful completion and reselecting the session. Provider responses are synthetic; this is not real-provider acceptance.
- Debug APK built; bilingual Kotlin/XML guard passed. Lint: **0 errors / 28 warnings**.
- Existing API33 direct-JDK scanner: **401 agent-core classes, 97 JDK classes, 248 members, 0 violations**. Compiler-linkage exclusions bound to this exact APK and compiled-class hashes; this is neither API33 device execution nor whole-app certification. App API checks are included in Lint.
- The reviewed RETURN was a merge conflict in both string files. Resolution retains the union of both parents' resource names and exact values (365 string entries per locale, no duplicates). An exact source comparison confirms the original ChatScreen chat/diagnostic body and main's ProviderScreen body are preserved. No diagnostic logic or existing assertion was changed.
- Historical initial guard/mapping failures remain preserved outside Git. This round's first merge command rejected a missing committer identity before modifying the tree; the retry used the authorized command-local identity. Raw failure and conflict records are retained. All final checks pass.

Current raw logs, XML, source/class manifests and APK are under `event-runs/b155fe97-b2d1-46c1-8452-bd6174802ae8` and `validation-issue11/issue111-b155fe97-merge`, outside Git. Historical evidence remains under the original `4b6decd9-6ca2-4bbf-87ce-3cd8f6f7dadf` run. No test deadline or assertion was weakened. No protected/build/workflow file changed.

## Remaining acceptance

**Visual verification was not performed.** The android-lab README and process inventory provide no available dedicated writer rendering lease; no phone or shared emulator was operated. Independent review should inspect final-APK expansion/collapse and text selection/copy, failure/pause/unknown states, success and session changes, narrow screens/large font, both languages and light/dark themes. Resource and JVM tests are not rendered UI evidence. The section uses existing Material typography, a minimum 48 dp toggle and scrolling content.

Real endpoint compatibility, API33 execution, Release/R8, final-head CI and prescribed emulator regression remain pending. The independent reviewer owns final acceptance and merge.

Compose state reference: AndroidX `Composables.kt` (`remember` overloads compare keys and recreate values when keys change), inspected from https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composables.kt; local reference SHA-256 `6eb495f178bc0214af2d7fa3328c693a41abf442083e0e98a3780f1f813a8b07`. Applied to per-session/result expansion only.
