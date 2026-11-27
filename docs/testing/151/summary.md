# Issue 151 validation

Implementation `44d947ca4835fb0f16326b33496dd5684e308e15`, tree `0054737736043159bfb7ef926626f1e6d0897011`, includes main `b817d9364c7261b19d925408121cf6a7d4dd6a4f`.

- Previous PR head `e0764848b518ce7817e8fa906036808ccee5b53c` with regression tests added: 3 of 22 targeted JVM cases failed. An entry admission cleared unsent text, interleaved parts produced ACB instead of ABC, and an older ledger snapshot emitted WAITING_APPROVAL after REJECTED. The first baseline archive used CRLF for gradlew and ran zero tests (exit 127). The LF archive reproduced all three failures.
- On the implementation tree all 23 targeted theta-gpu-test JVM cases passed, including draft and attachment identity, streaming before the first part ends, bounded overflow, attempt discard, stale revisions and a fresh approval while the call state is unchanged.
- Windows scripts/preflight.py with strict configuration cache passed in 167.9 s. App unit tests: 730 passed, 1 skipped. Lint and 558 bilingual keys passed. Core and SDK test tasks and storage fixture compilation were up to date.

No APK generated and no phone or emulator used. Chat composer visuals, real attachment selection, rotation, API 33 runtime, R8 plugin linkage and live provider streaming remain unverified. New-head CI, independent review and prescribed emulator regression are pending.

Revision `d4efa66a0749e1e2e5310851729c28bd8d93e5a3`, tree `c6bac7f0c90d26bfe8bf1402a327902946750267`. The previous baseline ran 24 targeted cases with two failures: an empty session survived failed admission and a completed entry run reported failure. Two initial attempts to test the candidate stopped before Gradle when the Dell endpoint was offline.

On the candidate, the development test endpoint selected the f512 runner and all 26 targeted JVM cases passed (17 entry-session, 4 chat-state, 5 plugin-entry). Strict Windows preflight passed again. The metadata-timeout test that failed CI on `d8b4459` passed locally. These checks cover session state and draft restoration; real attachment selection and screen rendering still require UI coverage.

## PR 164 草稿恢复与提前关闭回归

实现提交 e5ff4d96a4c678289ae91fde41d02dca832a3c0a，验证树 7ded491be048317056178c38d3b8754082ec9b64。

入口会话继续通过聊天内核执行。按会话原子保存未发送文字、版本及附件；页面保存状态只记录固定大小的版本号。关闭入口时，即使事件投递尚未开始，收集方也能在宿主锁外结束。

回归覆盖 40 个长草稿、仅含附件的草稿恢复及回执，以及首次投递前关闭。theta-gpu-test 定向 29/29 通过；Windows 严格配置缓存 preflight 通过，应用单测 736 通过、1 跳过。

本轮未构建 APK，视觉未验证。设备上的屏幕旋转、进程重建、真实图片选择及发送、大附件保存耗时、API 33 和 R8 运行行为仍待验证。新提交 CI、独立审核和规定回归待发布后执行。

第一版候选的资源检查将两个存储标识识别为硬编码 UI 文案，本地预检失败，同树远端 Gradle 退出 1、运行 0 个用例。修正后 558 对双语键检查通过，严格预检耗时 194.1 秒。

## PR 164 会话草稿事务

实现提交 `013fb8c987161b8b853b71ec810f8f84363c3172`，验证树 `81f0717c2b2bbf47653fd77f8a215bd50bb88d50`。

审核端在 `3991c01fb993686625e5a025a2c64c2473cabe59` 重现已发送文字冷启动再现，以及删会话后仍可读回草稿和完整附件。 同轮一版候选通过 31 项定向检查，随后补齐删除图片后的附件排序与延迟回执组合回归。

入口聊天按会话使用现有 AndroidX SQLite 保存草稿。发送历史、文字版本与附件消费在同一事务提交。重开数据库可恢复未发送内容，删除会话会清除草稿，旧页面随后保存也无法重建。

定向 JVM 回归覆盖冷启动、删除后回写、延迟回执、附件顺序，以及既有长草稿、仅附件和提前关闭场景。theta-gpu-test 32/32 通过，Windows 严格配置缓存预检通过。

本轮未生成 APK，视觉、设备冷启动、实际图片选择与发送、大附件耗时、API 33 和 R8 运行行为未验证。新提交的 CI、独立审核与正式回归待执行。

本地预检重新执行的测试计数：{"app": {"tests": 740, "failures": 0, "errors": 0, "skipped": 1}, "agent-core": {"tests": 632, "failures": 0, "errors": 0, "skipped": 1}}。远端使用 gpu-bai，设备与视觉结果不在该 JVM 检查覆盖范围。

