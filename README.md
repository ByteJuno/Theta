# Theta Core

一个“万物可插拔”的 Android AI 助手基座：内核只管 agent 循环和安全，其余功能都做成可以随时安装、卸载的插件。

An Android AI assistant base where almost everything is a plugin. The core only owns the agent loop and safety; everything else can be installed and removed at any time.

> **状态：设计阶段，还没有可用版本。**
> Status: design stage. Nothing is usable yet.

## 为什么要做

Eta 社区有几十个 fork 在各自重复改同样的东西：模型提供商、厂商语音助手适配、通知、本地化。改任何功能都要 fork 再自己编译。Theta Core 想让这些改动变成可以分享的插件，不用再 fork。

## 计划中的插件类型

| 类型 | 用途 | 门槛 |
|---|---|---|
| 数据插件 | Skill、人设、语言包、模型提供商配置 | 不用写代码 |
| MCP | 外部工具 | 写一个服务 |
| dex 插件 | 协议适配、厂商 Hook、通知、原生界面 | 用 Kotlin 编写并编译 |

dex 插件和 App 同进程运行，信任模型与 LSPosed 模块相同：只安装你信任的作者的插件。

## 许可证

[MIT](LICENSE)。本项目全新编写，不复制 Eta 的代码。
