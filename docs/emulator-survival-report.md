# Android 进程 / 前台服务存活测试报告（模拟器）

- 日期：2026-08-24
- 分支：`cursor/implement-charter-ui-1991`（仅此分支）
- 被测对象：`com.clipsync.android` 的 `ClipboardSyncService` —— `connectedDevice` 类型前台服务（FGS），前台提升成功时返回 `START_STICKY`，组件 `exported=false`（`android/app/src/main/AndroidManifest.xml`、`sync/ClipboardSyncService.kt`）。
- 测试内容（按任务要求）：服务启动、退到后台（HOME）、`am kill`、最近任务划走（adb 模拟）；另加两项对照：`kill -9` 进程后的 sticky 复活、`am force-stop`。
- APK：本机 `./gradlew :app:assembleDebug` 构建（BUILD_OK，`app-debug.apk` 81.5 MB，`minSdk=29 / targetSdk=35`），以 `adb install -r -t` 安装。

## 1. 宿主环境与 KVM 结论

云端 Linux VM：4 vCPU（Intel Xeon，`/proc/cpuinfo` 带 `hypervisor` 标志，`systemd-detect-virt` = `kvm`，即本机自身是 KVM 客户机）、15 GiB 内存、内核 `6.12.94+`。Android SDK cmdline-tools + platform-tools 37.0.1 + emulator 37.1.11 现装。

### 1.1 嵌套 KVM：损坏（宿主内核缺陷，硬加速不可用）

表面迹象全部正常，实际创建 vCPU 即触发宿主内核 BUG。证据链（全部当场复现）：

1. `/dev/kvm` 存在，`kvm_intel` 模块 `nested=Y`，`emulator -accel-check` 报告 **“KVM (version 12) is installed and usable”**——该检查只探测设备节点与 API 版本，结论是误导性的。
2. 最小 C 探针：`KVM_GET_API_VERSION=12` 成功、`KVM_CREATE_VM` 成功、**`KVM_CREATE_VCPU` 直接令探针进程段错误**，宿主 dmesg 出现内核 oops。
3. 直接启动 KVM 加速模拟器（`emulator -avd avd29`，默认加速）：qemu 主进程存活但 vCPU 线程被内核杀死，进程僵死在 0% CPU，`adb devices` 永远停留 `offline`，两次尝试（120 秒限时前台 + 约 9 分钟后台观察）均永不引导。

dmesg 关键信息（两次 qemu 尝试的 oops，`Comm: qemu-system-x86`）：

```text
kernel BUG at arch/x86/kvm/x86.c:702!
Oops: invalid opcode: 0000 [#4] PREEMPT SMP NOPTI
CPU: 0 UID: 1000 PID: 4782 Comm: qemu-system-x86 Tainted: G      D
RIP: 0010:kvm_spurious_fault+0xe/0x10
Call Trace:
  vmx_vcpu_create+0xa7/0x490
  kvm_arch_vcpu_create+0x1e1/0x2f0
  kvm_vm_ioctl_create_vcpu+0x180/0x4f0
```

即：L0 虚拟机监控器对外暴露了嵌套 VMX 标志，但 VMX 指令实际会陷入 `kvm_spurious_fault`。本次会话共触发 4 次同签名 oops（2 次探针 + 2 次 qemu），内核已被标记 `Tainted: D`。**为避免继续破坏宿主稳定性，未对 API 33/35 重复 KVM 尝试**——三个镜像走的是同一条 `KVM_CREATE_VCPU` 内核路径，结果必然相同。该结论与 `docs/android-instrumentation-test-report.md` 此前记录的同一缺陷一致。

### 1.2 软件模拟（QEMU TCG，`-accel off`）：可用，成为本报告全部动态结果的执行基础

模拟器日志中的 `TCG doesn't support requested feature: CPUID...avx` 等行证明确实运行在纯软件模拟下。三个 AVD（`avdmanager create avd -d pixel_4`，1080×2280）的引导实测：

