# Issue 155 — Anthropic native protocol validation

Implementation SHA: `072f496d9816f0d6006b5b058c5cd769f2a6e827`
Tested tree: `15a34dd38a50d9f2df74fa5979adf18bab1f7c3d`
Branch: `auto/155-anthropic-protocol`. RETURN: PR #172, `IC_kwDOVkrS_s8AAAABrpQoOg`.

This candidate preserves reasoningLevels from preset selection through encrypted v3 settings, cold reload and core run configuration. A model declaring low/high selected at medium sends low after reload. Supported values are unchanged, missing/null capabilities remain permissive, and explicit empty capabilities or no known lower level use the model default with a notice. Known efforts use semantic order independent of metadata list order; unknown custom names are not assigned an invented rank. Original saved selection remains intact. Core settings defensively copy capabilities; requests and notices stay frozen through tool calls and image snapshot attachment. Settings edits take effect on the next run. Paired reasoning_strings.xml resources report the effective level; host entry files contain only state/wiring additions.

Validation on this implementation:
- The permanent cold-read regression failed on old production code (1 test, expected low / actual medium), then passed after the repair.
- Local targeted core 21 + app 26 tests: 47 total, 0 failures, 0 skips.
- Existing /opt/homelab-report/theta-gpu-test entry, host gpu: 47 targeted tests, 0 failures, 61.0s, strict configuration cache. Includes actual host tool-loop freeze/next-run behavior and Anthropic wire encoding.
- python scripts/preflight.py passed in 177.1s with --configuration-cache --configuration-cache-problems=fail: core 684 tests (683 passed, 1 platform skip), app 820 (819 passed, 1 platform skip). Skips: SummaryWorkspaceTest link/parent traversal and PluginUpdatesTest executable bit on Windows.
- lintDebug, verifyUiResources (623 English/Chinese keys), storage-fixture source verification and Kotlin compilation passed. No unrelated checks were broadened after success.
- Source hashes unchanged throughout final validation and verified again before commit. GPU preserves indexed executable modes and uses the unchanged committed gradlew LF blob. Final fetch includes PR branch 03dd27db16c6132166188cd1eac89f340354dd1c and origin/main 40330df3a2758b752f90ac495e1aca69f8483e66. Reviewer commits retained.

Failure evidence retained outside Git at `E:\Desktop\AI研究\theta-auto-loop\event-runs\8ec273de-2581-48b4-8ebd-0c79592a660d`: reviewer GPU output (medium instead of low), baseline permanent regression failure, and first targeted UI-resource check failure caused by wire strings in a composable; corrected by reusing the existing localized effort mapping, without weakening guards/assertions. No Probe file, raw log or generated test evidence is part of the implementation commit.

Historical limitations/failures retained: previous revision 4897af9ab8965c741576fd7484945623b0274c5a fixed new-run tool history separation, malformed rejected argument checkpoint continuation, context-too-long error classification and invalid preset parsing. Its local null-argument expectation failure, GPU archive exit 126, and old-head CI 46032750268 plugin ranking failure remain recorded at `E:\Desktop\AI研究\theta-auto-loop\event-runs\98d3e920-bce5-4cba-8115-aea21b0422ec`; none are represented as current-head CI evidence. The current full local preflight passes that plugin test; final published-head CI is still required.

APK: not generated; SHA256: not applicable. 未生成 APK，未操作手机或模拟器，视觉未验证；新增降档提示在中英文、小屏、大字体和明暗主题中的实际显示仍待审核端检查。真实 Anthropic 接口、API33 运行、R8 与硬件未测；GPU 结果是 JVM 单测。未知能力保持原档位尝试；无已知较低档位或自定义档位无法排序时使用模型默认并提示。独立审核、最终发布 head CI 与正式模拟器回归仍须完成。既有 64 KiB 回放封装和未知服务端工具块保守拒绝限制保留。
