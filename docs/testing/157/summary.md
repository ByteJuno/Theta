实现提交 33a76e297a962bdd2d84d37fa8c248f1b317f763。设备 APK 来自 caf2aa00165578e45c0c00db7ac2cca0835383d6。

API 34 专用模拟器复现：Probe 节点 y=2309..2400，列表底边 2337，原点击 y=2354 后仍在列表，点击可见区域 y=2323 即进入页面。导入 APK 与 ZIP 均打开系统文件选择器，通知按钮打开系统授权弹窗，允许后系统报告 granted=true。

39 项观察测试通过。严格配置缓存 preflight 通过。候选 emulator-test.py 在同一 APK 上完整通过，耗时 345 秒，含首次 18/18、冷启动重新启用、恢复和卸载。早期定向试验记录了启动未进入诊断页和 DNS 解析失败。APK SHA-256 为 c3b3455952ed9de63cbb7134643ec261702867c2a937de9b9a87b2f96d94b3b9。

本轮使用原审核提交构建的同一 release APK，仅修改测试脚本。upgrade-and-data、data-zip、新提交 CI、独立审核及正式回归待执行。未测试真机或厂商系统设置。
