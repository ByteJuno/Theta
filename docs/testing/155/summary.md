# Issue 155 validation

Implementation: `403daaae95267274ee5993a78123577880791ca6`. Exact tested Git tree: `bb7f8fa9060edc6acea9c3e1536bb4805f26aa53`. Base/latest fetched main: `f47edb8b85291c04f84529460fdff73a0a1402f9`. Branch: `auto/155-anthropic-protocol`.

Implemented native Anthropic /messages and paginated /models, isolated authentication, ordered thinking/signature/redacted replay, tool/result and image encoding, explicit cache markers, cumulative usage, stream errors and finish reasons. Added v3 persistence/binding/preset integration and paired feature resources for protocol and per-model thinking/output options. No protected files or host-entry logic changed.

Targeted verification: core 45 and app 25 passed, 0 failures, 33.25s. Includes real loopback HTTP header isolation and discovery pagination, fragmented UTF-8/SSE, thinking replay, tools, pause limits, refusal discard, both OpenAI checkpoint-switch directions, cold settings read/key freezing and endpoint isolation. Fake transports use synthetic protocol fixtures, not recorded production provider traffic.

Final `python scripts/preflight.py`: passed, 161.23s; strict `--configuration-cache --configuration-cache-problems=fail`, cache stored. Core 676 = 675 passed / 1 platform skip; app 803 = 802 passed / 1 platform skip; no failures/errors. `:app:lintDebug`, `:app:verifyUiResources` (616 paired keys), `:samples:storage-fixture:verifyStorageFixtureSources` and `:samples:storage-fixture:compileDebugKotlin` passed. Source hashes stayed unchanged during validation; commit tree matches tested files. Final fetch confirmed main ancestry and no remote issue branch.

Preserved failures (no assertions/deadlines removed):
- targeted-first: test compilation failed from local messages variable shadowing builder property; corrected
- targeted-second: test compilation used ModelTarget instead of RecoveryProposal and assistant instead of ModelAttempt.partial; corrected
- targeted-third: 69 tests / 1 failure; continuation assertion missed the kernel explicit cache marker on the text block; assertion corrected while retaining exact thinking comparisons
- targeted-fourth: resource checker rejected wire strings in a Composable file; moved wire/settings mapping into a non-UI helper; no checker exemption added

APK: not generated; APK SHA256: none. 本轮未生成 APK、未操作手机或模拟器，视觉未验证。需由独立审核端验证当前构建中英文、明暗主题、小屏/大字体的协议选择、思考强度、输出上限、导入/保存/模型拉取与切换。未测试真实 Anthropic 服务、API33 运行、R8、硬件或实体断电。回放封装受既有单事件 64 KiB 上限约束，超限明确失败，不截断签名。当前适配器只接入 text/thinking/redacted_thinking/tool_use；未知服务端工具块保守拒绝，未宣称完整服务端工具支持。模型默认输出上限 4096，可逐模型修改；缓存标记遇到仅思考块时不装饰该不可变块。

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\2bf4c53f-23a7-4af0-bbae-c1ac8469689c`. New-head CI, independent review and formal emulator regression are pending; no old-head result is claimed for this commit. Publication and the single docs/testing/155/summary.md record belong to the existing publisher.
