# Issue #135 Testing Summary

## Scope
- Base commit (`origin/main`): `eb675203e7bc65ffbbf3829956d1fea1a5133b84`
- Validated implementation commit: `9f54f9e733e6680f3c2b107a2c4854178d5cb9ad`
- Branch: `auto/135-provider-presets`
- Summary:
  - Added strict fail-closed provider preset parser (`PluginProviderPresets.kt`) validating `providers/<name>.json` entries against the S3 schema (`id`, `name`, `protocol`, `baseUrl`, optional `compat` booleans, optional `models` array), validating `baseUrl` via `OpenAiChatConfig` / `OpenAiProviderSettings` (HTTPS or private/loopback HTTP only; rejecting credentials, query strings, fragments, and non-private cleartext), recognizing non-`openai-chat` protocols (`openai-responses`, `anthropic`, `gemini`) as unsupported for import, and rejecting unknown/malformed payloads without breaking other presets.
  - Extended `PluginDataCatalog.readProviderTemplates` and `PluginManager.activeProviderTemplates` to expose templates exclusively from enabled, non-quarantined, non-`pendingDelete` data plugins, carrying source plugin name and unsigned/unverified author metadata.
  - Updated `ProviderSettingsForm.kt` (`ProviderSettingsEditor.applyTemplate`) and `ProviderScreen.kt` so choosing a template always opens a new independent provider profile copy (never reusing an existing profile's API key), displays preset model quick-pick chips, marks unsigned plugin sources as unverified, blocks non-`openai-chat` templates with a clear unsupported label, and prominently shows the exact target host for explicit user confirmation before saving.
  - Added bilingual English/Chinese UI resources (`values/strings.xml`, `values-zh/strings.xml`) with exact key parity and unit tests in `ProviderPresetsTest.kt` proving preset parsing, non-`openai-chat` blocking, 17 fail-closed invalid payload cases, and full independent-copy isolation across plugin update (domain redirect attempt), disable, and uninstall.

## Automated Validation Executed
- Command:
  ```powershell
  .\gradlew.bat ":agent-core:test" ":app:verifyUiResources" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:agent-core:test`: Passed (`total=579`, `passed=578`, `skipped=1`, `failed=0`).
  - `:app:verifyUiResources`: Passed (`476` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=648`, `passed=647`, `skipped=1`, `failed=0`; includes `ProviderPresetsTest`; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `613245bc1552a27c657d0d596a2e453aa1d8c84b8c6b79cf49f3900539bac687`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Reused cleanly with `--configuration-cache-problems=fail`.

## Unverified Boundaries
- Physical Android device / emulator UI visual layout, Android API 33 runtime behavior, real third-party data plugin ZIP import on device, and R8 Release builds were not executed locally in this slice.

## Review Round 1 Correction
- Reviewed PR head: `6d98f2e29a0f8f8a1da7b736b2ff807a6206272d`; prior writer returns: `0`.
- Fixed implementation commit: `379ad384cac710bb282edbfe3460fef4010565f3`.
- A GPU JVM probe installed an enabled data plugin containing four documented model metadata variants. Before the fix, only the baseline preset loaded: the `ToolSupport` object was rejected as non-boolean, and nullable model capabilities were rejected as non-integers. The probe failed (`total=1`, `failed=1`).
- The parser now validates the `ToolSupport` object from PROTOCOL section 2.2 and accepts explicitly unknown nullable model metadata. Unknown fields and invalid types remain rejected. Regression tests cover catalog loading for each nullable model field, tool capability objects, and 18 invalid metadata cases. The original valid fixture now uses the documented object shape; no existing assertions were removed.
- Targeted GPU validation on the fixed implementation: `/opt/homelab-report/theta-gpu-test :app:testDebugUnitTest --tests '*ProviderPresetsTest' --tests '*ProviderPresetsSchemaProbe*'` passed (`total=7`, `failed=0`). The unchanged probe loaded all four presets. Probe source remains untracked and is excluded from commits.
- The earlier build, lint, and APK evidence applies to `9f54f9e733e6680f3c2b107a2c4854178d5cb9ad`; it is not a new APK result for this correction. Visual rendering of the template cards and model chips remains unverified. Final emulator regression is left to the review controller.

## Review Round 2 Fix (Storage Fixture Standalone Compilation)
- Addressed `[REVIEW:RETURN]` (`IC_kwDOVkrS_s8AAAABqoL27Q`) where `:samples:storage-fixture:compileDebugKotlin` failed because `:samples:storage-fixture` compiles `PluginDataCatalog.kt` directly without `PluginProviderPresets.kt` or `:agent-core`.
- Validated implementation commit: `a37125800c9cf5a099375d3413e04acb935c454a`.
- Replaced `PluginDataCatalog.readProviderTemplates` with a standalone generic `PluginDataCatalog.readEntries(selection, predicate)` and moved `PluginDataCatalog.readProviderTemplates` into `PluginProviderPresets.kt` as an internal extension function.
- Command:
  ```powershell
  .\gradlew.bat ":samples:storage-fixture:compileDebugKotlin" ":samples:storage-fixture:testDebugUnitTest" ":agent-core:test" ":app:verifyUiResources" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:samples:storage-fixture:verifyStorageFixtureSources` + `:samples:storage-fixture:compileDebugKotlin`: Passed (`14` production sources, `2` fixture sources, self-only project edges).
  - `:samples:storage-fixture:testDebugUnitTest`: Passed (`total=4`, `passed=4`, `skipped=0`, `failed=0`).
  - `:agent-core:test`: Passed (`total=579`, `passed=578`, `skipped=1`, `failed=0`).
  - `:app:verifyUiResources`: Passed (`476` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=650`, `passed=649`, `skipped=1`, `failed=0`; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `10ca337f12e600b64a5e05a58236a206034fa97c392968422570a25b53988a71`).
  - `:app:lintDebug`: Passed.

