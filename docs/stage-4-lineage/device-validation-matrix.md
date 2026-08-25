# Android 实体机验证矩阵

维护说明：本文自 **2026-08-18** 起按仓库内阶段变更记录回填，**取代**阶段 0「尚未提供任何实体设备 / 全部 `NOT_TESTED`」占位稿。`docs/dod-status.md` 曾声明不得以该占位稿为实机证据；以**本文 + 下表证据列指向的变更记录**为准。未出现在「已验证」表中的组合**不是**兼容性声明。模拟器结果可以补充 API 行为，**不得**替代 ROM 覆盖。

回填基线：仓库 `D:\paste`，编排指定 HEAD `722f755`。只核 hash / token，不读剪贴板正文。唯一实体机 + Windows 对端。

## 结论定义

验收结论（与 `docs/dod-status.md` 对齐）：

| 结论 | 含义 |
|---|---|
| **DEVICE-VERIFIED** | 阶段 5/6/8 变更记录写明已在下表设备上核对（只对 hash，不读正文）。 |
| **PARTIAL** | 代码或部分路径已证；缺口写在场景/结果栏。 |
| **进行中** | 编排端声明正在执行，仓库尚无完成记录（对应 DoD `IN-FLIGHT`）。编排器填结果，本文不预填通过。 |
| **NOT_TESTED** | 需要硬件或人工；写明缺什么。 |
| **NO_EVIDENCE** | 源码、测试、变更记录均未覆盖（比 `NOT_TESTED` 更重）。 |

能力态（probe / backend，出现在结果说明里，不是「已支持」）：`READY` / `DEGRADED` / `UNAVAILABLE` / `NEEDS_USER_ACTION`。

## 实体设备（唯一一行）

| 槽位 | 系统族 | 实体设备 / 型号 | Android / API | ROM | adb | 对端 | 锁屏 / 补丁级 |
|---|---|---|---|---|---|---|---|
| D3 | MIUI | Redmi Note 11T Pro（`22041216C` / `xaga`，Dimensity 8100） | Android 13 / API 33 | MIUI 14 `V14.0.5.0.TLOCNXM` | `HUHYEYDQDMVONZDU` | Windows 主机 WLAN `192.168.2.135`（SKU / 内部版本**未记**）；手机 Wi‑Fi `192.168.2.250/23` | **未记录** |

前提（已记，不是 compat 声明）：

- 安装：MIUI USB 侧载会弹 `INSTALL_FAILED_USER_RESTRICTED`，需手点「继续安装」。测试机装的是 **debug** 签名包。
- Shizuku：**13.5.4 (r1049)** 可用；每次重启后需 `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`。授权路径：Shizuku → 已授权应用 → ClipSync；重启+重装后授权保留。
- Shizuku **13.6.0**：该机 UserService 起不来（MediaTek + MIUI，`Application.getProcessName()` NPE；上游 RikkaApps/Shizuku #1198 / #1171）→ `UNAVAILABLE`。
- 配对：测试钩子（payload 文件 + 自动确认）；Windows `devices` 出现 `Xiaomi 22041216C`。钩子默认关闭。
- 主读模式：`SHIZUKU_EVENT`；写入走公开 `PUBLIC_API`（Shizuku 写回退已接线，实机未走到公开写失败）。

## 已验证矩阵

一行 = 该设备/ROM × 模式 × **场景组**（已在变更记录里核对过的才进表）。同一场景后来被更严的轮次覆盖时，以**更晚、更具体**的记录为准。

