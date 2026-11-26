实现提交 c86e1df4a5d9b92555ed4f6afe55ca788300b27e

「插件与诊断」显示后台运行状态、厂商设置指引及通知授权入口。将后台诊断说明移到导入操作和错误反馈之后，恢复与 main 一致的导入入口布局顺序。此前审核模拟器两次在进入页面后等待 Import code APK 超时。

严格配置缓存 preflight 通过。567 对中英文资源、lintDebug、storage-fixture 编译通过，agent-core:test 为 UP-TO-DATE。应用 JVM 单测共 718 项，跳过 1 项，失败及错误均为 0。

未生成主应用 APK，视觉未验证。新构建从聊天进入后导入按钮的首屏可见性、upgrade-and-data、系统设置及通知授权交互仍待独立审核端回归。未操作手机。
