# Issue 24 — Chat and inline approvals

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
