# Issue 176 validation

- Implementation commit: `89072e0e1f0bdef7f1cb68fcd7e4f5ff218ccf55`
- Tested source tree: `e21d542575bd7b158fc5f92e722efc8cb2b0df00`; changed source bytes unchanged throughout final checks.
- Branch: `auto/176-document-attachments`; reviewer head included: `9795b344a6100710040566024005ab40d44af811`; freshly fetched main included: `00ee9e91db4eef817008de09fec2bb3731f2b304`.
- APK: not generated; APK SHA-256: not generated.

## Implementation and RETURN repair

Document attachments include bounded real PDF/UTF-8 extraction, session BlobStore roots, durable composer drafts and preview/sent metadata. This round durably quarantines damaged contents, rejects republication without disabling unrelated sessions, preserves uncertain-commit protection, moves every provider operation to the one-shot process with independent watchdog, replaces host pipe reads with bounded post-exit result files, preserves Unicode filename boundaries and fixes the optional JP2Decoder R8 diagnostic.

## Actual checks

- Full `python scripts/preflight.py`: passed, 153.29 seconds; `--configuration-cache --configuration-cache-problems=fail`, cache reused.
- `:agent-core:test`: 719 tests, 0 failures/errors, 1 skipped; executed this round.
- `:app:testDebugUnitTest`: 886 tests, 0 failures/errors, 7 skipped; executed this round.
- Targeted permanent regressions: 6 core + 36 app, 0 failures/errors/skips.
- `:app:verifyUiResources`: 690 English/Chinese pairs. `:app:lintDebug`: 0 errors, 103 warnings. Storage fixture verification/compile passed.
- `:app:minifyReleaseWithR8`: passed, 150.10 seconds, strict configuration cache; this is shrinking only, not APK/signing or device acceptance.
- Source/hash binding and protected-path checks passed. No probe source, raw evidence or test summary committed by the writer.

## Failure evidence retained

Reviewer baseline: two core probes failed; app probe first did not compile due to reviewer call-signature error, corrected run failed two of three tests; license check passed. Trusted verdict/log retained. Writer permanent red tests reproduced two core isolation failures and Unicode truncation failure. Intermediate test-only mixed-array type/authority setup failures are retained and corrected, not hidden. Previous remote CI failed on missing optional JP2Decoder; local R8 now passes but remote CI has not rerun on this implementation.

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\3f2a0db6-ab6c-4ca5-9f62-cd47e4c5fa8a`. Includes reviewer-verdict.json, reviewer-log.txt, ci-failed.log, targeted-red, targeted-green-1/2/3, preflight-final, r8-final and verification-notes.md.

## Limits and reviewer continuation

未生成新 APK（APK SHA 未生成），未操作手机或模拟器，当前构建视觉未验证。Robolectric/JVM 测试验证生产调用路径、非协作阻塞时看门狗触发和宿主恢复逻辑；终止回调及 Binder 退出通知在测试中模拟，不代表真实 Android 进程终止/重启验收。SAF 提供方 query/getType/open/管道阻塞、进程崩溃或 OOM、旋转中导入、混合附件、小屏/大字体/中英文/明暗主题仍待审核端当前构建验证。仅完成 R8 minify 阶段，未打包/签名验收、未跑 paired-keep-size 全量比较、远端新 head CI、正式 S1、API33 设备运行或真实提供商 blob_read 多轮。

Independent reviewer owns current-head code review, required emulator regression, CI and merge. Publisher owns this unique summary, remote push and PR update. Previous-head device evidence is not attributed to this commit.

## 第 2 轮审核与本地修复

- 审核输入：`95f9be664f0f6c1a741d0f189f7fd36a915db04a`；历史退回次数为 1，前一轮失败记录保留于上文。
- 核对确认：写手实现提交 `89072e0e1f0bdef7f1cb68fcd7e4f5ff218ccf55` 的树为 `e21d542575bd7b158fc5f92e722efc8cb2b0df00`，与审核输入仅测试摘要不同。复用该源码的全量测试和此前许可证核对；本轮没有新增或升级依赖。可信 API 扫描通过，minSdk=33、hits=0。
- 审核修复提交：`c0ece5efdb2f63a55663ae9c3aca6a2f8f0cf21c`；测试时对应的受跟踪源码树：`ef464007c93edb95116ead82806a929cba039236`。后续提交仅更新本摘要。
- 修复内容：工具发布相同内容时，先检查已有文档所有者并持久隔离损坏；依据原始文件名识别扩展名，随后执行展示/存储名称截断；附件预览有界滚动，并优先为输入和发送控件保留空间。新增 5 项永久回归用例。

### 本轮失败证据

- 核心探针 1 项、失败 1 项，构建报告 25 秒：工具发布返回 `StorageFailure`，隔离记录为 0；读取未损坏偏移在当次和重启后都成功，违反整份内容永久隔离要求。
- 长文件名探针 1 项、失败 1 项，构建报告 41 秒：173 字符的合法 `.kt` 文件名、`application/octet-stream` 类型，直接提取成功，但经提供方路径返回 `UNSUPPORTED`。
- 紧凑布局探针 2 项、失败 1 项，构建报告 34 秒：真实 Compose 编辑区按 640×260 测量，空草稿发送图标为 24×24；四份文档时发送和附件入口的可见边界均变为 0×0。

### 修复后定向验证

- 指定 GPU 入口 `:agent-core:test`：`DocumentSharedContentProbe`、`SessionDocumentBlobsTest`、`SessionBlobTest`，共 21 项，失败 0，退出码 0。探针输出为发布 `Applied`、隔离记录 1、重启前后其他偏移均不可读，执行准入仍正常；永久用例同时验证工具读取拒绝和无关会话草稿可写。
- 指定 GPU 入口 `:app:testDebugUnitTest`：`DocumentLongFilenameProbe`、`DocumentComposerLayoutProbe`、`DocumentComposerLayoutTest`、`DocumentProviderTest`、`ChatCompactLayoutTest`，共 12 项，失败 0，退出码 0。长文件名导入成功；四份文档时发送图标恢复为 24×24；输入、附件入口、滚动范围及四图四文档/1.5 倍字体场景通过。
- 探针文件均保留未跟踪，未进入提交；没有提交原始日志、测试 XML、APK 或录屏。受保护文件未修改，差异空白检查通过。
- 本轮未重跑全量测试，旧全量结果不冒充修复后全量结果。没有生成或操作 APK、模拟器、手机；以上是 Robolectric 控件实际布局测量，不代表整页视觉或 Android 进程终止验收。最终提交的 CI/模拟器回归和发布操作由外层脚本负责，本轮不代跑。