| # | 日期 | 模式 | 场景组 | 结论 | 证据 |
|---:|---|---|---|---|---|
| 1 | 2026-08-17 | `SHIZUKU_EVENT` | 安装、配对、Shizuku 授权 | **DEVICE-VERIFIED** | `docs/stage-5-change-log.md` §「实机记录（2026-08-17，唯一一行矩阵）」：安装可用；配对成功；事件落 Room（`source_app=shizuku`）。 |
| 2 | 2026-08-17 | `SHIZUKU_EVENT` | Shizuku 版本可用性 | **DEVICE-VERIFIED** | 同上：13.5.4 可用；13.6.0 UserService `UNAVAILABLE`（上游缺陷，不是本应用 READY）。 |
| 3 | 2026-08-17 | `SHIZUKU_EVENT` | 主链路抽查：捕获、双向同步、Home 后 FGS、History 刷新 | **DEVICE-VERIFIED** | 同上：Share↔Windows 与 Win 剪贴板→Android 均约 1 s、哈希核对；Home 后 Win→Android 仍约 1 s；`isForeground=true`；History 顶部刷新；Status **Connected**。当时**无** P95 样本量（正式 P95 见 #13）。 |
| 4 | 2026-08-17 | `FOREGROUND_ONLY` | 分享面板 → Windows（`auto_apply_remote`） | **DEVICE-VERIFIED** | 同上「双向同步」Share→Windows 约 1 s。磁贴 / 通知复制 / 关 Shizuku 后的纯前台回归**未**单独记。 |
| 5 | 2026-08-17 | `ADB_LOG_OVERLAY` | `READ_LOGS` 授予后 probe | **DEVICE-VERIFIED**（能力态 **DEGRADED**） | 同上：权限已授，probe 仍 `ADB_LOG_NO_HEALTHY_SIGNAL`。该 ROM 只有 `ClipboardService: Denying clipboard access…`，无解析器可匹配的复制信号；`dumpsys clipboard` 为空。按设计不标 `READY`。**不是** READY 上行。 |
| 6 | 2026-08-17 | `OVERLAY_POLLING` | `SYSTEM_ALERT_WINDOW` 后 probe | **PARTIAL** | 同上：`appops … SYSTEM_ALERT_WINDOW allow` 后 probe **READY**，无需可触摸窗口。**轮询捕获 + 真同步未做**（见待完成 #P13）。 |
| 7 | 2026-08-18 | `SHIZUKU_EVENT` | 回声抑制（入站不回传） | **DEVICE-VERIFIED** | `docs/stage-6-change-log.md` §「实机验证轮（2026-08-18…）」：Win→Android 恰好 1 行远端 ingest，6 s 窗口后 **0** 条 `shizuku` 回声。阶段 5 曾写「实机复验待下次安装」——本轮已复验，不再待。 |
| 8 | 2026-08-18 | `SHIZUKU_EVENT` | 双向回归 + 5.7 自测 + Status | **DEVICE-VERIFIED** | 同上：两向约 1 s、恰好一次；`Test background read: OK · SHIZUKU_EVENT`；`Test background write: OK · PUBLIC_API`；令牌未落库、未上传；Network=Connected。 |
| 9 | 2026-08-18 | `SHIZUKU_EVENT` | 故障注入：手机 Wi‑Fi 断开→恢复 | **DEVICE-VERIFIED** | 同上：`svc wifi disable/enable`，恢复后 **11 s** 重连到达，恰好一次。这是开关 Wi‑Fi，**不是**换 SSID / 蜂窝。 |
| 10 | 2026-08-18 | `SHIZUKU_EVENT` | 故障注入：Windows 进程杀掉再起 | **DEVICE-VERIFIED** | 同上：杀掉再启动 Windows 端，首次复制立即到达，恰好一次。 |
| 11 | 2026-08-18 | `SHIZUKU_EVENT` | 故障注入：整机重启 + 开机恢复 | **DEVICE-VERIFIED** | 同上（两段）：未授 MIUI「自启动」时 **不投递** `BOOT_COMPLETED`（开机 70 s 无进程/无通知），打开应用即恢复。授予「自启动」后再重启：`BOOT_COMPLETED` → receiver → FGS **全程未打开应用**，Win→Android 约 1 s 恰好一次。WorkManager 失败兜底因 FGS 直接成功**未触发**（仅 JVM）。Shizuku daemon 仍要 `start.sh`。 |
| 12 | 2026-08-18 | `SHIZUKU_EVENT` | 断网 30 分钟浸泡 | **DEVICE-VERIFIED** | `docs/stage-6-change-log.md` §「延迟修复与 P95 验收轮」：离线注入 5 事件（Windows 3、手机 2），恢复后全部到达、恰好一次、零回声。 |
| 13 | 2026-08-18 | `SHIZUKU_EVENT` | P95 正式延迟（30 样本） | **DEVICE-VERIFIED** | 同上。Android→Windows **p50 0.27 s / p95 0.37 s / max 0.52 s**（验收线 1.5 s，通过）。Windows→Android **p50 1.69 s / p95 2.07 s**（含约 0.4 s 拉库轮询粒度；阶段 4 的 2 s 收件箱目标以 p50 达成）。双侧恰好一次、零回声。 |
| 14 | 2026-08-18 | `SHIZUKU_EVENT` | 故障注入：UserService 被杀后监听恢复 | **PARTIAL** | 同上「潜伏缺陷」：`probe()` Bound 分支曾在 UserService 重生后丢监听（会话 READY、事件不再到达）；已改 `attachSession`，**JVM 修前为红**。变更记录**没有**单独的实机行「`am force-stop` / 杀 `:clipsync-clipboard` 后事件恢复 PASS」。后续 P95 在修复后的构建上通过，不能反推该注入已做。 |
| 15 | 2026-08-18 | `SHIZUKU_EVENT` | 简体中文 UI + 历史详情对话框 | **DEVICE-VERIFIED** | `docs/stage-8-change-log.md` §「实机（2026-08-18…）」：中文 UI PASS；详情对话框复制 / 删除 / 关闭 PASS。 |
| 16 | 2026-08-18 | `SHIZUKU_EVENT` | Activity 销毁后捕获仍上行 | **DEVICE-VERIFIED** | 同上：`am stack remove` 后无 Activity，从系统设置搜索框复制仍送达 Windows（`fb9347b`）。这**不是** OS 强制停止。 |
| 17 | 2026-08-18 | `SHIZUKU_EVENT` | Shizuku 后启：一个 10 s tick 内自动绑定 | **DEVICE-VERIFIED** | 同上：先开应用、后起 Shizuku，一个健康周期内自动绑定（`8735345`）。 |
| 18 | 2026-08-18 | `SHIZUKU_EVENT` | 后台同步开关 OFF/ON 连续性 | **DEVICE-VERIFIED** | 同上：双向不中断（单一 `SyncController`，`1ef53ab`）。 |
| 19 | 2026-08-18 | `SHIZUKU_EVENT` | 孤儿 UserService 跨重装计数 | **DEVICE-VERIFIED** | 同上：计数保持 **1**（`USER_SERVICE_VERSION=2` + callback 死亡退出）。阶段 6 曾在 version=1 下发现残留进程（手工清理）；本行是 version=2 后的复验。 |
| 20 | 2026-08-18 | Windows 对端 | Modern Standby / S0 idle | **PARTIAL**（观察，**不是通过**） | 同上 + `02ec63c`：本机 S0 idle **不**抬 `PowerModeChanged`（内核 506/507 于 13:43–13:45，应用 0 条电源事件）；短 S0 期间 TCP 仍在（ping 未断）。Win32 `PowerRegisterSuspendResumeNotification` 已落地；**运行期 S0 事件落盘待自然待机**。经典 S3 睡眠→唤醒→复制恰好一条 **NOT_TESTED**。 |
| 21 | 2026-08-20 | `SHIZUKU_EVENT` | 内置特权宿主：`start.sh`、授权、本机落库 | **DEVICE-VERIFIED**（单机） | `docs/stage-8-change-log.md` §「真机复测（2026-08-20）」：本包 `start.sh` 拉起 `clipsync_priv_server` + `:clipsync-clipboard`；`attach uid=10417 api=13`；运行时 `active_read_mode=SHIZUKU_EVENT` / `READY`；本机 `origin_seq=184` `source_app=shizuku` `content_hash=359799ce9511a3276648bdb8456a65f4d2c08ba2a5a24d399ef1a9e91176316d`。不是官方 Shizuku 13.5.4。未覆盖重启后再跑 `start.sh`、杀 UserService 后恢复。 |

