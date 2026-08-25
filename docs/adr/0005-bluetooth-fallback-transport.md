# ADR 0005：Bluetooth RFCOMM 作为 IP 不可达时的备援传输

- 状态：提议（设计已定稿，未实现）
- 日期：2026-08-25
- 关联：修订 ADR 0001「被否决的方案」中的 Bluetooth 条目——当时否决的是"以蓝牙作为目标网络传输"；本 ADR 接受的是"蓝牙仅作为 IP/P2P 不可达时的可选备援"。ADR 0001 的直连 P2P 模型、信任模型和事件模型不变。

## 背景

ADR 0001 假定 LAN、VPN 或端口转发提供可达性。实际使用中存在 IP 路径整体失效的场景：系统代理或 VPN 全局接管后打断局域网直连、酒店/公司网络启用 AP 隔离、路由器故障但两台设备物理相邻。这些场景下两台设备近在咫尺却无法同步，用户体验断崖式下降。

调研结论（2026-08-25，`main` 与 `origin/feature/stage-4` 全量检索）：仓库内没有任何 Bluetooth/BLE/RFCOMM/GATT/L2CAP 实现代码，只有三处文档提及（ADR 0001 的否决条目、`plan.md` 的"类似蓝牙认证"类比、模拟器测试报告中关闭蓝牙栈的排障命令）。本设计从零开始，无历史包袱。

两端会话引擎已经与传输解耦：Android 侧是 `SyncTransport` / `SyncConnector` / `SyncSupervisor`，Windows 侧是 `ISyncTransport` / `SyncSessionEngine`。二者只消费"文本帧"抽象，这是插入新传输的天然接缝。

## 决策

### 1. 选型：Bluetooth Classic RFCOMM，不是 BLE GATT

- 传输走 **Bluetooth Classic（BR/EDR）RFCOMM 流式 socket**，使用 ClipSync 专属 Service UUID 发布/连接。
- **Windows 作为 RFCOMM 服务端**（WinRT `RfcommServiceProvider` 发布 SDP 记录 + `StreamSocketListener`），**Android 作为客户端**（`BluetoothDevice.createRfcommSocketToServiceRecord`）。保持 ADR 0001 的角色分工：Windows 监听、Android 主动拨号，规避 Android 后台监听的生命周期限制。
- 不用 BLE GATT 承载正文：ATT MTU 仅 23–517 字节，实测吞吐常在 10–50 KB/s 量级，1 MiB 文本上限不可接受，且需要自建分块/重组状态机；Windows 桌面适配器对 BLE peripheral 角色支持参差。BLE 未来只考虑用作"在线提示/唤醒"信标，不进入本版本。
- 不用 BLE L2CAP CoC：Windows 无公开 API（Android API 29+ 才有），单侧可用即整体不可用。

### 2. 平台范围

- 仅 Windows 10 22H2+（含蓝牙适配器）与 Android 10（API 29）+，与产品范围一致。
- Windows 需要把承载 WinRT 蓝牙 API 的程序集目标框架升到 `net8.0-windows10.0.19041.0`（CsWinRT 投影）；蓝牙代码隔离在新程序集 `ClipSync.Peer.Bluetooth`，避免污染 `ClipSync.Core` / `ClipSync.Peer` 的可测试性。
- Android 需要 `BLUETOOTH_CONNECT` 运行时权限（API 31+；API 29–30 用安装时 `BLUETOOTH` 权限，`maxSdkVersion=30`）。**不申请 `BLUETOOTH_SCAN` 和定位权限**：MVP 不做蓝牙发现，只从系统已配对（bonded）设备列表中选取。现有 `connectedDevice` 前台服务类型已覆盖蓝牙 socket 的后台使用。

### 3. 安全：复用 pair secret，不新增信任根

- **前置条件**：两台设备先在系统设置完成一次 OS 级蓝牙配对（bonding）。但 OS 配对（Just Works 无 MITM 保护）只当作"链路存在"，**不作为 ClipSync 的信任依据**——与"局域网不可信"的威胁模型同级。
- **不建立独立的 ClipSync 蓝牙配对流程、不生成第二份 secret**。首次配对仍然只能走现有 QR/IP 流程；蓝牙备援要求双方已持有 pair secret。两个信任根会让撤销语义分叉，被明确否决。
- RFCOMM 上没有 TLS + 证书指纹，改由应用层安全信道 **`bt1`** 替代 TLS 的角色：
  - 双向挑战响应：双方交换 32 字节随机 nonce、设备 UUID 和 `trust_epoch`，各自发送 `HMAC-SHA-256(pair_secret, "ClipSync/bt1/auth\n" || role || nonce_c || nonce_l || UUID_BYTES(both) || INT64_BE(trust_epoch))`，互验通过前不发送任何业务字节。域分隔前缀保证 bt1 证明与 v1/v2 证明不可跨传输重放。
  - 会话密钥：`HKDF-SHA-256(ikm=pair_secret, salt=nonce_c||nonce_l, info="ClipSync/bt1/keys")` 派生每方向独立的 AES-256-GCM 密钥；帧格式为 4 字节大端长度 + AEAD 密文，nonce 为每方向严格递增计数器，防重放、防乱序。
  - 撤销沿用 `trust_epoch`：撤销后 secret 删除、握手必然失败，与 IP 路径一致。
