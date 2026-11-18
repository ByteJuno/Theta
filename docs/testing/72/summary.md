# #72 — Runtime approval wait budget

Tested implementation: `439757124b809797099334967117e6852f1d9b3a`. The submitted source and protocol appendix match the tested files. APK: not generated (no SHA-256).

| Local JVM suite | Passed |
| --- | ---: |
| `:app:testDebugUnitTest` — `HostToolExecutionServiceTest` | 81 |
| `:app:testDebugUnitTest` — `PluginInvocationTest` | 10 |
| `:agent-core:test` — `RuntimeDecisionTest` | 12 |

103 passed, no failures, errors or skips; 61.6 s with `--configuration-cache --configuration-cache-problems=fail` (cache entry stored).

- Virtual time at the host bridge: approval after the original idle deadline succeeds. A call with 40 ms of idle or total execution time left still times out after those same 40 ms once approved.
- Two different prompts consume 350,000 + 250,000 ms. At the deadline, approval is rejected even without timer delivery; neither ordering with cancellation admits the second action.
- With the independent alarm delivered at 600,000 ms and a held completion handler, the existing timeout path preserves WRITE/UNKNOWN and the occupied execution slot until actual exit. Existing cases also cover request renewal, withdrawal, denial, unattended policy, storage failure and isolation.

No UI changes or device access. Android runtime/API 33, R8, independent final-head review, prescribed emulator regression and CI are outside this local result.
