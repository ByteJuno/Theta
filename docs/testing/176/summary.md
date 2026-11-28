# Issue 176 validation

- Implementation commit: `8d84df33a5e73e6176fce09a1579b3a4b0835eb0`
- Tested source tree: `93617d3ef337d14bd519f029201d425de7c3ab6c`; staged bytes matched the committed tree after full preflight.
- Branch: `auto/176-document-attachments`; both reviewer head `81f4249f7ee8e0ac7be2cd7d4343e8ec1e2f3a63` and main `92e241a14f43666ffb14007d35e07c339594241b` are ancestors.
- APK / APK SHA-256: not generated.

## Latest RETURN: main conflict

Verified OWNER RETURN `IC_kwDOVkrS_s8AAAABr2MbMw`, https://github.com/ByteJuno/Theta/pull/188#issuecomment-7237475123 . First integrated reviewer fix `c0ece5efdb2f63a55663ae9c3aca6a2f8f0cf21c` and its summary, then resolved the single ChatRouteSurface conflict. Settings navigation/saved page/tool-input state and document import guard/snapshot/send/file/removal wiring all remain. New strings and protected-file edits were unnecessary.

## Actual final checks

- `python scripts/preflight.py`: passed in 211.87 seconds, strict configuration cache; configuration cache stored.
- `:agent-core:test`: 720 tests, 0 failures/errors, 1 skipped; 73 fresh suites.
- `:app:testDebugUnitTest`: 905 tests, 0 failures/errors, 7 skipped; 97 fresh suites.
- Included regressions: SessionDocumentBlobsTest 7, DocumentProviderTest 3, DocumentComposerLayoutTest 3, ChatCompactLayoutTest 3, ChatSessionOwnerTest 72, SettingsNavigationTest 4; all zero failures/errors/skips in this run.
- UI resources: 713 English/Chinese pairs. Lint: 0 errors, 106 warnings. Storage-fixture source verification and compilation passed.
- No unmerged entries, whitespace errors or protected-path changes; own merge edits do not alter docs/testing/176/summary.md. The main-owned issue 162 summary is inherited verbatim.

## Preserved failures and prior repairs

The first merge attempt stopped before changing files because committer identity was unset; command-scoped ByteJuno identity fixed that environment issue. The next merge exposed the expected content conflict, then the resolved staged tree passed full preflight. Logs: merge-main.log and merge-main-with-identity.log.

Prior writer/reviewer failures remain preserved: initial core probes 2/2 failed; app probe initially had a test-signature error, then 2/3 failed; permanent tests reproduced quarantine and Unicode failures; optional JP2Decoder originally failed CI. Reviewer round two reproduced tool-to-document shared-content quarantine (1/1 failed), original long-extension detection (1/1 failed), and compact attachment layout (1/2 failed). Fixes are included, with permanent regressions now passing above. Prior targeted GPU checks and R8/CI results describe their recorded older heads only.

Earlier evidence: event-runs/3f2a0db6-ab6c-4ca5-9f62-cd47e4c5fa8a and the reviewer-authored summary at 81f4249f7ee8e0ac7be2cd7d4343e8ec1e2f3a63. This round: `E:\Desktop\AI研究\theta-auto-loop\event-runs\4edbf3fa-1421-4e6f-9831-0be99b7ae2d4` (preflight-final, merge/fetch/commit logs, complete GitHub comments/reviews/inline comments, merge-resolution.diff and verification-notes.md). Raw evidence is not committed.

## Limits and continuation

未生成 APK（APK SHA 未生成），未操作手机或模拟器。Robolectric 的实际 Compose 控件布局测量通过，但当前构建完整视觉未验证；设置与聊天往返、SAF 文档选择/阻塞提供方/解析进程终止及恢复、旋转草稿、混合附件、小屏/大字体/中英文/明暗主题仍需审核端当前构建验收。本轮未运行新 head CI、正式 S1、R8/paired-keep-size、签名或 API33 设备运行；旧 head 的 CI/R8/设备结果不迁移为本提交通过。

Publisher owns the unique test summary, push and PR update. Independent reviewer owns final-head review, required emulator regression, CI and merge. This writer neither publishes nor merges the PR.

## 本次第 2 轮审核补充

- 审核输入：`b565a68aae5b5898c78732f810e2f7034443df75`，基线 `92e241a14f43666ffb14007d35e07c339594241b`；此前累计退回 1 次。上文历史失败及先前审核证据继续保留，本次不重置次数。
- 已读全部生产差异及测试用例、脚手架，并对照 PROTOCOL 第 3.8 节和 DESIGN 第 10 节。实现提交 `8d84df33a5e73e6176fce09a1579b3a4b0835eb0` 的树确为 `93617d3ef337d14bd519f029201d425de7c3ab6c`，与审核输入仅摘要不同，复用其全量检查。依赖与前次审核一致，复用已通过的许可证核对；固定 API 扫描通过，minSdk=33、hits=0。
- 本轮发现并修复：纯文档消息生成非空模型文本后，持久化层误将空输入框版本视为已提交正文。另一页面持有相同空正文版本、仅发送全新文档时，前置检查允许，最终提交却报“Composer draft already consumed”。提交阶段现按原始输入框判断正文是否参与去重，附件身份去重保持有效。
- 修复提交：`1eda9c41b214e50434e069ddb65b1c7dab936723`；测试对应受跟踪源码树 `8fd80432e60c164de80726427782444814a2363d`。后续提交仅追加本摘要。

### 本次探针与修复验证

- 修复前指定 GPU 入口 `:app:testDebugUnitTest`，`DocumentDraftAdmissionProbe` 和 `DocumentImportCancellationProbe` 共 2 项、失败 1 项，构建报告 41 秒。回执探针输出 `guardRejected=false appendSucceeded=false error=Composer draft already consumed`。
- 取消导入探针通过：退出通知前 `firstCompleted=false binds=1 unbinds=0`；退出通知后下一次导入成功，`requests=2 binds=2 unbinds=2 remainingFiles=0`。使用模拟 Binder 退出通知，不能外推为实际 Android 进程验收。
- 修复后指定 GPU 入口 `:app:testDebugUnitTest --tests '*DocumentDraftAdmissionProbe*' --tests '*ChatSessionOwnerTest*'`，共 74 项、失败 0 项、退出码 0。探针输出 `guardRejected=false appendSucceeded=true error=null`。新增永久用例通过实际 ChatSessionOwner 发送两份共享空正文版本的新文档，并确认已发送附件仍不能重复提交。
- 探针文件均未跟踪、不提交。没有新增原始日志、XML、APK、录屏或第二份测试摘要；未修改受保护文件。本次修复差异空白检查通过。
- 未重跑全量检查，旧全量结果不归于修复提交；未生成 APK，完整视觉未验证，未操作模拟器或手机。最终提交的 CI/模拟器回归与发布由外层脚本负责。
