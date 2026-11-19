# Issue 24 — Chat and inline approvals

## November 19 pre-merge regression repair

Tested source `532be9424180183d1166e49787e8816498004af7`, Git tree `444c9a01bffeb3ebfcc857f8495ed7f11332d503`. The local alias test reproduced `UnsupportedOperationException` at line 199 on `fc9f165d` when clearing a one-tool snapshot. Restoring detached ordered copies preserves both caller behavior and the run's internal directory. The GPU checkout matched the tested tree and reported `GRADLE_EXIT=0`, 80 host-service tests, zero failures with strict configuration-cache flags.

In reviewer emulator run `20261119-082551`, the host displayed the expected signer mismatch. Repeated 1,164 px swipes in 250 ms alternated between the header and the fixture actions, skipping the version title. The signature acceptance entry now uses the shared overlapping list search. The middle-viewport regression failed on the original helper and passed after the fix. All 30 Python observation tests passed. Signer checks, exact text checks and device deadlines remain in force.

No APK generated in this repair. Device replay of the different-signer case, full final-head regression, CI and visual acceptance remain with the reviewer. Raw evidence is in `event-runs/a6a29238-d3e7-4106-9d23-10a9b3708199` and `validation-issue11/issue24-a6a29238-alias-before`.

## Round 3 review: renewed inline approval · 2026-11-19

After two prior returns, a GPU probe against `e14990743a46719e4d4696bfac138f9617880aa6` failed (1/1). Two parallel writes requested approval in one model response. While the second prompt was open, the first worker exited with `UNKNOWN`. Allowing the outdated prompt did not execute the second tool, but the chat waiter cancelled it instead of waiting for the renewed decision: `CANCELLED`, no pending request, zero receipts and one tool execution in total.

For repair source `f1ea016700f0594a16304b37ec21a7351ea99512`, the GPU probe instead observed `WAITING_APPROVAL` with the first call listed as unknown, zero receipts, one execution and the chat still active. The app run passed 13/13 (the probe and `ChatSessionOwnerTest`); the permanent regression checks that an explicit decision on the new ID can either reject without executing or allow exactly once. The core `UnknownContinuationTest` run passed 12/12. No probe, raw evidence or APK was added to Git. Visual interaction remains unverified; these JVM results do not replace the final emulator and CI gates.

## Cold approvals, task completion and catalog lifetime · 2026-11-19

Tested source `c3e623041724f84d48e5ff0f8021e7f1cca6880f`, Git tree `fad061a57288e24d4e756b8233249418ac2815f3`. The staging checkout reproduced that tree before invoking `theta-gpu-test`. The GPU runner reported Gradle exit 0, **84 tests, 0 failures/errors** for `ChatSessionOwnerTest`, `HostBlobOutputTest` and `ContextPolicyTest` with `--configuration-cache --configuration-cache-problems=fail`. The wrapper did not report skips or cache reuse. This round generated no APK. Android process death and visual interaction remain unverified.

- A SQLite snapshot taken while a real chat approval was pending was reopened with a different host catalog. Both structured and oversized results were approved once, recorded as succeeded/applied and projected into chat. The oversized result named the original collision-resolved reader. The raw blob remained readable and a later tool call also succeeded.
- At step 50, fault injection before or after the final append commit recovered the matching answer, accounting and chain. A subsequent question reached the provider. Continuing an exhausted paused task and retaining approval requirements for an accepted unknown write were also checked.
- After 24 independent text questions and a session switch, both frozen directories contained zero runs. A cancelled worker with a late, oversized return retained both directories until the host observed actual exit and persisted the result.

Original reviewer GPU findings at `ad98384`: successful cold write recorded as cancelled/unknown, a committed answer with an exhausted chain, and 24 retained catalogs. The first development check reported 1 failure in 84 tests because the new assertion compared raw result text to the protocol envelope. The corrected test checks the entire encoded envelope. Raw findings and both GPU logs are retained outside Git in `event-runs/8ae2e8ce-7fb1-43a7-b2bf-52ed1f7768ef`. Independent final-head review, prescribed regression and CI remain pending.

Implementation: `b5985b0`; validated source after main integration: `47fff50977ede06d10b2330eb018588ef00076ff`.
Base main: `0c8e97cd89dec865b6166bf2c207bef8bbf95a7c`.

## Delivered behavior

- Chat is the normal launch page. Plugin management/diagnostics remain reachable; recovery and incoming import entry points are retained. Existing lifecycle/navigation instrumentation and scripts follow the new entry.
- Application-owned chat uses the existing provider settings and shared SQLite owner. Streaming text appears before completion; session history survives store reopen. History/session pages are bounded.
- Tool details are collapsed initially while status and approval actions remain visible. Pending steps cannot be paged away. Allow/reject uses the host's ID-bound durable approval API. Cold decisions finish the saved tool step without replaying the model request.
- Shared host reservation precedes durable claims. Unavailable capacity cannot invent a worker. Existing actual-exit, unknown-effect, cancellation and stale-definition gates remain in force.
- Chinese and English strings use the existing resource checker/theme. Final history refresh now precedes publication of idle state, fixing the observed cold-approval UI race.

## Chat task budgets — 2026-11-19

The reviewer’s GPU probe on `9a5e339f` observed 50 model requests after 51 independent questions. Implementation `c76be86683b8fcb02e9d5fea35bbc10ab5aea5f5` persists the current chat task chain separately from the session. Finishing a settled task starts a new budget for the next question. Pauses, cold tool steps, checkpoint continuations, execution barriers and outstanding unknown-effect approvals retain their chain. Existing chat sessions without a stored chain use their original session-based identity until a task completes safely. The 50-step limit is unchanged.

