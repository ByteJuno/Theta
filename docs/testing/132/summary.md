# Issue #132 Testing Summary

## Scope
- Base commit (`origin/main`): `eb675203e7bc65ffbbf3829956d1fea1a5133b84`
- Validated implementation commit: `5f0cda89412e94cddc22205ba6cf5c32d238bc13`
- Branch: `auto/132-dex-tool-bridge`
- Summary:
  - Added external dex plugin tool registration and revocation (`registerPlugin`, `registerPluginAtomically`, `revokePluginTool`, `revokePlugin`, `pluginToolKeys`) to `FrozenToolCatalog.kt` with strict `<pluginId>/<toolId>` key validation, `host/` and `mcp/` prefix rejection, `Source.THIRD_PARTY` + `owner = pluginId` attribution, monotonic per-key `generation`, bounded 5-second metadata capture with `Schema202012Validator` checks, per-plugin metadata budget isolation (`HostToolBridgeRoot.kt`), and fail-closed `gate.uninstall` cleanup on failed/timed-out captures.
  - Addressed PR #142 `[REVIEW:RETURN]` (`IC_kwDOVkrS_s8AAAABqn2zzw`):
    1. Unified registration validity checks (`stillCurrent`) and catalog installation (`gate.install`) inside one atomic commit boundary (`registerPluginAtomically`, strict lock order `PluginRegistrations.lock -> PluginToolBridge.lock -> DefinitionGate`), and revoked in-flight captures immediately so revoked tools never publish, reappear, or execute after `revoke()` returns.
    2. Distinguished local metadata capture timeout (`MetadataCaptureTimeoutException`, preserving `TimeoutCancellationException` cause) from bridge coroutine cancellation, recording timeout failures and resuming subsequent healthy registrations once the timed-out worker releases its actual slot (`lease.actualExited`).
    3. Kept temporary metadata capacity exhaustion (`MetadataCapacityUnavailableException`) pending via event-driven slot release notifications (`PluginExecutionBudget.nextSlotRelease`) without marking tools permanently failed or relaxing capacity/timeouts.
    4. Wired chat bridge invocations (`HostToolExecutionService.dispatchLocked`, `beforeExecution`, `monitor`) into `PluginToolFailureWindow` (shared with `HostPluginContext`), recording each invocation's unique terminal status exactly once and blocking new submissions once quarantined.
  - Exposed active tool progress snapshots from `HostToolExecutionService` (`activeProgress`, `activeProgressSnapshot`) into `ChatState.toolProgress` (`ChatSessionOwner.kt`) and `ChatScreen.kt` `ToolCard`, while preserving idle-timeout reset + 10-minute hard total cap and ledger-backed `env.requestApproval` dynamic risk escalation (`effectiveRisk` persisted before returning `true`; `unattended` denied unless a matching `ALLOW_ALWAYS` grant exists).