## PR 164 第六轮：发送前替换的文字与移除的附件

实现提交 `1eabfe1a6d3f122fec58d2268a62e877283fff42`，验证树 `4e1c682fcdebecdf87b191571618930842e58209`。

审核端在 `e7076dcc3e9850dd5b765d8af3aa1a40e5cfb70c` 重开数据库后读回 old text；另一场景中，已移除的 removed 附件仍可读回原有 3 字节。两种场景均已加入正式冷启动用例。

入口聊天在点击发送时捕获已保存和本次提交的草稿标识。发送历史写入 SQLite 时一并消费旧稿和已移除的附件，后续新文字及新附件保留。

定向 JVM 回归覆盖冷启动、删除后回写、延迟回执、附件顺序，以及既有长草稿、仅附件和提前关闭场景。theta-gpu-test 35/35 通过，Windows 严格配置缓存预检通过。

本轮未生成 APK，视觉、设备冷启动、实际图片选择与发送、大附件耗时、API 33 和 R8 运行行为未验证。新提交的 CI、独立审核与正式回归待执行。

本地预检重新执行的测试计数：{"app": {"tests": 743, "failures": 0, "errors": 0, "skipped": 1}, "agent-core": {"tests": 632, "failures": 0, "errors": 0, "skipped": 1}}。远端使用 gpu-bai，设备与视觉结果不在该 JVM 检查覆盖范围。

## PR 164 第七轮多页面草稿

实现提交 `5b3271e1789ca02dd01c4c3ccfcb46cdb6793dac`，验证树 `bc5eda2b6318186c905631cd00bdbe7eed006d68`。审核端在 `4a20b3fc57fc4c0a4890eb0026e638ad21d11def` 先保存新文字，再由旧页只加图片，重开数据库后读回 old。两个页面各选四张图片时，重开后读回八张。

修复同一会话在多个页面保存草稿时的数据覆盖。只改附件的页面保留其他页已保存的文字。附件在事务中按最近编辑页的选择顺序保存，剩余名额保留其他页的附件，最多四张。旧数据库中超限的附件和数据块在首次读取该草稿时清理。

新增三项数据库重开回归，保留既有发送回执、删除和恢复断言。theta-gpu-test 定向 38/38 通过。Windows 严格配置缓存 preflight 通过，应用 745 项通过、1 项跳过，agent-core 631 项通过、1 项跳过。

本轮验证覆盖 JVM 和 SQLite 数据库重开。未生成 APK，设备冷启动、实际图片选择与发送、界面、API 33 及 R8 运行行为未验证。新提交的 CI、独立审核和正式回归待发布后执行。

## PR 164 第八轮：从聊天切到插件页

在 `0e3cec0` 上，JVM 探针模拟聊天页退出组合：保存文字 before，改为 after 并加一张图片，随后注销聊天页的保存回调。重新读取时仍为 before，附件为零；1 项用例失败。

修复提交 `5fc5b1cb0a779b9e43ab6779fb4a86cb88dcc641` 将草稿保存在 Activity 的共同上层。插件页显示期间，系统仍能保存最新编辑。gpu-bai 定向运行 ChatScreenStateTest，12/12 通过，包括文字及图片字节恢复。未生成 APK，未实测页面点击或渲染；最终提交的 CI 和模拟器回归由外层执行。

## PR 164 第九轮：跨页面连续发送回执与已消费草稿防重发

实现提交 `000f3943237b7877c45844d13e4c78f7c7fa1d72`，验证树 `b04db67968ffac0ed492eaff8521ec5b9c2a4bb4`（基于 `8b2622821cc5142d5e387f4ad5f92b24af73b0a5`）。

- 基线复现：在 `8b26228` 上加入双页面连续两次发送回归（树 `72af06b4fb6dab707e0221158ef1afe2da530c36`），`theta-gpu-test`（`gpu-bai`）2/2 失败：`twoPagesConsecutiveSendsClearReturnedDraftAndBlockDuplicateHistory` 与 `returningPageAfterConsecutiveSendsPreservesUnsentEditsAndAllowsFreshSend` 均报 `AssertionError: expected:<2> but was:<3>`，旧页面重发已消费草稿写入第三条历史，且仅收到第二次回执时无法清掉第一次已发送的文字与附件。
- 修复方案：
  1. `SessionComposerStore` 新增 `consumed` / `isSubmittedConsumed` 主键点查，并在 `ComposerConsumption` 中区分本次提交标识（`submittedVersions` / `submittedImageIds`）与旧基线消费集合；同时在空文本附件草稿写入路径补充 `!consumed` 守卫防止主键冲突。
  2. `ChatComposerDrafts` 与 `ChatScreen` 在页面重显、会话切换、新发送接纳或 Activity 恢复时按 SQLite 已消费记录和最新回执共同核对，仅清除已发送的文字版本（并轮换新鲜版本、同步已保存基线）和附件标识，完整保留该页未发送的新编辑及新附件；跨会话覆盖 `acceptedDraft` 时同样按当前会话核对。
  3. 发送入口双层防重发：`ChatSessionOwner.sendInternal` 在写入用户图片和历史前拦截已消费的提交并触发 `draftSync` 同步界面；`SqliteRunHistory.appendBatch` 在事务开始前拒绝已消费草稿提交而不污染数据库。
