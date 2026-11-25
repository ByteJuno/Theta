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
