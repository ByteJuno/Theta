# Issue 24 — Chat and inline approvals

## November 19 durable replies and explicit context recovery

Tested source `54f77775bc65639c61213b82427b9bd4c3552809`, exact Git tree `1b961fb9f4e9fb8d55d47b3ee5f79edb0cee619a`; base `77b200999c1d77e14a1eb651957f0f8637922bf0`. The approved `/opt/homelab-report/theta-gpu-test` entry received that exact tree from an isolated writer scratch checkout. Strict `--configuration-cache --configuration-cache-problems=fail` execution returned `GRADLE_EXIT=0`, **199 tests, zero failures**, in 72.86 seconds wall time. Suites: ChatSessionOwnerTest (20), HostToolExecutionServiceTest (87), StoredRunJournalTest (2), ContextPolicyTest (65), BoundedHistoryTest (9), RunRecoveryTest (16). The GPU wrapper exposes totals/failures, not individual skip/error counts or cache reuse; no stronger claim is made. `verifyUiResources` ran as the app build prerequisite and `git diff --check` passed.

SQLite-backed RunJournal records submissions and bounded model attempts separately from replay history. Refused, filtered, failed-stream and cancelled replies remain accessible after cold open; paging does not inject them into later model requests. An in-flight request is not mislabeled interrupted. Fault tests retain either the submitted interruption record or the complete reply after uncertain commit, fail closed on the poisoned handle, and preserve raw text and original history.

Chat now supplies its durable task boundary and goal to the existing context policy. The 34-pair / 2,176,000-character regression first pauses without requesting the model, then recovers after explicit summary opt-in, retaining all 70 original/result messages. Optional summaries use the already configured provider/model, require the user to enter its verified token window, conservatively charge UTF-8 bytes plus framing/output reserve, and keep the existing step/retry limits. No exact tokenizer or automatic window discovery is claimed. Checkpoint continuation is explicit, survives restart, preserves cumulative accounting, and rejects a changed tool catalog rather than guessing aliases. This does not implement deferred issue #31 full catalog restoration. The reconciliation UI requires the exact current receipt and explicit acceptance of possible remote duplicate effects; it never approves local tools or releases resource isolation. Existing unknown-effect, late-exit, approval, cold-result-reader and built-in isolation tests remain passing.

Original review probes on head `77b20099` are retained in `event-runs/100bda15-3800-4fb9-906b-8feef1820ca0/reviewer-raw.jsonl`: refusal `liveRetained=false/coldRetained=false`, and oversized conversation `requests=0` on two accepted messages. No probes are committed. New permanent tests cover those cases plus filtered/interrupted replies, cancellation, paging, journal write uncertainty, explicit checkpoint selection, changed catalog rejection and exact reconciliation.

Intermediate failures remain available: the first GPU source staging was rejected before tests because Windows Git archive transformed LF to CRLF; command-local `core.autocrlf=false` restored exact tree parity. Initial GPU build failures were traced locally to a UI resource guard on a state discriminator, followed by a missing paging argument. The 94-test local run passed all 76 core tests but exposed two new test-fixture mistakes (stream producer versus consumed preview timing, and a network retry unexpectedly succeeding). After correcting only those fixtures, local chat passed 18/18. The next GPU run passed 111/112; its sole failure was the new reconciliation test counting a failed request as a completed step. Exact local XML confirmed the retry itself worked; the test now compares preserved completed-step accounting. Final 199/199 covers the corrected sources. No timeout, production assertion, recovery barrier or limit was weakened.

APK not generated; APK SHA-256 not applicable. No phone/emulator operation. **Visual verification remains outstanding**: this writer has no permitted local render/device window and does not take the reviewer's emulator. Test the retained-reply section, scrollable recovery card, summary-window input and confirmation action on the final APK in Chinese/English, small screen, large font, keyboard and light/dark themes. JVM SQLite tests are not Android process-death or actual provider/network acceptance. Final-head independent review, lifecycle/signature device regression and CI remain mandatory before reviewer merge. Logs, source archives, stage parity and validation receipts remain outside Git under the run directory and `validation-issue11/issue24-100bda15-*`; the following summary-only commit changes no tested code.

## November 19 built-in isolation integration

Tested source `2cce30cf92f204fada9e25cc4d0bc10b572c22de`, Git tree `3f57b04ae54938eef8a6eb3db025a7fa98d2c61d`, integrates reviewed main `ea571d28776bb4a9ba5d9f2741c5341c182c1e64`. The single content conflict was in host dispatch. The integration retains the chat bridge's revision guard, cancellation clock and observer setup while using the trusted built-in tool key for both capacity reservation and invocation identity. Shared-resource leases and actual-exit accounting remain enforced. DESIGN and PROTOCOL match main byte-for-byte; no protected-file changes relative to main.

