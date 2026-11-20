# Issue 83 — foreground task execution

## CI return correction — active recovery inspection

- Source: `18f438f40df3914951884bf5102738031c2b4113`; tree: `697cdbe94b17d3d4febc8838a7aaa0b9f0532033`. Based on reviewed head `2b084910266399f73acc1019cdef88454767ecbb`; the following evidence commit changes only this summary.
- Exact-head CI run `44663343016`, build job `136046596810`, failed in `ChatSessionOwnerTest.oversizedHistoryOffersExplicitBoundedRecoveryWithoutLosingOriginals` with `TimeoutCancellationException` (540 app cases, one failure). The original remote log is retained. The isolated writer baseline passed in 8.051s, so the CI timeout itself was not reproduced locally.
- The 100ms status ticker called `checkpointInfo()` during active summarization: this validates `runSnapshot()` and hashes the full checkpoint source under the history monitor. It also performed recovery reconciliation intended for stopped runs. Recovery controls are already hidden while active. Inspection now runs only after the task stops, outside the retriable StateFlow update lambda. Active ledger/approval/notification updates still run; terminal/cold recovery continues to validate original durable data. No storage integrity checks, deadlines, assertions or request budgets were weakened.
- New regression pauses a real SQLite-backed summary request, verifies no active checkpoint/reconciliation is exposed, then cancels and verifies both durable recovery records return. Corrected test failed before the guard with active `SUMMARY_RESERVED` checkpoint exposure and passed after it. Initial test-fixture attempts were below the request-view budget and failed before the intended assertion; those failures remain preserved and are not counted as successful reproduction.
- Final tasks: `:app:testDebugUnitTest --tests *ChatSessionOwnerTest :app:assembleDebug`, through `run-validation.py` with strict `--configuration-cache --configuration-cache-problems=fail`. **34 passed; zero failures/errors/skips**, 61.33s including build, configuration cache stored. Original oversized-history case: 3.780s; new regression: 0.896s. Timing is observational, not a cross-machine performance guarantee.
- New Debug APK SHA-256: `3f2309df84a851ff22bbf761c01151ef333f2bc3f98f30f283e5201e4c5222a3`. Source file digests checked before publication. No new lint/resource run: neither layout/resources nor service APIs changed. No protected files changed; only this issue's single summary is tracked.
- Raw CI log, source manifest, APK and validation receipt: `event-runs/3f903f7f-c254-4c58-a44f-c0ef03919e29`; failed and successful runs: `validation-issue11/issue83-3f903f7f-{baseline,red,green,red-r2,final}`, all outside Git.
- **Independent final-head CI/review and required foreground-service/emulator acceptance remain pending. Visual and device behavior remain unverified.** No phone/emulator was operated and no scheduler was created. Earlier APK/test receipts below remain bound to their original commits, not this repair.

## Source and artifact (original writer validation)

- Implementation: `1c8aa5dd29cc35541c814c092d6492bfefb48e42`.
- Git tree: `06e9ca219b108f5822d23b61ff5a093c8dae124d`.
- Base: `a9194436561fd2a3f3e581fd95f1e4af38012b90` (PR98 merged).
- Debug APK SHA-256: `46839ec0a46bcca348c068fc781d599c420d6ac01b93a55678b718f72b2fe6ec`.
- Writer documentation-only commit `f98f663e75f2920ad0c8acc90b1a6b5a367d77e4` did not change that tested source or APK identity. The reviewer correction below changes source; this APK hash does not validate the correction.

## Delivered scope

The application-owned chat runner acknowledges the foreground service before requesting the model or approving a cold pending tool. Running notifications show model/tool progress and a token-bound Stop action through the existing owner cancellation path. Old notification actions cannot stop a later execution. Pending approvals stop foreground presence and show an ordinary attention notification; completion, failure, cancellation and paused outcomes stop the service. Noncooperative tool worker exit and resource isolation remain ledger-owned, not service-owned.

The service is non-exported, non-sticky and uses `specialUse` (API34+) with its subtype declaration; API33 uses the ordinary foreground entry. Interactive inference plus local plugin execution is not media playback, device messaging or a bounded data transfer. Current Android timeout documentation specifies the fixed time budgets for dataSync/mediaProcessing/shortService, not specialUse. This is not a guarantee against user stop, system process death, OEM battery policy or Play policy review. A renewable, timeout-bounded partial wake lock exists only while foreground service work is active and is released when it stops.

First-use battery guidance and notification permission handling reuse Material dialogs and bilingual resources. Dismissed/declined prompts do not recur. Denied notification permission does not falsely promise visible notifications; the dialog directs users to the chat Stop action. Explicit recovery remains required after an unavailable service; no automatic replay, boot receiver or scheduler is added.

