# Issue 111 — bounded chat diagnostics

Validated implementation: `ccdd8d358f734d1bfbcb0c426415cffa5418db68`, including the CI cancellation repair below and the previously integrated main `029adaf75c8118e27a33cbe6aed6057f3b587b0b`.
The following documentation commit changes only this summary; all 350 tracked non-document source digests match the tested tree. Current Debug APK SHA-256: `c3974706c5f481f901fe4bc65923a535dffd8e993a7d3b668f94af30150d83e8`.

## Delivered behavior

Failed and paused chat results now offer a collapsed **Diagnostic details** section below the result notice in the scrollable message area. The selectable bilingual summary reports state, recognized reason/model error code, steps, retries and the server-execution-unknown flag. It hides while loading/running and after success. Expansion is scoped to the session, accepted input and result, and is not saved across recreation. Existing recovery-detail reasons pass through the same sanitizer.

Reasons over 512 UTF-16 code units are discarded before scanning or copying. Shorter reasons are matched to fixed resource text and exact `ModelErrorCode` values; arbitrary bodies, headers, exception suffixes and checkpoint IDs are never echoed by the diagnostic formatter. Unknown reasons receive an explicit privacy-omission label. This deliberately avoids relying on regex token detection: secrets need not use a known prefix. Output remains bounded even for a 2-million-character input; test summaries in both locales remain below 600 characters. A false unknown flag is labelled “not reported”, not proof of absent remote effects.

`RunResult` currently retains the model error category but not HTTP status or rejected parameter names. This UI displays the available category; it does not invent or recover absent fields from raw logs. No run/storage schema, credentials, retry policy, approval or execution ownership changes.

## Verification

CI run `44897992086` failed the existing real-socket cancellation case. Single original-suite runs passed on Windows and Linux, but a Linux scratch probe calling the unchanged cancellation test failed on iteration 52. Its full stack identified the client read at `ModelTransport.kt:58`: call cancellation closes the socket, and the reader's `close(error)` could replace normal cancellation with `SocketException: Socket closed`.

The reader now checks `coroutineContext.ensureActive()` before publishing a caught exception. An already cancelled job retains its cancellation cause; an active job still propagates real I/O errors. The production change is limited to this catch path. The formal regression invokes the original real-socket cancellation case 200 times, retaining its peer EOF assertion and 5-second deadlines. A separate truncated HTTP response test proves genuine transport failures remain visible. No mock, exception swallowing or relaxed timeout substitutes for disconnection.

Commands use `--configuration-cache --configuration-cache-problems=fail`:

- `run-validation.py issue111-65e72130-green :agent-core:test --tests '*OkHttpModelTransportTest'`: **5/5 passed**, no failures/errors/skips, 16.15 seconds, cache reused. Covers repeated and original cancellation, genuine truncated response failure, no retry after 503, and no redirect following.
- The same five tests ran on Linux in an isolated scratch checkout. The new tests against old production reproduced the same cancellation failure; after applying the exact production fix **5/5 passed**, 200 cancellation iterations, Gradle 7 seconds, cache reused. These are the same five cases, not five extra unique cases. The temporary diagnostic probe is outside Git and excluded from the formal test filter.
- `run-validation.py issue111-65e72130-caller :app:testDebugUnitTest --tests '*ChatDiagnosticsTest' --tests '*ChatSessionOwnerTest.diagnosticsFollowFailureColdOpenSessionSwitchAndLaterSuccess' --tests '*ChatRecoveryTest' :app:assembleDebug :app:lintDebug`: **9/9 passed**, no failures/errors/skips, 66.23 seconds, cache reused. Includes bounded bilingual hostile-input formatting and the real SQLite failure/cold-open/session-switch/new-request/success path. Responses are synthetic, not real-provider acceptance.
- **14 distinct targeted cases pass**, aggregated across the two Windows invocations with identical source digests. No full-suite pass is claimed.
- Current Debug APK built; bilingual resource guard passed. Lint: **0 errors / 28 warnings**. API33 direct-JDK scan: **401 classes, 97 JDK classes, 248 members, 0 violations**, bound to current APK/class hashes. This is not API33 device execution or whole-app certification.

Current raw logs, red/green XML, source/class manifests and APK are under `event-runs/65e72130-fb71-4155-aa55-948edf05af32` and `validation-issue11/issue111-65e72130-*`, outside Git. Linux scratch evidence remains at `/tmp/theta-writer-65e72130` on the existing GPU host. The initial scratch launcher failed because the archived wrapper has CRLF; direct Java invocation of the unchanged wrapper JAR succeeded. Both launch failures and the real regression failures are retained. No wrapper/build/workflow/protected file changed.

Historical diagnostic/merge evidence remains under `b155fe97-b2d1-46c1-8452-bd6174802ae8` and `4b6decd9-6ca2-4bbf-87ce-3cd8f6f7dadf`; those APK results are not current-build acceptance. Current tests do not repeat unrelated provider model-list validation.

Cancellation handling follows the inspected kotlinx.coroutines [`Job.ensureActive` / `CoroutineContext.ensureActive`](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/Job.kt) implementation, which throws the cancelled job's original cancellation exception; reference SHA-256 `a617c79f9fae33828e3a9cbae65a128e026274ad204e117d4149d8af3c981c32`.

## Remaining acceptance

**Visual verification was not performed.** The android-lab README and process inventory provide no available dedicated writer rendering lease; no phone or shared emulator was operated. Independent review should inspect final-APK expansion/collapse and text selection/copy, failure/pause/unknown states, success and session changes, narrow screens/large font, both languages and light/dark themes. Resource and JVM tests are not rendered UI evidence. The section uses existing Material typography, a minimum 48 dp toggle and scrolling content.

Real endpoint compatibility, API33 execution, Release/R8, final-head CI and prescribed emulator regression remain pending. The independent reviewer owns final acceptance and merge.

Compose state reference: AndroidX `Composables.kt` (`remember` overloads compare keys and recreate values when keys change), inspected from https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composables.kt; local reference SHA-256 `6eb495f178bc0214af2d7fa3328c693a41abf442083e0e98a3780f1f813a8b07`. Applied to per-session/result expansion only.
