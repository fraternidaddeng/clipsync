# 阶段 5 变更记录

日期：2026-08-17
状态：**代码面完成，单 ROM（MIUI 14）实机验证通过主链路**。wave 1 已提交（`64c5a2b`）；wave 2、实机联测接线与修复、5.7 自测入口在工作区**未提交**。Android `testDebugUnitTest` **343 用例 / 47 类 / 0 失败 / 1 skipped**（skipped 为门控 E2E 用例本体）；`assembleDebug` 成功。Windows Debug 0 警告 0 错误，Tests **169/169**、App.Tests **33/33**（本阶段改过 3 个 Windows 文件后复跑）。四类 ROM 实机矩阵只有 MIUI 一行，其余保持 `NOT_TESTED`。

## 阶段目标（plan.md 阶段 5）

Android 后台自动剪贴板能力：读/写模式状态机与权限向导（5.1）、`connectedDevice` 前台服务与进程恢复（5.2）、Shizuku 事件模式（5.3）、ADB 日志 + 透明悬浮窗（5.4）、悬浮窗轮询兜底（5.5）、手动降级与隐私闭环（5.6）、自动化与实体机验收（5.7）。

## 构建环境（后续 agent 必读）

- 与阶段 4 相同：.NET 8.0.419 在 `D:\paste-tools\dotnet`；Android SDK 在 `D:\paste-tools\android-sdk`；JDK 17 在 PATH。
- 实机调试工具链：`adb` 用 `D:\paste-tools\android-sdk\platform-tools`；测试机序列号 `HUHYEYDQDMVONZDU`。
- 8.3 官方链接复核已在 wave 1 写入 `docs/android-background-clipboard.md`（复核日期 2026-08-17）。

## 交付内容

### Wave 1（已提交 `64c5a2b`，2026-08-17）

- **5.1 状态机**：`ClipboardReadMode`（SHIZUKU_EVENT / ADB_LOG_OVERLAY / OVERLAY_POLLING / FOREGROUND_ONLY）、独立 `ClipboardWriteMode`、`CapabilityState`/`BackendHealth`、`CapabilityReport` 读写分开持久化（`KeyValueClipboardCapabilityStore`，不含文本/logcat/目标 App 名）；`ClipboardAccessCoordinator` 模式切换事务（停旧 backend → 释放 focus → 刷新哈希 → 启新 backend → mode epoch +1，失败回滚）。
- **5.2 前台服务**：`ClipboardSyncService`（`foregroundServiceType="connectedDevice"`、`ServiceCompat.startForeground`、`MissingForegroundServiceTypeException`/`SecurityException` 捕获）；`ServiceOrchestrator`（JVM 可测的启停/交接/进程状态机，杀进程后显示“需要恢复”而不伪造在线）；通知动作（暂停全部 / 立即同步 / 打开状态，不含正文）；`BOOT_COMPLETED` 仅在“开机恢复”开启后注册。
- **5.3 Shizuku**：`ShizukuClipboardBackend` + 独立 UserService（只暴露剪贴板读/写/listener/health，不接网络、不放密钥）；七类稳定错误码；`linkToDeath` + 重绑；`ShizukuClipboardWriter`（写回退实现，见“已知限制”——生产装配未接线）。

### Wave 2（未提交，在工作区）

- **5.5**：`OverlayFocusController`（1x1、alpha 0、始终 `FLAG_NOT_TOUCHABLE`，读取时只临时移除 `FLAG_NOT_FOCUSABLE`）+ `OverlayPollingBackend`（哈希对比、熄屏/锁屏停轮询）。
- **5.4**：`AdbLogOverlayBackend`（`READ_LOGS` 受限 logcat 事件流 + 150ms 防抖 + overlay 读正文；版本化解析 fixture 在 `app/src/test/resources/`，不存真实日志）；`scripts/android-bootstrap.ps1` 只打印 grant/revoke 命令，从不代授。
- **5.1 向导**：`ui/wizard/`（7 张权限卡：通知 / FGS / 电池 / 悬浮窗 / READ_LOGS / Shizuku Binder / Shizuku 授权；每张独立 probe、跳过后果、四个 live 指示灯不合并）；`docs/stage-5-contract.md`（wave 2 文件所有权契约）。

