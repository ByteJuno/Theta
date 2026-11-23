# Issue #38: reasoning-only empty responses

- Implementation commit: `08c864d716588f69bcaa3b8027152266e7d3b229` (based on main `3263649762ae7756e44500dec42623c754d03b8c`).
- Only reasoning parts, with or without opaque replay, now follow the existing empty-response path: generation-only requests retry at most once within the existing retry budget; a second empty answer fails with `Empty model response`. No discarded reasoning enters replay history.
- Potential server-tool execution pauses with unknown state and no retry/fallback. The raw journal and event consumer retain the reasoning and signature; SQLite reopen verifies the stored bytes and durable uncertainty barrier. Visible answers, tool calls, explicit PAUSED continuation and non-reasoning replay behavior retain their existing contracts.

## Validation

- Baseline on unmodified production code: 7 new tests, 5 expected behavioral failures and 2 passing controls. Original failure evidence retained outside Git.
- Final invocation: **53/53 tests passed**, zero failures/errors/skips: `ReasoningOnlyResponseTest` 7, `AgentRunLoopTest` 27, `RunRecoveryTest` 16, `StoredRunJournalTest` 3.
- Command: `:agent-core:test --tests '*ReasoningOnlyResponseTest' --tests '*AgentRunLoopTest' --tests '*RunRecoveryTest' --tests '*StoredRunJournalTest' :app:assembleDebug` through the existing validation runner, with `--configuration-cache --configuration-cache-problems=fail`. Cache reused; final invocation **26.03 seconds**.
- Debug APK SHA-256: `4cd288a051da0ee1684f0b168c5a0fde8851074b3e06910be08a4e79712f9875`. APK and compiled-class hashes retained outside Git. Exact-APK-bound API33 direct-JDK scan passed: **405 classes, 0 violations** (compiler bootstrap exclusions verified against this APK).
- Two test-fixture errors were corrected without weakening assertions: `Reasoning.summary` field name and decoding the existing THS2 storage framing. Their logs remain alongside baseline and final evidence.
- Raw evidence: `event-runs/cd28ed27-ad56-4f23-8b6c-d0914eb7bc1e/`; Gradle/XML: `validation-issue11/issue38-cd28ed27-{red,red-r2,green,final}/`. Only this summary is committed as testing evidence.

## Limits

This changes response classification, without UI layout or resource changes. No device, visual, real-provider, Release/R8, API33 runtime, or physical power-loss validation was performed. API scanning is static compatibility evidence. No protected files changed. Final-head CI, independent review and prescribed regression remain reviewer gates; the writer does not merge.