| AVD | 系统镜像 | 引导结果 | `Boot completed`（模拟器自报） |
|---|---|---|---|
| avd29 | `android-29;default;x86_64`（AOSP，无 GMS） | 成功 | **116 秒** |
| avd33 | `android-33;google_apis;x86_64` | 成功 | **1 189 秒（≈19 分 49 秒）** |
| avd35 | `android-35;google_apis;x86_64` | 成功 | **1 274 秒（≈21 分 14 秒）** |

TCG + SwiftShader 的代价（后文结果解读需要）：CPU 长期 150–250%，Google APIs 镜像上 GMS 全家桶持续 ANR，SystemUI 的 quickstep 概览动画在部分镜像上根本无法渲染。

## 2. 测试方法（全部命令可复现）

服务未导出且应用未配对，因此统一用 `adb root` 后的 shell 直接操作（root 调用方对 BG-FGS 限制豁免，logcat 证实 `Background started FGS: Allowed ... callingUid: 0`）。安装后先 `cmd package compile -m speed -f` 规避 TCG 冷启动 10 秒 attach 死线（沿用先前仪器化报告的绕行方案）。

```bash
adb install -r -t app-debug.apk
adb shell cmd package compile -m speed -f com.clipsync.android
adb root
adb shell am start -n com.clipsync.android/.MainActivity        # 先起 UI（未配对 → 服务不会自启）
adb shell input keyevent 3                                       # HOME（见 §3.2 的顺序陷阱）
adb shell am start-foreground-service -n com.clipsync.android/.sync.ClipboardSyncService
adb shell dumpsys activity services com.clipsync.android         # isForeground=true 判定
adb shell pidof com.clipsync.android                             # PID 追踪
adb shell am kill com.clipsync.android                           # 测试 3
adb shell input keyevent 187 && adb shell input swipe 540 1200 540 150 150   # 测试 4（UI 划走）
adb shell am stack remove <taskId>                               # 测试 4 等价程序化路径（UI 不可用时）
adb shell kill -9 <pid>                                          # 对照 A：sticky 复活
adb shell am force-stop com.clipsync.android                     # 对照 B：强停
```

判定标准：`dumpsys activity services` 中 `isForeground=true foregroundId=1001`；`dumpsys activity oom` 中进程处于 `F/S/FGS (fg-service)`；PID 前后对比；`logcat -b events` 的 `am_kill` / `am_proc_died` / `am_foreground_service_*` 事件。

## 3. 结果

### 3.1 API 29（Android 10，AOSP 镜像）——全部通过，全程同一 PID

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 1 | 服务启动 | **通过** | `isForeground=true foregroundId=1001`，通知渠道 `clipsync.sync`；`dumpsys activity oom`：`prcp F/S/FGS (fg-service)`；PID 3610 |
| 2 | HOME 退后台 | **通过** | PID 3610 不变，`isForeground=true` |
| 3 | `am kill` | **通过（存活）** | PID 3610 不变，`isForeground=true`——`am kill` 只杀“可安全杀死”的进程，活跃 FGS 按设计豁免 |
| 4 | 最近任务划走（真实 UI 注入） | **通过（存活）** | `keyevent 187` 打开概览（截屏确认应用卡片可见）→ `input swipe 540 1200 540 150 150` 划走卡片 → `am stack list` 中任务消失；PID 3610 依旧存活，`isForeground=true`，`fg-service` 优先级不变 |
| A | `kill -9 3610` | **复活** | ≤5 秒内新 PID 3819 出现，`isForeground=true` 重新成立（`START_STICKY` + AMS 重启，且 API 29 无 BG-FGS 限制，前台提升直接成功） |
| B | `am force-stop` | 按预期终结 | 进程死亡，观察 38 秒无重启，`ServiceRecord` 消失（force-stop 清除 sticky 重启，预期行为） |

注：第一次划走手势（300 ms 慢划）未达删除阈值，卡片未消失；改用 150 ms 快速上划成功。TCG 下注入手势的速度阈值判定偏保守，属环境现象。

