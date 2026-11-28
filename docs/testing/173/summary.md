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