## 待完成 / 缺口

编排器只应改「进行中」单元格；不要把 JVM 绿或本机 MIUI 结果抄进其他 ROM。

### 进行中（本日；结论格保持「进行中」，由编排器改写成实测结论）

| # | 日期 | 设备 / ROM | 模式 | 场景组 | 结论 | 证据 / 说明 |
|---:|---|---|---|---|---|---|
| P1 | 2026-08-18 | AOSP 模拟器 API **34** | （汇总） | 模拟器矩阵全量（google_apis x86_64，WHPX） | **PASS（模拟器）** | 装机、英文 UI、前台捕获、Shizuku READY、自测读写、双向同步（隔离回环 E2eHost @10.0.2.2，未触碰用户端）、开机恢复全过。**模拟器结论不占实体 ROM 槽位**（G1 仍 NO_EVIDENCE）。 |
| P2 | 2026-08-18 | AOSP 模拟器 API **30** | （汇总） | 同上 | **未开跑** | 本轮时间全部投给完整的 API 34；API 30/35 留待下轮（AVD 基建已就绪：emulator 37.1.11 + `clipsync_api34`）。 |
| P3 | 2026-08-18 | AOSP 模拟器 API 34 | `SHIZUKU_EVENT` | 绑定 + READY + 自测 + 双向同步 | **PASS（模拟器）** | Shizuku v13.5.4（从本机手机只读拉取）`start.sh` 启动；`:clipsync-clipboard` 出现；向导 Background read Ready（约 15 s，停驻恢复路径）；自测读 `OK · SHIZUKU_EVENT`；A→W 与 W→A 令牌均达。 |
| P4 | 2026-08-18 | AOSP 模拟器 API 34 | `ADB_LOG_OVERLAY` | （编排器填） | **未测** | 本轮未覆盖该模式（`READ_LOGS` 授予后进程内刷新滞后的观察见 stage-8 附注）。 |
| P5 | 2026-08-18 | AOSP 模拟器 API 34 | `OVERLAY_POLLING` | （编排器填） | **未测** | 本轮未覆盖该模式。 |
| P6 | 2026-08-18 | AOSP 模拟器 API 34 | `FOREGROUND_ONLY` | 前台捕获 | **PASS（模拟器）** | 搜索框令牌复制 → `capture_stored` + History 行。 |
| P7 | 2026-08-18 | AOSP 模拟器 API 34 | （开机恢复） | 原生 AOSP 开机恢复 | **PASS（模拟器）** | **MIUI 无法证明的主张在原生系统成立**：`BOOT_COMPLETED` → 约 +28 s FGS（`uidState: RCVR`）→ `session_ready`，全程未打开应用；系统设置搜索框复制的令牌落库并送达主机。 |
| P8 | 2026-08-18 | AOSP 模拟器 API 30 | `SHIZUKU_EVENT` | （编排器填） | **未开跑** | 见 P2。 |
| P9 | 2026-08-18 | AOSP 模拟器 API 30 | `ADB_LOG_OVERLAY` | （编排器填） | **未开跑** | 见 P2。 |
| P10 | 2026-08-18 | AOSP 模拟器 API 30 | `OVERLAY_POLLING` | （编排器填） | **未开跑** | 见 P2。 |
| P11 | 2026-08-18 | AOSP 模拟器 API 30 | `FOREGROUND_ONLY` | （编排器填） | **未开跑** | 见 P2。 |
| P12 | 2026-08-18 | AOSP 模拟器 API 30 | （开机恢复） | 模拟器开机恢复 | **未开跑** | 见 P2。 |
| P13 | 2026-08-18 | Redmi Note 11T Pro / MIUI 14 / API 33 | `OVERLAY_POLLING` | **真同步**（复制 → 落库 → Windows；非仅 probe） | **FAIL（本机诚实结论）** | 悬浮窗授权 + 同意后模式达 READY、800ms 轮询确在跑，但**应用后台时 MIUI `ClipboardService` 逐次拒绝读取**（`Denying clipboard access … not in focus`，约 6s 内 27 次拒绝），后台复制零捕获；应用前台时同模式捕获约 1s 可同步（但那是前台焦点，不是悬浮窗抢焦点）。**该 MIUI 上悬浮窗模式实际仅前台可用。** 附带硬件确认：Shizuku 回归后 `tryRecoverRequestedMode` 约 21s 自动切回 `SHIZUKU_EVENT`。测后手机状态已全部还原（consent off / appops ignore / SHIZUKU READY / 末次令牌同步 PASS）。 |

