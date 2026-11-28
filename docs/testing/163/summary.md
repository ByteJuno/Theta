# Issue 163 validation summary

Implementation commit: `15a1d5a70632dbc7b78e7c62c536aadaf649eebe`
Tested source tree: `845fa5916b1621a7823e992ae3ee06273a4f964a`; verified equal to committed tree.
Branch: `auto/163-plugins-surface`. Both fetched main `53014eeb84210e74ed472a7210256f390e6a64fe` and remote review head `8f9779f6421ebe40fc622c640403ee43af720c0e` are ancestors. APK / SHA-256: 未生成。

## Current RETURN and repair

Verified OWNER RETURN `IC_kwDOVkrS_s8AAAABr3qiEw` at https://github.com/ByteJuno/Theta/pull/189#issuecomment-7239016979 . Complete comments/reviews/inline comments fetched; one RETURN, no other review requests. One open PR; no new issue selected. Fetched before merging and before final validation. Only merge conflict was app/build.gradle.kts; retained ui-test-junit4 plus main's document support.

Initial full preflight reproduced a combination regression: app 916 tests / 1 failure / 7 skips in 242.77 seconds. Calling removeDocument initialized DocumentAttachmentsKt's eager documentImportScope; kotlinx-coroutines-test brought by ui-test-junit4 exposed missing Android Main dispatcher in a plain JVM test. Made the same singleton SupervisorJob + Main.immediate scope lazy, initialized at picker composition. Existing test remains unchanged; no mock dispatcher, relaxed assertion or timeout. All other imported document sources and plugin feature sources match their parents.

## Current checks

- Full `python scripts/preflight.py`: passed in 143.13s; strict configuration cache reused.
- App: 916 tests, 0 failures/errors, 7 skipped; 98 fresh suites, executed final preflight.
- Core: 720 tests, 0 failures/errors, 1 skipped; 73 suites executed during initial preflight this round. Final preflight UP-TO-DATE for unchanged core inputs; not claimed rerun.
- Targeted post-fix: 86 tests, 0 failures/errors/skips in 67.08s with no build cache. ChatSessionOwnerTest 73, DocumentComposerLayoutTest 3, PluginsScreenTest 10.
- 724 paired English/Chinese keys; lint 0 errors/107 warnings; storage fixture source verification/compile passed (final compile UP-TO-DATE).
- Source index matches committed tree; no unmerged entries or feature whitespace errors; protected files unchanged relative to current main. Issue163 summary untouched by writer; issue176 summary inherited verbatim from main.

## Evidence and limits

Evidence: `E:\Desktop\AI研究\theta-auto-loop\event-runs\0a9f0b30-f009-404e-81a8-221327527362`; targeted: `validation-issue11/issue163-0a9f0b30-merge-regression`. Initial failure log/JSON/XML preserved. merge-main.log records original conflict; exact-byte edit initially stopped before writing due to CRLF (resolution-initial-failure.log). Diff-check against old head flagged a trailing blank line already in main's DocumentMessage.kt; feature check against current main passed, original warning retained in resolved-diff-check.log. No unrelated formatting repair was made.

未生成 APK（APK SHA 未生成），未操作手机或模拟器；当前构建完整视觉未验证。通过的是 JVM/Robolectric 语义、布局与回调测试，不代表真实字体像素、触摸或设备验收。小屏/大字体/中英文/明暗主题、安装/卸载/回滚弹窗、skip_plugins 冷启动和 S1/S2 节点契约仍需独立审核端当前构建验收。本轮未运行新 head CI、正式模拟器回归、R8、签名或 API33 设备验收；旧 head CI/设备结果不迁移。

Publisher owns summary commit, push and PR update. Writer performed no push, PR creation/update or merge. Independent reviewer owns final-head review, CI, prescribed device regression and merge.

## Historical implementation evidence (bound to old SHA only)

# Issue 163 validation summary

Implementation source commit: `20a9c54765d945a1cc70225722145e428ac6f648`
Tested tree: `47ada37d24d35e09d0cbd4ec2e5a1c037334e6c6`
Base/final fetched main: `92e241a14f43666ffb14007d35e07c339594241b`
APK: 未生成；APK SHA: 未生成。

- 实现：独立 ui/plugins 页面；默认 All 与单纵向滚动容器；四种筛选、可折叠环境卡、导入操作条、代码/数据卡片与状态徽标、Hook 折叠区、底部单次免责声明。宿主确认弹窗块与 main 字节相同。
- `python scripts/preflight.py`：通过，156.10 秒，严格 configuration cache；storage fixture 验证/编译通过；712 对双语资源；lint 0 错误/102 警告。
- App：896 tests，7 skipped，0 failures/errors，本轮实际执行。agent-core：713 tests，1 skipped，0 failures/errors，为未改变输入的 Gradle build-cache 恢复，未声称本轮重跑。
- 10 项永久 Compose 回归本轮通过：默认 All/单滚动、筛选与免责声明、环境展开收起与操作、读取时禁用与取消/清理、安全模式与恢复、正常模式、中文大字体暗色布局边界、代码操作、busy 禁用、数据删除重试。
- 失败证据：初版手动推进 looper 未可靠同步 Compose 重组/滚动，相关失败保留；使用 Compose 测试时钟后通过。native 图形尝试触发 Windows JNI 崩溃，保留 targeted-native/output.log、result.json 与 hs_err_pid26220.log。未删除断言或扩大生产超时。
- 证据目录：`E:\Desktop\AI研究\theta-auto-loop\event-runs\0c7ec46b-f634-4b23-a3b3-0741beecb9e1`；全部原始日志/XML/崩溃报告留在仓库外；作者未提交 docs/testing/163/summary.md。

未生成 APK（APK SHA 未生成）；未操作手机或模拟器。Windows Robolectric 原生图形渲染发生 UnsatisfiedLinkError/JNI 崩溃，原始日志及 hs_err 已保留；当前构建视觉未验证。通过的是 Robolectric legacy 图形模式的 Compose 语义/布局与回调测试，不代表真实字体像素、触摸或设备自动化验收。小屏、大字体、中英文、明暗主题、安装/卸载/回滚弹窗、skip_plugins 冷启动和 S1/S2 全部节点契约仍需独立审核端当前构建回归。未运行新 head CI、正式模拟器回归、R8、签名或 API33 设备验收。
