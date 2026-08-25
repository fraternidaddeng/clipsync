# Changelog

本文件记录 ClipSync 的用户可见变更，格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。尚无正式发布版本；首个发布时将 Unreleased 内容归入 `v0.1.0`。发布叙述与验收声明见 `docs/release-notes-template.md`。

## [Unreleased]

分支：`cursor/implement-charter-ui-1991`（自 Stage 0–3 基线以来）。

### 新增

- [双端] 设计宪章 UI 全面落地：令牌系统、日/夜主题运行时切换、随包三字族（Noto Sans SC / Plus Jakarta Sans / JetBrains Mono）、超椭圆卡片与动效令牌、polyline 应用图标（Windows ICO + Android 自适应图标）、全中文文案。
- [Android] 完整同步栈：Room 存储（序号分配、接收向量、outbox 同事务）、pinned-TLS WebSocket 同步引擎、指数退避重连、`connectedDevice` 前台服务。
- [Android] 三个无权限入口（分享面板、快捷磁贴、通知「复制」动作）与前台自动捕获上行。
- [Android] 入站收件箱 + `auto_apply_remote` 自动写回 + 失败回退通知；回环抑制。
- [Android] 三档后台读取后端代码落地：Shizuku 特权事件（UserService + `IClipboard` 反射适配）、ADB 日志 + 悬浮窗（AOSP/OneUI/MIUI-HyperOS/ColorOS 四族版本化解析器）、悬浮窗轮询（焦点控制器 + 不可触摸不变量）。**真实 ROM 可用性未验证，矩阵全 `NOT_TESTED`。**
- [Android] 通路页能力向导与真实探针（读/写能力分离探测）、对端可达性周期重探、首次运行引导、全套空状态、`POST_NOTIFICATIONS` 引导与关闭状态行。
- [Android] 开机恢复链（默认关，含诚实恢复通知）、保留期清理、用户可调大小上限。
- [Windows] 托盘四态图标 + 440px 托盘浮窗（最近剪贴 + 暂停开关）、托盘诊断查看器 + 认证锁定通知。
- [Windows] 自绘宪章标题栏、配对 QR/确认窗口重皮肤、通路页接真实会话状态（连接数、发件队列、对端确认至）。
- [双端] 暂停/私密模式在捕获、队列、引擎逐层真实关断，恢复后补投无丢失。
- [双端] 蓝牙备援传输阶段 1——bt1 握手与帧层（纯逻辑，无平台蓝牙依赖）：`docs/protocol-bt1.md` 定稿安全信道协议（共享 `pair_secret` 的 HMAC-SHA-256 双向认证、HKDF-SHA-256 按方向派生 AES-256-GCM 会话密钥、4 字节大端长度前缀 + 计数器 nonce 帧、7 MiB 明文上限、`BT1_` 错误码）；`protocol/bt1/` 新增跨语言测试向量与消息 fixtures 并纳入 `scripts/validate-protocol.py` 校验；C#（`ClipSync.Core/Security/Bt1`）与 Kotlin（Android `sync` 包）双端实现，针对同一 fixtures 的单测含篡改/重放/乱序/截断/超限负例。尚无任何真实蓝牙 I/O；RFCOMM 传输、降级编排与 UI 均属后续阶段（见 `docs/bluetooth-fallback-plan.md`）。
- [分发] 最小分发链（阶段 7 裁剪版）：`scripts/package-windows.ps1` 产出自包含 win-x64 便携 ZIP（含运行时/许可/安装指南 + SHA-256，Linux CI 经 `EnableWindowsTargeting` 可产包）；`scripts/package-android.ps1` 产出 Release APK（签名只读 `CLIPSYNC_ANDROID_*` 环境变量，密钥库不入库，另有 Debug/未签名校验路径）；`docs/install.md` 一页中文安装/配对/通路/排障指南（并随 Windows ZIP 分发）。

### 变更

- [Android] 能力路线去品牌化：Shizuku 在 UI 中呈现为「特权直读」。
- [Windows] 三窗口全部令牌刷 `DynamicResource` 化以支持运行时换肤。

### 修复

- [Windows] 启动时不再遗留空控制台窗口：当进程被控制台方式拉起（如 `dotnet ClipSync.App.dll`，窗口标题为 dotnet.exe 路径）时，应用在 `OnStartup` 里检测并分离继承来的控制台（`GetConsoleWindow` + `FreeConsole`），保持纯托盘启动；应用运行时自身不派生任何子进程。
- [Android] 通知栏不再回落成系统默认图标（绿色机器人）：小图标去掉主题属性着色（SystemUI 跨进程解析不了主题属性时会整体回退），三个通知渠道归入统一「剪贴同步」渠道组，前台服务通知补齐宪章配色（polyline 图标 + 流动蓝 #215F8F）、低优先级与无时间戳。
- [Windows] 历史列表图片缩略图不再显示为空灰块：修复并发刷新下缩略图临时文件互相踢掉导致的静默失败（改为每次尝试独立临时名，输者复用赢者成品）；WIC 解码/编码故障（含 COMException）降级而非中断刷新；位图在 `FromEntry` 一次解码并冻结后绑定，容器回收不再重解码；确实无法出图时显示诚实的「无预览」占位而非空灰块。
- [双端] 开启系统代理（Clash/Surge 等）时同步不再被劫持或断连：Android 端 OkHttp（同步、配对、健康探测）显式 `Proxy.NO_PROXY` 直连，Windows 端 `ClientWebSocket.Options.Proxy = null` 直连；TUN/VPN/全局模式的放行方法见 `docs/install.md` 第 5 节。
- [Windows] 合并 tray-diagnostics 后的 `DiagnosticsWindow` 构建错误。
- [Android] 自动写入通知配色与 Compose 弃用告警。
- [Android] 远端图片自动写入改为独立开关「自动写入远端图片」（`auto_apply_images`，默认关）：文本「自动写入剪贴板」不再连带把图片写进本机剪贴板，与 Windows 端及 ADR 0004（图片写入门独立于文本自动应用）对齐；暂停同步仍同时关断两者，图片照常进入历史可手动复制。

### 文档 / 测试

- CI 工作流重构为三作业：协议 schema/fixture 校验、Windows 构建 + 全部测试、Android 单元测试 + debug APK 组装；在 `cursor/**` / `feature/**` 分支与 PR 上运行。
- 测试规模：444 Android JVM + 185 跨平台对端 + 39 Windows 应用层用例；新增 Windows↔Android 全链路脚本化集成测试与真实会话事件驱动的通路页验证。
- 新增 `docs/verification-without-device.md`（绿测 ≠ 兼容的边界）、`docs/stage-gap-audit.md`、`docs/competitive-analysis.md`、`docs/design/ui-gap-audit.md`、`docs/manual-qa-checklist.md`、`docs/release-notes-template.md`；扩充 `docs/device-validation-matrix.md` 为脚本化检查清单。

### 已知欠账（进行中）

- 发布产物上传（GitHub Releases / 发布 CI）、历史导出导入——并行任务推进中，见 `docs/competitive-analysis.md` 状态更新。
- 实体机验证为零；图片同步（protocol v2）未做。
