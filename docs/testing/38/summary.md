# Issue #38: reasoning-only empty responses

- Implementation commit: `08c864d716588f69bcaa3b8027152266e7d3b229` (based on main `3263649762ae7756e44500dec42623c754d03b8c`).
- Only reasoning parts, with or without opaque replay, now follow the existing empty-response path: generation-only requests retry at most once within the existing retry budget; a second empty answer fails with `Empty model response`. No discarded reasoning enters replay history.
- Potential server-tool execution pauses with unknown state and no retry/fallback. The raw journal and event consumer retain the reasoning and signature; SQLite reopen verifies the stored bytes and durable uncertainty barrier. Visible answers, tool calls, explicit PAUSED continuation and non-reasoning replay behavior retain their existing contracts.

## Writer validation

- Baseline on unmodified production code: 7 new tests, 5 expected behavioral failures and 2 passing controls. Original failure evidence retained outside Git.
- Final invocation: **53/53 tests passed**, zero failures/errors/skips: `ReasoningOnlyResponseTest` 7, `AgentRunLoopTest` 27, `RunRecoveryTest` 16, `StoredRunJournalTest` 3.
- Command: `:agent-core:test --tests '*ReasoningOnlyResponseTest' --tests '*AgentRunLoopTest' --tests '*RunRecoveryTest' --tests '*StoredRunJournalTest' :app:assembleDebug` through the existing validation runner, with `--configuration-cache --configuration-cache-problems=fail`. Cache reused; final invocation **26.03 seconds**.
- Debug APK SHA-256: `4cd288a051da0ee1684f0b168c5a0fde8851074b3e06910be08a4e79712f9875`. APK and compiled-class hashes retained outside Git. Exact-APK-bound API33 direct-JDK scan passed: **405 classes, 0 violations** (compiler bootstrap exclusions verified against this APK).
- Two test-fixture errors were corrected without weakening assertions: `Reasoning.summary` field name and decoding the existing THS2 storage framing. Their logs remain alongside baseline and final evidence.
- Raw evidence: `event-runs/cd28ed27-ad56-4f23-8b6c-d0914eb7bc1e/`; Gradle/XML: `validation-issue11/issue38-cd28ed27-{red,red-r2,green,final}/`. Only this summary is committed as testing evidence.

## Writer validation limits

The writer implementation changed response classification, without UI layout or resource changes. No device, visual, real-provider, Release/R8, API33 runtime, or physical power-loss validation was performed. API scanning is static compatibility evidence. No protected files changed. Final-head CI, independent review and prescribed regression remain reviewer gates; the writer does not merge.

## Reviewer repair and validation (round 1, no writer returns)

- Reviewed head: `7ce1b0b270f33bf6a39cb5c16f5679c07fc32f7d`. Its only difference from the writer's tested implementation was this summary, so the writer's 53 passing tests were accepted without a reassurance rerun.
- A new visibility probe found a protocol 2.7 regression: reasoning-only replies were absent from `journalPage()` both live and after SQLite reopen. Generation failure expected 2 replies, successful retry expected 1, and server-unknown pause expected 1; all returned 0. Raw reasoning and signatures were present with disposition `KEEP`. All 3 probe tests failed before repair.
- Repair code: `3433f073c9c9bebfb5ba560f81242009adee05ce`. The reducer now classifies empty terminal replies once for both the run loop and journal. Discarded replies remain in the chat archive, use the existing bilingual empty-response label, and never enter model history. The archive notice was updated in both languages; no layout or schema changed.
- The repaired source passed **59/59 targeted tests**, zero failures: `ReasoningOnlyResponseTest` 8, `AgentRunLoopTest` 27, `RunRecoveryTest` 16, `StoredRunJournalTest` 3, untracked `ReasoningVisibilityProbe` 3, and 2 `ChatSessionOwnerTest` cases covering reasoning retention and existing truncated-reply retention. Probe results were 2/1/1 archived replies live and after reopen, with disposition `DISCARD`; expected run states and replay-history sizes were unchanged.
- Command through `/opt/homelab-report/theta-gpu-test`: `:agent-core:test --tests '*ReasoningVisibilityProbe*' --tests '*ReasoningOnlyResponseTest' --tests '*AgentRunLoopTest' --tests '*RunRecoveryTest' --tests '*StoredRunJournalTest' :app:testDebugUnitTest --tests '*ChatSessionOwnerTest.reasoningOnlyRepliesSurviveRetryAndColdOpenWithoutReplay' --tests '*ChatSessionOwnerTest.incompleteTruncatedRepliesSurviveColdOpenAndNeverReplay'`. `GRADLE_EXIT=0`, `TESTS total=59 failed=0`. Probe source and raw failure/pass output remain outside Git.
- Repaired-source compatibility scan through the same entry point with `--api-scan`: `API_SCAN pass minSdk=33 hits=0`.
- The APK hash above describes the original writer implementation, not the reviewer repair. No revised APK, device, visual or real-provider validation is claimed. Final-head CI and prescribed regression remain external gates.
