# Issue 24 — Chat and inline approvals

Implementation: `b5985b0`; validated source after main integration: `47fff50977ede06d10b2330eb018588ef00076ff`.
Base main: `0c8e97cd89dec865b6166bf2c207bef8bbf95a7c`.

## Delivered behavior

- Chat is the normal launch page. Plugin management/diagnostics remain reachable; recovery and incoming import entry points are retained. Existing lifecycle/navigation instrumentation and scripts follow the new entry.
- Application-owned chat uses the existing provider settings and shared SQLite owner. Streaming text appears before completion; session history survives store reopen. History/session pages are bounded.
- Tool details are collapsed initially while status and approval actions remain visible. Pending steps cannot be paged away. Allow/reject uses the host's ID-bound durable approval API. Cold decisions finish the saved tool step without replaying the model request.
- Shared host reservation precedes durable claims. Unavailable capacity cannot invent a worker. Existing actual-exit, unknown-effect, cancellation and stale-definition gates remain in force.
- Chinese and English strings use the existing resource checker/theme. Final history refresh now precedes publication of idle state, fixing the observed cold-approval UI race.

## Actual verification

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
