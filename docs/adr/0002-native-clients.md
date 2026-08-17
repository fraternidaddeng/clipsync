# ADR 0002：Windows 与 Android 使用原生客户端

- 状态：接受
- 日期：2026-08-12

## 背景

第一版只支持 Windows 和 Android。主要风险来自系统集成：Windows 剪贴板事件、托盘和 Win32 消息循环，以及 Android Foreground Service、通知、分享、快捷磁贴、Shizuku、logcat 和 overlay。

## 决策

- Windows 使用 .NET 8 + WPF。
- Android 使用 Kotlin + Jetpack Compose，JDK 17，`minSdk 29`，`targetSdk` 使用当前安装的稳定 SDK。
- Android 网络、存储和状态层计划使用 OkHttp/Coroutines、Room 与 ViewModel/StateFlow。
- 两端不共享业务源代码，只共享 `protocol/v1` 的 JSON Schema、fixtures 和测试向量。

## 后果

优点：生命周期、权限、后台服务和平台事件直接使用成熟原生 API；减少跨平台插件桥接的不确定性；平台故障更容易被单元测试和实体机测试隔离。

代价：两套 UI 和平台代码需要分别维护；协议变更必须同步更新 C#、Kotlin 和兼容性测试；不能依靠跨平台组件自动获得第三个平台支持。

## 被否决的方案

- Flutter + Rust 作为第一版基础：仍需分别实现关键原生桥接层，不能消除主要风险。
- 立即抽取 Rust/Kotlin Multiplatform 业务层：协议尚未稳定，会提前增加构建与调试复杂度。

若未来协议稳定并新增第三个平台，可重新评估共享纯业务层；这不是 MVP 前置条件。
