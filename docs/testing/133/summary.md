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

## Review Round 2: host readiness observation

- Addressed OWNER RETURN `IC_kwDOVkrS_s8AAAABqqgTzg`; cumulative review returns: 2.
- Tested script source commit: `8fb4efa77bd1edfe7f6ad95cc988201a75a36f04`. Reused the scoped readiness fix from `4373f95713fe6fdaaf92f04635893c9001917dea`; both affected script files matched before and after transplantation.
- Exact prior-head evidence: reviewer run `20261125-083450` identifies source `c7b5263a9fd0c0dc5734f2dc594c70d299f43051`, `VERIFY_S1_EXIT=1 seconds=413`, and `SIGNATURES_EXIT=0 seconds=2017`. Its readiness failure sees only the environment and data sections. The archived R8 failure screenshot also ends at the data section, and the crash log is empty. This is prior-build diagnostic evidence; its APK SHA was not independently obtained here.
- Fix: enable the existing bounded list sweep while waiting for the exact `No plugins installed` label. Keep the readiness assertion, crash detection, original timeout, and install ordering. No application code, resources, Gradle inputs, or protected files changed in this round.
- Before the fix, the two transplanted regression cases reproduced the missing-scroll behavior: offscreen label times out and the missing-label case performs no sweep. Raw failures were retained outside Git.
- After the fix, `python -X utf8 -B scripts/acceptance-observation-test.py` passed **39/39** (0.648 seconds process elapsed). Coverage includes finding the offscreen empty list and retaining the original deadline/no-install behavior if the label is absent. These are script unit tests with simulated observation/clock/adb boundaries, not device acceptance.
- `git diff --check`: passed. New APK: **not generated**; APK SHA: **not available**. Gradle/configuration-cache tests were not rerun because this round changes only Python acceptance scripts and this summary. Earlier JVM/build/APK results above remain bound to their recorded commits.
- Final-head CI, independent review, and the dedicated emulator regression remain required. The writer did not operate any device or merge the PR. Photo Picker, camera, image previews, multilingual layouts, font scaling, and real multimodal endpoints remain visually/functionally unverified at the device boundary.
- Raw reviewer evidence, red/green logs, file hashes, and timing: local event run `84ecab5e-2eb8-4082-bdd6-d43a9db0e961` (outside Git).

## Review Round 3: bounded image request memory

- Addressed OWNER RETURN `IC_kwDOVkrS_s8AAAABqy4qEg`; reviewer cumulative returns: **3**. Previous rounds and raw failure evidence remain retained.
- Implementation source: `c12862c8f62d43cd69537e6bf373ee94e9242aab`, after merging main `7edaa707de5785aa334366c81e37a3102f1d95cc` in `1bc6acc`. Resolved additive form/resource conflicts by retaining both image compatibility and provider-template settings/strings.
- Exact reviewed failure: `bbdad5244b3a7a73531ed5151273be505794b83a`, reviewer `20261125-232249-pr144-codex-239424048`. GPU reproduction used 15 rounds × 4 valid 1024×1024 PNGs of 3,148,927 bytes: `admitted=true images=60 heap=268435456`, then `OutOfMemoryError`, `transportCalls=0`. Verdict, metadata and raw output are retained outside Git.
- Added one shared, model-window-independent **64 MiB peak image allocation budget**. Each occurrence charges raw bytes plus twelve bytes per base64 character and structural overhead, reserving encoder buffers, UTF-16 base64/data URLs, JSON builder growth/final string and transport UTF-8 bytes. Checked across the whole request before the first image read, in runtime admission, in summary packing, and independently in the codec. This bounds incremental image allocations; it does not guarantee total process free memory.
- Summary packing flushes before the next image transaction exceeds that budget. A single oversized transaction pauses without reading images or making network calls; complete original messages and image references stay durable. Unknown model context windows never bypass the memory guard. Existing token limits, retry accounting, deadlines and tool gates remain unchanged.
- Red: the 60-image admission regression failed on the unfixed merged source (`image references must be charged for their encoded payload`). Green: **75/75** targeted JVM tests (`MultimodalImageInputTest`, `ContextPolicyTest`); **13/13** app tests (`ProviderSettingsFormTest`, `ProviderPresetsTest`, `UserImageChatTest`); bilingual resource verification and `lintDebug` passed. All Gradle runs used `--configuration-cache --configuration-cache-problems=fail`.
- Existing GPU entry `/opt/homelab-report/theta-gpu-test`: the same **75/75** tests passed with a **256 MiB test heap** and strict configuration cache. Tests prove zero image reads/network calls for the original 60-image metadata scenario, actual bounded base64 summary requests for 60 smaller images with every reference consumed exactly once, and safe durable pause for a single oversized transaction under known/unknown summary windows. Synthetic reader bytes test request memory behavior; they are not device image-decoder acceptance.
- Local elapsed: red 84.26 s, core green 38.75 s, app/lint 120.75 s. Initial GPU packaging failed before Gradle due to an absent `plugin-api` path; the corrected isolated archive succeeded. Failure output is retained. An inherited blank line in main's #135 summary caused the merge-time whitespace check; no unrelated summary was rewritten. Changed-source `git diff --check` passed.
- New APK: **not generated**, SHA-256: **not available**. No device operation, physical-device/API33/R8/real vision endpoint validation or current-build visual verification. This backend correction reuses the existing bilingual memory-pause message. Final-head CI, independent review and prescribed emulator regression remain the review controller's gate.
- Raw evidence and tested source hashes: event run `26fe6672-2b4c-4e2f-8647-19ec67149d94` outside Git. No probe, raw log, screenshot or APK committed.

## Review Round 4: reject irreducible input before prior compaction

- Reviewed head: `dfbc8937816854190e9d5c3a6f74d00165679316`; cumulative prior returns remain **3**. Its production sources match the previously tested implementation `c12862c8f62d43cd69537e6bf373ee94e9242aab`; the intervening change is this summary only. The supplied API scan passed for minSdk 33.
- New GPU probe reproduced an unnecessary summary call when two prior text messages precede one user message containing four 3,148,927-byte image references. Although the active input alone cannot fit the memory limit, prior compaction spent one summary call and one retry before pausing. Original history remained intact. The probe failed its zero-summary-call assertion on the reviewed head; raw output stays outside Git.
- Fix commit: `1e687f7b5ad5d4a911b82ff498c7f92233842d20`. Apply the existing irreducible-input check before prior compaction on the memory-limit path. Extend the formal oversized-image regression to both empty and nonempty prior history, with known/unknown summary windows, asserting zero calls, image reads, steps and retries while retaining all original history.
- Authorized GPU command: `/opt/homelab-report/theta-gpu-test :agent-core:test --tests '*OversizedActiveImageProbe*' --tests '*MultimodalImageInputTest*' --tests '*ContextPolicyTest*'`. Result: **76/76 passed**, `GRADLE_EXIT=0`. The original probe now pauses with zero summary/main calls, image reads, steps and retries, a `BUDGET` checkpoint, intact history and no reconciliation requirement. Probe source remains untracked and is not part of the commit.
- `git diff --check` passed. No new APK or visual/device acceptance; prior APK results retain their original source identities. Final CI and emulator acceptance remain the controller's separate gate. Real vision endpoint behavior and physical-device memory remain trial boundaries.
