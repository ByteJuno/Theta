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
