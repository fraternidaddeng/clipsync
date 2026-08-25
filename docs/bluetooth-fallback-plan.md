# 蓝牙备援传输实施计划

状态：软件实现完成（对应 ADR 0005），**阶段 0 实体机 spike 已完成并判定 GO**（2026-08-25，报告 `docs/bluetooth-phase0-report.md`）。阶段 1–4 的代码与单测均已落地（见各阶段状态）；阶段 5 的实体机矩阵未开始。诚实边界：双端功能默认关闭；阶段 0 只证明一对真机上 RFCOMM 链路与 bt1 信道可行（走 spike 工具路径，非产品内 `BluetoothSyncHost`/`BluetoothSyncConnector` 路径），补齐阶段 5 的产品级实测前，不得在文档或 UI 中宣称蓝牙备援 READY。
适用分支：`main`。所有阶段遵守仓库既有验收规则：无实体机证据不得标 `READY`；`TreatWarningsAsErrors`、detekt/ktlint 基线、协议 fixtures 由 `scripts/validate-protocol.py` 校验的约束照常适用。

## 总体形态

蓝牙路径只替换"TLS+pin 包住 WebSocket"这一层，协议 v1 会话原样运行在 bt1 安全信道内。改动集中在四处，均沿既有接缝：

1. 共享密码学/帧层（新，跨语言，两端各一份实现 + 共享测试向量）。
2. Windows：新程序集 `ClipSync.Peer.Bluetooth`（RFCOMM 服务端 + `ISyncTransport` 适配器）。
3. Android：`BluetoothSyncConnector`（RFCOMM 客户端 + `SyncTransport` 适配器）。
4. 编排：`SyncSupervisor`（Android）与 Windows 监听侧的降级/回切逻辑 + 双端设置与状态 UI。

会话引擎（`SyncSessionEngine` / `SyncEngine`）、协议 schema、数据库、配对流程零改动。

## 阶段 0：可行性验证（spike，产出证据，不产出功能）

状态：**已完成（GO，2026-08-25）**。报告见 `docs/bluetooth-phase0-report.md`（含双端完整日志与逐门槛判定）：G-W1、G-A1、G-C1、G-S1 全过，G-P1–P3 在一对真机上达标，结论为 GO——阶段 2 值得开工（且事实上阶段 1–4 的软件已先行落地）。运行手册（含逐步操作、期望输出、GO/REVISE 门槛与排障）见 `docs/bluetooth-phase0-spike.md`；结果回填模板见 `docs/bluetooth-phase0-report-template.md`。Windows 监听端 spike 在 `scripts/spike-bt1-windows/`（包装脚本 `scripts/spike-bt1-windows.ps1`，独立于 `ClipSync.sln`，引用 `ClipSync.Core` 的真实 bt1 实现与 `RfcommContract` 冻结 UUID）；Android 客户端 spike 是 debug 构建独有的「ClipSync BT Spike」入口（`android/app/src/debug/java/com/clipsync/android/spike/`，release APK 不含蓝牙权限与 spike 代码，logcat 标签 `ClipSyncSpike`）。双端 spike 项目在无实体机的 CI/Linux 主机上可编译（Windows 侧经 `EnableWindowsTargeting`）。

任务：

- Windows：验证未打包 WPF（`net8.0-windows10.0.19041.0` TFM）能否使用 `RfcommServiceProvider` 发布 SDP 并接受连接；确认 TFM 升级隔离在新程序集内不影响 `ClipSync.Core`/`ClipSync.Peer` 现有单测；记录至少两种适配器（Intel/Realtek 常见型号）的行为差异。
- Android：验证 `BLUETOOTH_CONNECT` 授权流、bonded 设备枚举、`createRfcommSocketToServiceRecord` 连通 Windows 端，FGS 存续下 Doze 中 socket 的存活情况。
- 双端互连测得真实吞吐与建连延迟（作为 ADR 0005 限制表的实证）。

验收：spike 报告落入 `docs/`（含吞吐数据与适配器矩阵）；任一平台不可行则回到 ADR 修订，不进入阶段 1。

主要风险：Windows 桌面（非 MSIX）对 WinRT 蓝牙 API 的访问在个别系统版本上受限；OEM Android 蓝牙栈对 RFCOMM secure socket 的差异。

### 阶段 0 实测结果摘要（2026-08-25，详见 `docs/bluetooth-phase0-report.md`）