### 3.2 API 33（Android 13，Google APIs 镜像）——核心结论同 29，另有两项诚实发现

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 1 | 服务启动 | **通过** | `isForeground=true foregroundId=1001`；PID 4688（后续干净复测为 10508） |
| 2 | HOME 退后台 | **通过** | PID 不变，`oom`：`F/S/FGS (fg-service-act)` |
| 3 | `am kill` | **通过（存活）** | PID 不变（4688 与 10508 两轮均验证），`isForeground=true` |
| 4 | 最近任务移除（程序化） | **通过（存活）** | UI 概览不可用（见发现②），改用 `am stack remove 19`（概览划走底层同一 `removeTask` 调用）：任务从 `am stack list` 消失，PID 10508 存活，`isForeground=true`，事件日志无新的 `am_kill` |
| A | `kill -9 10508` | **进程复活，前台身份未恢复**（见发现③） | AMS 日志 `Scheduling restart of crashed service ... in 1000ms`，约 3 秒后新 PID 11101 专为服务而起（`am_proc_start ... service ClipboardSyncService`）；但无新的 `am_foreground_service_start` 事件，服务随即自停，进程沦为 `cch-empty` |
| B | `am force-stop` | 按预期终结 | 进程死亡，33 秒无重启 |

**发现①（测量陷阱，非缺陷）**：第一轮任务移除时进程确实被杀（`am_kill ... adj 905, remove task`），但 `adj 905` 表明被杀时进程已是 cached——根因是本人在测试中途把 `MainActivity` 重新拉回前台，应用的 `SyncServiceController` 按设计在“未配对”状态下主动停掉了服务（`am_foreground_service_stop ... STOP_SERVICE`，18:06:58，与 Activity 回前台同时刻；源码 `MainActivity.kt` 中 `PairingUiState.Idle && pairedPeer == null → onStopService()`）。也就是说**平台杀的是一个早已没有 FGS 的 cached 进程，行为完全正常**。按“启动 Activity → HOME → 再起 FGS → 不再触碰 UI”的干净顺序复测后得到上表第 4 行的存活结论。对测试者的教训：凡是未配对状态下让 UI 回前台，都会重置服务状态。

**发现②（环境限制）**：该镜像上 quickstep 概览在 TCG + SwiftShader 下无法渲染——4 次尝试（`keyevent 187` 两种导航模式、上划停顿手势）均黑屏或触发 “System UI isn't responding” ANR 弹窗，`ResumedActivity` 始终停在桌面。因此 API 33 的“划走”用其底层等价调用 `am stack remove` 完成，并如实标注。

**发现③（真实行为差异，值得产品侧关注）**：API 33 上 `kill -9` 后 AMS 忠实执行了 `START_STICKY` 重启（1 秒调度、3 秒起进程），但服务的**前台再提升未发生**：无 `am_foreground_service_start` 事件，仅见一条 1001 号通知以**不带** `FLAG_FOREGROUND_SERVICE` 的旗标（0x2a 而非 0x6a）入队，服务在重启后约 3 秒自停（`am_foreground_service_stop ... STOP_SERVICE`，其 109 秒时长字段对应被杀进程的前台生命周期记账），进程最终 `cch-empty`。这与 Android 12+ 对后台重启服务调用 `startForeground()` 的限制（`ForegroundServiceStartNotAllowedException`）及服务内 `runCatching` 降级路径（`FGS_START_DENIED` → 恢复通知 → `stopSelf`，`START_NOT_STICKY`）吻合；对照 API 29（无此限制）重启后前台身份完整恢复。**存疑点如实记录**：`POST_NOTIFICATIONS` 已授权、`clipsync.recovery` 渠道存在，但「需要恢复」通知未出现在 `dumpsys notification` 活动列表中——确切拒绝机制与通知去向无法从进程外完全归因（异常被 `runCatching` 吞掉，属设计），建议后续在真机上以 `startErrorCodes` 状态流复核。

### 3.3 API 35（Android 15，Google APIs 镜像）——全部通过，`kill -9` 后完整自愈

