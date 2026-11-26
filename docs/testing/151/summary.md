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