- 设备对：Lenovo 21STA001CD（Windows 25H2 build 26200，Realtek USB 蓝牙适配器，驱动 18.4028.0.3005）× Redmi Note 11T Pro（Android 13 / SDK 33，MIUI 版 `TP1A.220624.014`）。
- bt1 模式 256 KiB 档**连续 3 轮全通**（G-S1）：`connect_ms` 667 / 1665 / 2152（均 ≤ 5000，G-P1）；`bt1_handshake_ms` 103–110；`rtt_ms_median` 30.5–31.4 ms（G-P2 上限 500）；上行 153.9–176.7 KiB/s、下行 154.9–175.7 KiB/s（G-P3 下限 50）。
- Windows 核心答案（G-W1）：未打包 .NET 8 进程 `winrt_rfcomm_provider=ok` 且 `sdp_published=true`——**不必为监听角色改成 MSIX**。
- Android 核心答案（G-A1）：`BLUETOOTH_CONNECT` 授权流、bonded 枚举、`connect=ok` 全部成立。
- 实操发现：小米上 Windows「添加设备」常扫不到手机，正确路径是从手机系统蓝牙「可用设备」列表点击 PC 发起 bonding（已回填 `docs/install.md` 第 7 节与运行手册排障表）；`connect_ms` 三轮 0.7→2.2 s 递增波动，阶段 2/3 的重连与 30 秒拨号看门狗保持必要，不得假设亚秒建连。
- **已知缺口（转入阶段 3/5）**：1 MiB 档、锁屏 ≥10 分钟连接存活（Doze 观察项，FGS 下的正式结论属阶段 3/5）、第二连接拒收演示、第二种适配器（仅测 Realtek，未测 Intel）与第二个 OEM 均未测；raw 模式未跑（bt1 三轮直通，无需拆链路排障）。spike 用公开测试密钥，与真实 pair secret 无关。

## 阶段 1：bt1 握手与帧层（纯逻辑，无平台依赖）

状态：**已完成**。协议定稿于 `docs/protocol-bt1.md`；共享向量与消息 fixtures 在 `protocol/bt1/`（由 `scripts/generate-bt1-vectors.py` 生成，`scripts/validate-protocol.py` 校验：3 组握手向量、7 组帧向量、6 正例 + 13 负例消息）；C# 实现在 `windows/ClipSync.Core/Security/Bt1/`（无 WinRT 依赖），Kotlin 实现在 `android/.../sync/Bt1*.kt`；双端共 64 个单测（C# 30 + Kotlin 34）针对同一 fixtures 全绿，含篡改/重放/乱序/截断/超限负例。无任何真实蓝牙 I/O——这正是本阶段的验收边界。

任务：

- 写 `docs/protocol-bt1.md`：握手消息、HMAC 域分隔（`ClipSync/bt1/auth\n`）、HKDF 密钥派生、AES-256-GCM 帧格式（4 字节大端长度 + 密文）、计数器 nonce、错误码、7 MiB 帧上限。
- `protocol/bt1/fixtures/`：跨语言测试向量（给定 pair_secret/nonce/UUID/trust_epoch 的期望证明与密文），`scripts/validate-protocol.py` 扩展校验。
- C# 实现进 `ClipSync.Core`（或 `ClipSync.Peer` 的无 WinRT 依赖部分），Kotlin 实现进 Android `sync` 包；两端全部用共享向量做单测，另加篡改/重放/乱序/超限的负例测试。

验收：两端针对同一 fixtures 全绿；无任何真实蓝牙依赖即可测试。

## 阶段 2：Windows RFCOMM 服务端

状态：**软件完成（实体机验证待做）**。`ClipSync.Peer.Bluetooth` 双 TFM（可移植 `net8.0` 承载全部可测逻辑 + `net8.0-windows10.0.19041.0` 仅承载 WinRT 的 `RfcommServer`）：`Bt1StreamFrames`（异步帧 I/O）、`Bt1ListenerHandshake`（监听侧握手，未知设备/撤销/epoch 不符/证明错误/限速全路径）、`Bt1SyncTransport`（`ISyncTransport` 适配器）、`BluetoothSyncHost`（单会话接受循环 + `AuthThrottle` + 每地址接受限速 + 无线电故障重启，内部会话强制协议 v1）。App 层：`蓝牙备援` 设置开关（默认关）、通路页网络段状态行（未启用/待命/同步中/适配器不可用）、暂停/私密门与 IP 路径共用。`ClipSync.Tests` 用内存双工流跑到真实 `SyncSessionEngine` 的端到端双向同步（无 WinRT、无硬件）。`RfcommServer` 本体只能在实体机上验证——阶段 0 spike 已在真机证实其依赖的未打包 WinRT `RfcommServiceProvider` 路径可行（G-W1），但产品内 `RfcommServer`/`BluetoothSyncHost` 的实机端到端仍属阶段 5。

任务：

- 新程序集 `ClipSync.Peer.Bluetooth`：`RfcommServiceProvider` 发布 ClipSync Service UUID、单连接接受、bt1 握手、`ISyncTransport` 适配器把解密后的 JSON 文本交给现有 `SyncSessionEngine`。
- 认证前限速（复用 `AuthThrottle` 模式）；无线电关闭/休眠恢复后重新发布 SDP（挂 `Resilience/` 既有钩子）。
- 设置开关（默认关）+ 托盘状态"蓝牙备援：监听中/已连接/适配器不可用"。

验收：单测用内存流伪装 socket 覆盖握手与帧层；实体机上 Android spike 客户端可完成 v1 全流程文本同步。

## 阶段 3：Android RFCOMM 客户端

