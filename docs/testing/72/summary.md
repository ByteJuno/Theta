# #72 — Runtime approval wait budget

Previous tested implementation: `6521a55782c97b7f89ae818484fc5712b5a59067`. APK: not generated (no SHA-256).

| Local JVM suite | Passed |
| --- | ---: |
| `:app:testDebugUnitTest` — `HostToolExecutionServiceTest` | 84 |
| `:app:testDebugUnitTest` — `PluginInvocationTest` | 10 |
| `:agent-core:test` — `RuntimeDecisionTest` | 12 |

106 passed, no failures, errors or skips; 59.6 s with `--configuration-cache --configuration-cache-problems=fail` (cache entry reused).

- Virtual time at the host bridge: approval after the original idle deadline succeeds. A call with 40 ms of idle or total execution time left still times out after those same 40 ms once approved.
- Two different prompts consume 350,000 + 250,000 ms. At the deadline, approval is rejected even without timer delivery; neither ordering with cancellation admits the second action.
- With the independent alarm delivered at 600,000 ms and a held completion handler, the existing timeout path preserves WRITE/UNKNOWN and the occupied execution slot until actual exit. Existing cases also cover request renewal, withdrawal, denial, unattended policy, storage failure and isolation.

- Review return on `f0db658`: the old host admitted a late approval after simulated suspend and timed out after a blocked ROOT choice. New host regressions cover 10 s awake + 590 s asleep, both decision-first and cancellation-first, with no allow receipt or tool action. With 5 ms of idle time left, a 6 ms blocked policy check still allows a subsequent one-time approval. A permitted choice whose policy check crosses the approval deadline cannot create a grant.

No UI changes or device access. Android runtime/API 33, R8, independent final-head review, prescribed emulator regression and CI are outside this local result.

## Silent wake after approval expiry

Implementation `cb0cc6d2d8aac7f8bb8c345e313968feff38a8fa`. The reviewer reproduced the old timer on `f00ebb8`: after 10 seconds awake and 590 seconds suspended the ledger still showed RUNNING and a pending request, until 590 more seconds of awake time elapsed. The new host regression failed against the old scheduling path (one test, one timeout).

With the fix, the same virtual wake delivers the elapsed deadline without a decision, cancellation, progress or additional awake time. The ledger records TIMED_OUT and clears the prompt without an allow receipt. The execution slot stays occupied while the worker's completion handler is held. Local JVM: 85 HostToolExecutionServiceTest and 10 PluginInvocationTest cases passed, zero skips, failures or errors, 49.4 s with strict configuration cache (stored). The test drives the host using two simulated clocks. Android's absolute wait and system time-change receiver were compiled; physical suspend/resume and system clock changes were not exercised on a device. No APK generated. Final-head review, prescribed emulator regression and CI belong to the reviewer.

## Run cancellation at the approval deadline

Implementation `b88207200b197b7833e7ede884cd521694497fc3`. The host-backed run cancellation regression failed on `15c29e1` with expected `TIMED_OUT`, actual `CANCELLED`. The first terminal write now records `CANCELLED` at 599,999 ms and `TIMED_OUT` at 600,000 ms after 10 seconds awake and 590 seconds of simulated sleep. Delivering the already queued deadline callback after cancellation preserves the timeout. Both cases clear the prompt without a receipt, keep the execution slot until actual exit, and cancel the unsubmitted sibling with effect `NONE`.

Local JVM checks used strict configuration cache. Four suites passed in 45.5 s (86 host, 10 invocation, 30 ledger, 24 run-loop). After strengthening the queued-callback assertion, the 86 host cases passed on the final source in 38.7 s. The other 64 cases cover unchanged source and tests. Both runs stored the cache. No APK generated or device exercised. Final-head independent review, emulator regression and CI are pending.
