# Issue 154 validation — final main alignment

Implementation SHA: `ed13314c908f28044c137507d4415cc70af0b853`
Tested tree: `b9d8076660360f68ff27d4a08d8c0bebec6f2de8`
Branch: `auto/154-openai-responses`
Fetched main: `341e7c195324dad4f049b29fbfc6a23545857716`
Observed remote PR head: `ca4b4ee2b434c227e23947f5323a11ef58050866`
RETURN: https://github.com/ByteJuno/Theta/pull/169#issuecomment-7220074638

The preceding RETURN implementation `7a523c8f190149b526615b5ae37b497933885930` was preserved. It accumulates ordinary response parts by content_index, reconciles text/refusal/content done events, rejects contradictory function argument completion before execution, and maps known Responses schema errors safely to responseSchema recovery. This resumption merges current main, including the independent reviewer's compact chat and stable composer fixes from PR170. Merge was clean with no manual source changes. PR169's remote head was already an ancestor; reviewer commits were not overwritten. Fetch and comment/head checks before commit found no additional movement or OWNER event.

`python scripts/preflight.py` passed in 168.1s with strict `--configuration-cache --configuration-cache-problems=fail`. App: 800 tests, 799 passed, 1 existing Windows skip, executed against the merged source. Core: 657 tests, 656 passed, 1 existing Windows skip; Gradle reported :agent-core:test UP-TO-DATE. Reuse is backed by exact hashes of 154 unchanged agent-core/sdk/build input files from prior implementation `7a523c8f190149b526615b5ae37b497933885930`; it is not a claim that the full core suite reran. Source-run results and the comparison are retained in core-result-reuse.json. Existing skips are PluginUpdatesTest.snapshotKeepsExecutableBit and SummaryWorkspaceTest's link/parent traversal test.

GPU-host JVM tests reran on the exact full merged tree: 24/24 passed, GRADLE_EXIT=0, 46.9s, STAGED_TREE matched `b9d8076660360f68ff27d4a08d8c0bebec6f2de8`. These cover OpenAiResponsesTest and ResponsesReplayCheckpointTest, including six permanent RETURN regressions. UI resources verified 604 paired English/Chinese keys; lint and storage fixture source/compile checks passed. The worktree hash manifest stayed unchanged throughout validation, and the committed tree equals the validated and GPU-staged tree.

