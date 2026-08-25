# 蓝牙备援阶段 0 spike 运行手册（Windows ↔ Android RFCOMM 可行性验证）

状态：证据收集工具，非产品功能。对应 ADR 0005 与 `docs/bluetooth-fallback-plan.md` 阶段 0。
**本手册与 spike 工具不产生任何 READY 声明**——它们只回答"阶段 2/3 值不值得做"，结果回填到
`docs/bluetooth-phase0-report-template.md` 后由报告说话。任一门槛失败即回 ADR 0005 修订，不进入阶段 2。

spike 要回答的三个问题（`docs/bluetooth-fallback-plan.md` 阶段 0 原文）：

1. **Windows**：未打包（非 MSIX）的 .NET 8 桌面进程能否用 WinRT `RfcommServiceProvider`
   发布 ClipSync 服务 UUID 的 SDP 记录并接受 RFCOMM 连接？
2. **Android**：`BLUETOOTH_CONNECT` 授权流、bonded 设备枚举、
   `createRfcommSocketToServiceRecord` 连通 Windows 端是否成立？
3. **双端**：真实建连延迟、RTT 与持续吞吐是多少（验证 ADR 0005 限制表的估值）？

## 0. 两种"配对"的区别（必读）

| | OS 级蓝牙配对（bonding） | ClipSync 配对 |
|---|---|---|
| 在哪做 | Windows 设置 > 蓝牙 / Android 系统设置 > 蓝牙 | ClipSync 应用内 QR/IP 流程 |
| 产出 | 系统蓝牙栈里的链路密钥（Just Works，无 MITM 保护） | 32 字节 pair secret + 设备身份 + trust_epoch |
| 在 ADR 0005 中的角色 | 只当作"链路存在"的前提，**不是信任依据** | 唯一信任根；bt1 握手用它做双向认证 |

**本 spike 只需要 OS 级 bonding，不需要（也不要用）ClipSync 配对。**
spike 的 bt1 握手用一个双端内置的、公开的测试密钥（可覆盖），与真实 pair secret 无任何关系。

## 1. 前置条件

Windows 侧：

- Windows 10 22H2（build 19045）或更新；`winver` 确认。
- 蓝牙适配器（支持 Bluetooth Classic/BR/EDR；设备管理器 > 蓝牙 记下适配器完整名称与驱动版本，报告要填）。
- .NET SDK 8.x（`dotnet --version`）。仓库根目录 `global.json` 允许 8.0.4xx 任意补丁版。
- 本仓库检出；spike 工具在 `scripts/spike-bt1-windows/`（不属于 `ClipSync.sln`，不会随产品构建/分发）。

Android 侧：

- Android 10（API 29）+ 真机（模拟器没有蓝牙栈，不可用）。
- Debug 版 ClipSync APK（spike 界面只存在于 debug 构建；release APK 不含蓝牙权限与 spike 代码）。
- 电脑上有 `adb`（采集 logcat 用；也可以直接抄 spike 界面里的日志，二者内容相同）。

双端共同：

- **先在系统设置里完成 OS 级蓝牙配对**：Windows 设置 > 蓝牙和其他设备 > 添加设备，与手机完成配对，
  两边都确认对方出现在"已配对"列表里。实测提醒（2026-08-25，小米）：Windows「添加设备」经常
  扫不到手机——改从**手机侧发起**（系统设置 > 蓝牙 > 「可用设备」里点 PC 名），成功率高得多。
- 两台设备物理相邻（1–2 m 内做基准测量；距离/隔墙是阶段 5 的事）。
- 不需要任何网络；防火墙、代理、VPN 与本 spike 完全无关（RFCOMM 不走 IP 栈）。

## 2. 运行顺序

**永远先启动 Windows 监听端，再操作 Android 端**（Windows 是 RFCOMM 服务端，Android 是客户端，
与 ADR 0005 的角色分工一致）。完整一轮：

1. Windows：启动 spike 监听（§3）。
2. Android：装 debug APK，打开 spike 界面，授权，选 PC，运行全部测试（§4）。
3. 双端各自把控制台输出 / logcat 保存下来。
4. 重复第 1–3 步至少 3 轮（稳定性门槛 G-S1）。
5. 有第二台不同适配器的 PC 或第二台不同 OEM 的手机就再来一轮（矩阵越宽越好，非硬性）。
6. 把结果填进 `docs/bluetooth-phase0-report-template.md`。

## 3. Windows spike（RFCOMM 监听端）

在仓库根目录：