The established GPU test runner reported Gradle exit 0, 73 tests and zero failures/errors across `ChatSessionOwnerTest` and `ContextPolicyTest`, with `--configuration-cache --configuration-cache-problems=fail`. The aggregate runner does not report skips. The formal chat tests check 51 independent questions with a cold reopen before question 51, and separately exhaust an unfinished task and confirm that a cold retry cannot submit another model request. The unknown-effect regression checks that a completed explanatory answer preserves the next write’s approval. The staging Git tree matched the implementation tree (`69ae6b11b848b89ed077cd1cb95f24a726bd6c2a`). Earlier GPU attempts exited 127 before running tests due to CRLF in the Windows source archive. The original reviewer verdict and all three runner logs are retained under `event-runs/1d04d991-0de4-40bf-8173-5299d7a2750d`.

APK not generated for this implementation. No device or visual validation in this round. Final-head review, prescribed simulator regression and CI belong to the review pipeline.

## Earlier main-integration repair — 2026-11-18

Validated implementation: `0a344f1ee2cabacfd8c668d92ff6eb323d9feed5`; integrated main: `500b49f7` (exact merge parent in Git).
The sole content conflict joined the chat streaming callback and the bounded-history request limit in the AgentRunLoop constructor. Both behaviors are retained; other history/ledger changes came from reviewed main. No layout or navigation changes in this repair.

**134/134 JVM tests passed**, zero failures/skips: ChatSessionOwnerTest 7, AgentRunLoopTest 24, BoundedHistoryTest 9, ContextPolicyTest 64, RunRecoveryTest 16, SqliteRunHistoryTest 9, SurrogateToolRoundTest 5. This covers streaming before completion, live/cold approvals and persisted sessions alongside bounded request/summarization and recovery. `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` succeeded; the build also ran `verifyUiResources`. Strict configuration-cache errors were fatal and the cache was stored. Elapsed: 64.29 s. `git diff --check` passed; no protected files changed.

Current Debug APK SHA-256: `63eadcc0ade7066ea275db16eb3663b4192a6d92e83032f206dda4c3854517c5`. Production/test bytes are recorded in the local source manifest; the following summary-only commit does not change them. Earlier APK/test results below retain their original source binding. No device operations or current visual verification: chat launch, bilingual small screen/large font/light-dark, keyboard/streaming, visible approval actions and Android restart remain reviewer simulator checks. No API33 runtime/R8 acceptance claimed.

Evidence: `validation-issue11/issue24-0a1d7756-main` and `event-runs/0a1d7756-85f5-4e06-aad7-bf236fabf1e2`. GitHub EOF query failures and the initial merge identity error remain in worker evidence; retries used explicit per-command author identity and preserved the worktree. Independent final-head review, prescribed regression and CI remain pending.

## Earlier verification (historical source binding)

- Final source `47fff50977ede06d10b2330eb018588ef00076ff`: **65/65 JVM tests passed**, zero failures/skips: ChatSessionOwnerTest 7, AgentRunLoopTest 24, HistoryLedgerConsistencyTest 7, RecoveryCancellationTextTest 2, RunRecoveryTest 16, SqliteRunHistoryTest 9. Strict configuration cache stored; elapsed 40.29 s.
- Before main integration: **143/143 passed**, including HostToolExecutionServiceTest 80. This evidence remains bound to that worktree; main changed the ledger, so it is not labeled a final-head rerun of all 80 host tests. The final-head chat integration exercises the host bridge and approval paths.
- Final source: **29/29 Python observation/navigation tests passed**. These are script unit tests, not device acceptance.
- `:app:assembleDebug`, `:app:compileDebugAndroidTestKotlin`, and bilingual `verifyUiResources` succeeded with strict `--configuration-cache --configuration-cache-problems=fail`.
- Debug APK SHA-256: `6e94716c2b36574d2c1ffd94bbd6dca48ba90b82c743cd05ef51a598b515f843`. Built from `47fff50977ede06d10b2330eb018588ef00076ff`; no release credentials were used. A subsequent summary-only commit does not change the tested production/test sources.
- `git diff --check` passed. No protected file changes. Raw logs/XML/APK remain outside tracked evidence.

## Failure retained and repaired

The recovery run's initial 87-test check had one failure in `storedApprovalSurvivesColdOpenAndUsesOriginalCallForBothDecisions`: idle was published before pending/history refresh. Production ordering was corrected; existing assertions/timeouts were preserved. The 143-test fix run and 65-test main-integration run passed. Earlier provider interruption and capacity-test failure records are retained locally.

## Remaining evidence

Visual quality is **not verified**: no authorized running local emulator was available. Cold-open fixtures use real SQLite/ledger and a synthetic model/tool on JVM; they do not establish Android process-death, real provider/network or physical-device behavior. No phone or emulator was operated.

Independent reviewer must verify final-head CI and prescribed simulator regression before merging. Check actual chat launch/plugin return, bilingual small-screen/large-font light/dark layouts, streaming and keyboard reachability, collapsed running/failed/unknown labels, visible allow/reject cards and process-restart pending decisions. R8/API33 runtime, device process death and release acceptance are not claimed here. Full run-catalog restoration remains issue #31; this scope restores only saved calls.

Local evidence: `event-runs/503a44f2-77c0-4f69-ae4b-e204c3955d6f` and `validation-issue11/issue24-503a44f2-{initial,state-fix,main}`. Original failure logs are preserved.
