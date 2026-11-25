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