### 无硬件 / 本机阻塞（不是进行中）

| # | 系统族 / 模式 | 设备 | 场景组 | 结论 | 说明 |
|---:|---|---|---|---|---|
| G1 | AOSP / Pixel **实体机** | 未提供（原 D1） | 任意后台读档 | **NO_EVIDENCE** | 与模拟器 P1–P12 分开。模拟器填绿也不占此行。 |
| G2 | OneUI | 未提供（原 D2） | 任意 | **NO_EVIDENCE** | 无硬件。不得用 MIUI 行冒充。 |
| G3 | ColorOS / OriginOS | 未提供（原 D4） | 任意 | **NO_EVIDENCE** | 无硬件。阶段 0 曾提醒部分 OriginOS 日志可能不可用，**从未实测**。 |
| G4 | HyperOS / 其他 MIUI | 无 | 任意 | **NO_EVIDENCE** | 已测的是 MIUI 14 一台，不是「MIUI 族」或 HyperOS。 |
| G5 | `ADB_LOG_OVERLAY` | 本机 MIUI 14 | **READY 态上行**（落库 + 同步 + P95） | **NOT_TESTED**（本机**阻塞**） | #5 已核：授予后仍 `DEGRADED` / `ADB_LOG_NO_HEALTHY_SIGNAL`。READY 上行需要**别的 ROM** 能匹配复制信号。不要在这台机上把 DEGRADED 改写成 READY。 |