### 实机联测接线与修复（本会话，未提交）

配对与双向同步在真机上跑通所需的改动，以及联测暴露的三个缺陷修复：

1. **MainActivity 接入读协调器**：按向导选择的 `preferredReadMode`/`autoFallbackAllowed` 启动 `ClipboardAccessCoordinator`，Shizuku 事件落 Room（`source_app=shizuku`）；`onDestroy` 停。`ClipboardAccessCoordinator.canStartWhileDegraded`：Shizuku probe 因 bind 异步而 DEGRADED 但已授权时仍允许 start（bind 完成后事件自然到达）。`ClipSyncApplication` + manifest `<queries>`（`moe.shizuku.privileged.api`，修向导“打开 Shizuku”跳错页）。
2. **配对自动化测试钩子**（仅测试便利，不改配对密码学）：Windows 端把二维码 payload 写入 `%LocalAppData%\ClipSync\last-pairing-qr.json`（或 `CLIPSYNC_PAIRING_PAYLOAD_PATH`），`CLIPSYNC_AUTO_APPROVE=1` 跳过批准窗，`CLIPSYNC_SHOW_PAIRING=1` 启动即弹配对窗；Android 端 `MainActivity` intent extras `clipsync.pairing_payload` / `clipsync.pairing_file` / `clipsync.pairing_auto_confirm` / `clipsync.enable_background_sync`。
3. **修复：History 不实时刷新**（联测发现）。Room DAO 增加 Flow 孪生查询（`observeSearchVisible` / settings `observe`），`ClipRepository.observeSearch` / `observeSetting`；`HistoryViewModel` 改为 combine 订阅（查询词随快照携带，过期查询结果不回写搜索框）；`HistoryScreen` 最新 `eventId` 变化时滚回列表顶部。JVM 内存持久化在事务提交后（锁外）发失效信号，测试无需 Room。
4. **修复：连通状态卡在“Windows unreachable”**（联测发现）。会话 READY 只发布在 `SyncController.state`，原 UI 仅在配对/服务快照变化时重读。`SyncStatusProvider` 增加默认 `snapshots()` Flow；`SyncControllerStatusAdapter.snapshots()` 以 orchestrator 快照 × `controllerTicks` 为 key 重挂当前 dialer 的 `state`（FGS↔Activity 交接后自动重订阅，`onActivityControllerAttached` 补发 tick）；`SettingsViewModel` 状态变化只更新连接相关字段，不再把进行中的开关从磁盘盖回去；MainActivity 移除会覆盖实时状态的重复 `refresh()`。
5. **修复：写回环抑制从未被消费**（实机复现：Windows→Android 的入站文本被 Shizuku 监听再捕获成一条 `source_app=shizuku` 的重复行）。`ClipServices.writeCoordinator` 改为进程单例（原先每次调用新建实例，抑制标记跨路径不可见）；新增 `shouldSuppressCapture(text)`（捕获侧只有文本没有 originEventId，按哈希一次性匹配任意在窗内的自家写入：入站应用、History 复制、自测令牌）；MainActivity 捕获回调先查再落库。抑制表访问加锁。
6. **5.7 一键自测**：`ClipboardSelfTest` + 向导“Background self-test”卡片（Test background read / Test background write）。只使用应用生成的随机令牌：写测试经 `ClipboardWriteCoordinator` 写入成功后立即清空剪贴板；读测试先种入令牌再让当前读 backend 读回、按哈希比对后立即清空。种入失败或无 backend 时**不读不清**（绝不触碰用户现有剪贴板）；结果只含状态与稳定错误码（`SELFTEST_NO_READ_BACKEND` / `SELFTEST_SEED_WRITE_FAILED` / `SELFTEST_READ_MISMATCH` / `SELFTEST_READ_EMPTY` / `SELFTEST_CLEAR_FAILED`），不含任何文本；令牌经抑制标记不会被捕获或上传。