```powershell
# 默认：bt1 模式（真实握手 + 加密帧），等待连接 300 秒，输出同时落盘到仓库根目录的 .log 文件
powershell -ExecutionPolicy Bypass -File scripts/spike-bt1-windows.ps1

# 链路排障用：raw 模式（无握手、无加密，只测裸 RFCOMM）
powershell -ExecutionPolicy Bypass -File scripts/spike-bt1-windows.ps1 -Mode raw

# 或者不经包装脚本直接跑：
dotnet run --project scripts/spike-bt1-windows -c Release -- --help
```

工具做的事，按顺序：

1. 打印环境与配置（OS build、适配器名称/地址、radio 状态、spike 密钥指纹——不打印密钥本身）。
2. `RfcommServiceProvider.CreateAsync` 发布服务 UUID `5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec`
   （即 `ClipSync.Peer.Bluetooth.RfcommContract.ServiceUuid`，双端冻结值）的 SDP 记录，
   外加 ServiceName 属性。**这一步成功与否就是阶段 0 的 Windows 核心答案**（门槛 G-W1）。
   默认不开无线电可发现（bonded 设备直连不需要发现，这正是阶段 2 依赖的行为；
   排障时可加 `-Discoverable`）。
3. 只接受**一个** RFCOMM 连接（第二个连接直接拒收并记 `extra_connection_rejected=true`）。
4. bt1 模式下执行 `docs/protocol-bt1.md` §3 的监听端握手——用的是 `ClipSync.Core/Security/Bt1/`
   里阶段 1 的真实实现，不是复制品。
5. 进入 spike 测量协议（§5），响应 Android 端驱动的 echo/吞吐测试，直到收到 `bye`。

期望输出样例（bt1 模式、一切正常）：

```text
SPIKE_RESULT:spike=windows-listener
SPIKE_RESULT:mode=bt1
SPIKE_RESULT:service_uuid=5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec
SPIKE_RESULT:os_version=10.0.19045.0
SPIKE_RESULT:packaged=false
SPIKE_RESULT:secret_fingerprint=93a86f22
SPIKE_RESULT:adapter_present=true
SPIKE_RESULT:adapter_name=Intel(R) Wireless Bluetooth(R)
SPIKE_RESULT:adapter_classic_supported=true
SPIKE_RESULT:radio_state=On
SPIKE_RESULT:winrt_rfcomm_provider=ok
SPIKE_RESULT:sdp_published=true
SPIKE_RESULT:discoverable=false
[02:41:07.123] Listening for ONE RFCOMM connection (timeout 300s). Start the Android spike now.
SPIKE_RESULT:connection_accepted=true
SPIKE_RESULT:remote_host=(AA:BB:CC:DD:EE:FF)
SPIKE_RESULT:bt1_handshake=ok
SPIKE_RESULT:bt1_handshake_ms=142
SPIKE_RESULT:up_bytes=262144
SPIKE_RESULT:up_ms_listener_side=2210
SPIKE_RESULT:up_kib_per_s_listener_side=115.8
SPIKE_RESULT:down_bytes=262144
SPIKE_RESULT:down_ms_listener_side=2453
SPIKE_RESULT:down_kib_per_s_listener_side=104.4
SPIKE_RESULT:ping_frames_echoed=50
SPIKE_RESULT:session=completed
SPIKE_RESULT:exit=0
```

失败时的关键行（原样抄进报告）：

- `winrt_rfcomm_provider=failed` + `winrt_rfcomm_provider_error=...`（含 HRESULT）——G-W1 失败的直接证据。
- `adapter_present=false` / `radio_state=Off`——环境问题，不是结论。
- `bt1_handshake=failed` + `bt1_handshake_error=...`——两端密钥/设备 ID/epoch 不一致，或帧层不通。

## 4. Android spike（RFCOMM 客户端）

构建并安装 debug APK（spike 只进 debug；用 Android Studio 或命令行均可）：

```bash
cd android
./gradlew :app:installDebug     # 或 assembleDebug 后 adb install
```

然后：

1. 手机上会多出一个启动图标 **「ClipSync BT Spike」**（debug 构建独有），打开它。
2. 点「授予 BLUETOOTH_CONNECT 权限」（API 31+ 弹系统对话框；API 29–30 无需运行时授权，直接可用）。
   这本身就是阶段 0 要验证的授权流（门槛 G-A1 的一半）。
3. 已配对设备列表里选中你的 PC（列表就是 OS bonding 列表——没有你的 PC 就先回 §1 完成系统配对）。
4. 确认 Windows 端 spike 正在监听，点 **「连接并运行全部测试」**。
   spike 会依次：RFCOMM 建连（计时）→ bt1 客户端握手（计时）→ RTT×50 → 上行吞吐 → 下行吞吐，
   然后**保持连接打开**。
