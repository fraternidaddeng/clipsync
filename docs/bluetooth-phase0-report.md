# 蓝牙备援阶段 0 spike 报告

> 由 `docs/bluetooth-phase0-report-template.md` 复制后填写。
> 运行步骤见 `docs/bluetooth-phase0-spike.md`。数字从 `SPIKE_RESULT:` 行原样抄录。
> **本报告不构成任何 READY 声明。** 阶段 0 只回答「阶段 2 值不值得开工」。

- 报告日期：2026-08-25
- 执行人：本机 agent（仓库 `D:\paste`，分支 `main`）
- 使用的 commit：`git rev-parse HEAD` → `c2a535906314d96431e3abc21fdc47e6d193edfa`（含 `1fc3ce6` 及之后）

## 1. 环境矩阵

### Windows 机器

| 编号 | 机型 | Windows build（winver） | 蓝牙适配器型号 | 驱动版本 | 备注 |
|---|---|---|---|---|---|
| W-1 | Lenovo 21STA001CD（计算机名 / 蓝牙名 DENG） | 10.0.26200.9168（DisplayVersion 25H2；注册表 ProductName 仍写 Windows 10 Home China） | Realtek Bluetooth Adapter（USB `VID_0BDA&PID_4853`）；Classic/RFCOMM TDI 在线 | 18.4028.0.3005 | radio=On；Classic supported=true；地址 `1C:70:C9:D1:86:C9`。本轮前安装了 .NET SDK 8.0.424。 |
| W-2（可选） | 未测 | 未测 | 未测 | 未测 | 仅一对设备 |

### Android 设备

| 编号 | OEM / 机型 | Android 版本（SDK） | OEM build（`oem_build`） | 备注 |
|---|---|---|---|---|
| A-1 | Xiaomi / Redmi 22041216C（Redmi Note 11T Pro，device=xaga） | 13（SDK 33） | `TP1A.220624.014` | 真机 USB adb；蓝牙名「邓的Redmi Note 11T Pro」；地址 `F8:AB:82:99:B1:3A`。debug APK + `BLUETOOTH_CONNECT` 已授予。OS bonding 于 12:42:23 完成：`1C:70:C9:D1:86:C9 [ DUAL ] DENG`。 |
| A-2（可选） | 未测 | 未测 | 未测 | 仅一对设备 |

## 2. 逐轮测量

权威计时以 **Android** 为准。吞吐档位 256 KiB。

| 轮次 | 设备对 | 模式 | 档位 | connect_ms | bt1_handshake_ms | rtt_ms_median (min/max) | up_kib_per_s | down_kib_per_s | 结果 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | W-1 × A-1 | bt1 | 256 KiB | 667 | 109 | 31.4 (9.1/56.9) | 166.3 | 154.9 | 通过（`session=completed`，`bye_ack=ok`） |
| 2 | W-1 × A-1 | bt1 | 256 KiB | 1665 | 110 | 31.2 (12.6/67.7) | 176.7 | 160.6 | 通过 |
| 3 | W-1 × A-1 | bt1 | 256 KiB | 2152 | 103 | 30.5 (18.4/59.1) | 153.9 | 175.7 | 通过 |
| 4（可选） | W-1 × A-1 | bt1 | 1 MiB | 未测 | 未测 | 未测 | 未测 | 未测 | 未测 |
| 5（可选） | W-1 × A-1 | raw | 256 KiB | 未测 | 未测 | 未测 | 未测 | 未测 | 未测（bt1 三轮已通，未拆 raw） |

Windows 监听端旁证（`*_listener_side`）：

| 轮次 | handshake_ms | up_kib_per_s_listener_side | down_kib_per_s_listener_side |
|---|---|---|---|
| 1 | 99 | 169.9 | 245.2 |
| 2 | 98 | 182.3 | 233.8 |
| 3 | 92 | 156.9 | 227.8 |

双端 `secret_fingerprint=4773d12e`（三轮一致）。

### 可选观察

| 项 | 结果 |
|---|---|
| 锁屏静置 ≥10 分钟后「再测 RTT」（连接是否存活、RTT 变化） | 未测 |
| 第二个连接被拒（`extra_connection_rejected`） | 未测 |
| 其他异常/现象 | 配对前 Windows「添加设备」经常扫不到这台红米；要从手机「可用设备」点 DENG。小米会把电脑折进「可用设备」，不在 spike 已配对列表里（配对前）。`connect_ms` 三轮上升 667→1665→2152，仍远低于 5s。 |

## 3. 门槛判定（判据见运行手册 §7）

