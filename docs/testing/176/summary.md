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
