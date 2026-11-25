# Issue #137 Testing Summary

## Scope
- Base commit (`origin/main`): `1b102c1ce5b96c5ff313d5374b882223ea972da6`
- Branch: `auto/137-approval-modes`
- Summary:
  - Added `ApprovalMode { STRICT, BALANCED, FULL_AUTO }` and optional `mode: () -> ApprovalMode = { ApprovalMode.STRICT }` to `ApprovalPolicy` (`agent-core`), preserving all safety invariants (`denied` priority, unverified `UNKNOWN` side-effect prompts, strict `unattended == true` gating, definition digest binding, refresh gates, exclusive leases, and dynamic `env.requestApproval` target-risk re-evaluation).
  - Added `ChatApprovalSettingsStore` (defaulting fresh installations to `ApprovalMode.BALANCED` and `allowRootLongTerm = false`) and wired it into `HostToolBridgeRoot` / `PluginManager` / `ThetaApplication`.
  - Added `ChatApprovalDialog`, persistent `FULL_AUTO` warning banner with one-tap switch back to `BALANCED` in `ChatScreen` top bar, and enabled `Decision.ALLOW_SESSION` and `Decision.ALLOW_ALWAYS` in `ChatSessionOwner.decide` and `ToolCard` (with `ROOT` multi-call grant gating) alongside bilingual string resources (`values/strings.xml`, `values-zh/strings.xml`).

## Automated Validation Executed
- Command:
  ```powershell
  .\gradlew.bat ":agent-core:test" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:agent-core:test`: Passed (`total=579`, `passed=578`, `skipped=1`, `failed=0`).
  - `:app:verifyUiResources`: Passed (`425` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=634`, `passed=633`, `skipped=1`, `failed=0`; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `ba4d29b6d5a7f5aafa0d781404c4727299f028c4f5266db7066e4431e1be35f3`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Stored and reused cleanly with `--configuration-cache-problems=fail`.

## Unverified Boundaries
- Physical Android device / emulator UI layout rendering, Android API 33 runtime behavior, and R8 Release builds were not executed locally in this slice.
