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