**143/143 targeted JVM tests passed**, zero failures, errors or skips: ChatSessionOwnerTest 14, HostScreenToolTest 5, HostToolExecutionServiceTest 87, PluginExecutionBudgetTest 4, InvocationLedgerTest 32 and HistorySchemaTest 1. Strict `--configuration-cache --configuration-cache-problems=fail` validation stored the configuration cache; elapsed 91.68 seconds. The added bridge regression holds a noncooperative screen worker after timeout, rejects a stale claim revision without allocating capacity, blocks another tool sharing the screen resource, and executes clock plus blob_read through the chat execution bridge before the old worker actually exits. Existing direct-dispatch, unknown-effect/cold-restart, approval, alias and chat tests also pass.

Logs and fresh test XML: `validation-issue11/issue24-2f19bb84-isolation`. Source binding and remote review receipts: `event-runs/2f19bb84-cc9a-4573-909c-b134194189f2`. The following summary-only commit changes no tested sources. The initial merge command's missing-identity failure and the expected content conflict are retained in local worker evidence; no test failure occurred in this integration run.

APK not generated; APK SHA-256 not applicable. No phone/emulator operations, current-build visual verification, API33 runtime, R8 or physical power-loss test. This integration changes no UI resources or layout. The previous lifecycle-helper tests retain their original source binding; no new device pass is inferred. Independent final-head review, lifecycle/signature emulator regression and CI remain required before reviewer merge.

## November 19 clipped acceptance target repair

Tested source `9d0b2f0a174ea190a0791c4fd89c4b5d1cadc155` preserves reviewer repair `14a8d11d1947d3318451e5cef2e887c7e182ffba` and changes only the lifecycle observation helper and its regression tests. The reviewer verdict, commit parent and reviewer `own_commit` were cross-checked before the explicit branch fast-forward.

Reviewer run `20261119-110921`, dedicated emulator `emulator-5580`, failed `upgrade-and-data` while waiting for `Fixture runtime normalV2`. Its source log binds the run to `14a8d11d`; the Release APK SHA-256 was `d672a724bf978fa2129179f14077d940a4bc84f42e577cae3f3bcda71dbb92c2`. The raw results, screenshot, XML, search trace and fixture events were read. V2 enabled and reached `counter:3`; the screen remained in plugin management after a rejected version import. This is not evidence that the runtime failed to upgrade.

The search trace exposes `Fixture page` at `[44,2157][1036,2289]` under a `[0,268][1080,2208]` scroll viewport. The former helper taps `(540,2223)`, outside that viewport. The new regression reproduces this concrete coordinate defect; the retained failure trace does not record the preceding successful lookup/tap, so it cannot prove that this was the exact original tap. The helper now intersects a target with its actual XML ancestors and taps the visible intersection, `(540,2182)` for that geometry. Fully clipped or invalid targets keep searching under the original deadline; unrelated toolbar siblings are not clipped. Exact-text uniqueness and runtime/counter assertions remain unchanged. No retry of the page action or timeout increase was added.

`python -X utf8 -I -B scripts/acceptance-observation-test.py -v`: **35/35 passed**, zero failures/errors, 0.589 seconds wall time. The red run on the old helper retained 7 failing subtest assertions and 1 invalid-bounds error; all 30 earlier tests stayed passing. Five new tests cover bottom/top clipping, horizontal clipping, a fixed toolbar sibling, and fully clipped/invalid targets. Offline replay of the actual captured XML retained the existing visible page center. These are script tests with synthetic device/clock boundaries, not Android acceptance. `git diff --check` passed.

No APK generated, no Gradle run, and no device operated in this repair. The screenshot is failure evidence for the old reviewer head, not visual acceptance of this commit. The old signature run returned `SIGNATURES_EXIT=0`; its result is not transferred to the new head. Final-head lifecycle/signature regression, CI, chat visual checks and Android restart remain the independent reviewer's gates. Original evidence and red/green output are retained outside Git in `event-runs/d717aa4f-2339-49ad-91d7-0ac94a1d85bf`.

## November 19 integration of approval timing and blob storage

Tested source `668111458c5f5ee11f8ff8cb13ec4132ec71366d` includes main `f9ee70a953f53721eaae32422b61e99e6818002e`. Chat and cold approvals use the session store. Live and recovered dispatch retain the revision check and use the host clock to distinguish cancellation from timeout. The clock is bound before the worker starts. The integration also includes main’s updated SQLite blob storage.

99/99 JVM tests passed on this source (87 HostToolExecutionServiceTest, 12 ChatSessionOwnerTest), with zero failures, errors or skips. Strict Gradle configuration cache was stored; the final run took 43.93 seconds. The first integration exposed a positional clock argument mismatch during unit-test compilation. The constructor was corrected and the original log retained under `validation-issue11/issue24-ea7566b0-main`. Final test XML and Gradle output are in `validation-issue11/issue24-ea7566b0-final`.

No APK was generated. Visual layout, Android restart and the different-signer device scenario await the independent reviewer’s final-head regression and CI. This integration changes no UI resources.

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
