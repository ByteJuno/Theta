# Issue 173 validation

Implementation SHA: `0d3295bfb5660f74ae6c4e6ea01dab487c5671fa`
Validated Git tree: `66416c8a66ddca30ac9a35a290113d18dd53540f`
PR: https://github.com/ByteJuno/Theta/pull/186
RETURN: https://github.com/ByteJuno/Theta/pull/186#issuecomment-7235088676
Base/reviewer head: `b20ad351075fd0299c8ac88ed42860de9cfd05bd`; latest fetched main: `7df1b70981cc555e5ea529db98a42f4f7f9e701f`. Reviewer fixes are included; remote branch/main ancestry verified before final validation.

## Current repair and evidence

Role-only terminal content now handles finish reason/usage without a parts lookup failure. Request-wide IDs pair calls and results, preserve first wire IDs, avoid generated-ID collisions and remain deterministic after database reopen. Signed native parts are unchanged; an unresolvable signed duplicate triggers the durable continuation checkpoint before request estimation or transport. Original bindings and signed payloads are retained.

- Local Gemini regression: 18 tests passed.
- Existing GPU entrypoint `/opt/homelab-report/theta-gpu-test :agent-core:test --tests '*Gemini*' --configuration-cache --configuration-cache-problems=fail`: `TEST_HOST=gpu`, `GRADLE_EXIT=0`, `TESTS total=18 failed=0`, 54.789 seconds. Same Git tree as the implementation commit.
- `python scripts/preflight.py` passed in 231.108 seconds, strict configuration cache reused. `:agent-core:test`: 711 tests, 0 failures/errors, 1 skipped. `:app:testDebugUnitTest`: 871 tests, 0 failures/errors, 7 skipped. Both suites executed fresh. `:app:verifyUiResources`, `:app:lintDebug`, storage-fixture verification and Kotlin compile passed.
- Commit tree exactly matches the GPU-tested staged tree; no source changes during final checks. Eight implementation/test files only; this writer did not commit a testing summary, protected-file edits or diagnostic probes.

Initial failures preserved: targeted-1 missing test import; targeted-2 incomplete test ledger fixture; targeted-3 missing matching test tool catalog; targeted-4 test estimator also rejected valid continuation inputs. Fixtures were corrected without weakening production assertions; targeted-5 and final validation passed. Logs/XML remain outside Git at `E:\Desktop\AI研究\theta-auto-loop\event-runs\94901b0b-5408-4126-9805-f330cff93af1`. A late thread-inspection attempt found the test worker had already exited; preflight completed normally.

## Coverage limits

未生成 APK（APK SHA 未生成）；未操作手机或模拟器，视觉未验证。本轮仅修改 agent-core 协议/运行逻辑及测试，没有 UI 改动。真实 Gemini/兼容网关、真实密钥、多模态联网与签名多轮没有执行；测试为确定性 SSE 和持久化历史场景。API33 设备运行、R8、当前提交 CI 与审核端正式模拟器回归仍待独立审核；此前 CI 结果不转移到本提交。原 PR 的 Gemini 配置/模型发现/思考档位/提示及连续 Markdown 的实际渲染仍未验证。GPU 入口此次只执行 JVM 测试，不代表设备回归。

Earlier implementation evidence remains historical on `d36ffd516bd3ded791ccc24327d9ff92d89c0e80`; reviewer-added fixes and final current-tree checks supersede those counts for this submission. The existing full PR scope includes native SSE, schema/tools, signatures, image input, usage, discovery and first-step thinking recovery; no new claim of real-account or rendered-UI acceptance is made. Non-streaming generateContent HTTP, image generation, server tools and automatic continuation-token handling remain outside the implementation. Over-limit replay fails explicitly without stripping signatures.

## Reviewer round 2 (2026-11-28)

Reviewed head: `fc60f23e48ae99f6cc7f898410867c17349e1875`; prior writer returns: 1. The implementation tree above matches its commit, and the reviewed head differs only in this summary. Existing suite evidence was reused.

Reviewer fix: `43dc350adfce2c62aa7a1973c69ffe909468bf64`; tested tracked tree: `a6ddd90cc731f0cd9c48cec4554452fde2472bfb` (two diagnostic Probe files were additionally present, untracked and excluded from the commit).

- GPU probes reproduced two defects before edits: signed empty text, alone or following only thought text, completed with no visible answer and entered replay history; prior-history summarization consumed the first-step thinking-recovery gate, leaving the first generation failed at `high` with no capability notice.
- Empty signed answers now follow the existing empty-response policy: at most one generation-only retry, original replay retained in the attempt journal, no empty answer in durable conversation history. Possible server execution still pauses without retry. Visible signed answers retain their original behavior.
- Summary reservations still count toward durable step/retry budgets but no longer consume the first assistant step for thinking recovery. Inherited steps and output/tool-loop guards remain effective. Regression coverage includes SQLite reopen and unchanged cumulative accounting.
- Final existing GPU entrypoint: `/opt/homelab-report/theta-gpu-test :agent-core:test --tests '*Gemini*' --tests '*ReasoningRecoveryTest*' --tests '*AgentRunLoopTest*' --tests '*ContextPolicyTest*'`; `TEST_HOST=gpu`, `GRADLE_EXIT=0`, 122 tests, 0 failures (120 committed regression tests plus 2 untracked probes). The empty cases retried once and produced a visible answer; the visible-answer control sent once. The compaction probe completed with reasoning `[high, null]`, one notice and two accounted steps.
- Failure evidence retained: both initial probes failed their expected-behavior assertions on the reviewed head. The first combined regression run had 122 tests and 1 failure: the new compaction fixture omitted an output reserve, so its budget was unknown and no summary ran. Setting the fixture's output limit to 100, matching the successful probe, exercised the intended path; assertions and timeouts were not weakened. The final run above passed.

Only core behavior/tests changed in this reviewer fix. No APK was produced, no device/emulator or real model API was used, and visual behavior remains unverified. Earlier full-suite results are not relabeled as results for the reviewer fix. The final summary update changes no tested source; final-commit automation remains responsible for its own checks.