| 门槛 | 判据摘要 | PASS / FAIL / 未测 | 证据（引用轮次或日志行） |
|---|---|---|---|
| G-W1 | 未打包进程 `winrt_rfcomm_provider=ok` + `sdp_published=true` | PASS | 三轮 Windows 日志均 `packaged=false`、`winrt_rfcomm_provider=ok`、`sdp_published=true` |
| G-A1 | 授权流 + bonded 枚举 + `connect=ok` | PASS | `BLUETOOTH_CONNECT` granted；`bonded_device_count=20` 含 DENG；三轮 `connect=ok` |
| G-C1 | bt1 握手与全部测试端到端通过 | PASS | 三轮 `bt1_handshake=ok` + RTT/上/下行 + `session=completed` |
| G-P1 | connect_ms 典型 ≤ 5000 | PASS | 667 / 1665 / 2152，均 ≤ 5000（远低于 15s REVISE 线） |
| G-P2 | rtt_ms_median ≤ 500 | PASS | 31.4 / 31.2 / 30.5 |
| G-P3 | 上/下行均 ≥ 50 KiB/s（256 KiB 档） | PASS | 最低上行 153.9、最低下行 154.9 |
| G-S1 | 同一设备对连续 3 轮通过 | PASS | W-1 × A-1，bt1 256 KiB，连续 3 轮无需重启蓝牙/设备 |

## 4. 失败与阻塞记录

| # | 设备对 | 现象（原始日志行） | 复现步骤 | 可复现？ |
|---|---|---|---|---|
| 1 | W-1 × A-1 | 配对阶段（测量前）：多次 `BOND_STATE_BONDING` → `BOND_STATE_NONE`；Windows「未知设备 / 连接失败」。无 `SPIKE_RESULT:*_error`（会话未开始）。 | 自动化点 PIN 超时或从 Windows「添加设备」扫手机。 | 是（配对前）。12:42:23 手机点 DENG「配对」后 `BOND_STATE_BONDED`，之后三轮测量无此错误。 |
| 2 | W-1 × A-1 | 测量轮无 `SPIKE_RESULT:*_error` | — | — |

### 已按手册 §8 做过的排障

| §8 症状 | 本轮动作与结果 |
|---|---|
| 列表里没有 PC | 不要在 spike 里找（只列已配对）。在系统设置 → 蓝牙 → **可用设备**（往下滚）点 **DENG**。Windows 侧扫不到红米是常态。 |
| `bt1_handshake=failed` | 未出现。指纹双端 `4773d12e`。 |
| bt1 失败但 raw 能通 | 未出现。bt1 三轮全通，未跑 raw。 |
| 防火墙/VPN | RFCOMM 不走 IP。手机有 Clash VPN，未影响本轮。 |

## 5. 结论与建议

- **建议：GO**
- 理由（对照门槛表）：G-W1、G-A1、G-C1、G-S1 全过；G-P1–P3 在 W-1 × A-1 上达标。本轮**不声明 READY**，只说明阶段 2 RFCOMM 实现值得开工。
- 若 NO-GO：建议的 ADR 0005 修订方向：不适用。
- 若 GO：对阶段 2/3 的具体提醒：
  - 未打包 WinRT `RfcommServiceProvider` 在本机成立，不必为此改成 MSIX 才能监听。
  - 小米：电脑出现在系统「可用设备」，不在 spike 列表，直到 OS bonding 完成。Windows「添加设备」扫不到手机不要当链路不可行。
  - 实测 RTT 中位约 31 ms、256 KiB 吞吐约 150–180 KiB/s，好于 ADR 0005 限制表的保守估值（RTT≤500、吞吐≥50）。
  - `connect_ms` 有 0.7–2.2 s 波动，阶段 2 仍应做重连/超时，不要假设亚秒建连。
  - 未测 1 MiB 档、未测锁屏 10 分钟存活、未测第二台 OEM。阶段 3/5 再补。
  - 本 spike 用公开测试密钥，与真实 ClipSync pair secret 无关。

## 附录 A：Windows spike 完整日志

### 轮次 1（`spike-logs/win-round1.log`）

