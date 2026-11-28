# Issue 155 — Anthropic native protocol validation

Implementation SHA: `c9cd641d4facfeac8d962480e05c7845407e3866`
Tested tree: `e4dbdf84ffb29c62395815c21d6656c0ea5ea865`
Branch: `auto/155-anthropic-protocol`. RETURN: PR #172, `IC_kwDOVkrS_s8AAAABrwbJTw`.

This revision stops superseded output-surface ranking before its next priority callback, letting the conflated task update restart selection promptly. It preserves all cleanup, callback deadlines and worker-exit semantics. No assertions were removed, no timeout was increased, and the existing slow-ranking/disable regression is unchanged. Only PluginOutputSurfaces.kt and its permanent test changed in this implementation commit.

Current implementation evidence:
- Original reviewer evidence on 2cf4a6d26712c0e635011511d87c60e7108e28fb: disable, clear and resources-closed events completed; backup21 APPROVAL show was absent after the 8-second wait. Preserved XML identifies the exact failed assertion at line 161. An obsolete pass traversing the remaining slow callbacks provides a concrete delay path consistent with that failure.
- Permanent latch regression on unchanged production code: 1 test / 1 failure, expected first callback for the new task but observed second callback from the stale pass. This distinguishes stale work from cleanup deadline failure.
- Windows output-surface suite: 13 tests, zero failures/skips, 33.9s. Existing original regression and the new latch regression pass.
- Existing /opt/homelab-report/theta-gpu-test entry on host gpu: 13 tests, zero failures, 72.4s, strict configuration cache, same source hashes. This is Linux JVM evidence, not emulator acceptance.
- python scripts/preflight.py passed in 188.9s with --configuration-cache --configuration-cache-problems=fail; configuration cache reused. Core 685 tests = 684 passed + 1 Windows platform skip; app 821 = 820 passed + 1 Windows platform skip; zero failures/errors. Includes the reviewer's new Anthropic pause/recovery regression.
- lintDebug, verifyUiResources (623 English/Chinese keys), storage-fixture source checks and compileDebugKotlin passed. Source hashes unchanged throughout validation and checked again after commit. No APK generated; SHA256 not applicable.
- Final fetch: origin/main 40330df3a2758b752f90ac495e1aca69f8483e66 and origin/auto/155-anthropic-protocol 2cf4a6d26712c0e635011511d87c60e7108e28fb are ancestors of this implementation. Reviewer changes retained, including the pause-turn side-effect barrier and regression. No protected files changed by this repair.

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\2d139b81-c130-489c-828e-b3b32fcc04db`. Original reviewer failure XML/run log, baseline failure, local/GPU manifests and results remain outside Git. Tests were not weakened and no temporary Probe files are committed.

Historical evidence is bound to its original source, not promoted to this head: 072f496d9816f0d6006b5b058c5cd769f2a6e827 preserved reasoning capabilities through presets/encrypted reload and froze supported effort; its Windows/GPU 47-test results and original medium-vs-low failure are in event-runs/8ec273de-2581-48b4-8ebd-0c79592a660d. 4897af9ab8965c741576fd7484945623b0274c5a repaired tool history separation, rejected-argument checkpoint continuation, context-too-long classification and invalid preset handling; prior failures remain in event-runs/98d3e920-bce5-4cba-8115-aea21b0422ec. Reviewer 2cf4a6d26712c0e635011511d87c60e7108e28fb added the server continuation barrier with 54 GPU tests; its review failure/probe evidence remains with the independent reviewer. This round adds no live provider or device evidence.

未生成 APK；未操作手机或模拟器，视觉未验证。本轮未改布局或文案，输出面实际呈现及此前降档提示的中英文、小屏、大字体、明暗主题仍待审核端验证。真实 Anthropic API、Android API33 运行、R8、硬件未测。GPU 为 Linux JVM 测试。独立审核、最终发布 head CI 与正式模拟器回归仍须完成；不把本地测试当作正式验收。
