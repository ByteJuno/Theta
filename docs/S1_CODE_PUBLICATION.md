# S1 CODE 发布中断补证 — 2026-11-09

本轮补齐真实 Host UI 的 CODE 摘要拒绝、目的文件部分写入、五个发布边界进程死亡及冷恢复。与 [物理 G/J/K 补证](S1_CLOSEOUT.md)、已接受的逐候选运行时及 DATA 专项合并核对后，DESIGN §11 的现行 S1 可行性门槛已完成。结论按各自候选和场景累计，不表示下面的新 Debug APK 已通过旧候选的全部真机或 R8 测试，也不是产品发版结论。

## 冻结输入

源码基线为 `2d8258c7dcf82b6750cb390b5b343cd648ad6b6b`，新增 androidTest、固定签名载荷和设备驱动；未修改生产 Kotlin。123 个编译/测试相关源文件的哈希清单、实际返回记录及独立归档复核见 [证据索引](evidence/s1-code-publication-20261109.json)。清单 SHA-256 为 `65a6bb7577d5db21975396a296f883ee39cec287c8a5b4cd4b27ca3d3e0a3401`。

| 输入 | SHA-256 |
|---|---|
| 本轮 Debug Host | `5a43e0ea5357db7a23186ce9b5ab368266d8cccff91a811a6758c3bbed786668` |
| 最终 androidTest APK | `8f1ddd6465ad6de9fb158903ccf649e8162d5f7688724db2964c603ea637b59c` |
| 签名 CODE v1 | `da929caa97ec18e00c634dbbce81b65a58ee1e6f1a6c781074d83c2f70e7b0de` |
| 签名 CODE v2 | `520fe0d6560cee57b01cddab9252c0eed6cb51230124d1d6b9996e18a785d34c` |

四包均通过 build-tools 37 apksigner，证书 SHA-256 为 `0a03e9518759eb79fcb57b6264010ed1eff1193d5eea23facfbc75378b2ddf6b`。v1/v2 为同 ID、同签名的实际 fixture；v2 含确定生成的 8 MiB 惰性资产，总包长 8,468,926 字节，用于观察真实复制中的部分文件。它们不沿用历史 fixture 的哈希。

仅使用新建的 API 36 x86_64 AVD `theta_s1_code_20261109` / `emulator-5590`，每次设备操作前核对专属 AVD 名和持久 marker。未操作手机或已有模拟器。本轮末再次核对已安装 Host 哈希后，已关闭本轮 AVD。

## 16 个真实 UI / 冷恢复场景

每个场景先用 MainActivity 实际导入/Install v1，进入 Fixture page，确认 `Fixture runtime normalV1`，将计数 0 增为 1；停用组再点击实际 `Disable Fixture`。随后使用生产 PluginManager、PluginStore 和更新恢复路径。androidTest 仅通过反射接入现有私有 publication 回调，在匹配边界 fsync 记录后自行 `killProcess`；没有生产故障入口、授权捷径或外部杀进程制造通过。

| 场景（各含 enabled / disabled） | 次数 | 独立观察与冷恢复 |
|---|---:|---|
| INTENT、TEMP_CREATED、PAYLOAD_READY、PARENT_READY | 8 | v2 未发布；冷态仍 v1，计数 1、启用意愿保持 |
| MOVED | 2 | 完整、合法签名 v2 已原子发布；冷态正常 v2，计数 1、启用意愿保持，更新记录 dataRestorable=true |
| PARTIAL_WRITE | 2 | FileObserver 在生产目的文件非空但未完整时触发自杀；进程外归档实际残留分别为 2,953,216 / 1,671,168 字节，均小于完整包；冷态清理残留并保留 v1 / 计数 1 |
| 审核后摘要篡改 | 2 | 先正常显示 v2 审核页，再改变 staged APK 一字节；点击实际 Install，明确显示 `Package changed while installing`；没有 v2 发布，冷态仍 v1 |
| 正常发布正控 | 2 | 实际 UI 完整安装 v2，下一冷态进入 v2；计数和启用意愿保持 |

启用组冷恢复还实际打开 Fixture page，核对对应 `normalV1` / `normalV2` 运行时及 `Counter 1`，不只读取 manifest。全部场景检查 publication journal 和 staging 已清理。每次 instrumentation 具有独立 requestId/processToken；外部命令记录确认 12 次故障均自行消失，随后新进程恢复。

CODE 的 MOVED 已越过完整包原子发布点，不能套用 DATA 的“没有 selection commit 就保留旧版”断言。CODE 冷态按生产 PluginUpdates 逻辑验证完整包并采用保守快照策略；不把合法 v2 冷激活误记成部分写入激活，也不要求中断丢弃已发布的完整包。

