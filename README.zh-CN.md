# 剪剪相传 ClipSync

[![CI](https://github.com/fraternidaddeng/clipsync/actions/workflows/ci.yml/badge.svg)](https://github.com/fraternidaddeng/clipsync/actions/workflows/ci.yml)

[English](README.md) | **简体中文** | [日本語](README.ja.md)

**Windows 与 Android 之间的私有点对点剪贴板同步。** 一台设备复制、另一台直接粘贴——文本与图片只在你自己的两台设备之间经加密直连传输。无账号、无云端、无中转、无遥测。

配对像蓝牙一样一次完成（扫码、比对证书指纹、双向确认），同步走局域网或 Tailscale；对能做什么、不能做什么刻意保持诚实——尤其是 Android 的后台剪贴板限制。

> v0.1.0 产品线已在 `main` 完成。当前最新发布为 [v0.1.0-rc.2 预发布](https://github.com/fraternidaddeng/clipsync/releases)（Windows 便携 ZIP + 正式签名 Android APK，均附 SHA-256 校验文件）。

## 功能

### 同步

- **双向、近实时。** 内容先在产生端落库，再在已配对设备之间交换；断线期间复制的内容重连后按序补齐、exactly-once——不重复、不回环。
- **文本与图片。** 图片同步（protocol v2 分块传输、历史内缩略图）自 2026-08-28 起**默认开启**，收到的图片也**默认自动写入本机剪贴板**；两个开关互相独立、随时可关。超过 1 MiB 的文本仅保留本机，绝不静默截断。
- **双端历史。** 可搜索历史、删除、保留上限（条数与时长）、导出/导入、敏感来源排除。
- **一切由你掌控。** 暂停同步、私密模式、暂停自动捕获；Android 另有「后台同步服务」总开关——关即真关，重开应用或重启手机都不会自行复活。

### 配对与加密

- **扫码配对 + 指纹核对。** Windows 出示的二维码只含本机地址、端口、证书指纹与一次性令牌，**绝不包含配对密钥**；两边屏幕都会显示指纹，肉眼比对一致后各自确认一次即可。
- **每条连接都走 TLS 1.3 + 证书指纹固定**；每对设备独立的配对密钥分别由 Windows DPAPI 与 Android Keystore 保护，指纹不符绝不降级。
- **只走本地网络。** 设备之间经局域网或 Tailscale 直连；没有云数据库、公共中转或 NAT 穿透——互相连不通就不同步，这是设计边界，不是故障。
- **信任可撤销。** 任一端删除对方设备即断连；对端指纹变化（重装、换机）时，Android 要求显式「我已核实——替换配对」确认。

### Android 后台读取——诚实的能力阶梯

Android 10 起系统禁止普通应用后台读剪贴板，剪剪相传把这项能力拆成四档，在**通路**页逐项如实探测——「读」与「写回」分开探测，只有实测读到内容才显示 READY：

| 档位 | 需要什么 | 特点 |
|---|---|---|
| **特权直读** | 内置特权宿主，由电脑经 adb 执行一次启动命令（USB 或 Android 11+ 无线调试；Windows 端可一键） | 体验最好：息屏也能即时上行 |
| **日志感知 + 悬浮窗** | 电脑 adb 授予一次 `READ_LOGS` + 悬浮窗权限 | 应用内提供一键复制命令 |
| **悬浮窗轮询** | 仅悬浮窗权限 | 不需要电脑；代价是耗电与秒级延迟 |
| **前台 / 手动** | 无 | 分享面板、快捷磁贴、通知复制——永远可用 |

每项权限都经可见、有解释、可撤销的流程授予；撤掉任何一项即自动降级到仍可用的档位。应用**绝不**静默调用 adb。

### 蓝牙备援（可选，默认关闭）

所有 IP 路由都不可用时（AP 隔离、VPN/TUN 全局接管、路由器故障），已配对设备可改走**蓝牙 RFCOMM** 继续同步**文本**。链路上运行 bt1 安全信道——基于既有配对密钥的 HMAC-SHA-256 双向认证 + 按连接派生的 AES-256-GCM——系统蓝牙配对只是承载层，从不替代剪剪相传自己的配对与撤销。双端默认关闭；IP 恢复后自动切回。图片不过蓝牙。

### 界面

- 双端五步首次运行引导（配对、后台读取路线、权限——每一步都有「稍后设置」出口）。
- 日/夜主题（跟随系统或手动固定），完整设计体系（[设计纲领](docs/design/DESIGN-CHARTER.md)）。
- **19 种界面语言**（含从右到左的阿拉伯语）；源语言为简体中文。

## 下载与安装

从 [GitHub Releases](https://github.com/fraternidaddeng/clipsync/releases) 获取预编译安装包：

| 文件 | 用途 |
|---|---|
| `ClipSync-windows-x64.zip` | Windows 便携包（自带 .NET 运行时，免安装） |
| `ClipSync-android.apk` | 正式签名的 Android APK |
| `*.sha256` | 对应文件的 SHA-256 校验值 |

1. **先校验**：比对 `.sha256` 文件（Release 正文亦列明各产物 SHA-256）——Windows 用 `Get-FileHash`，Linux/macOS 用 `sha256sum -c`。
2. **Windows**（Windows 10 22H2 或 11，x64）：解压到任意目录，运行 `ClipSync.App.exe`。发布包**未做代码签名**，首次运行 SmartScreen 可能拦截——校验 SHA-256 后点「更多信息 → 仍要运行」。防火墙弹窗时勾选专用网络并允许（TCP `47654`，UDP `47653` 发现广播）。卸载 = 删程序目录 + 删数据目录，无注册表残留。
3. **Android**（Android 10 / API 29 及以上，无需 root）：安装 Releases 上的 APK，按系统提示允许「安装未知应用」。调试包签名不同，**不能覆盖安装**正式包。
4. 遇到 Clash / Surge 等代理的全局 / TUN 模式，请按安装指南放行局域网直连。

完整逐步指南——组网、Tailscale、代理注意事项、各档 Android 通路与排障——见 [docs/install.md](docs/install.md)（Windows ZIP 内附带副本）。

## 快速上手

1. **接通网络**：两台设备连同一局域网；或都装 Tailscale，并在 Windows 通路 → 网络段「连接」卡的「额外监听地址」里填入 `100.x.y.z` 地址。
2. **配对**：Windows → 通路 → 「配对新设备」出示二维码；Android → 通路 → 扫码。比对两边屏幕上的证书指纹，双向确认。约两分钟完成。
3. **复制点什么。** 文本与图片即刻双向同步；离线期间复制的内容重连后按序补齐。
4. **可选——最佳 Android 体验**：开启**特权直读**，息屏时后台复制也能即时上行。手机打开开发者选项与 USB 调试（或 Android 11+ 无线调试），然后用 Windows 端一键「启动特权直读」（或在 Android 通路页复制一行启动命令到电脑执行）。注意：**手机重启后特权通道会关闭**，需在电脑上再执行一次启动命令（除非撤销过调试授权，否则无需重过 RSA 指纹确认）。
5. 完全不想用电脑？选**悬浮窗轮询**档，或直接用永远可用的分享面板 / 快捷磁贴 / 通知复制。

## 隐私与安全

- 内容只在你明确配对并核对过指纹的设备之间流动，传输走 TLS 1.3 + 证书指纹固定（蓝牙备援运行独立的认证加密信道）。
- 无账号、无云端存储、无中转服务器、无遥测、无崩溃上报。
- 剪贴板正文绝不进入日志或通知——有专门测试钉死；诊断导出可放心提供。
- Android 云备份与设备间迁移全域排除（剪贴历史是敏感明文）。
- 删除以本机优先：一端删除不会远程撤回已到达对端的内容（Windows→Android 方向对已同步条目有墓碑传播）。
- 详见：[威胁模型](docs/threat-model.md) · [产品范围与明确不做](docs/product-scope.md) · 协议 [v1](docs/protocol-v1.md)、[v2（图片）](docs/protocol-v2.md)、[bt1（蓝牙）](docs/protocol-bt1.md)。

## 当前状态（诚实声明）

- **2026-08-26 用户已签核真机验证完成**（[docs/manual-qa-results.md](docs/manual-qa-results.md)）；逐设备矩阵（[docs/device-validation-matrix.md](docs/device-validation-matrix.md)）在结构化明细回填前维持 `NOT_TESTED`，暂不构成逐 ROM 兼容性承诺。
- 自动化覆盖充分（双端合计 1400+ 用例，CI 含跨端端到端链路），但发布 ≠ 真机验证——发布说明中亦如实注明。
- **Windows 产物未签名**——SmartScreen 会拦截；运行前先校验 SHA-256。
- **Android 必须安装 Releases 上的正式签名 APK**；调试包签名不同，不能覆盖升级。
- **特权直读**通道不会在手机重启后自愈——回电脑重跑一次启动命令即可（Windows 一键）。无线调试场景下，手机的 `IP:端口` 在息屏/切网后常会漂移，按当前值重新连接即可，配对不用重来。
- 设计上明确不做：iOS/macOS/Linux 客户端、文件传输（请用 LocalSend）、账号、云中转、NAT 穿透、遥测。见[产品范围](docs/product-scope.md)。

## 从源码构建

前置条件：Git、.NET 8 SDK（由 `global.json` 固定）、JDK 17、Android SDK Platform 35、建议 PowerShell 7。

```powershell
pwsh ./scripts/build-windows.ps1       # 构建 + 测试 Windows 端
pwsh ./scripts/build-android.ps1       # 构建 + 测试 Android 端
pwsh ./scripts/validate-protocol.ps1   # 校验共享协议 fixtures

pwsh ./scripts/package-windows.ps1     # dist/ClipSync-windows-x64.zip (+ .sha256)
pwsh ./scripts/package-android.ps1     # dist/ClipSync-android.apk    (+ .sha256)
```

Release APK 签名只从 `CLIPSYNC_ANDROID_*` 环境变量读取（密钥库绝不入库）；打包、签名与按 tag 触发的发布工作流见 [docs/install.md §10](docs/install.md)。

仓库结构：

- `docs/` —— 产品、安全、协议、Android 能力、ADR 与验证记录
- `protocol/` —— JSON Schema 与跨语言共享 fixtures（`v1/`、`v2/`、`bt1/`）
- `windows/` —— .NET 8 WPF 应用与 xUnit 测试
- `android/` —— Kotlin/Compose 应用与 JVM 单元测试
- `scripts/` —— 可重复的构建、校验、打包与显式 adb 检查命令

分支策略：`main` 保持可发布；所有工作在测试与验收通过后落入 `main`。

## 文档导航

- [安装与配对指南](docs/install.md)——最终用户路径
- [产品范围](docs/product-scope.md) · [威胁模型](docs/threat-model.md)
- [设计纲领](docs/design/DESIGN-CHARTER.md)——UI 的权威记录
- [Android 后台剪贴板](docs/android-background-clipboard.md)——能力阶梯的原理
- [CHANGELOG](CHANGELOG.md) · [v0.1.0 发布记录](docs/releases/v0.1.0.md) · [Releases](https://github.com/fraternidaddeng/clipsync/releases)

## 参与与许可

剪剪相传是一个范围刻意冻结的个人项目——新增功能必须服务于捕获、传输、恢复、检索或信任/隐私之一（[范围规则](docs/product-scope.md)）。欢迎经 GitHub Issues 报告问题；请勿在报告中附带剪贴板正文（内置诊断导出已做脱敏）。

MIT 许可——见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