采用 §3.2 教训后的干净顺序（启动 Activity → HOME → 起 FGS → 之后不再触碰 UI）。

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 1 | 服务启动 | **通过** | `isForeground=true foregroundId=1001 types=0x00000010`（即 `connectedDevice`），通知旗标含 `FOREGROUND_SERVICE`；PID 4409；`oom`：`F/S/FGS (fg-service-act)` |
| 2 | 后台运行 | **通过** | FGS 在应用退后台状态下起动并持续运行贯穿全部后续测试（HOME 先于 FGS 启动，见 §2 顺序） |
| 3 | `am kill` | **通过（存活）** | PID 4409 不变，`isForeground=true` |
| 4 | 最近任务移除（程序化） | **通过（存活）** | 概览 UI 与 API 33 同样在 TCG 下黑屏不可用（1 次 `keyevent 187` 尝试后即改道）；`am stack remove 9`：任务消失，PID 4409 存活，`isForeground=true`，事件日志无 `am_kill` |
| A | `kill -9 4409` | **完整自愈（含前台身份）** | AMS 先调度 14 111 ms 重启，随即因 `mem-pressure-event` 改为 0 ms；约 2 秒后新 PID 5471 专为服务而起；**`isForeground=true` 完整恢复**。细节：事件日志中没有新的 `am_foreground_service_start`，Android 15 把这次重启记账为原前台会话的延续（ServiceRecord 上缓存的 `infoAllowStartForeground` 授权被复用） |
| B | `am force-stop` | 按预期终结 | 进程死亡，38 秒无重启 |

与 API 33 的差异值得注意：同样的 `kill -9`，API 33 上前台身份无法恢复（§3.2 发现③），API 35 上完整恢复。机制上与 ServiceRecord 缓存并复用首次 `startForeground` 许可（dumpsys 可见 `infoAllowStartForeground=[...code:SYSTEM_UID...]`）一致。**诚实缓存说明**：本测试的许可来源是 root shell（`SYSTEM_UID`），真机上用户从应用内（前台）启动服务时缓存的许可类型不同，重启后的复用行为需真机复核。

## 4. 诚实声明与限制

1. **KVM 全线不可用**是宿主（嵌套虚拟化）缺陷，与本仓库无关；本报告所有动态结果均来自 QEMU TCG 软件模拟。TCG 时序比真机慢一个数量级以上，本报告不将任何“耗时”读数外推到真机。
2. API 33/35 未重复 KVM 崩溃实验（同一内核路径，且每次实验都在宿主内核追加 oops）。
3. API 33 与 35 上“最近任务划走”未能以真实 UI 手势完成（quickstep 概览在 TCG + SwiftShader 下无法渲染），使用了底层等价的 `am stack remove`；API 29 上完成了真实 UI 手势版本，三者结论一致（FGS 存活）。
4. 所有 FGS 启动均以 root shell 发起（BG-FGS 豁免）。真机上用户路径是应用内启动（前台发起），豁免性质等同；但**配对后长期运行场景**（服务因 pairing 存在而自启）未在本报告覆盖——模拟器上无 Windows 对端可配对。
5. OEM 魔改系统（MIUI/EMUI 等）的激进查杀不在模拟器可验证范围内，按 `docs/device-validation-matrix.md` 走实体机 QA。

## 5. 结论速览

- **FGS 对 `am kill` 与最近任务移除的抵御：API 29 / 33 / 35 三个级别全部实测存活**，进程 PID 前后不变、`isForeground=true` 保持。
- **进程被强杀后的自愈**：API 29 完整自愈（进程 + 前台身份）；API 33 上进程复活但前台身份被平台拒绝、服务按设计降级自停（应用内“需要恢复”通知路径存在的原因，真机验证时应重点核对该通知确实可见）；API 35 上完整自愈（含前台身份，靠 ServiceRecord 缓存的启动许可复用）。API 33 是三者中对“进程死亡后无干预自动复活”最不利的级别。
- **`force-stop` 是唯一可靠终结手段**（三个 API 级别一致：进程死亡、sticky 重启被清除），符合平台语义。
