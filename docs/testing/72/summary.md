# #72 — Runtime approval wait budget

Tested implementation: `6521a55782c97b7f89ae818484fc5712b5a59067`. The submitted source and protocol appendix match the tested files. APK: not generated (no SHA-256).

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