状态：**软件完成（实体机验证待做）**。阶段 0 spike 已在真机证实授权流、bonded 枚举与 `createRfcommSocketToServiceRecord` 连通 Windows 端成立（G-A1，Redmi Note 11T Pro / Android 13），但产品内 `BluetoothSyncConnector` 路径的实机端到端仍属阶段 5。`Bt1StreamFraming`（阻塞帧 I/O）、`Bt1ClientHandshake`（拨号侧握手）、`Bt1SyncTransport`（`SyncTransport` 适配器）、`BluetoothSyncConnector`（RFCOMM 拨号：每次拨号复查开关/选定设备/运行时权限/适配器状态，30 秒看门狗经关 socket 中止阻塞 I/O）。Manifest 权限（`BLUETOOTH` maxSdk 30 + `BLUETOOTH_CONNECT`）、偏好页开关 + bonded 设备选择器、授权引导与拒绝降级均已落地。单测经内存管道覆盖握手正反例与帧层攻击负例。

任务：

- `BluetoothSyncConnector`：从 bonded 列表按用户选定的设备建 RFCOMM 连接，bt1 握手后包装成 `SyncTransport`。
- Manifest：`BLUETOOTH`（`maxSdkVersion=30`）+ `BLUETOOTH_CONNECT`；授权引导 UI 与撤销路径（遵守"权限存在不等于 READY"的能力承诺规则）。
- 设置页：蓝牙备援开关（默认关）+ bonded 设备选择器。

验收：单测覆盖握手/帧层与权限缺失降级；实体机与 Windows 端完成双向文本同步。

## 阶段 4：降级编排与回切

状态：**软件完成**。`SyncSupervisor`：IP 候选全部失败后追加一次蓝牙拨号（证书 pin 不符除外——安全失败绝不降级）；蓝牙会话内周期探测 IP 并响应 `nudgeReconnect`，IP 握手成功后优雅关闭蓝牙回切；`SyncConnectionState.Connected` 带 `SyncTransportKind` 传输标记，通知与通路页显示「蓝牙备援」。Windows 侧 HTTPS 监听与 RFCOMM 监听并存（蓝牙侧单会话）。`SyncSupervisorTest` 覆盖：IP 失败→蓝牙成功、pin 不符不降级、蓝牙失败同退避、IP 会话不拨蓝牙、蓝牙中 IP 恢复→回切、nudge 立即探测。

任务：

- `SyncSupervisor`：IP 候选全部失败后追加一次蓝牙拨号；蓝牙会话中保持 IP 探测（`nudgeReconnect` + 定时兜底），IP 认证成功后优雅关闭蓝牙会话回切；退避计数与既有语义合并（互认成功即重置）。
- Windows 侧允许 HTTPS 监听与 RFCOMM 监听并存，但活动会话互斥。
- `SyncConnectionState.Connected` 增加传输标记；通知/托盘文案"已连接（蓝牙备援，仅文本）"；蓝牙会话内图片事件在历史标注"仅本机保留"。

验收：`SyncSupervisorTest` 式的脚本化传输单测覆盖：IP 失败→蓝牙成功、蓝牙中 IP 恢复→回切、蓝牙认证失败→退避、撤销后蓝牙握手必败。

## 阶段 5：验证与发布门槛

任务：

- 实体机矩阵（沿用 `docs/device-validation-matrix.md` 模式）：断 LAN 拉代理、AP 隔离、距离/隔墙、Doze 过夜、无线电开关反复横跳。
- `docs/threat-model.md` 增补蓝牙行：近场攻击者、Just Works MITM、MAC 伪造、认证前 DoS、蓝牙栈 CVE 面，映射到 bt1 双向认证 + AEAD + 限速 + 不要求可发现模式。
- `docs/manual-qa-checklist.md` 增补；更新 CHANGELOG 与安装文档权限说明。

验收：矩阵证据齐备后功能开关才可默认可见（仍默认关闭）；任何一项失败回退到对应阶段。

## 明确不在本计划内

BLE GATT（含在线提示信标）、蓝牙首次配对、图片正文过蓝牙、图片延迟补传（需协议修订）、L2CAP CoC、多 peer、空闲自动断开策略（列为观察项）。

## 复杂度评估（按改动面，不按日历）

- **低风险、机械性**：阶段 1（纯逻辑 + 共享向量，模式与现有协议 fixtures 完全一致）、阶段 4 的状态机改动（`SyncSupervisor` 已为可脚本化测试而设计）。
- **中风险**：阶段 2 的 TFM/程序集切分（一次性构建工程问题）、阶段 3 的权限与 OEM 差异。
- **高不确定性、决定成败**：阶段 0 的两项平台验证（Windows 非打包应用的 WinRT 蓝牙访问、OEM RFCOMM 稳定性）——因此放在最前，失败即止损回 ADR。**2026-08-25 已解除**：两项验证均在真机通过（见阶段 0 结果摘要），剩余不确定性收敛为适配器/OEM 矩阵宽度（阶段 5）。