PR170 completion was separately registered after checking GitHub mergedAt, mergeCommit `341e7c195324dad4f049b29fbfc6a23545857716`, its parents and the OWNER MERGED receipt. The PR170 validation record remains bound to its original implementation; this registration does not rebind prior visual or device evidence. Other PR cursors and evidence waits were preserved independently.

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\0d6e0677-f223-4bfd-8d32-b95b2ac54c49`. Files include preflight-final, gpu-final.log/json, core-result-reuse.json, verified-review-and-refs.json and final-refs.json. Initial GitHub TLS/EOF/timeout failures and a merge attempt rejected for missing local committer identity are preserved; both resolved without source loss. No test failure occurred in this round. Prior regression baseline and intermittent PluginOutputSurfacesTest failures remain in the historical evidence below and are not claimed fixed here.

Limitations: 未生成 APK，未调用真实供应商 API，未操作手机或模拟器，视觉未验证。GPU 指既有 GPU 主机上的 JVM 定向测试，使用合成 SSE/fake transport，不代表 GPU 渲染或真实接口验收。原 PR 的协议选择、导入和切换界面，以及合入 main 的紧凑聊天布局，仍需当前构建的实际渲染与关键交互验收。API33、R8、硬件 Keystore、实体断电未测；发布后的 CI、独立审核及正式模拟器回归由审核端负责。 Existing size bounds, timeouts and assertions were not relaxed.

The writer created only the validated main merge commit, performed no push, PR write or merge on GitHub, and did not edit/commit a new issue154 test summary. Already tracked summaries came solely from existing ancestry/main and remain unchanged. The existing publisher owns the new summary, publication and PR update; the independent reviewer owns final CI/regression/merge.

## Historical RETURN evidence — original SHA bindings retained

# Issue 154 validation — indexed content and argument completion

Implementation SHA: `7a523c8f190149b526615b5ae37b497933885930`
Tested tree: `7cea22d89a2e5b3db32b101bae94b49d103d5911`
Branch: `auto/154-openai-responses`
Fetched main: `e5015815cfb97bb89b90cf1b0ef6cba503b554a4`
RETURN: https://github.com/ByteJuno/Theta/pull/169#issuecomment-7220074638

Ordinary message content is now accumulated independently by content_index and drained in index order. Text/refusal done and content-part done values are reconciled with deltas, each other and final item content; missing indices, changed types, contradictory done values and post-done deltas fail closed. Buffered higher-index data emits liveness events. Function-call argument completion must match its prefix and final item exactly; contradictory streams produce PROTOCOL and never execute a tool.

Responses known schema error fields (text, text.format and explicit schema metadata paths) map to the existing response_format recovery key only when responseSchema is configured. Both original and mapped values pass credential sanitization, unknown paths remain filtered and Chat behavior is unchanged. HTTP errors, SSE error and response.failed use the same recovery route.

Six formal regressions added. GPU baseline on unchanged production code: 24 tests, 6 failures, tree 160063e86a886dfbbcddafc74da6cd264e61eda3. Same regression assertions on candidate: 24/24 passed in 61.0s; remote STAGED_TREE matched exactly. Includes interleaved and out-of-order parts, done-only content, refusal and invalid indices/types, argument completion/prefix checks, zero execution and zero ledger records for contradictory tool streams, HTTP/SSE schema recovery, credential alias redaction and Chat isolation. Original assertions and limits remain unchanged.

Final python scripts/preflight.py passed in 164.3s with strict --configuration-cache --configuration-cache-problems=fail. Core: 657 tests, 656 passed, 1 platform skip. App: 797 tests, 796 passed, 1 platform skip. Resources, lint and storage fixture verification/compile passed. Configuration cache reused. Worktree hashes unchanged during validation; implementation tree equals GPU tree. Skips are existing Windows SummaryWorkspaceTest link/parent traversal and PluginUpdatesTest snapshotKeepsExecutableBit.

Evidence: `E:\Desktop\AI研究\theta-auto-loop\event-runs\73870e9a-bae6-4682-9e35-3e79eec5db8a` (gpu-red.log/json, gpu-green.log/json, preflight-final, final-refs.json, review-verdict.json). No Probe, raw log or summary committed by writer. No push, PR write or merge by writer.

Limitations: 本轮仅修改 agent-core；未生成 APK，未调用真实供应商 API，未操作手机或模拟器，视觉未验证。GPU 指既有 GPU 主机的 JVM 定向测试，使用合成 SSE/fake transport，不代表渲染或真实接口验收。原 PR 协议选择与导入界面仍待当前构建渲染验收；API33、R8、硬件 Keystore、实体断电未测。独立审核端负责发布后的 CI 与正式模拟器回归。未修改超时、事件大小限制或原有断言。

## Historical evidence (applies only to the SHAs stated below)

# Issue 154 validation — RETURN repair

Implementation SHA: `09fcc31e3ce4ced712a5db3a6eb424547e8fda6c`
Tested tree: `6f110d715f20db20c7b39145bbfa7b60d13fac58`
Branch: `auto/154-openai-responses`
Fetched main: `e5015815cfb97bb89b90cf1b0ef6cba503b554a4`
Return: https://github.com/ByteJuno/Theta/pull/169#issuecomment-7219300227

Raw reasoning content and summaries now have independent indexed accumulators, text-done and final-item checks. Missing/changed parts, malformed raw content, post-done deltas and final output mutations fail closed. The full final JSON object, including both channels, encrypted content and additional fields, is preserved for replay. The host's single reasoning display stays on its first nonempty channel; every incoming reasoning event still proves liveness. The real two-request tool-loop regression asserts the complete replay input and final persisted answer.

Official Java SDK was evaluated at `273ed57253318b382c9e3e90a5b6ce4cdb700ed4`. It supports Responses, Java 8+, shared OkHttp and custom HttpClient. At that revision ResponseAccumulator's raw/summary reasoning visitors are empty. Adoption would still require Theta transport lifecycle, ModelEvent/call binding, final consistency and replay integration; this bounded repair retains existing ModelTransport and uses the official typed events as a reference. Verifiable source links are in the PR body; local evaluation is `sdk-evaluation.md`.

Final validation: GPU JVM entry 18/18 passed, GRADLE_EXIT=0, 57.0s, exact staged tree matched on the remote scratch checkout. Final `python scripts/preflight.py` passed in 130.7s with strict `--configuration-cache --configuration-cache-problems=fail`. agent-core 651 tests: 650 passed, 1 platform skip; its successful result from final-r3 was reused unchanged by final-r4. app 797 tests: 796 passed, 1 platform skip, rerun in final-r4. UI resource checks, lint and storage fixture verification/compile passed. Seven new formal tests plus strengthened tool-loop assertions cover raw fragmented Unicode, terminal-only raw content, independent multipart/channel reconciliation, both channel arrival orders, malformed/contradictory finals, complete replay and nonvisible-channel liveness. Original assertions, timeouts and 64 KiB limits remain.

Skipped: SummaryWorkspaceTest link/parent traversal and PluginUpdatesTest snapshotKeepsExecutableBit (Windows platform constraints).

Preserved failures: original implementation GPU baseline had 4 failures / 15 tests; first candidate missed a protocol import; next candidate's new assertion omitted the existing empty initial reasoning delta and was corrected to assert that event too. final-r3 additionally had one unchanged PluginOutputSurfacesTest assertion failure: backup21 show event absent after 8s. Full class independently passed 11/11 without code/assertion/timeout changes, and final-r4 full preflight passed. This intermittent failure is disclosed, not claimed fixed. Evidence is in `E:\Desktop\AI研究\theta-auto-loop\event-runs\8f183c0d-fab3-4d0a-a3d9-2bac6d90415b` and `validation-issue11/issue154-8f183-plugin-failure`.

Limitations: 未生成 APK，未调用真实供应商 API，未操作手机或模拟器，视觉未验证。本轮仅修改 agent-core；原 PR 的协议选择、导入及切换界面仍待当前构建渲染验收。GPU 是既有 GPU 主机上的 JVM 定向测试，不是 GPU 渲染或模拟器验收。SSE 使用合成 fixture/fake transport；API33、R8、硬件 Keystore、实体断电未验证。新提交 CI、独立审核和正式模拟器回归由审核端负责。完整推理回放仍受既有 64 KiB 单事件上限约束，未扩大限制。一次未改动的 PluginOutputSurfacesTest 备用展示 8 秒等待失败，根因未确认；同树该类 11/11 与随后完整预检通过，未宣称修复该偶发问题。

Only verified implementation files were committed. No test summary was committed by the writer, no push/PR write/merge was performed. Existing publisher owns publication; independent reviewer owns CI, formal emulator regression and merge.

## Historical initial implementation evidence (not rebound to this SHA)

# Issue 154 validation

Implementation SHA: `cba1a82ae6ba079cefd0176b1b8d648361680fb4`
Tested tree: `8eff75616d908110c7aa5ce2cba3947000d42ee2`
Branch: `auto/154-openai-responses`
Base/final fetched main: `e5015815cfb97bb89b90cf1b0ef6cba503b554a4`

Implemented native OpenAI Responses streaming and v3 provider routing, protocol selection/template import, Chat-only compat, schema-profile forwarding, request admission estimation, native tool call/result identity, complete reasoning replay, and bidirectional checkpoint handoff. Shared the existing OpenAI HTTP/SSE lifecycle rather than adding a network stack.

Validation: `python scripts/preflight.py --gradle-arg=--configuration-cache --gradle-arg=--configuration-cache-problems=fail --gradle-arg=--max-workers=4 --gradle-arg=-Pandroid.overridePathCheck=true --gradle-arg=-Pandroid.builder.sdkDownload=false` passed. Final preflight took 103.2s. agent-core: 644 tests, 643 passed, 1 platform skip. app: 797 tests, 796 passed, 1 platform skip; Gradle reused this result in the final run because app inputs were unchanged. UI resources (603 paired keys), lintDebug and storage-fixture verification/compile passed. 14 new test methods cover wire fixtures, parallel deltas, exact reasoning replay, native two-request tool loop with one ledger execution, checkpoint preservation in both protocol-switch directions, v3 persistence/binding/quick switching, templates, schema forwarding, key isolation, cancellation, malformed streams, error redaction and limits. Source hashes matched throughout validation and committed tree matches validated tree.

Skipped cases: [{"class": "io.github.bytejuno.thetacore.agent.run.SummaryWorkspaceTest", "test": "link plus parent traversal cannot clean a different active session"}, {"class": "io.github.bytejuno.thetacore.plugin.PluginUpdatesTest", "test": "snapshotKeepsExecutableBit"}]

Failures retained: initial resource scan rejected literal protocol names in UI; replaced with existing enum values. First final preflight failed one new tool-loop expectation (bare text versus the existing status/effect/message ledger envelope); corrected to assert all envelope fields, then core suite passed. All original logs/XML remain at `E:\Desktop\AI研究\theta-auto-loop\event-runs\fac83cbd-5562-43b0-bfdb-2c40fadf179a` and `validation-issue11/issue154-fac83-*`. No failing assertion was removed.

Limitations: 未生成 APK，未调用真实供应商 API，未操作手机或模拟器。视觉未验证：服务商协议选择、Responses 下兼容项隐藏、模板导入及模型切换的中英文、小屏、大字体、明暗主题实际渲染与交互待审核端验证。SSE 测试使用按官方文档构造的协议 fixture / fake transport，不冒充线上录制或真实服务验收。完整 reasoning 回放当前受既有 64 KiB 单事件上限约束，超限明确失败，不截断；未新增大载荷外部存储。未知服务端输出类型拒绝处理，extraBody 按可能执行服务端工具保守分类。Responses 不支持停止词，配置停止词时明确拒绝。API 33、R8、硬件 Keystore、实体断电、CI 及正式模拟器回归本轮未验证；发布后的独立审核端负责 CI 与正式回归。

Protocol references checked: https://developers.openai.com/api/reference/resources/responses/methods/create ; https://developers.openai.com/api/docs/guides/reasoning ; https://developers.openai.com/api/docs/guides/migrate-to-responses . Fixtures are synthetic, not live API captures.

Writer did not push, create/update a PR, commit a testing summary, or merge. Existing publisher owns publication and independent reviewer owns CI/regression/merge.
