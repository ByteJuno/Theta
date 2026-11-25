# Issue #130 Testing Summary

## Scope
- Base commit (`origin/main`): `1b102c1ce5b96c5ff313d5374b882223ea972da6`
- Branch: `auto/130-plugin-env-requires`
- Summary:
  - Added backward-compatible `provides`, `declares`, and `requires` (`root`, `lsposed`, `accessibility`, `shizuku`) parsing with strict fail-closed validation and 64 KiB manifest cap to `PluginManifest.kt` while keeping legacy dex fields free from the data-package 2048-character string cap (`dexLegacyString`) and adding a cold-restore regression test through `PluginStore.installed()`. Expanded `PluginDataManifest.kt` `requires` key allowlist while continuing to reject any `true` requirement on data ZIP packages.
  - Added read-only background `HostEnvironmentStatus` model with tri-state readiness (`EnvironmentReadiness.READY`, `DETECTED_UNVERIFIED`, `UNAVAILABLE`), evaluation helpers (`evaluateHostAccessibilityEnabled`, `evaluatePassiveHostEnvironment`), and `AndroidHostEnvironmentDetector` (`HostEnvironment.kt`): Accessibility is only `READY` when Theta's own package has an enabled accessibility service (other apps' accessibility services never mark Theta ready); passive `su` binary detection and Shizuku manager package visibility (`<queries>` in `AndroidManifest.xml`) report `DETECTED_UNVERIFIED` without invoking `su` or prompting for permission; LSPosed reports `READY` when `HookHost.isConnected` is true.
  - Updated `MainActivity.kt` and bilingual string resources (`values/strings.xml`, `values-zh/strings.xml`) to display consistent English/Chinese readiness labels, `provides`, `declares`, advisory notices, and Accessibility settings navigation across both the plugin list cards and the install confirmation dialog without blocking install or triggering privilege prompts.

## Automated Validation Executed
- Command:
  ```powershell
  .\gradlew.bat ":app:testDebugUnitTest" ":app:assembleDebug" ":samples:hello:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:app:verifyUiResources`: Passed (`430` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=637`, `passed=636`, `skipped=1`, `failed=0`; includes regression tests for legacy >2048-char dex cold restore, other-app-only Accessibility service not marking Theta ready, and Shizuku manager-only `DETECTED_UNVERIFIED` status; the 1 skipped test is the existing Windows POSIX `snapshotKeepsExecutableBit` check).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `2afc7ec6a2a7742460b9ab95adc26ce6f4e494ba36c66d43991ed3b44289e199`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Stored and reused cleanly with `--configuration-cache-problems=fail`.
- Static Plugin Link Verification:
  - `python -X utf8 scripts/check-plugin-links-test.py`: Passed (`3` tests OK).
  - `python -X utf8 scripts/check-plugin-links.py app/build/outputs/apk/debug/app-debug.apk samples/hello/build/outputs/apk/debug/hello-debug.apk`: Passed (`281 classes, 400 references checked, 0 bundled, 0 missing, 0 optional`).

## Unverified Boundaries
- Physical Android device / emulator UI layout rendering, live Accessibility / Shizuku / Root / LSPosed state transitions on device, Android API 33 runtime behavior, and R8 Release builds were not executed locally in this slice.