5. （可选，对应阶段 0 的 Doze 观察项）锁屏放置 10 分钟，回来点 **「再测 RTT」**看连接是否还活着。
   注意：spike 是前台 Activity，不是 `connectedDevice` 前台服务，此观察仅供参考，
   FGS 下的正式 Doze 结论留给阶段 3/5。
6. 点 **「断开」** 优雅结束（发 `bye`），Windows 端随之退出。

日志采集（两种取一即可，内容相同）：

```bash
adb logcat -c && adb logcat -s ClipSyncSpike
```

或直接抄界面日志区。期望输出样例：

```text
SPIKE_RESULT:spike=android-client
SPIKE_RESULT:phone=Xiaomi 2210132C
SPIKE_RESULT:android_release=14
SPIKE_RESULT:android_sdk=34
SPIKE_RESULT:transfer_bytes=262144
SPIKE_RESULT:mode=bt1
SPIKE_RESULT:secret_fingerprint=93a86f22
SPIKE_RESULT:target_name=DESKTOP-ABC123
SPIKE_RESULT:target_address=AA:BB:CC:DD:EE:FF
SPIKE_RESULT:connect=ok
SPIKE_RESULT:connect_ms=487
SPIKE_RESULT:bt1_handshake=ok
SPIKE_RESULT:bt1_handshake_ms=156
SPIKE_RESULT:rtt_count=50
SPIKE_RESULT:rtt_ms_min=18.4
SPIKE_RESULT:rtt_ms_median=24.1
SPIKE_RESULT:rtt_ms_avg=26.3
SPIKE_RESULT:rtt_ms_max=87.9
SPIKE_RESULT:up_bytes=262144
SPIKE_RESULT:up_ms=2244
SPIKE_RESULT:up_kib_per_s=114.1
SPIKE_RESULT:down_bytes=262144
SPIKE_RESULT:down_ms=2471
SPIKE_RESULT:down_kib_per_s=103.6
SPIKE_RESULT:bye_ack=ok
SPIKE_RESULT:session=completed
```

界面选项说明：

- **bt1 安全信道开关**：关掉即 raw 模式（双端都要 raw 才能互通），用于把"链路问题"和"bt1 问题"分开定位。
- **吞吐量档位**：64 KiB / 256 KiB / 1 MiB。基准测 256 KiB；1 MiB 用于验证 ADR 限制表
  "1 MiB 上限文本最坏数十秒"的说法。
- **Spike 密钥**：默认公开测试值，与 Windows 端默认一致，开箱即通。改了就两端一起改
  （对照双方日志里的 `secret_fingerprint` 是否相同）。

## 5. spike 测量协议（两端工具的私有约定）

仅为产出数据而存在，**不是 bt1 或 protocol v1 的一部分**，随 spike 一起消亡。
每条消息在 bt1 模式下是一个加密 bt1 帧、raw 模式下是一个 4 字节大端长度前缀的明文帧：

| 消息 | 方向 | 语义 |
|---|---|---|
| `ping <填充>` | 手机→PC | PC 原样回射整个载荷（RTT 测量，默认 32 字节 ×50 次） |
| `up <n>` | 手机→PC | 随后手机发若干 `data <字节>` 帧共 n 数据字节；PC 收满回 `up-ok <n>`（上行吞吐） |
| `down <n>` | 手机→PC | PC 发若干 `data <字节>` 帧共 n 数据字节（下行吞吐，32 KiB/帧） |
| `bye` | 手机→PC | PC 回 `bye`，双方关闭 |

权威计时点：建连/握手/RTT/上行/下行都以 **Android 端**数字为准（覆盖完整往返）；
Windows 端的 `*_listener_side` 数字仅作旁证。

## 6. 需要记录什么（对照报告模板）

- 环境矩阵：Windows build（`winver`）、蓝牙适配器型号 + 驱动版本、PC 机型；
  Android OEM/机型/版本（spike 自动打印 `phone` / `android_release` / `oem_build`）。
- 每轮的 `connect_ms`、`bt1_handshake_ms`、`rtt_ms_median`（以及 min/max）、
  `up_kib_per_s`、`down_kib_per_s`（至少 256 KiB 档，最好补 1 MiB 档）。
- 所有失败：完整的 `SPIKE_RESULT:*_error` 行、当时的操作步骤、是否可复现。
- 可选观察：锁屏静置后的「再测 RTT」结果；第二个连接被拒（`extra_connection_rejected`）。

## 7. 阶段 2 的 GO / REVISE 门槛