```text
SPIKE_RESULT:spike=windows-listener
SPIKE_RESULT:mode=bt1
SPIKE_RESULT:service_uuid=5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec
SPIKE_RESULT:os_version=10.0.26200.0
SPIKE_RESULT:is_64bit_os=true
SPIKE_RESULT:packaged=false
SPIKE_RESULT:secret_fingerprint=4773d12e
SPIKE_RESULT:client_device_id=11111111-1111-4111-8111-111111111111
SPIKE_RESULT:listener_device_id=22222222-2222-4222-8222-222222222222
SPIKE_RESULT:trust_epoch=1
SPIKE_RESULT:adapter_present=true
SPIKE_RESULT:adapter_address=1c70c9d186c9
SPIKE_RESULT:adapter_classic_supported=true
SPIKE_RESULT:adapter_name=DENG - 涓?
SPIKE_RESULT:radio_state=On
SPIKE_RESULT:winrt_rfcomm_provider=ok
SPIKE_RESULT:sdp_published=true
SPIKE_RESULT:discoverable=false
[12:43:35.507] Listening for ONE RFCOMM connection (timeout 180s). Start the Android spike now.
SPIKE_RESULT:connection_accepted=true
SPIKE_RESULT:accepted_at_utc=2026-08-25T04:44:17.9147409+00:00
SPIKE_RESULT:remote_host=(F8:AB:82:99:B1:3A)
SPIKE_RESULT:bt1_handshake=ok
SPIKE_RESULT:bt1_handshake_ms=99
[12:44:19.680] Uplink test: expecting 262144 data bytes from the client.
SPIKE_RESULT:up_bytes=262144
SPIKE_RESULT:up_ms_listener_side=1507
SPIKE_RESULT:up_kib_per_s_listener_side=169.9
[12:44:21.222] Downlink test: sending 262144 data bytes to the client.
SPIKE_RESULT:down_bytes=262144
SPIKE_RESULT:down_ms_listener_side=1044
SPIKE_RESULT:down_kib_per_s_listener_side=245.2
SPIKE_RESULT:ping_frames_echoed=50
SPIKE_RESULT:session=completed
SPIKE_RESULT:exit=0
```

### 轮次 2（`spike-logs/win-round2.log`）

```text
SPIKE_RESULT:spike=windows-listener
SPIKE_RESULT:mode=bt1
SPIKE_RESULT:service_uuid=5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec
SPIKE_RESULT:os_version=10.0.26200.0
SPIKE_RESULT:is_64bit_os=true
SPIKE_RESULT:packaged=false
SPIKE_RESULT:secret_fingerprint=4773d12e
SPIKE_RESULT:adapter_present=true
SPIKE_RESULT:adapter_address=1c70c9d186c9
SPIKE_RESULT:adapter_classic_supported=true
SPIKE_RESULT:radio_state=On
SPIKE_RESULT:winrt_rfcomm_provider=ok
SPIKE_RESULT:sdp_published=true
SPIKE_RESULT:discoverable=false
[12:46:51.192] Listening for ONE RFCOMM connection (timeout 120s). Start the Android spike now.
SPIKE_RESULT:connection_accepted=true
SPIKE_RESULT:accepted_at_utc=2026-08-25T04:47:36.5363700+00:00
SPIKE_RESULT:remote_host=(F8:AB:82:99:B1:3A)
SPIKE_RESULT:bt1_handshake=ok
SPIKE_RESULT:bt1_handshake_ms=98
SPIKE_RESULT:up_bytes=262144
SPIKE_RESULT:up_ms_listener_side=1404
SPIKE_RESULT:up_kib_per_s_listener_side=182.3
SPIKE_RESULT:down_bytes=262144
SPIKE_RESULT:down_ms_listener_side=1095
SPIKE_RESULT:down_kib_per_s_listener_side=233.8
SPIKE_RESULT:ping_frames_echoed=50
SPIKE_RESULT:session=completed
SPIKE_RESULT:exit=0
```

### 轮次 3（`spike-logs/win-round3.log`）

```text
SPIKE_RESULT:spike=windows-listener
SPIKE_RESULT:mode=bt1
SPIKE_RESULT:service_uuid=5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec
SPIKE_RESULT:os_version=10.0.26200.0
SPIKE_RESULT:is_64bit_os=true
SPIKE_RESULT:packaged=false
SPIKE_RESULT:secret_fingerprint=4773d12e
SPIKE_RESULT:adapter_present=true
SPIKE_RESULT:adapter_address=1c70c9d186c9
SPIKE_RESULT:adapter_classic_supported=true
SPIKE_RESULT:radio_state=On
SPIKE_RESULT:winrt_rfcomm_provider=ok
SPIKE_RESULT:sdp_published=true
SPIKE_RESULT:discoverable=false
[12:47:59.504] Listening for ONE RFCOMM connection (timeout 120s). Start the Android spike now.
SPIKE_RESULT:connection_accepted=true
SPIKE_RESULT:accepted_at_utc=2026-08-25T04:48:38.5323681+00:00
SPIKE_RESULT:remote_host=(F8:AB:82:99:B1:3A)
SPIKE_RESULT:bt1_handshake=ok
SPIKE_RESULT:bt1_handshake_ms=92
SPIKE_RESULT:up_bytes=262144
SPIKE_RESULT:up_ms_listener_side=1632
SPIKE_RESULT:up_kib_per_s_listener_side=156.9
SPIKE_RESULT:down_bytes=262144
SPIKE_RESULT:down_ms_listener_side=1124
SPIKE_RESULT:down_kib_per_s_listener_side=227.8
SPIKE_RESULT:ping_frames_echoed=50
SPIKE_RESULT:session=completed
SPIKE_RESULT:exit=0
```