## Executed checks

Windows, JDK25 and Android compile SDK37; `run-validation.py` clears release-signing environment variables and applies `--configuration-cache --configuration-cache-problems=fail`.

Final command tasks: `:app:testDebugUnitTest --tests *ChatSessionOwnerTest :app:verifyUiResources :app:lintDebug :app:assembleDebug`.

- 31 tests passed, zero failures/errors/skips, 95.82 seconds for the combined build; configuration cache stored.
- Coverage includes foreground acknowledgement before model submission, admission rejection without a model request, durable paused recovery, approval foreground suspension/restart, failed restart without granting approval, cold allow/deny admission, stale notification cancellation, ordinary completion, model filtering/context pause, active noncooperative tool cancellation and unchanged ledger exit truth.
- Existing chat/SQLite/approval/restart/context regressions in that class pass.
- Bilingual resource guard passed. Lint: zero errors, 25 warnings; the added SharedPreferences usage has a stylistic UseKtx warning, not a correctness error. Debug APK assembled; no release signing used.
- `git diff --check` passed. Protected paths unchanged. Existing two stashes and untracked `scripts/__pycache__/` retained.

## Failed attempts retained outside Git

- Initial resource guard rejected preference-storage keys inside a Compose file. The keys now belong to a non-UI preferences owner; no guard was weakened.
- Next attempt rejected a test fixture trailing-lambda signature after adding the presence dependency. Parameter order was corrected; no assertion or timeout was relaxed.
- An intermediate 30-test pass preceded the final admission-recovery improvement and is not claimed as final-source validation.
- Raw evidence: `event-runs/f2b122ad-435c-41e0-8001-8dc870ed4333` and `validation-issue11/issue83-f2b122ad-{initial,r2,r3,final}` outside the repository. Only this issue summary is tracked.

## Remaining acceptance and limits

No phone or emulator was accessed. No dedicated authorized writer renderer was available; other tasks' emulators are excluded. Actual rendering is **not verified**: English/Chinese battery and permission dialogs at small screen/large font/light-dark themes, running/approval notifications, and their actual touch targets.

The independent reviewer must validate the final head and APK: long-running task completion after Home and screen lock, notification Stop cancellation, approval transition to ordinary notification, resume after approval, notification-permission denial, service rejection, cold recovery and wake-lock/service cleanup. Prescribed lifecycle/signature regression and CI remain mandatory before reviewer-authorized merge. OEM phone acceptance belongs to S2 trial, not this writer run. API33 device execution, process death, Release/R8 and real-provider network behavior were not exercised.

References informing this implementation:

- Android service type/subtype requirements: https://developer.android.com/develop/background-work/services/fgs/service-types
- Type-specific timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
- Background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/launch
- Now in Android notification implementation (permission check, channels, immutable activity intent; adapted to platform notification APIs, not its WorkManager news-sync model): https://github.com/android/nowinandroid/blob/main/core/notifications/src/main/kotlin/com/google/samples/apps/nowinandroid/core/notifications/SystemTrayNotifier.kt

## Reviewer correction (round 1)

- Source commit: `9bc251dab98a5310f3a4ac1d6b3415a3d5bde6d0`; tree: `e5f28e6a0f411b5b0936de3d72c7f9a79ba9d145`.
- A temporary JVM probe reproduced an internal approval with `state=RUNNING`, `pendingInternal=true`, and presence still `Tool(name=host_clock)`. The foreground service therefore did not switch to the approval notification while this worker waited. Progress now excludes workers with pending internal approvals from active work, while other running workers continue to retain foreground presence. Ledger state and execution leases are unchanged.
- Added regression coverage for internal allow/deny and the transition from a working parallel sibling to only a waiting worker.
- Validation through the authorized GPU entry: `/opt/homelab-report/theta-gpu-test :app:testDebugUnitTest --tests '*ChatSessionOwnerTest*'`; `GRADLE_EXIT=0`, `TESTS total=33 failed=0`. The commit contains exactly the production/test changes exercised by this run. This summary update is documentation only.
- Probe history: the first attempt had a test-scaffold compilation error (suspend initialization in a property initializer), with zero tests executed. After correcting the scaffold, two probes ran: the internal-approval defect reproduced; the separate hypothesis that an ordinary UNKNOWN result stops foreground presence was disproved (`requests=2`, `EXIT_CONFIRMED`, `recoveryPending=false`, presence `Model`). That second probe's hypothesis assertion failed and is not counted as a passing check. No existing assertions or deadlines were relaxed.
- Probes remain untracked; raw evidence is retained in the reviewer execution transcript. No corrected APK was built in this review. Final-build lifecycle/rendering/CI gates remain external; visual quality and device behavior are still unverified. Writer return count remains zero.
