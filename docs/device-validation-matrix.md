# Android 实体机验证矩阵

状态：设备盘点模板 + 脚本化检查清单（2026-08-24 扩充；2026-08-25 增补蓝牙 spike 证据与同日人工 QA 会话交叉引用）。**剪贴板通路矩阵（S0–S4）尚未在任何实体设备上按本清单执行，各槽位结果全部为 `NOT_TESTED`，不得视为兼容性声明。** 例外：蓝牙备援阶段 0 spike 已于 2026-08-25 在一对真机上执行并判定 GO（见下方「蓝牙备援 spike 证据」小节），但该 spike 不涉及本页的剪贴板通路检查，矩阵槽位状态不因此改变。另：同日的人工 QA 会话（`docs/manual-qa-results.md`）在一台 D3 系统族真机上触及了前台同步路径，但未按本清单执行，同样不改变槽位状态（见「执行记录」）。本页的检查步骤与 READY 判据只是把「人到场之后要做什么、做到什么程度算过」预先写死，防止临场即兴降低标准。

参考：`feature/stage-4` 谱系在 Redmi Note 11T Pro / MIUI 14 上的实测结果已存档在 [`stage-4-lineage/device-validation-matrix.md`](stage-4-lineage/device-validation-matrix.md)（含 MIUI 后台剪贴板拒绝、Shizuku 版本可用性、P95 延迟等 ROM 行为数据）。那些结论属于另一条代码谱系，**不填入本表**，但排期实机验证时应先读它避开已知坑。

## 状态定义

- `NOT_TESTED`：没有在符合描述的实体设备上执行。
- `READY`：实测满足该 backend 的功能、安全和延迟标准。
- `DEGRADED`：部分功能可用且存在明确降级路径。
- `UNAVAILABLE`：该组合不能安全工作。
- `NEEDS_USER_ACTION`：缺少用户可执行的授权或恢复步骤。

模拟器结果可以补充 API 行为，但不得替代 ROM 覆盖。

## 目标设备组合

| 槽位 | 系统族 | 实体设备/型号 | Android/API | 锁屏策略 | 检查步骤 | Shizuku | `READ_LOGS` + overlay | Overlay polling | 当前状态 |
|---|---|---|---|---|---|---|---|---|---|
| D1 | AOSP/Pixel | 待提供 | 待记录 | 待记录 | S0 → S1/S2/S3/S4 + D1 判据 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| D2 | OneUI | 待提供 | 待记录 | 待记录 | S0 → S1/S2/S3/S4 + D2 判据 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| D3 | MIUI/HyperOS | 待提供 | 待记录 | 待记录 | S0 → S1/S2/S3/S4 + D3 判据 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| D4 | ColorOS/OriginOS | 待提供 | 待记录 | 待记录 | S0 → S1/S2/S3/S4 + D4 判据 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |

如果实际能获得的四台设备不能覆盖四个系统族，必须保留缺口说明，不能用同一 ROM 的多台设备冒充覆盖。

## 脚本化检查清单

约定：`$SERIAL` 为 `adb devices -l` 输出的设备序列号；应用包名固定为 `com.clipsync.android`（`scripts/android-bootstrap.ps1` 的默认值）。执行者只需按序照抄命令并记录输出，不需要理解实现。

### S0 · 通用盘点（每台设备最先执行，填矩阵前五列）

1. 连接设备并开启 USB 调试，运行盘点脚本（只读，不改任何权限）：

   ```powershell
   pwsh scripts/android-bootstrap.ps1 -Serial $SERIAL
   ```

   脚本会打印可见设备、所选设备的厂商/型号/Android 版本/API，以及 `READ_LOGS` 的声明与授予行。等价的手工命令：

   ```bash
   adb devices -l
   adb -s $SERIAL shell getprop ro.product.manufacturer
   adb -s $SERIAL shell getprop ro.product.model
   adb -s $SERIAL shell getprop ro.build.version.release
   adb -s $SERIAL shell getprop ro.build.version.sdk
   adb -s $SERIAL shell dumpsys package com.clipsync.android | grep android.permission.READ_LOGS
   ```

2. 安装当前构建（记录 APK 来源 commit 与构建命令）：

   ```bash
   adb -s $SERIAL install -r app-release.apk
   ```

3. 记录 ROM 名称/版本、补丁级别、锁屏策略（PIN/指纹/无）、电池优化与自启动初始状态。
4. 首次启动应用，完成 `POST_NOTIFICATIONS` 引导，确认「通路」页能力向导可打开。

### S1 · Shizuku 特权事件档

