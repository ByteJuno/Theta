# Issue #133 Testing Summary

## Scope
- Base commit (`origin/main`): `eb675203e7bc65ffbbf3829956d1fea1a5133b84`
- Validated implementation commit: `7de808977bda5db7d18e7a91afcecad740947f1a`
- Branch: `auto/133-chat-image-input`
- Summary:
  - Extended `agent-core` protocol (`ModelProtocol.kt`), `RunHistory.kt`, `BoundedHistoryView.kt`, and `HistoryCodec.kt` with `ContentPart.Media(MediaKind, BlobRef)` and `MediaContentReader`, keeping v1 text-only JSON serialization byte-for-byte identical via `@EncodeDefault(EncodeDefault.Mode.NEVER)` and never embedding base64 image data inside history JSON.
  - Added user image blob storage (`PrivateImageStore.putUserImage` / `readUserImage`) backed by the existing chunked SQLite private image tables so user images survive restarts without a schema bump and are deleted atomically with `deleteSession`.
  - Added `OpenAiChatConfig.Compat.supportsImageInput` (default `true`), multimodal `USER` message encoding to OpenAI `content: [{"type":"text",...},{"type":"image_url","image_url":{"url":"data:<mime>;base64,..."}}]` with on-demand `MediaContentReader` reads, conservative `estimateRequestBytes` image budget accounting without decoding blobs, base64 non-leakage in error diagnostics, and pre-network `AgentRunLoop` local rejection (`"Image input disabled for active model"`) when `supportsImageInput == false`.
  - Added pure-JVM image header & `ImageLimits` validation (`UserImageValidator.kt`, max 4 images/message, 8 MiB/image, 4096px edge, 4 MP, PNG/JPEG/WebP only) plus Android `ImageDecoder` decode validation in `ThetaApplication`, provider compatibility toggle (`ProviderSettingsCodec.kt`, `ProviderSettingsForm.kt`, `ProviderScreen.kt`), and chat UI Photo Picker (`PickMultipleVisualMedia`) + Camera (`TakePicturePreview`) composer previews & message image rendering (`ChatSessionOwner.kt`, `ChatImage.kt`, `ChatScreen.kt`, `ChatDiagnosticText.kt`).
  - Added bilingual EN/ZH UI strings and unit + loopback HTTP server integration tests in `MultimodalImageInputTest.kt` and `UserImageChatTest.kt`.

## Automated Validation Executed
- Command:
  ```powershell
  .\gradlew.bat ":agent-core:test" ":app:verifyUiResources" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:agent-core:test`: Passed (`total=583`, `passed=582`, `skipped=1`, `failed=0`; includes `MultimodalImageInputTest`).
  - `:app:verifyUiResources`: Passed (`477` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=647`, `passed=646`, `skipped=1`, `failed=0`; includes `UserImageChatTest`; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `4703944531cbc7136d2f30c16f496c21ca062a4283896f3e909b7d55bf86bba4`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Reused cleanly with `--configuration-cache-problems=fail`.

## Unverified Boundaries
- Physical Android device / emulator Photo Picker & Camera intents, on-device Android `ImageDecoder` decode path, visual rendering layout, real multimodal vision LLM endpoints, Android API 33 runtime behavior, and R8 Release builds were not executed locally in this slice.

## Review Round 1 Fix
- Addressed `[REVIEW:RETURN]` (`IC_kwDOVkrS_s8AAAABqoffGQ`; prior review returns on PR #144: `1`).
- Validated implementation commit: `1a3c903caf0c987aad7245fae9315a50f4c29453`.
- Changes:
  1. Updated `ContextSummarizer` (`ContextPolicy.kt`) to attach actual `ContentPart.Media` parts when summarizing multimodal transactions (charging real image budget and sending real `image_url` payloads to vision-capable `SummaryModel`s) while keeping durable SQLite history strictly `BlobRef`-only, and failing closed (`null` -> `PAUSED`, never `CONTINUATION`) when the summary model cannot consume images or fit them in budget.
  2. Updated `AgentRunLoop` (`AgentRunLoop.kt`) to check media capability BEFORE summary network calls and BEFORE consuming recovery/readmission checkpoints (`readmitBudgetCheckpointId` / `resumeCheckpointId`), ensuring definite local rejections (`"Image input disabled for active model"`) make 0 network calls, never write `ADMITTED` or `SUMMARY_RESERVED`, leave existing `BUDGET`/`CONTINUATION` checkpoints intact without reconciliation requirements, and allow clean subsequent retries. Also blocked `recover` fallbacks from switching to models without required media support.
  3. Replaced the hand-written PNG/JPEG/WebP binary header parser in `UserImageValidator.kt` with Android `ImageDecoder`'s `OnHeaderDecodedListener` callback + software full decode validation (`validateAndDecodeAndroidUserImage`), wired `ChatSessionOwner`'s default `userImageValidator` to `validateAndDecodeAndroidUserImage`, and used the injected validator seam in pure-JVM tests (`UserImageChatTest.kt`).
  4. Added formal regression tests in `MultimodalImageInputTest.kt` covering `CONTEXT_TOO_LONG -> image-consuming summary -> CONTINUATION -> resume`, fail-closed `PAUSED` when `SummaryModel` lacks image input, old-history `compactPrior` with images, and disabled image input across ordinary + budget-readmission + clean retry flows.
- Command:
  ```powershell
  .\gradlew.bat ":agent-core:test" ":app:verifyUiResources" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:lintDebug" --no-daemon --configuration-cache --configuration-cache-problems=fail "-Pandroid.overridePathCheck=true"
  ```
  - `:agent-core:test`: Passed (`total=585`, `passed=584`, `skipped=1`, `failed=0`).
  - `:app:verifyUiResources`: Passed (`477` English/Chinese keys; Kotlin PSI + XML checks passed).
  - `:app:testDebugUnitTest`: Passed (`total=647`, `passed=646`, `skipped=1`, `failed=0`; 1 existing Windows POSIX skip).
  - `:app:assembleDebug`: Passed (`app-debug.apk` SHA-256: `31bf7a0dd26c2003099622b9db0405ce9c090df0b398585a1210bdafc7e7976e`).
  - `:app:lintDebug`: Passed.
  - Configuration cache: Reused cleanly with `--configuration-cache-problems=fail`.

