# S1 插件加载原型验证

当前是 S1 的增量原型，不是完整的插件管理器或可发布版本。

## 执行位置

- GitHub Actions：Debug/Release 编译（含 R8）与 lint。
- InstaBox：单元测试、APK 清单检查、插件链接预检和专用模拟器测试。
- osaka-lab：开发、同步源码及发起 InstaBox 任务，不在 lab 运行 Gradle。

目前 GitHub Actions **不会自动触发 InstaBox**。`scripts/verify-s1.sh` 是统一的验证入口，尚未接入自动远程调度或必需状态检查。不要把一次人工触发的通过结果当作后续提交的测试状态。InstaBox 凭据保留在部署环境，不进入仓库或 PR 日志。

## 在 InstaBox 执行

需要 JDK 25、Android SDK platform `android-37.0`、Python 3、jq、unzip，以及已启动的专用 Android 模拟器。先加载环境并进入同步后的仓库根目录，再执行：

```bash
ADB="/var/tmp/emusdk/platform-tools/adb -s emulator-5554" TEST_TIMEOUT=240 bash scripts/verify-s1.sh
```

脚本会卸载 `io.github.bytejuno.thetacore`，清空该应用的数据，再安装 debug 签名的 R8 Release 宿主。**不要对日常使用的设备执行。**

通过远程 exec API 调用时，应在 InstaBox 后台启动任务，将标准输出与退出码写入本次运行独有的文件；API 请求超时不能视为构建失败或成功，也不能据此盲目重复启动。

## 验证内容

1. Debug/Release 编译、lint、`testDebugUnitTest`。
2. Hello Debug/Release APK 清单和入口类检查。
3. Hello 与 Probe 的共享库重复打包、类型与成员链接预检。
4. 模拟器安装 R8 宿主，在宿主内加载 Hello 和 Probe。
5. Probe 共享库检查、按钮、对话框、菜单交互。
6. 强制结束并重启宿主，从磁盘恢复 Probe。

注册表有 8 个 JUnit 测试，覆盖重复 ID、关闭后拒绝登记、逆序清理、异常隔离、幂等撤销以及登记/关闭和撤销/关闭竞争。

链接检查是预检而非完整的 JVM/DEX 链接证明：当前沿继承链走出已解析类时会保守放行，也不完整检查访问权限和调用种类。必须结合实际设备执行，不能把 `0 missing` 等同于所有插件 API 兼容。

## 证据与结果

保存完整运行日志、最终退出码及本次源码提交 SHA。JUnit XML 位于 `app/build/test-results/testDebugUnitTest/`；设备测试失败时在 `build/emulator-test/` 保存截图和 logcat。每次修改源码后需要重新验证，不能复用旧构建的成功标记。

## 尚未完成

- 完整的禁用/卸载、`onDisable` 和加载实例生命周期。
- 非协作式 `onEnable` 超时、持久化隔离和崩溃恢复。
- 插件签名身份校验、升级/降级策略及重复安装处理。
- 损坏安装包恢复、安装并发和界面生命周期等异常路径。
- 等待已被手动撤销但仍在运行的 cleanup；当前仅保证同一登记不重复调用。

当前失败清理会先原子关闭登记入口并移除扩展，再取消作用域、在独立线程倒序清理；5 秒超时只提示需要重启，不代表插件代码已经终止。
