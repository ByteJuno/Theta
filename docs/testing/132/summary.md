# Issue #132 Testing Summary

## Scope
- Base commit (`origin/main`): `5614b1067ae986e96c89fc7e75b765395386ae5b`
- Validated implementation commit: `4d97f2908c1999a7d56090a639b864a8203569a5`
- Branch: `auto/132-dex-tool-bridge`
- Summary:
  - Added external dex plugin tool registration and revocation (`registerPlugin`, `revokePluginTool`, `revokePlugin`, `pluginToolKeys`) to `FrozenToolCatalog.kt` with strict `<pluginId>/<toolId>` key validation, `host/` and `mcp/` prefix rejection, `Source.THIRD_PARTY` + `owner = pluginId` attribution, monotonic per-key `generation`, bounded 5-second metadata capture with `Schema202012Validator` checks, per-plugin metadata budget isolation (`HostToolBridgeRoot.kt`), and fail-closed `gate.uninstall` cleanup on failed/timed-out captures.
  - Added `PluginToolBridge.kt` hooked directly into `PluginRegistrations.tracked` (sharing the same `PluginRegistrations` close gate) and wired into `PluginManager` / `HostPluginContext`: `onEnable` tools activate only after durable `completeStart`, runtime `ctx.register(tool)` captures sequentially per plugin without self-contention, and `Registration.revoke()`, plugin disable, uninstall, or fault isolation (`beginClose`) synchronously remove tools from `FrozenToolCatalog` and `DefinitionGate` immediately.
  - Exposed active tool progress snapshots from `HostToolExecutionService` (`activeProgress`, `activeProgressSnapshot`) into `ChatState.toolProgress` (`ChatSessionOwner.kt`) and `ChatScreen.kt` `ToolCard`, while preserving idle-timeout reset + 10-minute hard total cap and ledger-backed `env.requestApproval` dynamic risk escalation (`effectiveRisk` persisted before returning `true`; `unattended` denied unless a matching `ALLOW_ALWAYS` grant exists).

## Automated Validation Executed
- Command:
  ```powershell
  .\gradlew.bat ":agent-core:test" ":app:verifyUiResources" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:agent-core:test`: Passed (`total=579`, `passed=578`, `skipped=1`, `failed=0`).
  - `:app:verifyUiResources`: Passed (`451` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=646`, `passed=645`, `skipped=1`, `failed=0`; includes `PluginToolBridgeTest` covering ID/prefix validation, lifecycle sync & immediate `STALE` revocation on `revoke()`/`beginClose()`, `ChatSessionOwner` discovery/execution across `BALANCED` and `STRICT` approval modes + digest invalidation, per-plugin `PluginExecutionBudget` & cross-session `exclusiveResource` leases, `env.progress` idle-timeout reset + hard cap + `ChatState.toolProgress` sync, and `env.requestApproval` durable `effectiveRisk` persistence + `unattended` policy; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `0c8a94150e05aebe9096a4a466685ac4de895252c518465002e1fb9026442898`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Reused cleanly with `--configuration-cache-problems=fail`.

## Unverified Boundaries
- Physical Android device / emulator UI rendering, real APK dex classloading of third-party plugins on device, Android API 33 runtime behavior, and R8 Release builds were not executed locally in this slice.