## 实机记录（2026-08-17，唯一一行矩阵）

设备：**Redmi Note 11T Pro**（`22041216C` / `xaga`，MediaTek Dimensity 8100），Android 13 / API 33，MIUI 14 `V14.0.5.0.TLOCNXM`。手机 Wi‑Fi `192.168.2.250/23`，PC WLAN `192.168.2.135`（同网段）。

| 能力 | 结果 |
|---|---|
| 安装/启动 | 可用。MIUI USB 安装弹 `INSTALL_FAILED_USER_RESTRICTED`，需手点“继续安装”。 |
| Shizuku 13.6.0 | **UserService 起不来**：MediaTek + MIUI 上 `LoadedApk.makeApplicationInner` NPE（`Application.getProcessName()` 为 null；上游 RikkaApps/Shizuku #1198/#1171）。 |
| Shizuku 13.5.4 (r1049) | **可用**。每次重启后需 `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh` 重新拉起。 |
| Shizuku 事件读取 | **实测可用**：复制事件落 Room（`source_app=shizuku`）。授权路径：Shizuku 应用 → 已授权应用 → ClipSync。 |
| READ_LOGS / ADB 日志模式 | 权限经 adb 授予后 probe 仍 **DEGRADED**（`ADB_LOG_NO_HEALTHY_SIGNAL`）：该 ROM 只输出 `ClipboardService: Denying clipboard access…`，无解析器可匹配的复制信号；解析器按 plan 5.4 宁可不触发。`dumpsys clipboard` 在该 MIUI 上为空。 |
| 悬浮窗 | `appops set … SYSTEM_ALERT_WINDOW allow` 后 probe **READY**；无需可触摸窗口。 |
| 配对 | 经测试钩子（payload 文件 + 自动确认）配对成功；Windows `devices` 表出现 `Xiaomi 22041216C`。 |
| 双向同步 | Share→Windows 与 Windows剪贴板→Android 均 ~1 秒内落对端库（哈希核对，不读正文）；按 Home 退后台后 Windows→Android 仍 ~1 秒。未做正式 P95 统计。 |
| 前台服务 | `isForeground=true`，Home 后存活；MIUI 默认隐藏常驻通知（应用内“notification hidden”提示生效）。 |
| History 实时刷新 | 修复后实测：停在 History 页，Windows 复制 ~1 秒出现在列表顶部；Status 网络卡 **Connected**。 |
| 回环回声 | 修复前实测存在（入站文本被再捕获成重复行）；修复后 JVM 测试固化，**实机复验待下次安装**。 |
| 未测场景 | 锁屏/熄屏轮询、电池优化长期驻留、Wi‑Fi 切换、重启恢复、1.5s/2s P95 验收、5.7 自测按钮实机走查。 |

## plan.md 阶段 5 任务对照

