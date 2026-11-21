# Issue 111 — bounded chat diagnostics

Validated implementation: `f5bbde985391c9b04491289978d9b7da8facdd74`, including the cancellation ordering repair below and main `996db9a7d3205d05e3d993feebe0e64e197221f8` integrated by the reviewer.
The following documentation commit changes only this summary; all 354 tracked non-document source digests match the tested tree. Current Debug APK SHA-256: `8e89a2c9028a4e3bbb175adfce652d0b256d32df7fa614aeb1a7308cc438671d`.

## Delivered behavior

Failed and paused chat results now offer a collapsed **Diagnostic details** section below the result notice in the scrollable message area. The selectable bilingual summary reports state, recognized reason/model error code, steps, retries and the server-execution-unknown flag. It hides while loading/running and after success. Expansion is scoped to the session, accepted input and result, and is not saved across recreation. Existing recovery-detail reasons pass through the same sanitizer.

Reasons over 512 UTF-16 code units are discarded before scanning or copying. Shorter reasons are matched to fixed resource text and exact `ModelErrorCode` values; arbitrary bodies, headers, exception suffixes and checkpoint IDs are never echoed by the diagnostic formatter. Unknown reasons receive an explicit privacy-omission label. This deliberately avoids relying on regex token detection: secrets need not use a known prefix. Output remains bounded even for a 2-million-character input; test summaries in both locales remain below 600 characters. A false unknown flag is labelled “not reported”, not proof of absent remote effects.

`RunResult` currently retains the model error category but not HTTP status or rejected parameter names. This UI displays the available category; it does not invent or recover absent fields from raw logs. No run/storage schema, credentials, retry policy, approval or execution ownership changes.

## Verification

CI run `44904399005` failed the repeated real-socket cancellation case on head `a72a97aa6bb4599191d2194d9b5532ca445e929b`. The unchanged five-case suite reproduced that failure in a Linux scratch checkout. A temporary diagnostic probe captured `callCancelled=true`, `jobActive=true`, followed by `close(error)` accepting the socket error. `awaitClose` cleanup can cancel the HTTP call before cancellation reaches its reader child; checking only the child job's activity did not cover this ordering. Original CI, reproduction and probe logs remain outside Git.

The reader and pre-response failure callback now check the call's cancellation state before publishing an I/O error. Only `awaitClose` cleanup cancels this call; errors from active calls remain visible, and the existing coroutine cancellation check is retained. The formal regression still invokes the original real-socket cancellation case 200 times, preserving its peer EOF assertion and 5-second deadlines. Two additional real-socket cases exercise cancellation before response headers (20 iterations with peer EOF) and genuine disconnect before headers. The existing truncated-body failure, 503 no-replay and redirect tests remain intact. Temporary probe logging is not in the tested or committed production source.

Commands use `--configuration-cache --configuration-cache-problems=fail`:

- `run-validation.py issue111-15b4d379-green :agent-core:test --tests '*OkHttpModelTransportTest' :app:testDebugUnitTest --tests '*ChatDiagnosticsTest' --tests '*ChatSessionOwnerTest.diagnosticsFollowFailureColdOpenSessionSwitchAndLaterSuccess' --tests '*ChatRecoveryTest' :app:assembleDebug :app:lintDebug`: **16/16 passed in one invocation**, no failures/errors/skips, **83.84 seconds**, strict configuration cache stored. Seven transport tests and nine diagnostic/SQLite owner/recovery tests passed. Responses are synthetic or loopback, not real-provider acceptance.
- Linux isolated scratch checkout: the same seven socket tests **7/7 passed**, Gradle **8 seconds**, strict cache reused. Each OS exercised 200 repeated plus one standalone blocked-body cancellation and 20 before-header cancellations. Linux repeats the same seven cases and is not added to the unique count. The baseline suite failed before the production fix; no assertion or deadline was weakened.
- Current Debug APK built; bilingual resource guard passed. Lint: **0 errors / 28 warnings / 1 hint**. API33 direct-JDK scan: **401 classes, 97 JDK classes, 248 members, 0 violations**, bound to current APK/class hashes. This is not API33 device execution or whole-app certification. No full-suite pass is claimed.

Current raw logs, red/green XML, source/class manifests and APK are under `event-runs/15b4d379-9413-4ea6-90db-78f2059187fc` and `validation-issue11/issue111-15b4d379-green`, outside Git. Linux scratch evidence remains at `/tmp/theta-writer-15b4d379` on the existing GPU host. Direct Java invocation used the unchanged wrapper JAR; no wrapper/build/workflow/protected file changed. A probe-setup quoting error is preserved in the execution log; it did not change the scratch source before the corrected probe ran.

Earlier diagnostic/merge and first cancellation repair evidence remains in the preceding run directories, including `65e72130-fb71-4155-aa55-948edf05af32`; those passing runs did not establish the later CI outcome and their APK results are not current-build acceptance. Current tests do not repeat unrelated provider model-list validation.

The inspected kotlinx.coroutines [`awaitClose` implementation and cancellation-race documentation](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/channels/Produce.kt) support treating cleanup and coroutine cancellation as concurrent events. The local probe establishes the specific HTTP-call/child-job ordering here. The existing `ensureActive` check still preserves the cancelled job's original exception.

## Remaining acceptance

**Visual verification was not performed.** The android-lab README and process inventory provide no available dedicated writer rendering lease; no phone or shared emulator was operated. Independent review should inspect final-APK expansion/collapse and text selection/copy, failure/pause/unknown states, success and session changes, narrow screens/large font, both languages and light/dark themes. Resource and JVM tests are not rendered UI evidence. The section uses existing Material typography, a minimum 48 dp toggle and scrolling content.

Real endpoint compatibility, API33 execution, Release/R8, final-head CI and prescribed emulator regression remain pending. The independent reviewer owns final acceptance and merge.

Compose state reference: AndroidX `Composables.kt` (`remember` overloads compare keys and recreate values when keys change), inspected from https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composables.kt; local reference SHA-256 `6eb495f178bc0214af2d7fa3328c693a41abf442083e0e98a3780f1f813a8b07`. Applied to per-session/result expansion only.