1. 安装并启动 Shizuku（Android 11+ 可用无线调试自启；否则 `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`，以 Shizuku 官方文档为准），在 Shizuku 中授权本应用。
2. 「通路」页选择特权直读路线，观察探针从未授权 → 已授权的状态迁移。
3. 息屏 + 锁屏状态下，在任意第三方应用复制文本 20 次（间隔 ≥ 5 s），记录到达 Windows 端的条数与每条耗时。
4. 同 Wi-Fi 下执行 100 次双向循环互拷，确认无回传（自动写回的内容不得再次上行）。
5. 杀掉 Shizuku 进程 / 重启 Binder / 重启设备，各场景下记录应用是否在 10 s 内如实转入非 READY 状态并给出下一步提示。

**READY 判据**（对应「最低验收数据」表）：Wi-Fi P95 ≤ 1.5 s；公开 writer 优先；100 次循环零回传；三种中断场景全部如实降级。

### S2 · ADB 日志 + 悬浮窗档

1. 授予 `READ_LOGS`（命令与 `scripts/android-bootstrap.ps1` 尾部打印一致）：

   ```bash
   adb -s $SERIAL shell pm grant com.clipsync.android android.permission.READ_LOGS
   ```

2. 在系统设置中授予悬浮窗权限（各 ROM 路径见 D1–D4 判据），「通路」页确认该路线探针转为可用。
3. 第三方应用复制文本，确认 logcat 信号被本槽位的解析器命中（各族期望 tag 见 D1–D4；**禁止保存整段 logcat 或任何剪贴板正文**，只记解析器版本与命中计数）。
4. 复制 50 次记录延迟分布；观察悬浮窗是否始终不可触摸、焦点是否即取即还。
5. 撤销权限并验证降级：

   ```bash
   adb -s $SERIAL shell pm revoke com.clipsync.android android.permission.READ_LOGS
   ```

**READY 判据**：P95 ≤ 2 s；撤销或遇到未知日志格式后 10 s 内转 `DEGRADED` 并给出可执行提示；无正文/整段日志落盘。

### S3 · 悬浮窗轮询档

1. 仅授予悬浮窗权限（确保 `READ_LOGS` 已撤销、Shizuku 未授权），选择轮询路线并记录轮询间隔设置。
2. 息屏/锁屏/前台切换三种场景各复制 20 次，记录到达率与延迟。
3. 用 `adb -s $SERIAL shell dumpsys window windows | grep -i clipsync` 抽查：轮询窗口在读取后释放、无残留窗口、无持续焦点占用。
4. 连续运行 2 小时，记录电池页显示的耗电与唤醒次数是否可解释。

**READY 判据**：P95 ≤ 轮询间隔 + 1 s；无残留窗口、无持续焦点、无无界唤醒；被前台应用夺焦后能恢复。

### S4 · 前台/手动档（所有设备必测，无特殊权限）

1. 应用在前台时复制文本，确认自动上行。
2. 分别通过分享面板、快捷磁贴、通知「复制」动作各发送 5 条，全部到达 Windows 端。
3. 断网复制 3 条 → 恢复网络，确认按序补齐、不重不漏。
4. 暂停/私密模式开启期间上述入口的行为符合设置（不上行），恢复后补投。

**READY 判据**：功能性验收——三个入口与断线补同步全部不被 ROM 阻塞；这是每台设备的保底档。

## 各槽位期望判据（预填，结果仍为 NOT_TESTED）

### D1 · AOSP/Pixel

- S2 期望 logcat 信号：tag `ClipboardService`（解析器 `aosp-v1`）。
- 电池优化路径：设置 → 应用 → 电池 → 不受限制；无独立自启动管理器。
- Shizuku：Android 11+ 优先无线调试方式启动。
- 已知风险：Pixel 上 Android 10+ 对后台读剪贴板限制最标准，本槽位结果是其余槽位的基线。
- 结果：`NOT_TESTED`。

### D2 · OneUI

- S2 期望 logcat 信号：tag `SemClipboardService`（解析器 `oneui-v1`）。
- 电池路径：设置 → 电池 → 后台使用限制，确认应用不在「深度休眠」列表。
- 已知风险：三星自带剪贴板历史可能改变复制事件的日志形态；息屏后 FGS 存活策略需专项记录。
- 结果：`NOT_TESTED`。

### D3 · MIUI/HyperOS

- S2 期望 logcat 信号：tag `MiuiClipboardService` / `MiuiClipboardManager` / `HyperClipboardService`（解析器 `miui-hyperos-v1`）。
- 必做前置：设置 → 应用设置 → 授权管理 → 自启动允许；省电策略改为「无限制」；悬浮窗权限在「其他权限」内单独授予。
- 已知风险：MIUI/HyperOS 的剪贴板隐私弹窗与「神隐模式」可能拦截读取或杀死 FGS；此槽位最可能出现 `NEEDS_USER_ACTION`。
- 结果：`NOT_TESTED`。

### D4 · ColorOS/OriginOS

