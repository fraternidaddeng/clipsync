# 蓝牙备援阶段 0 spike 报告（模板）

> 使用方法：复制本文件为 `docs/bluetooth-phase0-report.md` 后填写，或直接在副本上作业。
> 运行步骤见 `docs/bluetooth-phase0-spike.md`；所有数字从 spike 的 `SPIKE_RESULT:` 行原样抄录，
> 不要凭印象填。空着的格子写 `未测`，不要删行。

- 报告日期：
- 执行人：
- 使用的 commit：`git rev-parse HEAD` →

## 1. 环境矩阵

### Windows 机器

| 编号 | 机型 | Windows build（winver） | 蓝牙适配器型号 | 驱动版本 | 备注 |
|---|---|---|---|---|---|
| W-1 | | | | | |
| W-2（可选） | | | | | |

### Android 设备

| 编号 | OEM / 机型 | Android 版本（SDK） | OEM build（`oem_build`） | 备注 |
|---|---|---|---|---|
| A-1 | | | | |
| A-2（可选） | | | | |

## 2. 逐轮测量

每一轮 = Windows 监听启动 → Android 全部测试 → 断开。同一设备对至少 3 轮。
吞吐档位注明 KiB 数（基准 256 KiB；如测了 1 MiB 另起一行）。

| 轮次 | 设备对 | 模式 | 档位 | connect_ms | bt1_handshake_ms | rtt_ms_median (min/max) | up_kib_per_s | down_kib_per_s | 结果 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | W-1 × A-1 | bt1 | 256 KiB | | | | | | |
| 2 | W-1 × A-1 | bt1 | 256 KiB | | | | | | |
| 3 | W-1 × A-1 | bt1 | 256 KiB | | | | | | |
| 4（可选） | W-1 × A-1 | bt1 | 1 MiB | | | | | | |
| 5（可选） | W-1 × A-1 | raw | 256 KiB | | | | | | |

### 可选观察

| 项 | 结果 |
|---|---|
| 锁屏静置 ≥10 分钟后「再测 RTT」（连接是否存活、RTT 变化） | |
| 第二个连接被拒（`extra_connection_rejected`） | |
| 其他异常/现象 | |

## 3. 门槛判定（判据见运行手册 §7）

| 门槛 | 判据摘要 | PASS / FAIL / 未测 | 证据（引用轮次或日志行） |
|---|---|---|---|
| G-W1 | 未打包进程 `winrt_rfcomm_provider=ok` + `sdp_published=true` | | |
| G-A1 | 授权流 + bonded 枚举 + `connect=ok` | | |
| G-C1 | bt1 握手与全部测试端到端通过 | | |
| G-P1 | connect_ms 典型 ≤ 5000 | | |
| G-P2 | rtt_ms_median ≤ 500 | | |
| G-P3 | 上/下行均 ≥ 50 KiB/s（256 KiB 档） | | |
| G-S1 | 同一设备对连续 3 轮通过 | | |

## 4. 失败与阻塞记录

每条失败一行：完整的 `SPIKE_RESULT:*_error` 行原文 + 复现步骤 + 是否稳定复现。

| # | 设备对 | 现象（原始日志行） | 复现步骤 | 可复现？ |
|---|---|---|---|---|
| 1 | | | | |

## 5. 结论与建议

- **建议：GO / NO-GO（REVISE ADR）**（二选一）：
- 理由（对照门槛表）：
- 若 NO-GO：建议的 ADR 0005 修订方向：
- 若 GO：对阶段 2/3 的具体提醒（实测数字与 ADR 0005 限制表的差异、OEM 特例等）：

## 附录 A：Windows spike 完整日志

```text
（粘贴 scripts/spike-bt1-windows.ps1 落盘的 .log 内容，每轮一段）
```

## 附录 B：Android spike 完整日志

```text
（粘贴 adb logcat -s ClipSyncSpike 输出或界面日志，每轮一段）
```
