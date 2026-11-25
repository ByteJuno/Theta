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

