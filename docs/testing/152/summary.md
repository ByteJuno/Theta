实现提交 90dc2acfa8efcbab45c7f559bbab086b98065b5e。Debug 宿主 APK SHA-256 043e003adc6f0906a5134afe389971b149773a118de455bfa991de7db5d7063a。

严格配置缓存 preflight 通过，覆盖 storage fixture 编译、UI 资源、Lint、agent-core 和 app JVM 测试。JUnit 共 1353 项，1351 通过、2 跳过，其中新增输出表面测试 3/3 通过。Hello Debug 插件链接检查 400 个引用，0 个缺失，3 个可选类提示。

本轮仅做 JVM 和 Debug APK 静态验证。第三方表面的设备渲染、前台通知与停止按钮实机交互、API 33 设备及 R8 发布产物未验证。远端 CI、独立审核和正式模拟器回归待发布后执行。