## Automated Validation Executed
- Command:
  ```powershell
  .\gradlew.bat ":agent-core:test" ":app:verifyUiResources" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:agent-core:test`: Passed (`total=579`, `passed=578`, `skipped=1`, `failed=0`).
  - `:app:verifyUiResources`: Passed (`467` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=654`, `passed=653`, `skipped=1`, `failed=0`; includes `PluginToolBridgeTest` with 4 new regression tests covering atomic revocation during capture, local metadata timeout vs. cancellation + post-slot-release continuation, temporary capacity exhaustion recovery, and once-per-terminal failure counting + quarantine blocking; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `53c50b49981d5325e48a6b999118f86b4f2ff60e0e07f6e65c9cf8fb11c44e67`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Reused cleanly with `--configuration-cache-problems=fail`.

## Unverified Boundaries
- Physical Android device / emulator UI rendering, real APK dex classloading of third-party plugins on device, Android API 33 runtime behavior, and R8 Release builds were not executed locally in this slice.

## Review Round 2 Fix
- Addressed `[REVIEW:RETURN]` (`IC_kwDOVkrS_s8AAAABqomkhA`; prior return comments on PR #142: 3 total including 1 merge-conflict return `IC_kwDOVkrS_s8AAAABqnouog` and 2 substantive review returns `IC_kwDOVkrS_s8AAAABqn2zzw`, `IC_kwDOVkrS_s8AAAABqomkhA`).
- Validated implementation commit: `aa4c4dac007e551105531732122e8939d836eed0`.
- Changes:
  1. Decoupled background slot-release / actual-exit waits from host startup (`PluginToolBridge.attachCatalog`) and plugin management operation locks (`PluginManager.load` -> `PluginToolBridge.awaitInitialPass`): once a capture times out or hits temporary capacity exhaustion, `initialPassSettled` completes immediately so host startup recovers and `setEnabled(false)` / `uninstall` never wait for non-cooperative capture workers, while background `syncJob` still resumes pending registrations once capacity frees.
  2. Added strictly monotonic unique `registrationId` to `PluginRegistrations` / `RegisteredExtension` and keyed `PluginToolBridge`'s `registered`, `failed`, `inFlight`, and atomic `commit` checks by `registrationId`, preventing stale failures or captures from an earlier registration from poisoning or overwriting a re-registration of the same `Tool` object instance.
  3. Added formal regression tests in `PluginToolBridgeTest` covering non-cooperative timeout during host startup (`attachCatalog`), non-cooperative timeout under plugin operation lock + immediate disable/uninstall, and re-registering the exact same `Tool` object instance across both stale failure and stale publish races.
- Command:
  ```powershell
  .\gradlew.bat ":agent-core:test" ":app:verifyUiResources" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:agent-core:test`: Passed (`total=579`, `passed=578`, `skipped=1`, `failed=0`).
  - `:app:verifyUiResources`: Passed (`467` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=656`, `passed=655`, `skipped=1`, `failed=0`; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `7545e6488127ec5015e7b8e36ae1e0dcf80dc2de523a2e2cb404932f7621acd9`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Reused cleanly with `--configuration-cache-problems=fail`.

## Review Round 3 — Reviewer Fix
- Reviewed input head: `f8f8fab1721a115dd6e20645ddcd894b90f1fcca`. This is the third substantive review, following two substantive returns; the separate merge-conflict return remains recorded above.
- Confirmed failure on the input head through the prescribed GPU entry point: `:app:testDebugUnitTest --tests '*InitialPassChurnProbe*'` ran 1 test with 1 failure. With the original tool already published and 20 successful metadata captures (0 metadata errors), continuous runtime registration kept startup incomplete and the simulated plugin operation mutex unavailable. Startup returned only after the producer stopped. The probe remains untracked and is not part of this PR.
- Validated implementation commit: `4c6e69b90e88df3ed38a83aacd042ccccc97ac8e`.
- Fix: the initial pass now tracks the registration IDs present when it begins. Later runtime registrations continue in the background without extending or resetting the initial wait. Metadata capture remains limited to 5 seconds, with 4 process slots and 1 slot per plugin domain.
- Added `runtimeRegistrationsDoNotExtendInitialPassOrBlockPluginOperations`, covering both catalog attachment during startup and enable under the plugin operation mutex. It verifies that runtime metadata capture cannot prolong the initial pass, disable can acquire the mutex before that capture exits, and the revoked tool cannot subsequently publish.
- Validation on the fixed source using `/opt/homelab-report/theta-gpu-test :app:testDebugUnitTest --tests '*InitialPassChurnProbe*' --tests '*PluginToolBridgeTest*'`: `GRADLE_EXIT=0`, 14 tests, 0 failures (13 formal regressions and 1 untracked probe). With 20 successful captures and 0 metadata errors, the probe observed startup complete and the operation mutex available while the producer was still active. The existing non-cooperative timeout, capacity recovery, and same-object re-registration regressions also passed in this run.
- The APK hashes and full-suite results above describe their original implementation commits. No new APK, emulator rendering, or physical-device validation was performed for this reviewer fix; those historical APKs are not evidence for the final source. Only this summary is committed as test evidence.

## Review Round 4 — Reviewer Fix
- Reviewed input head: `571a05f70d3cbe1fe6a1f0ee1399a1f48a08ab45`, base `1df6426d92747657ef75fed048c7e5c9d8a9c290`. The review controller identifies this as round 4, after 3 returns; the historical counts above remain unchanged.
- Previous final-regression failure is retained: input `f485e20d`, dedicated-emulator run `20261125-111236`, `VERIFY_S1_EXIT=1`, elapsed `401` seconds, `SIGNATURES_SKIPPED=s1`. Failure: `host shows up: timed out`; visible text began `Back to chat`, `Theta`, `Plugin API level 1`, `App language`, `Follow system`, `中文`, `English`, `Device runtime environment`, `Accessibility: Not rea...`. Current input includes upstream `fcafa4e`, which adds viewport sweeping to that readiness check while preserving its `No plugins installed` assertion. This source inspection does not replace the controller's final-commit emulator regression.
- Confirmed a separate defect with `/opt/homelab-report/theta-gpu-test :app:testDebugUnitTest --tests '*PluginQuarantineBatchProbe*'`: 1 test, 1 failure, `GRADLE_EXIT=1`. After 3 failures quarantined a plugin tool, a new chat batch containing that tool followed by a healthy sibling produced `healthyCalls=0`, both calls `CANCELLED/NONE`, and run `PAUSED` with reason `Tool step interrupted: Plugin tool quarantined after repeated failures`. This violates PROTOCOL 3.7's independent handling of rejected calls.
- Validated implementation commit: `e0f1d91bacb47ab00aaafce8de8872d88acf2a5a`.
- Fix: host quarantine admission now persists `REJECTED/NONE` for the unsubmitted invocation instead of raising a batch-wide scheduling error. The ledger rejects attempts to apply this transition to a submitted instance and preserves existing terminal records. Added regressions for persistence across reopen, rejection/claim races, and a mixed batch through `ChatSessionOwner`; the existing quarantine test now requires the durable rejection and absence of an execution instance.
- Validation on the fixed source: `/opt/homelab-report/theta-gpu-test :agent-core:test --tests '*InvocationLedgerTest*' :app:testDebugUnitTest --tests '*PluginQuarantineBatchProbe*' --tests '*PluginToolBridgeTest*' --tests '*HostToolExecutionServiceTest*'` returned `GRADLE_EXIT=0`, 138 tests, 0 failures. Probe output changed to `badCalls=3`, `healthyCalls=1`, run `COMPLETED`, and ordered results `REJECTED/NONE`, `SUCCEEDED/NONE`.
- The probe remains untracked and is not committed. No raw logs, APKs, or recordings are added. The fixed API-level scan supplied for the reviewed input passed (`minSdk=33`, 0 hits); the reviewer fix introduces no Android platform API calls or dependencies.
- Visual rendering remains unverified here, including the live progress text and progress bar in the tool card. No new APK or device regression was run by the reviewer; the controller must run the existing final-commit emulator checks. Historical APK hashes above are not evidence for this fix.
