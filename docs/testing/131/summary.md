# Issue #131 Testing Summary

## Scope
- Base commit (`origin/main`): `5614b1067ae986e96c89fc7e75b765395386ae5b`
- Validated implementation commit: `d1bc095660196fc9ee18ef61b31f520a670fe471`
- Branch: `auto/131-provider-profiles`
- Summary:
  - Extended `OpenAiProviderSettings` and `OpenAiRunSnapshot` with bounded optional `systemPrompt` (`<= 32,000` characters, injected stably as `ChatRequest.system` without volatile timestamps) and explicit `NoAuthCredentials` support, plus cross-profile reasoning replay compatibility checks.
  - Upgraded `ProviderSettingsCodec` and `OpenAiSettingsStore` to manage multiple named chat profiles (`id`, `displayName`, `OpenAiProviderSettings`, per-profile `apiKey` or explicit `noAuth`, and `activeProfileId`) inside one AES-GCM authenticated atomic record (`active.bin`), keeping `images.bin` independent, zeroing plaintext byte arrays, and preserving `#56`/`#57`/`#110` invariants.
  - Addressed PR #140 review feedback:
    - `saveProfile(..., replaceUnreadable = true)` re-reads the catalog inside the same storage lock before discarding; if the catalog became readable again, it throws `ProviderSettingsException.Failure.CATALOG_RECOVERED` without overwriting any saved profiles, and `ProviderScreen` reloads all preserved profiles. When replacing an unreadable catalog, `ProviderScreen` displays an explicit bilingual warning before saving.
    - Added `ProfileQuickSwitchController` wired into `ChatScreen` so new chat runs/continuations/recovery actions wait until the active profile write succeeds; write failures preserve the previous active profile and surface a retryable bilingual error message.
    - Added `fallbackProfileDisplayName(model)` for legacy v1 migration and blank display names so only the generated display name falls back cleanly while preserving original `model`, `parameters`, `compat`, `baseUrl`, and `apiKey` verbatim.

## Automated Validation Executed
- Command:
  ```powershell
  .\gradlew.bat ":agent-core:test" ":app:verifyUiResources" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:agent-core:test`: Passed (`total=579`, `passed=578`, `skipped=1`, `failed=0`).
  - `:app:verifyUiResources`: Passed (`472` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=647`, `passed=646`, `skipped=1`, `failed=0`; includes regression tests for locked recovery re-read & editor reload, quick-switch write gating + failure preservation + retry, and legacy v1 display-name fallback preserving model/parameters/compat/key verbatim; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `dc14c81a85badfbf7a69d777d9c357be4d09258da5f7916e1e72ac5c4cc3c1f7`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Reused cleanly with `--configuration-cache-problems=fail`.

## Unverified Boundaries
- Physical Android device / emulator UI layout rendering, hardware-backed AndroidKeyStore key invalidation on physical devices, Android API 33 runtime behavior, and R8 Release builds were not executed locally in this slice.
