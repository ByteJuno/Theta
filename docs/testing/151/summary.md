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