## 附录 B：Android spike 完整日志

### 轮次 1

```text
SPIKE_RESULT:spike=android-client
SPIKE_RESULT:phone=Xiaomi 22041216C
SPIKE_RESULT:android_release=13
SPIKE_RESULT:android_sdk=33
SPIKE_RESULT:oem_build=TP1A.220624.014
SPIKE_RESULT:service_uuid=5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec
SPIKE_RESULT:transfer_bytes=262144
SPIKE_RESULT:mode=bt1
SPIKE_RESULT:secret_fingerprint=4773d12e
SPIKE_RESULT:target_name=DENG
SPIKE_RESULT:target_address=1C:70:C9:D1:86:C9
SPIKE_RESULT:target_bond_state=12
SPIKE_RESULT:connect=ok
SPIKE_RESULT:connect_ms=667
SPIKE_RESULT:bt1_handshake=ok
SPIKE_RESULT:bt1_handshake_ms=109
SPIKE_RESULT:rtt_count=50
SPIKE_RESULT:rtt_ms_min=9.1
SPIKE_RESULT:rtt_ms_median=31.4
SPIKE_RESULT:rtt_ms_avg=32.6
SPIKE_RESULT:rtt_ms_max=56.9
SPIKE_RESULT:up_bytes=262144
SPIKE_RESULT:up_ms=1539
SPIKE_RESULT:up_kib_per_s=166.3
SPIKE_RESULT:down_bytes=262144
SPIKE_RESULT:down_ms=1653
SPIKE_RESULT:down_kib_per_s=154.9
SPIKE_RESULT:bye_ack=ok
SPIKE_RESULT:session=completed
```

### 轮次 2

```text
SPIKE_RESULT:spike=android-client
SPIKE_RESULT:phone=Xiaomi 22041216C
SPIKE_RESULT:android_release=13
SPIKE_RESULT:android_sdk=33
SPIKE_RESULT:oem_build=TP1A.220624.014
SPIKE_RESULT:mode=bt1
SPIKE_RESULT:secret_fingerprint=4773d12e
SPIKE_RESULT:target_name=DENG
SPIKE_RESULT:target_address=1C:70:C9:D1:86:C9
SPIKE_RESULT:connect=ok
SPIKE_RESULT:connect_ms=1665
SPIKE_RESULT:bt1_handshake=ok
SPIKE_RESULT:bt1_handshake_ms=110
SPIKE_RESULT:rtt_count=50
SPIKE_RESULT:rtt_ms_min=12.6
SPIKE_RESULT:rtt_ms_median=31.2
SPIKE_RESULT:rtt_ms_avg=34.0
SPIKE_RESULT:rtt_ms_max=67.7
SPIKE_RESULT:up_bytes=262144
SPIKE_RESULT:up_ms=1449
SPIKE_RESULT:up_kib_per_s=176.7
SPIKE_RESULT:down_bytes=262144
SPIKE_RESULT:down_ms=1594
SPIKE_RESULT:down_kib_per_s=160.6
SPIKE_RESULT:bye_ack=ok
SPIKE_RESULT:session=completed
```

### 轮次 3

```text
SPIKE_RESULT:spike=android-client
SPIKE_RESULT:phone=Xiaomi 22041216C
SPIKE_RESULT:android_release=13
SPIKE_RESULT:android_sdk=33
SPIKE_RESULT:oem_build=TP1A.220624.014
SPIKE_RESULT:mode=bt1
SPIKE_RESULT:secret_fingerprint=4773d12e
SPIKE_RESULT:target_name=DENG
SPIKE_RESULT:target_address=1C:70:C9:D1:86:C9
SPIKE_RESULT:connect=ok
SPIKE_RESULT:connect_ms=2152
SPIKE_RESULT:bt1_handshake=ok
SPIKE_RESULT:bt1_handshake_ms=103
SPIKE_RESULT:rtt_count=50
SPIKE_RESULT:rtt_ms_min=18.4
SPIKE_RESULT:rtt_ms_median=30.5
SPIKE_RESULT:rtt_ms_avg=32.8
SPIKE_RESULT:rtt_ms_max=59.1
SPIKE_RESULT:up_bytes=262144
SPIKE_RESULT:up_ms=1663
SPIKE_RESULT:up_kib_per_s=153.9
SPIKE_RESULT:down_bytes=262144
SPIKE_RESULT:down_ms=1457
SPIKE_RESULT:down_kib_per_s=175.7
SPIKE_RESULT:bye_ack=ok
SPIKE_RESULT:session=completed
```
