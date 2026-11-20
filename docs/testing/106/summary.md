# Issue 106 — Chat image generation

## Source and artifact

- Base: `cd1d4e7fd34025fdf474baa3ca96a816373c9845`.
- Tested repair implementation: `0cd296b659fdd12928be1bc7536c13cd4ef60aa4`; tree `aebc4a25cb2ac70737c157c541fc4bdf7b57c26a`. The following documentation commit changes only this summary. All 338 tracked non-document source digests match the tested working tree.
- Debug APK SHA-256: `99d34da5bc049a7f0dfc2bac991833fe817229d2536ac104e012085a36b2a006`.
- Current raw review, failure logs, XML, APK and source hashes remain outside Git: `event-runs/40d38d76-b871-41b0-b0db-e930da0d686e` and `validation-issue11/issue106-40d38d76-*`. Original implementation evidence remains under run `74f6250f-d5b1-4ad2-99fd-f088ff7caf18`; it is not final-build evidence.

## Delivered scope

Use the existing chat input to ask for an image. The chat model selects the registered WRITE-risk image tool, subject to the normal host approval and resource-isolation rules. There is no keyword router, new image button, mode or alternate chat path. Chat and image models have separate encrypted profiles. Ordinary text chat remains available.

One approved call submits an OpenAI-compatible POST to the configured base URL plus `images/generations` with model, prompt and n=1. b64_json and HTTPS URL responses are supported. Downloads use a separate unauthenticated client, no proxy, no redirects or retry, and reject private/local or mixed DNS answers. Response limits remain 12 MiB JSON, 8 MiB image, 4096 per dimension and 4,194,304 pixels. Android ImageDecoder rejects invalid/partial/unsupported images; PNG normalization removes metadata. Only fixed status text enters model/tool history, not image bytes, model credentials or temporary signed URLs.

## Verified return and repairs

The trusted reviewer returned `866f41bc` with the OWNER marker `IC_kwDOVkrS_s8AAAABozDzlQ`. Raw GPU evidence was read and retained: deleting an unknown-effect failed session removed its sole UI recovery entry while the ledger restriction survived, including cold restart; three legitimate public addresses were rejected. Local regression tests reproduced both defects before the repair.

- **Session-wide deletion gate:** consult all ledger records in the session, not just the current 40-message page. Unfinished/exit-unconfirmed calls, unacknowledged denials and unresolved isolation/recovery block deletion. The ledger repeats its gate atomically with deletion. Chat also preserves pending intent, retry reconciliation and saved recovery/checkpoints. Bilingual feedback tells the user to resolve recovery, including older messages. No unknown effect is silently cleared. Once explicitly verified clear, deletion can proceed; UNKNOWN evidence and approvals remain intact.
- **Precise address ranges:** narrow IPv4 documentation/reserved exclusions to their actual ranges, retaining private, link-local, CGNAT and multicast rejection. Legal adjacent networks and public special-purpose exceptions have positive controls. IPv6 checks direct global unicast and documented special-prefix exceptions, rejecting documentation/benchmark/transition addresses. Production DNS rejects an entire mixed answer set. Range references: [IANA IPv4](https://www.iana.org/assignments/iana-ipv4-special-registry/) and [IANA IPv6](https://www.iana.org/assignments/iana-ipv6-special-registry/). Global classification is not proof of endpoint reachability.
- **Existing AndroidX SQLite, not a second file protocol:** private attempt/attachment tables share the process-owned no-backup `agent.db`, FULL WAL configuration, ownership and schema migration (v5). Image bytes are bounded 64-KiB chunks, saved with size/hash in one transaction. Attempts commit before network submission; failed/cancelled/reopened calls cannot automatically submit again. Image tables have no links to model history, invocation contents or `blob_read`. Session tombstone, history removal, attempts and image chunks are deleted in the same database transaction. The hand-written `.started`/`.png`/delete-marker protocol and startup cleanup scan are removed.
- No import or deletion of the earlier unmerged file-storage prototype is performed. Existing schema-v4 history is preserved; deployed schema migrations are tested. No guarantee of remote exactly-once billing, physical-power-loss behavior or actual filesystem erasure is claimed.

## Executed checks

**97 applicable targeted cases passed, 0 skipped**, using source-bound results, not a claim that every command passed:

- `green`: 96 cases, 93 passed. The three failures were schema-v1/v2/v3 fixture constructors retaining new v5 tables while lowering user_version. Fixtures now remove those tables to represent the actual old schemas; existing assertions remain. `migration`: 26/26 SessionBlobTest + SharedHistoryDatabaseTest cases passed (29.20s).
- `final`: 42 cases, 41 passed. One unchanged oversized-history case exceeded its existing 10s wait while build/Lint were also executing. The exact case passed unchanged in `isolated` (5.974s test / 27.01s Gradle); root cause of that single timeout is not established. Original failure XML/log retained; no timeout increase, ignored test or weakened assertion.
- Aggregate: 26 migration cases + 42 final-source cases including the isolated case + 29 unaffected cases from green. The latter cover image tool/storage, Android store ownership/configuration, and SQLite open failures. Current network and private-storage suites passed 7/7 and 4/4. Final ChatSessionOwner coverage is 31/31 across the final and isolated commands.
- New coverage: failed-session recovery retained after attempted deletion, cold open and history pagination; saved summary/checkpoint deletion refusal without extra model calls; exact public-range positives/negatives; chunk-save rollback, commit-acknowledgement failure, durable no-replay, corruption rejection; attachment bytes inaccessible via ledger blob reads/history; schema-v4 upgrade; pre/post-commit deletion interruption preserves unrelated sessions and never resurrects deleted attempts.
- Existing HTTP/TLS fixtures still validate generation URL/model/prompt, b64_json, separate no-auth downloads, cancellation/error/size boundaries, mixed DNS and redirect rejection. They are local synthetic-provider tests, not real service acceptance.
- Final-source Debug APK, bilingual resource guard, Lint (**0 errors / 27 warnings**) and decoder instrumentation compilation completed successfully in the final combined invocation (91.27s overall; its exit code was 1 solely for the retained unit timeout). Instrumentation was **compiled, not executed**. Strict `--configuration-cache --configuration-cache-problems=fail` was used throughout; cache entries stored.

## Remaining acceptance and boundaries

**Visual verification and device acceptance were not performed.** The writer did not operate a phone or any emulator. Independent review must bind screenshots and prescribed emulator regression to the final source/APK: actual image decode/render/reopen, loading/cancellation/error/configuration, approval, delete confirmation and recovery-blocked feedback, English/Chinese, narrow layout, large fonts and light/dark themes. The new explanatory text was resource-checked, not visually accepted.

Real chat-model intent for image generation versus prompt writing/API explanation, real provider behavior/billing, unsupported tool endpoints, API33 execution, Release/R8 and physical power loss remain unverified. Final-head independent review, CI and emulator regression remain mandatory before reviewer-authorized merge. PR104 stays independently tracked; its reviewer repair was reconciled against exact commits/verdict/state, without copying that work into this PR. No protected files, scheduler, phone operation or writer merge.
