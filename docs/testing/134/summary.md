# Issue 134 validation

Implementation source commit: `f4a9d697e6e3e87f8dcb5539720394cae476ae91`
Reviewed predecessor: `b6d2a37aa7765003eb26d2544c830041ce05ac41`
Main baseline: `6d11d8191ced3d77188710e9e58bd3574137a85f`
RETURN cursor: `IC_kwDOVkrS_s8AAAABq_3Slw`

## Implemented

Run-bound skill directory and retained archive version/SHA-256 reads, current lifecycle revocation, explicit missing/digest mismatch refusal, and next-run updates. Unicode validation rejects unpaired UTF-16 while accepting valid supplementary characters. SafeConstructor-based bounded SnakeYAML parsing replaces the handwritten subset. DataStore Core/Okio replaces handwritten persistence, reads existing JSON under noBackupFilesDir, and recovers corrupt preference contents with a persistent bilingual notice and explicit selection acknowledgement. Original stable skill prefix, READ/BlobStore handling, persona fallback and image/chat integration remain.

## Actual checks

- Full preflight passed in 208.30s: agent-core 590 tests / 589 passed / 1 platform skip; app 682 tests / 681 passed / 1 platform skip; zero failures/errors.
- PluginPromptDataTest 9/9, PersonaSettingsTest 3/3, ChatPromptIntegrationTest 2/2 and UserImageChatTest 3/3 passed. Update-during-run returns v1 content with v1 metadata; the next run uses v2. Corrupt preferences do not block chat.
- lintDebug, storage-fixture source verification/compile, and 496 English/Chinese resource keys passed.
- Final preflight + Debug APK assembly after license assets: 67.50s, strict --configuration-cache --configuration-cache-problems=fail, cache reused. Production and test sources unchanged from full suite; Gradle reports unit tasks UP-TO-DATE.
- Debug APK SHA-256: `06b2b72775c33941fc150503987dbab016d86078cb9d5337fca0cc0aefc98833`; size 55713165 bytes. Local artifact: event-runs/9c3d2704-8c82-4005-8815-3843fbc4371d/app-debug.apk. Not installed.
- Source hash manifest verified before commit and committed blobs verified against Git-normalized tested files. APK includes Apache-2.0 and prompt component attribution assets.
- SnakeYAML 2.4 and AndroidX DataStore Core/Okio 1.1.7 licenses verified as Apache-2.0 from official published POM/source artifacts; component rationale and links are in the PR body. No protected file modified.

## Failure evidence retained

The first targeted run had six failures rooted in the default DataStore FileStorage's Windows File.renameTo replacement. Production switched to the library's official OkioStorage backend after source inspection. The next run passed 16/17; one new test kept an InputStream open, blocking later replacement on Windows. Closed the stream via use, kept assertions/timeouts unchanged; final full suite passed. Reviewer original verdict and earlier failure history remain outside Git. Reviewer PromptReviewProbe.kt was not imported or committed.

## Limits and continuation

Visual unverified: new recovery notice and persona picker, English/Chinese, long names, small-screen/large-font, light/dark, selection and empty/error states. No phone or emulator operation, API33 device runtime, Release/R8, physical power loss or live model endpoint acceptance. Cold-recovered skill calls without a process-local run binding fail closed; pruned old archives cannot silently use newer bytes. Current package disable/quarantine/uninstall revokes reads even for retained versions.

Existing publisher must publish this implementation and the unique summary; independent reviewer owns new-head review, CI and prescribed dedicated-emulator regression. Earlier-head CI is not claimed for this commit. Raw logs/XML, dependency sources and APK remain in event-runs/9c3d2704-8c82-4005-8815-3843fbc4371d/ and validation-issue11/issue134-9c3d-* outside Git.
