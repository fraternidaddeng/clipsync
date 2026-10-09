# 剪剪相传 ClipSync

[![CI](https://github.com/fraternidaddeng/clipsync/actions/workflows/ci.yml/badge.svg)](https://github.com/fraternidaddeng/clipsync/actions/workflows/ci.yml)

[English](README.md) | **简体中文** | [日本語](README.ja.md)

**Windows 与 Android 之间的私有点对点剪贴板同步。** 一台设备复制、另一台直接粘贴——文本与图片只在你自己的两台设备之间经加密直连传输。无账号、无云端、无中转、无遥测。

配对像蓝牙一样一次完成（扫码、比对证书指纹、双向确认），同步走局域网或 Tailscale；对能做什么、不能做什么刻意保持诚实——尤其是 Android 的后台剪贴板限制。

> 下载版本与校验值以 [Releases](https://github.com/fraternidaddeng/clipsync/releases) 为准；本页介绍当前源码中的功能，未发布改动见 [CHANGELOG](CHANGELOG.md)。首次配置见[快速上手](#快速上手)。

## 功能

### 同步

- **双向同步与断线补传。** 已捕获、允许同步且仍在保留期内的内容，会在重新连接后继续传输；同步内容有去重与回环抑制。标为「仅本机保留」的条目不补传。
- **文本与图片。** 图片同步（protocol v2 分块传输、历史内缩略图）自 2026-08-28 起**默认开启**，收到的图片也**默认自动写入本机剪贴板**；两个开关互相独立、随时可关。超过 1 MiB 的文本仅保留本机，绝不静默截断。
- **双端历史。** 可搜索历史、删除、保留上限（条数与时长）、导出/导入、敏感来源排除。
- **一切由你掌控。** 暂停同步、私密模式、暂停自动捕获；Android 另有「后台同步服务」总开关——关即真关，重开应用或重启手机都不会自行复活。

### 配对与加密

- **扫码配对 + 指纹核对。** Windows 出示的二维码只含本机地址、端口、证书指纹与一次性令牌，**绝不包含配对密钥**；两边屏幕都会显示指纹，肉眼比对一致后各自确认一次即可。
- **IP 连接使用 TLS 1.3 + 证书指纹固定**；每对设备独立的配对密钥分别由 Windows DPAPI 与 Android Keystore 保护，指纹不符时阻止连接。蓝牙备援使用下述独立加密信道。
- **只走本地网络。** 设备之间经局域网或 Tailscale 直连；没有云数据库、公共中转或 NAT 穿透——互相连不通就不同步，这是设计边界，不是故障。
- **信任可撤销。** 任一端删除对方设备即断连；对端指纹变化（重装、换机）时，Android 要求显式「我已核实——替换配对」确认。

### Android 后台读取——诚实的能力阶梯

Android 10 起系统禁止普通应用后台读剪贴板，剪剪相传提供三条后台读取路线，并保留前台/手动入口；**通路**页逐项探测——「读」与「写回」分开探测，只有实测读到内容才显示 READY：

| 档位 | 需要什么 | 特点 |
|---|---|---|
| **特权直读** | 内置特权宿主，由电脑经 adb 执行一次启动命令（USB 或 Android 11+ 无线调试；Windows 端可一键） | 优先推荐；无需悬浮窗轮询，后台与锁屏效果以手机实测为准 |
| **日志感知 + 悬浮窗** | 电脑 adb 授予一次 `READ_LOGS` + 悬浮窗权限 | 应用内提供一键复制命令 |
| **悬浮窗轮询** | 仅悬浮窗权限 | 不需要电脑；代价是耗电与秒级延迟 |
| **前台 / 手动** | 无后台读取授权 | 可从其他应用分享内容；快捷磁贴可打开手动发送入口，收到的内容可在历史中复制 |

每项权限按需授予、可撤销；路线不可用时会尝试降级。Windows 的「检测手机」、启动特权直读、无线配对与连接会调用 adb；无线配对二维码显示期间会限时查询设备，结束或取消后停止。

### 蓝牙备援（可选，默认关闭）

所有 IP 路由都不可用时（AP 隔离、VPN/TUN 全局接管、路由器故障），已配对设备可改走**蓝牙 RFCOMM** 继续同步**文本**。链路上运行 bt1 安全信道——基于既有配对密钥的 HMAC-SHA-256 双向认证 + 按连接派生的 AES-256-GCM——系统蓝牙配对只是承载层，从不替代剪剪相传自己的配对与撤销。双端默认关闭；IP 恢复后自动切回。图片不过蓝牙。

### 界面

- 双端首次运行引导，可跳过并在「偏好 → 帮助 → 重新查看引导」中重看。Windows 配对页直接显示二维码；Android 引导介绍读取路线与权限，再进入实际设置。
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
2. **Windows**（Windows 10 22H2 或 11，x64）：解压到任意目录，运行 `ClipSync.App.exe`。发布包**未做代码签名**，首次运行 SmartScreen 可能拦截——校验 SHA-256 后点「更多信息 → 仍要运行」。首次运行通常会弹出防火墙警报，勾选「专用网络」并允许——需要放行的只有 TCP `47654` 入站；没有弹出、账户不是管理员或之前点过「取消」时，按[安装指南](docs/install.md)第 3 节手动放行并检查网络类型。卸载 = 删程序目录 + 删数据目录；开过「开机自启」或防火墙放行的，先在应用内关掉 / 移除（它们分别是当前用户的启动项与一条系统防火墙规则）。
3. **Android**（Android 10 / API 29 及以上，无需 root）：安装 Releases 上的 APK，按系统提示允许「安装未知应用」。调试包签名不同，**不能覆盖安装**正式包。
4. 遇到 Clash / Surge 等代理的全局 / TUN 模式，请按安装指南放行局域网直连。

完整逐步指南——组网、Tailscale、代理注意事项、各档 Android 通路与排障——见 [docs/install.md](docs/install.md)（Windows ZIP 内附带副本）。

## 快速上手

1. **接通网络**：两台设备连同一局域网，Windows 允许当前监听端口入站（默认 TCP `47654`）。Tailscale 配置、代理与防火墙排查见[安装指南](docs/install.md)。
2. **配对**：Windows 首次引导的配对页，或「通路 → 配对新设备」出示二维码。Android 引导结束点「去配对」，或在「通路 → 网络 → 去配对」扫码。比对证书指纹，在手机确认后到电脑批准。
3. **先验双向文本**：电脑复制一段测试文字，确认手机历史出现；手机从其他应用把另一段文字「分享 → 剪剪相传」，确认电脑收到。自动写入未成功时先从历史手动复制，按通路页提示排查。
4. **需要手机后台自动捕获时再选路线**：Android「通路 → 本机读取 → 打开引导」。推荐特权直读：手机开 USB / 无线调试，Windows「通路 → 特权直读」检测手机并启动；回手机「测试后台读取」。**手机重启后需重新启动特权通道**。不使用调试授权时可选悬浮窗轮询，或继续手动分享。
5. **再验图片与后台**：确认两端「偏好 → 同步 → 图片同步」开启，分别复制或分享一张 PNG/JPEG 图片。再把手机退到后台测试；历史收到与自动写入剪贴板是两项能力，分别确认。蓝牙备援仅传文本，按[安装指南第 7 节](docs/install.md#7-蓝牙备援可选lan-断开时的后备通道)另行开启。

## 隐私与安全

- **使用前必读**：[隐私与风险说明](docs/privacy-and-risks.zh-CN.md)——内容去哪里、谁能看到、哪些事需要你自己留意。
- 内容只在你明确配对并核对过指纹的设备之间流动，传输走 TLS 1.3 + 证书指纹固定（蓝牙备援运行独立的认证加密信道）。
- 无账号、无云端存储、无中转服务器、无遥测、无崩溃上报。
- 剪贴板正文绝不进入日志或通知——有专门测试钉死；诊断导出可放心提供。
- Android 云备份与设备间迁移全域排除（剪贴历史是敏感明文）。
- 删除以本机优先：一端删除不会远程撤回已到达对端的内容（Windows→Android 方向对已同步条目有墓碑传播）。
- 详见：[威胁模型](docs/threat-model.md) · [产品范围与明确不做](docs/product-scope.md) · 协议 [v1](docs/protocol-v1.md)、[v2（图片）](docs/protocol-v2.md)、[bt1（蓝牙）](docs/protocol-bt1.md)。

## 当前状态（诚实声明）

- 已有 Windows 11 与 Redmi Note 11T Pro 的多轮[真机记录](docs/manual-qa-results.md)，其中逐项注明通过、未测与已知限制；[设备矩阵](docs/device-validation-matrix.md)尚未补齐，不代表其他 ROM 已验证。
- 自动化包含双端单元测试与跨端集成链路。测试范围与结果以对应构建记录为准，不能代替各手机的后台、锁屏与功耗实测。
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
- [CHANGELOG](CHANGELOG.md) · [发布记录](docs/releases/) · [Releases](https://github.com/fraternidaddeng/clipsync/releases)

## 参与与许可

剪剪相传是一个范围刻意冻结的个人项目——新增功能必须服务于捕获、传输、恢复、检索或信任/隐私之一（[范围规则](docs/product-scope.md)）。欢迎经 GitHub Issues 报告问题；请勿在报告中附带剪贴板正文（内置诊断导出已做脱敏）。

MIT 许可——见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