- bt1 信道建立后，**未经修改的协议 v1 会话（hello → challenge → auth → known_vector → …）原样运行在信道内部**，与今天"TLS+pin 包住 WebSocket 会话"完全同构。会话引擎、事件模型、幂等键、游标语义零改动。
- Windows 蓝牙监听端复用 `AuthThrottle` 式限速：认证前只接受握手帧，失败计数触发锁定；MAC 地址仅作路由提示，不参与信任判断（MAC 可伪造）。

### 4. 协议内容：MVP 仅文本（v1 能力集）

- 蓝牙会话的 hello **不声明 `image_clip_v2`**。按 protocol v2 §7 的混连规则，图片事件在蓝牙会话中以 `unavailable` + `local_only` 终止标记推进游标。
- 直接后果（必须向用户明示）：**在"仅蓝牙可用"窗口内复制的图片不会同步，且 IP 恢复后也不会补传**（终止标记是 origin 权威的、不可逆的）。备选方案"扣住图片事件不应答"会阻塞连续游标、连带卡死后续文本，危害更大，故不采用。图片的"延迟补传"需要新增非终止 reason 与重新宣告机制，属协议修订，列为后续版本。
- 帧上限与 WebSocket 文本帧一致（7 MiB 明文），1 MiB 文本上限、JSON 深度、禁止正文入日志等 v1 约束全部继承。

### 5. 编排与 UX：自动降级、自动回切

- 降级顺序（每个重连周期内）：**先按现有偏好顺序拨号全部 IP 候选地址 → 全部失败且蓝牙备援已启用且已选定 bonded 设备 → 尝试一次蓝牙拨号 → 进入既有指数退避**。任一路径完成双向认证即重置退避。
- IP 永远优先：蓝牙会话存续期间保持低频 IP 探测（网络可用信号触发 + 定时兜底），IP 会话认证成功后优雅关闭蓝牙会话切回。两条路径共享同一事件模型，切换就是"关一个会话、开一个会话"，游标/ack 幂等保证不丢不重。
- 同一时刻至多一条活动会话，禁止双路并行收发。
- **默认关闭，双端各自显式开启**：Windows 端开关控制 RFCOMM 服务端起停；Android 端开关 + 权限授予 + 从 bonded 列表选定目标设备，三者齐备才参与降级。
- 状态可见：`SyncConnectionState.Connected` 增加传输标记，Android 通知与 Windows 托盘显示"已连接（蓝牙备援，仅文本）"；蓝牙会话期间产生的图片事件在历史中标注"仅本机保留"。

### 6. 限制（作为验收口径写死）

| 项 | 值/说明 |
|---|---|
| 实际吞吐 | BR/EDR RFCOMM 约 50–200 KB/s（视适配器/干扰）；典型剪贴板文本（<4 KiB）应在 1–2 s 内送达；1 MiB 上限文本最坏数十秒 |
| 链路 MTU | RFCOMM 帧由 L2CAP MTU 决定（常见 ~1000 字节，栈自动分段），应用层不感知；应用帧上限 7 MiB 明文 |
| 有效距离 | 约 10 m 级，隔墙衰减明显 |
| 并发 | 单蓝牙会话；服务端拒绝第二连接 |
| Android 后台 | 依赖既有前台服务；Doze 下已建立的 socket 可存活，重连由 FGS 驱动；不承诺系统蓝牙关闭时的任何行为 |
| Windows | 适配器缺失/无线电关闭时开关置灰并提示；休眠恢复后重新发布 SDP（挂接既有 Resilience 钩子） |
| 功耗 | 空闲蓝牙连接维持成本低于 Wi-Fi 扫描，但非零；MVP 不做空闲断开，列为观察项 |

## 后果

优点：物理相邻场景下代理/AP 隔离不再中断同步；不新增信任根与撤销路径；会话引擎与协议 fixtures 零改动；权限面最小化（无扫描、无定位）。

代价：Windows 需引入 WinRT 投影目标框架并新增程序集；需要维护 bt1 握手/帧层的跨语言测试向量；蓝牙窗口内的图片是明确的功能空洞；实体机验证矩阵扩大（适配器差异、OEM 蓝牙栈差异）。按仓库规则，没有实体机证据不得标 `READY`。

## 被否决的方案

- **BLE GATT 承载正文**：吞吐与 MTU 不满足 1 MiB 文本，需自建分块状态机，Windows peripheral 角色支持不稳。仅保留为未来"在线提示"候选。
- **BLE L2CAP CoC**：Windows 无公开 API。
- **仅依赖 OS 蓝牙配对的链路加密**：Just Works 无 MITM 保护，且不绑定 ClipSync 设备身份与 trust_epoch。
- **在 RFCOMM 流上跑 TLS**：Android 侧需手工泵送 `SSLEngine`，证书管道复杂；双方已共享高熵 secret，对称握手更简单且威胁模型等价。
- **独立的 ClipSync 蓝牙配对/第二 secret**：双信任根导致撤销语义分叉。
- **蓝牙承载图片正文**：16 MiB 上限在 100 KB/s 量级下需要数分钟且极易中断，MVP 明确排除。
- **蓝牙作为主传输或常开并行传输**：与 ADR 0001 冲突，功耗与复杂度不可辩护。
- **蓝牙发现/扫描配对**：需要 `BLUETOOTH_SCAN`（隐含邻近感知）权限，扩大隐私面；bonded 列表足够。