| 任务 | 结论 | 说明 |
|---|---|---|
| 5.1 读/写模式状态机 + `CapabilityReport` 持久化 + 切换事务 | **done**（JVM） | 见 wave 1。 |
| 5.1 配对后向导（逐项权限卡 + 用途/风险/跳过后果） | **done** | 7 张卡独立 probe；READ_LOGS 明示仅 adb。 |
| 5.1 用户选择（首选模式/降级/轮询间隔/自动上行/自动应用） | **done** | 实机上已用 SHIZUKU_EVENT + 自动上行/应用。 |
| 5.1 四个独立状态灯 | **done** | 向导 + Status 页均分开；现在随 `SyncController.state` 实时更新。 |
| 5.2 connectedDevice FGS + 错误分类 + 通知动作 | **done**（JVM+MIUI） | 实机 Home 后存活。 |
| 5.2 BOOT_COMPLETED 门控 + WorkManager 有界健康检查 | **partially** | 开机恢复开关 + receiver 有；WorkManager 健康检查**未实现**。 |
| 5.3 Shizuku 七类错误码 / UserService / listener / linkToDeath | **done**（JVM+MIUI 13.5.4） | 13.6.0 在该机不可用（上游缺陷）。 |
| 5.3 Shizuku 写回退注册进 `ClipboardWriteCoordinator` | **partially** | `ShizukuClipboardWriter` 已实现，**生产装配未接线**（`ClipServices` 只配公开 writer）。 |
| 5.3 写回环抑制 | **done** | 本会话把标记接到捕获侧并单例化协调器；JVM 固化。 |
| 5.3 一键“测试后台读取/写入” | **done**（JVM） | `ClipboardSelfTest` + 向导卡片；随机令牌即测即清；实机走查待下次安装。 |
| 5.4 READ_LOGS 引导 + logcat 事件 + 防抖 + overlay 读正文 | **done**（JVM） | 该 MIUI 无可匹配信号，正确保持 DEGRADED 并可降级。 |
| 5.4 各 ROM 解析 fixture | **partially** | fixture 机制 + 样本在；真实 ROM 只有 MIUI“未匹配”结论。 |
| 5.5 OverlayFocusController / 轮询 / 熄屏停 | **done**（JVM；probe 实机 READY） | 轮询实机长跑未做。 |
| 5.6 统一策略引擎 | **partially** | 暂停/私密在捕获、share、tile 生效；§3.4 完整规则（黑名单/方向/超限策略）仍缺。 |
| 5.6 不可写时入站留收件箱 + 通知手动复制 | **done**（JVM） | 阶段 4 交付，未回归实机。 |
| 5.7 fake backend 单测矩阵 | **done** | 343 用例含模式切换/回滚/哈希去重/回环抑制/自测。 |
| 5.7 overlay Instrumentation 测试 | **not done** | 仍无 instrumentation 目标。 |
| 5.7 Shizuku 可跳过设备测试 | **partially** | 本次为手工实机会话；未自动化成 skipped-aware 测试。 |
| 5.7 四类实体 ROM 完整表 | **partially** | 仅 MIUI 一行（见上表）；AOSP/OneUI/ColorOS 为 `NOT_TESTED`。 |
| 验收：Shizuku P95 ≤1.5s / ADB P95 ≤2s / 轮询 P95 | **NOT_TESTED** | 只有 ~1s 的抽查观测，无 P95 样本量。 |

## 验证（2026-08-17）

- Android `testDebugUnitTest`：**343 / 47 类 / 0 失败 / 0 错误 / 1 skipped**（门控 E2E）；`assembleDebug` 成功。
- Windows `build-windows.ps1 -Configuration Debug`：0 警告 0 错误；Tests **169/169**、App.Tests **33/33**。
- 实机：见上方矩阵；实机验证用的哈希比对脚本未入库（一次性，防误留敏感路径）。

## 已知限制 / 交接给阶段 6

- **Shizuku 写回退未接线**：`ClipServices.writeCoordinator` 只配公开 writer。接线时注意单例语义与自测 `writerKind` 的显示。
- **WorkManager 开机健康检查未实现**（5.2）。
- **§3.4 完整策略引擎未实现**（黑名单、方向、字符/字节规则、超限 `local_only`/`drop`）——阶段 6 有独立黑名单任务，建议合并设计。
- **Instrumentation 缺失**：overlay 窗口生命周期/不可触摸/焦点恢复只有 JVM fake 证据。
- **实机矩阵只有 MIUI**；P95、锁屏、熄屏、断网 30 分钟恢复、1000 次回环压力（阶段 6）都未测。
- **回声修复与自测按钮尚未在实机复验**（改动在本地构建里，装机即可验）。
- 测试钩子（`CLIPSYNC_AUTO_APPROVE` 等）只读环境变量/intent extras，默认关闭；发布打包前审查是否保留。
- 本记录覆盖的 wave 2 + 联测修复 + 自测均**未提交**；提交需用户明确要求。
