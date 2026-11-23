# Issue 39 verification

Implementation `e974bbdb2c49b8480fdc7f2368e9afd6384ed620` was tested on Windows with JDK 25. The Debug APK SHA-256 was `6f3b229760d870e980c56835c9982193c4cc166e926c293397aeedeace4f95b8`.

On the original implementation, both new behavioral assertions reproduced the incorrect truncation message. On the implementation above, all 42 targeted JVM tests passed with zero skips. The same Gradle invocation assembled Debug and stored the configuration cache with configuration-cache problems treated as failures (69.33 seconds).

The run-loop tests checked the repeated-call limit in history and two client calls in a paused turn in the next model request. Both paths retained `CANCELLED` and `NONE`. Tests also covered actual truncation, interrupted registration and reopen, old records without a reason, new record round trips, and streamed checkpoint serialization. Raw failure and success logs are retained outside Git.

No device, live provider, visual, Release/R8 or Android API 33 runtime test was performed locally. Final-head CI and independent regression belong to the reviewer.

## Reviewer verification, round 1 (0 returns to writer)

On PR head `af8fb34d82ef2036d67175c09f3091a17ef51e1e`, the untracked `NonExecutionOverlapProbe` reproduced two failures: after two successful identical calls, a third complete call ending with `LENGTH` or `CONTEXT_EXCEEDED` wrote the repeated-call-limit message to both the ledger and model history. PROTOCOL 2.7 requires the truncation message. Both cases submitted only the first two calls and retained `CANCELLED`/`NONE` for the third.

Repair `fc4fb89349db2905b6203f3754aec9dc711132b4` gives truncation precedence when choosing the stored non-execution reason and adds a permanent regression covering both finish reasons. The GPU entry point `/opt/homelab-report/theta-gpu-test :agent-core:test --tests '*NonExecutionOverlapProbe*' --tests '*AgentRunLoopTest*'` passed all 31 tests (`GRADLE_EXIT=0`, zero failures). Both probe outputs now show the truncation message in the ledger and history, with two submissions and `CANCELLED`/`NONE` unchanged. Probe source and raw results are not committed.

The reviewer run produced no APK; the APK hash above remains evidence for the original implementation only.
