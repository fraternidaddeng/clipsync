# 蓝牙备援传输实施计划

状态：设计定稿（对应 ADR 0005）。阶段 1 已完成（纯逻辑，见下）；阶段 0 与阶段 2–5 未开始。阶段 1 无平台依赖、不受阶段 0 结论影响，故先行落地；进入阶段 2/3 前仍须补做阶段 0 的实体机验证。
适用分支：`main`。所有阶段遵守仓库既有验收规则：无实体机证据不得标 `READY`；`TreatWarningsAsErrors`、detekt/ktlint 基线、协议 fixtures 由 `scripts/validate-protocol.py` 校验的约束照常适用。

## 总体形态

蓝牙路径只替换"TLS+pin 包住 WebSocket"这一层，协议 v1 会话原样运行在 bt1 安全信道内。改动集中在四处，均沿既有接缝：

1. 共享密码学/帧层（新，跨语言，两端各一份实现 + 共享测试向量）。
2. Windows：新程序集 `ClipSync.Peer.Bluetooth`（RFCOMM 服务端 + `ISyncTransport` 适配器）。
3. Android：`BluetoothSyncConnector`（RFCOMM 客户端 + `SyncTransport` 适配器）。
4. 编排：`SyncSupervisor`（Android）与 Windows 监听侧的降级/回切逻辑 + 双端设置与状态 UI。

会话引擎（`SyncSessionEngine` / `SyncEngine`）、协议 schema、数据库、配对流程零改动。

## 阶段 0：可行性验证（spike，产出证据，不产出功能）

任务：

- Windows：验证未打包 WPF（`net8.0-windows10.0.19041.0` TFM）能否使用 `RfcommServiceProvider` 发布 SDP 并接受连接；确认 TFM 升级隔离在新程序集内不影响 `ClipSync.Core`/`ClipSync.Peer` 现有单测；记录至少两种适配器（Intel/Realtek 常见型号）的行为差异。
- Android：验证 `BLUETOOTH_CONNECT` 授权流、bonded 设备枚举、`createRfcommSocketToServiceRecord` 连通 Windows 端，FGS 存续下 Doze 中 socket 的存活情况。
- 双端互连测得真实吞吐与建连延迟（作为 ADR 0005 限制表的实证）。

验收：spike 报告落入 `docs/`（含吞吐数据与适配器矩阵）；任一平台不可行则回到 ADR 修订，不进入阶段 1。

主要风险：Windows 桌面（非 MSIX）对 WinRT 蓝牙 API 的访问在个别系统版本上受限；OEM Android 蓝牙栈对 RFCOMM secure socket 的差异。

## 阶段 1：bt1 握手与帧层（纯逻辑，无平台依赖）

状态：**已完成**。协议定稿于 `docs/protocol-bt1.md`；共享向量与消息 fixtures 在 `protocol/bt1/`（由 `scripts/generate-bt1-vectors.py` 生成，`scripts/validate-protocol.py` 校验：3 组握手向量、7 组帧向量、6 正例 + 13 负例消息）；C# 实现在 `windows/ClipSync.Core/Security/Bt1/`（无 WinRT 依赖），Kotlin 实现在 `android/.../sync/Bt1*.kt`；双端共 64 个单测（C# 30 + Kotlin 34）针对同一 fixtures 全绿，含篡改/重放/乱序/截断/超限负例。无任何真实蓝牙 I/O——这正是本阶段的验收边界。

任务：

- 写 `docs/protocol-bt1.md`：握手消息、HMAC 域分隔（`ClipSync/bt1/auth\n`）、HKDF 密钥派生、AES-256-GCM 帧格式（4 字节大端长度 + 密文）、计数器 nonce、错误码、7 MiB 帧上限。
- `protocol/bt1/fixtures/`：跨语言测试向量（给定 pair_secret/nonce/UUID/trust_epoch 的期望证明与密文），`scripts/validate-protocol.py` 扩展校验。
- C# 实现进 `ClipSync.Core`（或 `ClipSync.Peer` 的无 WinRT 依赖部分），Kotlin 实现进 Android `sync` 包；两端全部用共享向量做单测，另加篡改/重放/乱序/超限的负例测试。

验收：两端针对同一 fixtures 全绿；无任何真实蓝牙依赖即可测试。

## 阶段 2：Windows RFCOMM 服务端

任务：

- 新程序集 `ClipSync.Peer.Bluetooth`：`RfcommServiceProvider` 发布 ClipSync Service UUID、单连接接受、bt1 握手、`ISyncTransport` 适配器把解密后的 JSON 文本交给现有 `SyncSessionEngine`。
- 认证前限速（复用 `AuthThrottle` 模式）；无线电关闭/休眠恢复后重新发布 SDP（挂 `Resilience/` 既有钩子）。
- 设置开关（默认关）+ 托盘状态"蓝牙备援：监听中/已连接/适配器不可用"。

验收：单测用内存流伪装 socket 覆盖握手与帧层；实体机上 Android spike 客户端可完成 v1 全流程文本同步。

## 阶段 3：Android RFCOMM 客户端

任务：

- `BluetoothSyncConnector`：从 bonded 列表按用户选定的设备建 RFCOMM 连接，bt1 握手后包装成 `SyncTransport`。
- Manifest：`BLUETOOTH`（`maxSdkVersion=30`）+ `BLUETOOTH_CONNECT`；授权引导 UI 与撤销路径（遵守"权限存在不等于 READY"的能力承诺规则）。
- 设置页：蓝牙备援开关（默认关）+ bonded 设备选择器。

验收：单测覆盖握手/帧层与权限缺失降级；实体机与 Windows 端完成双向文本同步。

## 阶段 4：降级编排与回切

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
- **高不确定性、决定成败**：阶段 0 的两项平台验证（Windows 非打包应用的 WinRT 蓝牙访问、OEM RFCOMM 稳定性）——因此放在最前，失败即止损回 ADR。