- 验证结果：
  - `theta-gpu-test`（`gpu-bai`，验证树 `b04db67968ffac0ed492eaff8521ec5b9c2a4bb4`，严格配置缓存）：
    - 定向套件（`ChatEntrySessionsTest`、`ChatScreenStateTest`、`ChatSessionOwnerTest`、`PluginEntryRuntimeTest`、`UserImageChatTest`）：106/106 通过，0 失败。
    - 全量 `:agent-core:test` + `AndroidAgentOwnershipTest`：636/636 通过，0 失败。
  - 本地 Windows 严格配置缓存检查：静态检查（29 路径、无受保护文件改动、无 `probe` 命名）、`:samples:storage-fixture:verifyStorageFixtureSources`、`:samples:storage-fixture:compileDebugKotlin`、`:app:verifyUiResources`（567 对双语键）、`:app:lintDebug` 与 `:agent-core:test`（632 通过、1 跳过）全部通过；`:app:testDebugUnitTest` 753 通过、1 跳过、1 失败为本机 Windows 开发者模式符号链接路径导致的 `AndroidAgentOwnershipTest.trustedContextAliasResolvesButInjectedAliasAndDuplicateWriterAreRejected`（已在 Linux `gpu-bai` 4/4 验证通过）。

本轮验证覆盖 JVM 与 SQLite 数据库重开。未构建 APK，真实设备冷启动、页面交互渲染、实际图片选择与发送、API 33 及 R8 运行行为未验证。新提交的 CI、独立审核和正式回归待发布后执行。

## PR 164 第十轮：旧草稿单文件格式隔离与删会话草稿缓存释放

实现提交 `871167bfa0c9639c1940a0352b8eed56f314d915`，验证树 `49a59ef34166dd01239444e808578f279080f37d`（基于 `d04a7a6b6ca2b2f04036a264b31b32724bea2f87`）。

- 基线复现：在 `d04a7a6` 上新增两条回归（树 `bcf2b1c92c9f467de9c4107f1313c7d43536efa2`），`theta-gpu-test`（`gpu-bai`）2/2 失败：
  - `corruptLegacyDraftIsIsolatedAcrossTwoReopensWhileDatabaseErrorsPropagate` 抛出 `java.io.EOFException`，单个损坏的旧草稿文件导致 `AndroidAgentStore.open` 失败；
  - `deletingSessionReleasesCachedDraftAndAttachmentsWhilePreservingOtherSessions` 报 `ComparisonFailure: expected:<[]> but was:<[delete me]>`，删除会话成功后草稿缓存仍滞留已删会话文字与附件。
- 修复方案：
  1. `LegacyComposerDrafts` 将旧草稿格式/截断/越界/重名/尾字节错误封装为 `LegacyDraftFormatException` 并隔离到单个文件，同时记录存活会话文件名 `liveNames` 使孤儿清理跳过存活会话对应的损坏原件，保证正常会话迁移并能连续两次重开；数据库读写（`store.sessionIds` 与 `store.composerDrafts.save`）位于 `catch` 之外，绝不吞掉数据库自身错误。
  2. `SessionComposerStore` 增加 `isSessionDeleted` / `deletedSessions` 查询并在 `save` 入口加删会话墓碑守卫；`ChatSessionOwner.deleteCurrentSession` 删除成功后递增 `draftSync` 且 `selectInternal` 保留 `draftSync`；`ChatComposerDrafts`（含 `reconcile`、`checkpoint`、`get` 与 `rememberChatComposerDrafts`）在删除成功后释放已删会话缓存及附件引用，拒绝将已删会话重新塞回缓存或由旧页面回写，同时保留其他存活会话的未发送编辑与附件；删除被拦截时保留草稿；`ChatScreen` 相册/拍照异步回调在写入前校验目标草稿仍存活。
