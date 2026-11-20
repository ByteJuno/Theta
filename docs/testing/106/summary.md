# Issue 106 — Chat image generation

## Source and artifact

- Base: `cd1d4e7fd34025fdf474baa3ca96a816373c9845` (fresh origin/main).
- Tested implementation: `102b34f0edb056e59781b95fabdace1efb7e120f`; tree `357d2d13756720f60e2f67fb5997a8baebda4604`. The following documentation commit changes only this summary.
- Debug APK SHA-256: `b544b168afb8d186e8f19cb9e7c61c93183a9d37eb31cd964b98518970ecd089`.
- Raw logs, XML, source hashes and APK stay outside Git: `event-runs/74f6250f-d5b1-4ad2-99fd-f088ff7caf18` and `validation-issue11/issue106-74f6250f-*`.

## Delivered scope

The normal chat model receives an approved WRITE-risk `host/generate_image` tool. Its definition distinguishes image creation from prompt writing/API discussion; no keyword router, image mode, chat image button or alternate chat path exists. Configuration uses a separate encrypted image profile with the existing no-backup/Keystore mechanism. Text configuration remains independent.

One model-selected tool call submits one OpenAI-compatible POST to the configured base URL plus `images/generations`, with model, prompt and n=1. A private per-session call-ID attempt marker is written before networking; repeated/reopened/cancelled attempts are not automatically charged again. This is process-restart protection, not a claim of physical-power-loss durability or remote exactly-once delivery. User approval, uncertain outcomes, worker exit and tool resource isolation remain host-ledger owned.

Base64 and HTTPS responses have bounded bodies. Downloads use a separate client, no model authorization/cookie, no proxy, public-only DNS results, literal IP checks, no redirects and no retry. Limits: 12 MiB JSON, 8 MiB image, 4096 per dimension and 4,194,304 pixels. Android ImageDecoder rejects partial/corrupt/unsupported images before full allocation; normalization strips source metadata into PNG. Only a fixed result status enters tool/model history: image bytes and signed URLs never enter the ledger, blob retrieval tool or logs. Files are private, session/call hashed, bounded and no-follow checked. Deleting a session removes attachments; deletion intent allows interrupted cleanup on next open without sweeping unrelated storage.

Chat shows bilingual approval/running/completed/cancelled/failure feedback and saved pictures inside the associated tool row. Existing Model settings chooses which independent profile to edit, not a generation mode. Session deletion requires confirmation. Picture decoding stays off the main thread; Compose fits the image to the available width.

## Executed checks

- **53 tests passed, 0 failures/errors/skips**, 54.74 seconds: ChatSessionOwnerTest 30; ImageAttachmentsTest 5; OpenAiSettingsStoreTest 12; OpenAiImageClientTest 6.
- Real local MockWebServer HTTP request validates base path, bearer key, image model, Chinese prompt, n=1, b64_json, HTTP/malformed/oversize rejection and cancellation without retry.
- Local TLS server validates URL response download, absence of Authorization/Cookie and redirect rejection. This test injects a trusted synthetic TLS client to reach the local fixture; production public-only DNS is separately tested for private/link-local/loopback/CGNAT/IPv6 and mixed-answer rejection. It is not proof of a real public image-provider interaction.
- Real host/catalog/approval/SQLite/run-loop integration: no image request before approval; one approved execution; result and private attachment survive cold open; no automatic generation on open; deletion removes the image and session. Prompt-writing/API-question fixtures return text and do not execute a tool. The chat model is deterministic in these tests, so real-model intent accuracy is **not** established.
- Direct tool/storage tests: same call ID blocked after completion, exception and cancellation, missing config does not consume a paid attempt, no secret-bearing errors, cross-session isolation, size limits, independent config clear/reopen and interrupted deletion cleanup.
- Debug APK build, bilingual resource guard and Lint passed, **0 errors / 27 warnings**, 80.13 seconds. ImageDecodeInstrumentation compiled successfully but was **not run**.
- Both test and build commands enforce `--configuration-cache --configuration-cache-problems=fail`; tests reused the cache, build stored it. Source digests preserved and checked for publication.
- Failed attempts retained: compile caught UI literal guard violations (fixed using status constants); first test fixture omitted MockWebServer3.start, causing four failures (fixed fixture startup). Assertions/deadlines were not weakened. The earlier corrected 50-case run also passed; final 53-case run supersedes it.

## Outstanding independent acceptance

**Visual verification and real image-provider verification were not performed.** No phone, shared emulator or other task device was accessed. JVM normalization is injected and does not prove Android decode/render correctness. Reviewer must run the compiled `io.github.bytejuno.thetacore.chat.ImageDecodeInstrumentation` probe on the dedicated authorized emulator, then inspect the actual final APK Compose result: image success and reopen, loading, cancellation/error/missing config, approval, delete confirmation and cleanup; Chinese/English, narrow width, large font, light/dark, touch targets. Bind screenshots to source/APK/scenario. Probe pixel roundtrip is not a substitute for Compose visual acceptance.

Real chat-model intent for “画一只在窗边睡觉的猫”, “帮我写一个画猫的提示词”, “解释生图 API”, unsupported tool endpoints, real service billing, API33 runtime and Release/R8 remain unverified. Final-head CI, independent code review and required emulator regression remain merge gates. No protected files changed, new scheduler created or writer merge performed. PR104 foreground-service work remains separate; this branch does not copy or bypass its pending review fixes.

