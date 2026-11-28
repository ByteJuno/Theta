# Issue 173 validation

- Implementation SHA: `d36ffd516bd3ded791ccc24327d9ff92d89c0e80`
- Tested tree: `8c0bc103f4a68a47efed95ca8073000684903019`
- Branch: `auto/173-gemini-native`
- Freshly fetched main: `9b26e4c714820fdb68b8ba9cec2bdb9e5d4986fd`; ancestor verified before final validation. No remote candidate branch existed; no reviewer commits to integrate for this new issue. PR185 remained independently waiting_review with null cursor and unchanged head.
- Final `python scripts/preflight.py`: passed in 158.66 seconds, `--configuration-cache --configuration-cache-problems=fail`; cache reused. Agent-core 706 tests / 1 skipped; app 866 tests / 7 skipped; 0 failures, 0 errors. Both suites fresh; 663 paired resources, lintDebug, storage-fixture source verification and Kotlin compile passed. Final source manifest unchanged during validation.
- Targeted evidence: initial 37 passed; expanded Gemini/core 18 passed. Final full preflight additionally covers continuous display text with exact native signed-part replay. Tests exercise local real HTTP auth/URL sanitization, deterministic SSE fragments, single/parallel whole calls, absent IDs, ordered result groups, media, thinking, stop/error/usage handling, SQLite cold binding/replay, checkpoint switching, first-step recovery and v3 cold capabilities/concurrent edits.
- Original failure: compile-1 resource check rejected protocol wire constants in a Compose file. Moved enum to a non-UI settings file without modifying the checker; all later resource checks pass. Existing local branch creation initially rejected, then verified no unique commits and fast-forwarded. Failed multi-file patch applied no changes and was corrected. Stage whitespace check found enum trailing blank (removed); SSE trailing blank is required frame framing and intentionally retained. jcmd inspection raced with successful worker exit; diagnostic preserved, no process killed. Full preflight runs 1/2 passed before the final continuous-display correction; only preflight-3 binds this final tree.
- Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\60a20f71-8cd0-46ec-95e4-663e394aba25`. No raw evidence or docs/testing summary committed by writer.
- APK: 未生成. APK SHA256: 未生成.
- 未生成 APK（APK SHA 未生成）；未操作手机或模拟器，视觉未验证。需要在当前构建检查 Gemini 配置/模型发现/思考档位和降级提示的中英文、小屏/大字体/明暗主题，以及连续 Markdown 回复。未使用真实 API 凭据；SSE 为按官方 schema 编写的确定性夹具，不是实际账户录制响应，真实 Gemini/兼容网关联网、看图和签名多轮仍待验证。API33 设备运行、R8、CI 与审核端正式模拟器回归未在本轮执行。适配器使用 streamGenerateContent SSE，非流式 generateContent HTTP 调用、图片生成、服务器工具和 continuation token 自动续接未实现；未知停止原因在客户端工具执行前失败。超过现有 64 KiB 事件上限的回放明确失败，未截断或剥离签名。
- Publication, CI, independent review and official emulator acceptance remain downstream. Writer did not push, create/update a PR, merge, create scheduling or access a device.

## PR 186 第一轮审核补充

- 审核起点：`b717b2b92c2fc5459939368be16c5012499a99c1`；此前退回 0 次。原实现的测试树与上述 SHA 核对一致。
- GPU 探针原始失败：`GeminiUsageProbe` 输入正文 7、思考 9，实际输出用量为 7，预期 16；`GeminiCapabilityProbe` 在自动降级后显式选择 low，冷读实际档位仍为 null。两个探针各执行 1 项、失败 1 项，未提交探针文件或原始日志。
- 已修复输出用量漏算思考 token，以及自动降级后无法通过原生模型档位选择重新尝试的问题。手动重新尝试只影响后续运行，已知非空能力列表仍按既有规则选取较低档。
- 修复后经既定 GPU 入口执行 `:agent-core:test --tests '*GeminiUsageProbe*' --tests '*GeminiContractTest*'`：12 项、0 失败；`:app:testDebugUnitTest --tests '*GeminiCapabilityProbe*' --tests '*GeminiConnectionsTest*' --tests '*AnthropicConnectionsTest*'`：12 项、0 失败。探针分别输出 output=16、effective=low，包含冷读、冻结快照和原有档位校验的回归断言。上述原实现全量结果未转记为本次修复的全量结果。
- Gemini 新增的 13 对中英文字符串键匹配。未生成 APK，视觉及真实模型接口仍未验证；最终提交的正式模拟器回归仍由外层执行。