- S2 期望 logcat 信号：tag `OplusClipboardService` / `ColorClipboardService` / `ClipboardServiceExtImpl`（解析器 `coloros-originos-v1`）。
- 必做前置：自启动允许；应用耗电管理改为「允许完全后台行为」；悬浮窗权限单独授予。
- 已知风险：后台冻结机制激进，2 小时轮询存活（S3 第 4 步）是此槽位的关键项。
- 结果：`NOT_TESTED`。

## 每台设备必须记录

- 制造商、型号、ROM 名称/版本、Android 版本、API、补丁级别。
- 安装方式、目标 SDK、通知权限、电池优化、自启动/最近任务锁定状态。
- 屏幕点亮、息屏、Keyguard 锁定与解锁策略。
- Shizuku：未安装、未启动、未授权、已授权、Binder 重启及设备重启后的状态。
- adb：`READ_LOGS` 授予/撤销命令、实际信号匹配、解析器版本；不得保存真实正文或整段 logcat。
- overlay：授权/撤销、窗口创建与释放、始终不可触摸、焦点恢复、读取耗时。
- 网络：同 Wi-Fi、切换网络、断网恢复、杀进程与重启。
- 上行、入站、自动写回、回环抑制、断线补同步和下一步提示。
- 每个声明可用档位的样本数、P50/P95、失败数和稳定错误码。

## 最低验收数据（阶段 5）

| 模式 | ROM 组合数 | 延迟要求 | 额外要求 |
|---|---:|---|---|
| Shizuku event | 至少 2 | Wi-Fi P95 ≤ 1.5 s | 公开 writer 优先；100 次循环不回传 |
| ADB log + overlay | 至少 2 | P95 ≤ 2 s | 授权撤销/未知格式后 10 s 内转 `DEGRADED` |
| Overlay polling | 至少 3 | P95 ≤ 轮询间隔 + 1 s | 无残留窗口、持续焦点或无界唤醒 |
| Foreground/manual | 所有无特殊权限设备 | 功能性验收 | 分享、磁贴、通知复制及断线补同步不被阻塞 |

## 蓝牙备援 spike 证据（阶段 0，非 S0–S4 通路检查）

来源：`docs/bluetooth-phase0-report.md`（含双端完整日志）；构建 commit `c2a5359`；执行日期 2026-08-25。证据走 spike 工具路径（`scripts/spike-bt1-windows/` + debug 版「ClipSync BT Spike」），**不是产品内 `BluetoothSyncHost`/`BluetoothSyncConnector` 路径**，不构成蓝牙备援 READY；产品路径矩阵属阶段 5。

| 设备对 | Windows 端 | Android 端 | 模式/档位 | 轮次 | connect_ms | rtt_ms_median | 上行 KiB/s | 下行 KiB/s | 门槛判定 |
|---|---|---|---|---|---|---|---|---|---|
| W-1 × A-1 | Lenovo 21STA001CD，Windows 25H2（build 26200），Realtek USB 适配器（驱动 18.4028.0.3005） | Redmi Note 11T Pro（xaga），Android 13 / SDK 33，MIUI `TP1A.220624.014` | bt1 · 256 KiB | 3/3 通过 | 667 / 1665 / 2152 | 31.4 / 31.2 / 30.5 | 166.3 / 176.7 / 153.9 | 154.9 / 160.6 / 175.7 | G-W1、G-A1、G-C1、G-P1–P3、G-S1 全 PASS → **GO** |

蓝牙侧已知缺口（阶段 3/5 补）：1 MiB 档、锁屏 ≥10 分钟连接存活、第二连接拒收演示、第二种适配器（Intel 未测）、第二个 OEM、raw 模式。OEM 行为备注：小米上 Windows「添加设备」常扫不到手机，需从手机系统蓝牙「可用设备」点击 PC 发起 bonding。

## 执行记录

剪贴板通路（S0–S4）目前无按本清单执行的实体机记录；蓝牙备援阶段 0 spike 的执行记录见上节与 `docs/bluetooth-phase0-report.md`。首个 S0–S4 测试开始时，为每台设备新增带日期的记录，包含操作者、构建 commit、前提、按 S0–S4 执行的步骤、原始计数（不含剪贴板正文）、结论和已知缺口。

### 2026-08-25 · 人工 QA 会话（非矩阵执行，槽位不变）

`docs/manual-qa-results.md`（构建 commit `41aa1d2`）记录了一轮 Windows `DENG` × Redmi Note 11T Pro（MIUI，Android 13 / SDK 33，属 D3 系统族）的人工 QA：在既有配对上验证了前台双向文本同步（Windows 复制上行、Android 分享面板上行）、回环抑制（单样本）、同文二次复制仍上行、Windows 暂停捕获。该会话**未按 S0–S4 清单执行**——未跑 S0 盘点脚本，Shizuku / `READ_LOGS` / overlay 三档一项未测，样本量不满足任何 READY 判据。按「未测不得改绿」规则，D3 及所有槽位维持 `NOT_TESTED`。