| 门槛 | 判据 | 不满足时 |
|---|---|---|
| G-W1 未打包 WinRT 访问 | `winrt_rfcomm_provider=ok` 且 `sdp_published=true`，非 MSIX 进程 | **REVISE ADR**（考虑 MSIX 打包、Win32 socket API 或放弃 Windows 监听角色）|
| G-A1 Android 授权与建连 | 授权流可完成；bonded 列表可枚举；`connect=ok` | **REVISE ADR**（该 OEM 列入不支持清单或换客户端策略）|
| G-C1 bt1 端到端 | `bt1_handshake=ok` 且 RTT/吞吐测试在 bt1 模式下全部通过 | 先查两端配置；排除配置后仍失败 → 阶段 1 实现或协议缺陷，修复后重测 |
| G-P1 建连延迟 | `connect_ms` 典型值 ≤ 5000 | 记录实际值 → ADR 限制表改写；>15 s 需 REVISE（降级体验不可接受）|
| G-P2 小载荷 RTT | `rtt_ms_median` ≤ 500 | 同上；这是"典型剪贴板文本 1–2 s 内送达"承诺的基础 |
| G-P3 持续吞吐 | 256 KiB 档 `up_kib_per_s` 与 `down_kib_per_s` 均 ≥ 50 | < 50 KiB/s → ADR 限制表与"1 MiB 最坏数十秒"的说法必须改写；< 20 KiB/s 建议 REVISE |
| G-S1 稳定性 | 同一对设备连续 3 轮完整通过，无需重启蓝牙/设备 | 记录失败模式；偶发失败 → 阶段 2 需评估重连策略；必现失败 → REVISE |

**GO 判定：G-W1、G-A1、G-C1、G-S1 全过，且 G-P1–P3 至少在一对真实设备上达标。**
GO 之后阶段 2 才开工；任何 REVISE 结论回 ADR 0005，不写代码。

## 8. 排障

| 症状 | 排查 |
|---|---|
| Windows `adapter_present=false` | 设备管理器确认适配器存在且驱动正常；USB 蓝牙棒换口重插 |
| Windows `radio_state=Off` | 设置 > 蓝牙 打开开关；飞行模式会连带关掉 |
| `winrt_rfcomm_provider=failed` | 这可能就是阶段 0 要找的答案——先在**另一台 Windows 机器**上复测，仍失败则带着 HRESULT 走 REVISE 流程 |
| Android 连接立刻抛 `IOException: read failed / Connection refused` | ① Windows spike 没在监听（先启动它）② 选错了 bonded 设备 ③ SDP 缓存陈旧：两边系统设置里删除配对、重新配对后重试 |
| Android `connect` 卡 10 秒后超时 | PC 蓝牙关了/超距/适配器休眠；Windows 电源管理里禁用"允许计算机关闭此设备以节约电源" |
| 列表里没有 PC | 做的是"连接"不是"配对"——回 §1 在系统设置完成 bonding；部分 OEM 会把长期未连的配对设备折叠，进系统蓝牙设置刷新 |
| Windows「添加设备」扫不到手机 | 小米等 OEM 上是常态（阶段 0 实测）——从手机侧发起：系统设置 > 蓝牙 > 「可用设备」点 PC 名完成配对；bonding 完成后 spike 的已配对列表即可见 |
| `bt1_handshake=failed` | 对照两端日志 `secret_fingerprint` 是否一致；device id / trust epoch 用默认值就不要单边改 |
| bt1 模式失败但 raw 模式通 | 链路没问题，问题在握手配置或帧层——收集两端完整日志回填报告 |
| 吞吐异常低（< 20 KiB/s） | 2.4 GHz Wi-Fi 大流量与蓝牙同天线会互踩：测试时暂停大下载；远离微波炉/无线鼠标接收器；换 USB 3.0 口（辐射干扰）|
| 防火墙/杀软弹窗 | RFCOMM 不走 IP，Windows 防火墙管不到它；若第三方杀软带"蓝牙防护"，测试期间暂时关闭并记录 |
| 第二台手机连不上 | 预期行为：spike 只收一个连接（`extra_connection_rejected=true`），ADR 0005 单会话限制的演示 |

## 9. 结果回填

复制 `docs/bluetooth-phase0-report-template.md` 为
`docs/bluetooth-phase0-report.md`（或直接在模板上填），把双端日志原文贴进附录，
按模板给出每个门槛的 PASS/FAIL 和最终 GO / NO-GO 建议。
报告落库后，阶段 0 才算有产出；spike 工具本身不构成任何完成度声明。
