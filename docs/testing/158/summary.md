# Issue 158 validation

- Implementation SHA: `791ecd1f25826e338a9db5e05a5e368335695a2b`
- Tested Git tree: `52b5cfe950838b7a7c3cf382513416f67d8ef24a`
- Base/main at final fetch: `e5015815cfb97bb89b90cf1b0ef6cba503b554a4`
- Branch: `auto/158-compact-chat-layout`
- Scope: compact toolbar/composer and thumbnails based on remaining window height; preserve existing draft persistence, guards and actions. Paired bilingual accessibility string and vector icons. No protected files changed.
- Targeted validation: 22 tests passed, 0 failures/errors/skips, 59.03 s; actual classes ChatCompactLayoutTest, ChatScreenStateTest and UserImageChatTest. The requested ChatProfileSwitchTest glob matched no class on this base and is not counted.
- Full `python scripts/preflight.py`: passed in 137.46 s with `--configuration-cache --configuration-cache-problems=fail`; cache reused. agent-core: 633 tests, 632 passed, 1 skipped. app: 797 tests, 796 passed, 1 skipped. verifyUiResources: 600 English/Chinese keys. lintDebug and storage-fixture compilation passed.
- Skips: [{"class": "io.github.bytejuno.thetacore.plugin.PluginUpdatesTest", "test": "snapshotKeepsExecutableBit"}, {"class": "io.github.bytejuno.thetacore.agent.run.SummaryWorkspaceTest", "test": "link plus parent traversal cannot clean a different active session"}]
- Final source manifest unchanged during validation and matched the committed tree. No implementation/test failure occurred this round.
- Evidence: `event-runs/719e8adc-897d-40db-bb24-ff8cd762f953/preflight-final/` and `validation-issue11/issue158-719e-targeted/` (outside Git).
- APK: not generated; SHA256: unavailable.

未生成 APK，未操作手机或模拟器，视觉未验证。需审核端在本实现构建上验证横屏（含 2772×1272）、竖屏、分屏/小窗、键盘弹出、中英文、大字体和明暗主题；检查横向操作栏可达性、附件菜单、图片删除、发送/停止、全自动警示条、错误/恢复操作与消息可读高度。JVM Saver/SQLite 测试验证草稿文字、版本、图片 ID 与字节恢复，不等于真实 Activity 旋转或图片渲染验收。极低高度与大字体叠加场景仍需实际渲染判断。API 33、R8、硬件 Keystore、真实服务及实体断电未测；CI、独立审核和正式模拟器回归由发布后的审核端执行。


## 第 1 轮独立审核追加修复

- 原始审核 head：`2acb591a160e5e0cdd97430e9f40f1ade7305f26`；与上述受测实现的差异只有本摘要，原测试证据可复用。本轮此前退回次数为 0。
- 修复键盘引起布局切换时输入框处于不同组合位置的问题：两种布局现在共用同一 Row 内的单个输入框调用，保留其局部编辑状态。受测 `ChatComposer.kt` Git blob：`a073e4640b0a35ba5f9d02e2e4d2947bf6bee5ba`。
- GPU 上与原生产调用结构一致的 Compose runtime 探针复现：可用高度从 700dp 降到 400dp 后输入槽位创建 2 次、销毁 1 次。修复后的对应探针跨 400/700/260/800/479/480dp 六次切换，始终仅创建 1 次、销毁 0 次，选区和输入法组合区对象保持不变。
- 修复后通过既定 GPU 入口运行 ChatImeStableProbe、ChatCompactLayoutTest、ChatScreenStateTest 和 UserImageChatTest：23 项全部通过，`GRADLE_EXIT=0`。探针及仅供探针使用的 Android Trace 空实现未进入提交。
- 探针搭建期间先出现一次 Android Trace 未模拟错误、两次未触发重组的脚手架断言失败；修正为通过 setContent 初始化组合后，原结构复现探针通过。以上失败保留记录，不计为产品测试通过。
- 本次探针验证组合调用结构的生命周期，未运行 Android 输入框或实际键盘；没有生成 APK，视觉仍未验证。最终提交的模拟器回归由外层脚本执行，需覆盖键盘打开/关闭跨越紧凑布局阈值时的焦点、选区和连续输入。