- 验证结果：
  - `theta-gpu-test`（`gpu-bai`，验证树 `49a59ef34166dd01239444e808578f279080f37d`，严格配置缓存）：定向运行 `ChatEntrySessionsTest`、`ChatScreenStateTest`、`ChatSessionOwnerTest`、`PluginEntryRuntimeTest`、`UserImageChatTest` 与 `AndroidAgentOwnershipTest`，**113/113 通过，0 失败**（保留全部既有跨页发送、延迟回执、删会话和数据库重开断言）。
  - 本地 Windows 严格配置缓存检查：`scripts/preflight.py --quick` 通过（29 路径、无受保护文件改动、无 `probe` 命名、`:samples:storage-fixture:compileDebugKotlin`、`:app:verifyUiResources` 567 对双语键、`:app:lintDebug`），`:agent-core:test` 全量通过。

本轮验证覆盖 JVM 与 SQLite 数据库重开。未构建 APK，真实设备冷启动、页面交互渲染、实际图片选择与发送、API 33 及 R8 运行行为未验证。新提交的 CI、独立审核和正式回归待发布后执行。

### 第十轮独立审核补充：进程重建后的附件结果

在 `879176c` 上，JVM 探针用已保存的会话 ID 模拟选图结果早于聊天库就绪返回。草稿恢复标记为 1，按该 ID 读取时报 `IllegalArgumentException: Required value was null.`，重开 SQLite 后仍能读到原文字。gpu-bai 探针 1/1 失败。

修复提交 `abd77f7`：相册和相机回调保留发起选择的会话 ID，等待聊天库就绪后再取草稿。gpu-bai 定向运行 `ChatScreenStateTest`，16/16 通过；新增用例核对重开后的原文字、选中附件的字节，以及另一会话未受影响。本次未生成 APK，未实测系统选择器回调或界面渲染。

## PR164 第十一轮：首次调度前取消的运行清理

实现提交 `51e2b39e0fd36e4a7dbf844b60d3e17479d645c9`，验证树 `9dcc405f7e41593e9a1df80046247dac16181a84`。包含远端 PR head `0dda953e79c15e049196b11df3cda3dd904d326a`（含审核员修复）和 main `2cd1b6d78f8fe44aa2937f0d666129718df71073`。最终检查前已 fetch，两个远端引用均为 HEAD 祖先。

- 失败来源：GitHub CI https://github.com/ByteJuno/Theta/actions/runs/45961103548 ，在旧 head 上 ChatEntrySessionsTest.revocationAtAdmissionDiscardsOnlyTheUnpublishedNewSession 超时，769 项中 1 失败。原始日志保存在本轮 ci-failure.log。该用例在隔离 Linux 基线中 18/18 通过，不能据此否认 CI 的时序失败。
- 确定性复现：只注入运行调度器并新增正式回归的树 `ace5f0ede98613adada7c0196b2fc9b6e46e1aae`，theta-gpu-test 19 项中 1 失败：stoppingBeforeRunDispatchReleasesAdmissionAndPreservesAcceptedHistory 报 A stopped undispatched run must release admission。LAZY 协程在首次执行前被取消，不进入 finally，留下 active=true。失败证据 deterministic-red.log/json 保留。
- 修复：ATOMIC 启动确保进入清理；在聊天 gate 内检查取消，避免未发布运行句柄或已取消运行执行前台/模型工作。保留所有既有断言及超时限制，没有修改受保护文件或 UI 文案。
- 修复树首次定向：19/19 通过。最终同树 theta-gpu-test（TEST_HOST=gpu）：ChatEntrySessionsTest、ChatSessionOwnerTest、PluginEntryRuntimeTest 共 92/92 通过，耗时 72.1 秒。覆盖提前停止、历史保留、入口终态、句柄释放、再次接纳，以及原有审批、工具退出与草稿消费回归。
- 提交前 scripts/preflight.py 完整通过，严格 --configuration-cache --configuration-cache-problems=fail，耗时 253.4 秒。应用 770 项=769 通过/1 跳过；agent-core 632 项=631 通过/1 跳过（FROM-CACHE，非本轮重执行）；:sdk:test UP-TO-DATE/NO-SOURCE；567 对中英文资源、:app:lintDebug、storage-fixture 源码检查和编译通过。工作树散列核验未变，提交树与 Linux 验证树相同。

本轮未生成 APK，未操作手机或模拟器。验证仅覆盖 JVM/SQLite；设备冷启动、真实图片选择与发送、实际界面渲染、API 33 与 R8 运行行为未验证。新提交的 CI、独立审核及正式模拟器回归待发布后由审核端执行。 APK SHA256：未生成。本轮证据目录：E:\Desktop\AI研究\theta-auto-loop\event-runs\b27138b8-c83a-4d10-8653-f2894faa6e7e。历轮失败证据与限制在本摘要中保留。