## 独立证据复核与限制

完成后从原始 tar 重新计算 48 份文件系统归档的 770 个文件条目，全部匹配驱动清单。另核对 16 个组合齐全、48 个唯一 requestId、48 个唯一 processToken、结果文件与归档内容一致、自然进程消失记录、v1/v2 完整字节、counter 文件、持久 enabled、启用组运行时 UI、最终清理和源码哈希。原始归档共 242,544,640 字节，保留在本地 `theta-code-publication-20261109/matrix-1`；索引保存逐份哈希，不将原始 APK/tar 提交仓库。

`smoke-1` 至 `smoke-4` 保留 FAILED：分别涉及启动等待/SystemUI ANR、测试 provider 的 Kotlin 依赖、按钮文字匹配、停用后 restart-required 状态匹配。`smoke-5` 是单独的三项冒烟；最终 16 项结论仅来自最终 APK 的 `matrix-1`。独立复核脚本的前两次失败分别为 Windows 默认 GBK 读 UTF-8 manifest、把 RECOVER 的 previousRequestId 也误计为 CRASH 请求，修正后重新完整复核，不修改原始设备记录。

本轮是 Android 进程死亡与原子发布恢复，不是物理断电、磁盘满或任意文件系统耐久性证明。Host 当前没有输入可信索引 expectedSHA 的产品入口，因此不新增测试专用产品 UI：已接受 DATA storage20 继续证明其 API 层 expectedSHA 拒绝；本轮实际 Host UI 证明审核内容与提交内容不一致会拒绝，两者边界分别保留。任意 ABI/包/调度组合、旧专项中的私有 tombstone、精确 idle 续期等披露不扩成新的 S1 门槛。

## 构建与提交边界

Debug Host/androidTest 构建通过；app JVM 412 项（411 通过、1 项既有 Windows 跳过），agent-core 168 项通过，SDK 任务无用例；lintDebug 零错误、15 警告。Release merged manifest 已确认不含测试 instrumentation/provider/authority。普通 PR CI 新增 androidTest 编译；设备矩阵是上述本地独立 AVD 执行，不能把 CI 编译视为再次设备通过。

首次提交 `7de23a2` 的 CI42637486329 中 paired-keep-size 通过，build 因新增 padding 任务捕获 Gradle script、Release lint 对生成资产缺少依赖而失败。后续将生成逻辑移入类型化任务，并用 AGP `addGeneratedSourceDirectory` 接线；本地配置缓存保存/复用、Host/androidTest、normalV2 Release 与 Debug lint 均通过。四个重建 APK 的完整 SHA-256 与上述冻结实测包全部相同；不重命名原始设备结果、不宣称另一次设备执行。错误日志、修正源码哈希和逐包相同比较见 [构建修正索引](evidence/s1-code-publication-build-fix-20261109.json)。原始 123 文件清单保留其初次冻结身份，此修正索引单列后续构建接线变更。

`863b62f` 的 CI42639462940 又暴露 androidTest 资产的 lint 依赖缺失（配对任务仍通过）。单独增加 lint model 依赖的本地尝试也失败，因为 lint analysis 同样消费该目录，日志保留。最终 androidTest 资产改用类型化 `StageS1PublicationAssets`，通过 AGP deviceTest generated-assets API 注册，不逐个补任务名；仅为加载该构建任务类声明 convention plugin `apply false`，不将插件约定应用到 Host。随后与 CI 相同的完整任务图（全部 Debug/Release、androidTest、lint、单测）3,460 项任务成功、配置缓存保存成功；第二次 3,456 项任务成功并复用缓存。JVM 总计 604 项，603 通过、1 项既有 Windows 条件跳过、零失败。再次确认四个完整 APK 哈希均与实测冻结输入相同；这些构建跟进没有再次操作设备。

审核指出 PR Check 默认上传 APK/R8 配置与注释不一致。本次新增 `workflow_dispatch`，依赖记录及物理测量输入上传仅在手动触发时执行；普通 PR/main CI 继续测量、仅上传原摘要白名单。PR 仍待审，不合并、不启用 auto-merge。

复现入口（脚本仅接受同名专属 AVD 和 marker，会清空该专属模拟器的 Host 数据）：

```powershell
python -I scripts/s1-code-publication-device.py --serial emulator-5590 `
  --host-apk app/build/outputs/apk/debug/app-debug.apk `
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk `
  --output <new-immutable-evidence-directory>
```