### 本机已声明未测（避免误读已验证表）

下列在阶段 5/6/8「未测 / 仍未测」里点名，**不要**从主链路 PASS 外推：

- 锁屏 / 息屏策略；息屏停 overlay 轮询；「不能安全读写时暂停自动模式、入站留收件箱、禁止伪装已应用」。
- 运行中撤销 Shizuku / overlay / `READ_LOGS` 且**仅 FGS 存活**。
- Android **强制停止** / 划掉最近任务后的磁盘与「需要恢复」文案（`am stack remove` ≠ 强制停止）。
- 通知权限拒绝、电池优化长期驻留、换 SSID / 蜂窝、不同网段、Tailscale/WireGuard。
- Windows 经典 S3 睡眠→唤醒→复制恰好一条；锁屏/解锁实机；Windows 开机自启托盘。
- Shizuku 写回退在「公开写失败」下的实机路径；WorkManager 开机失败兜底通知。
- Android 10 / 12 / 14 实体机；purge / 搜索 / 暂停的本日实机走查。

## 目标设备组合（盘点，不是验收）

| 槽位 | 系统族 | 实体设备 | 当前状态 |
|---|---|---|---|
| D1 | AOSP / Pixel | 待提供 | **NO_EVIDENCE**（模拟器 P1–P12 进行中，不占此槽） |
| D2 | OneUI | 待提供 | **NO_EVIDENCE** |
| D3 | MIUI / HyperOS | Redmi Note 11T Pro / MIUI 14 / API 33 | **DEVICE-VERIFIED**（单机、`SHIZUKU_EVENT` 主链路）；不是 HyperOS |
| D4 | ColorOS / OriginOS | 待提供 | **NO_EVIDENCE** |

四类 ROM 缺口必须留着。不能用同一 ROM 的多台设备或模拟器冒充覆盖。

## 每台新设备仍须记录

制造商、型号、ROM 名称/版本、Android 版本、API、补丁级别；安装方式、目标 SDK、通知 / 电池 / 自启动 / 最近任务；亮屏、息屏、Keyguard；Shizuku 各状态；adb `READ_LOGS` 授予/撤销与解析器版本（不存正文或整段 logcat）；overlay 授权、窗口、不可触摸、焦点、读耗时；同网 / 切网 / 断网 / 杀进程 / 重启；上行、入站、自动写回、回环、补同步；每个声明可用档位的样本数、P50/P95、失败数、稳定错误码。

## 阶段 5 最低验收对照（诚实进度）

| 模式 | 计划：ROM 组合数 | 本仓库实际 | 延迟 |
|---|---|---|---|
| Shizuku event | ≥ 2 | **1**（本机 MIUI 14） | Wi‑Fi P95：**DEVICE-VERIFIED**（A→W 0.37 s ≤ 1.5 s）。W→A p95 2.07 s（含拉库粒度）。100 次循环不回传：实机为零回声样本，1000 次在 JVM。 |
| ADB log + overlay | ≥ 2 | **0** READY；**1** 台核过 **DEGRADED** | READY P95：**NOT_TESTED**（本机阻塞）。 |
| Overlay polling | ≥ 3 | **0** 真同步；**1** 台 probe READY | P95：**NOT_TESTED**（真同步进行中）。 |
| Foreground / manual | 所有无特权设备 | 分享路径 **DEVICE-VERIFIED**；磁贴 / 通知复制未齐 | 功能性，无 P95 要求。 |
