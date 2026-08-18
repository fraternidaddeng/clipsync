# ClipSync 第一版 Definition of Done 审计

日期：2026-08-18  
基线：仓库 `D:\paste`，分支 `feature/stage-4`，用户指定最新提交 `5039eec`。  
方法：只读对照 `plan.md` §7 与 §6.1 / §6.2 / §6.3；证据限于源码、测试类/文件、阶段变更记录、安全/迁移/分发文档、协议 fixtures 与脚本。**未运行** Gradle / `dotnet`（机上另有实机长跑）。  
已知可引用的当场事实（编排端，2026-08-18）：Android `testDebugUnitTest` 411/0/1 skipped；Windows Tests 191 + App.Tests 33，0 警告；detekt / ktlint 基线通过；实机轮已验证配对、双向同步约 1 s、回声抑制、自测按钮（`SHIZUKU_EVENT` 读 OK、`PUBLIC_API` 写 OK）、Windows 进程重启与 Wi‑Fi 开关故障注入恰好一次、MIUI 自启动授权后的开机全链路；已签名 release APK 与 keystore 指纹已记录；**断网 30 分钟浸泡测试进行中**。

结论只表示仓库能证明到哪一步。JVM / xUnit 绿不写成实机；模拟器不写成 ROM 覆盖。`docs/device-validation-matrix.md` 仍停在阶段 0「尚未提供任何实体设备」，与阶段 5/6 变更记录不一致，**不以该矩阵为实机证据**。

## 结论定义

| 结论 | 含义 |
|---|---|
| **DONE** | 有实现 + 自动化测试。括号 `(JVM)` / `(xUnit)` 表示未上实体机。 |
| **DEVICE-VERIFIED** | 在上述之外，阶段 5/6 变更记录写明已在 Redmi Note 11T Pro / MIUI 14 / API 33 上核对（只对 hash，不读正文）。 |
| **PARTIAL** | 代码或部分路径已证，缺口写在证据栏。 |
| **IN-FLIGHT** | 编排端声明该项正在实机执行，仓库尚无完成记录。 |
| **NOT_TESTED** | 需要硬件或人工；写明缺什么。 |
| **N/A** | 计划允许不做或该项不适用于当前设计，附理由。 |
| **NO_EVIDENCE** | 源码、测试、变更记录均未覆盖该场景（比 NOT_TESTED 更严重）。 |

---

## 7. Definition of Done

| 条目 | 结论 | 证据 |
|---|---|---|
| Windows 配对后可以完全后台捕获纯文本剪贴板 | **DEVICE-VERIFIED** | 实现：`windows/ClipSync.App` 的 `AddClipboardFormatListener` 零尺寸 `WS_POPUP`（`docs/stage-1-change-log.md`）。测试：`MessageOnlyClipboardWindowSmokeTests`、`Win32ClipboardAdapterTests`、`ClipboardDataAccessorTests`、`scripts/run-windows-stage1-smoke.ps1`（主窗口句柄 0）。实机：阶段 6 双向同步与 Windows 进程重启后首次复制即达（`docs/stage-6-change-log.md` 实机表）。未单独测锁屏/睡眠期间的捕获。 |
| 数据只在已配对设备之间直连传输，不依赖账号、云端后端或公共 Relay | **DONE** (xUnit) | 范围：`docs/product-scope.md`、`docs/adr/0001-direct-p2p.md`。拒绝未配对：`PeerSyncIntegrationTests.UnknownDeviceIsRejected` / `WrongSecretFailsAuthAndRepeatsGetThrottled`。无账号/云/Relay 实现；`THIRD_PARTY_NOTICES.md` 声明不引入。架构项，无「云端」可测。 |
| Windows 和 Android 都先本地落库，再通过来源序号向 peer 交换缺失事件 | **DEVICE-VERIFIED** | 落库+序号：`ClipRepositoryTest`（原子 `origin_seq` + outbox）、`SqliteSyncStoreTests`。缺口交换：`OriginReceiveStateTests`、`SyncRangeMathTest`、`PeerSyncIntegrationTests.TwoWayConvergenceDeliversEverythingExactlyOnce` / `OfflineCapturesArriveAfterReconnectWithoutDuplicates`。跨端：`CrossClientSyncE2eTest`（门控，套件 1 skipped）+ `scripts/run-e2e-stage4.ps1`（`docs/stage-4-change-log.md` E2E-PASS）。实机双向恰好一次（阶段 5/6 记录）。 |
| Windows 到 Android 的在线同步不要求打开主界面 | **DEVICE-VERIFIED** | FGS：`ClipboardSyncService` + `ServiceOrchestratorTest`。实机：阶段 5 Home 后 Win→Android 仍约 1 s；阶段 6 授 MIUI 自启动后 **未打开应用**，`BOOT_COMPLETED` → receiver → FGS，Win→Android 约 1 s 恰好一次（`docs/stage-6-change-log.md`）。 |
| Android 可以从通知、历史或分享面板完成复制/发送；无任何特殊权限时该路径仍完整可用 | **PARTIAL** | 分享：`ShareCaptureHelperTest` + 实机 Share→Windows（`docs/stage-5-change-log.md`）。历史复制：`HistoryViewModelTest`；实机见过 History 实时刷新，未单独走「点复制」。通知复制：`InboundClipApplierTest`、`InboundNotifyPolicyTest`（含拒绝 `POST_NOTIFICATIONS` 不崩），**无实机拒绝通知权限记录**。磁贴：`TileClipboardSenderTest` 仅 JVM。无特权路径代码在，产品化验收未齐。 |
| `SHIZUKU_EVENT` / `ADB_LOG_OVERLAY` / `OVERLAY_POLLING` 为 `READY` 时，复制后无需打开 App 即可落库并同步到 Windows | **PARTIAL** | **`SHIZUKU_EVENT`：DEVICE-VERIFIED**（阶段 5/6：后台读、自测 `OK · SHIZUKU_EVENT`、开机后未开 App 仍同步）。`ADB_LOG_OVERLAY`：该 MIUI 授予 `READ_LOGS` 后仍 `DEGRADED` / `ADB_LOG_NO_HEALTHY_SIGNAL`（正确不标 READY）；从未在 READY 下测上行。`OVERLAY_POLLING`：`appops` 后 probe **READY**（阶段 5），**轮询捕获+同步未做**。JVM：`ShizukuClipboardBackendTest`、`AdbLogOverlayBackendTest`、`OverlayPollingBackendTest`。 |
| 公开 writer 或已授权写回退为 `READY` 且 `auto_apply_remote` 开启时自动写入系统剪贴板；不可写时进收件箱并提供通知操作 | **PARTIAL** | 公开写：**DEVICE-VERIFIED**（自测 `OK · PUBLIC_API`；Win→Android 自动落库且回声抑制 0 条 `shizuku` 行）。Shizuku 写回退：已接线（`MainActivity` → `writeFallbackProvider`），`ClipServicesWriteCoordinatorTest` 固化「仅 READY 才回退」，**实机未走到公开写失败**。不可写→收件箱+通知：`InboundClipApplierTest` / `InboundNotifyPolicyTest` 仅 JVM。 |
| 每种已声明支持的 Android 模式都有设备验证记录、P95 延迟、权限前提和降级结果 | **PARTIAL** | 权限前提与降级文案：`docs/distribution.md`、`docs/android-background-clipboard.md`。实机只有 **一种 ROM / 一个模式主链路**（MIUI 14 + `SHIZUKU_EVENT`），抽查约 1 s，**无 P95 样本量**（阶段 5/6 均写明）。`ADB_LOG_OVERLAY` 仅有「无匹配信号」降级；`OVERLAY_POLLING` 仅 probe。官方矩阵 `docs/device-validation-matrix.md` 未回填。不能把「理论可行」标 READY——仓库也没标。 |
| Android 分享面板发送的文本，在 `auto_apply_remote` 开启时自动出现在 Windows 系统剪贴板 | **DEVICE-VERIFIED** | `ShareReceiverActivity` / `ShareCaptureHelper` + `ShareCaptureHelperTest`。实机：Share→Windows 约 1 s、恰好一次（`docs/stage-5-change-log.md`）。Windows `auto_apply_remote` 默认开（`docs/stage-2-change-log.md`，`App.xaml.cs`）。 |
| 断线补同步不丢失、不重复、不产生回环 | **PARTIAL** | 短断：**DEVICE-VERIFIED**——Wi‑Fi 断开恢复 11 s 恰好一次；Windows 杀进程再起恰好一次（阶段 6）。JVM：`PeerSyncIntegrationTests.OfflineCapturesArriveAfterReconnectWithoutDuplicates`、`LoopSuppressionStressTest`（1000 次回环）、`AckIdempotencyTest`、`ModeSwitchIdempotencyTest`。**断网 30 分钟浸泡：IN-FLIGHT**（编排端进行中，仓库无完成记录）。 |
| 历史搜索、删除、过期和暂停行为稳定 | **PARTIAL** | 搜索/删除/清空/暂停：`ClipRepositoryTest`、`HistoryViewModelTest`、`SettingsViewModelTest`、`CapturePolicyTest`、`SqliteClipboardEventStoreTests.SearchIsLiteralParameterizedAndExcludesSoftDeletedRows` / `ClearSoftDeletesEveryVisibleEntry`、`ClipboardCapturePolicyTests.EvaluateRejectsPausedPrivateAndBlacklistedSources`。Windows 过期：`SqliteClipboardEventStoreTests` 保留策略（`maximumEntries` + `maximumAge`）+ UI `RetentionDays`。**Android 无过期清理作业**（仅有 `expires_at` 列与 `TerminalReason.EXPIRED` 常量，`ClipRepository` 不按龄 purge）。实机未走搜索/删除/过期/暂停。 |
| 设备撤销后无法继续连接 | **DONE** (xUnit) | `PeerSyncIntegrationTests.RevocationDropsLiveSessionAndBlocksNewOnes`、`TrustEpochMismatchIsRejected`；`PairingHttpTests` 重配对使旧凭证 `TRUST_EPOCH_MISMATCH`（`docs/stage-3-change-log.md`）。Android 会话内重读 `PairingStore` epoch（`docs/stage-4-change-log.md`）。**无实机「撤销后再连」记录。** |
| 令牌、证书和日志不泄露剪贴板正文 | **DONE** | `docs/stage-6-security-audit.md` D 节 grep 无命中；`PeerSyncIntegrationTests.LogsNeverContainContentSecretsOrProofs`；配对 `PairingLogsNeverContainTokensOrSecrets`（阶段 3）。自测结果只含状态/错误码（`ClipboardSelfTest` / `ClipboardSelfTestTest`）。导出 JSONL **有意含明文正文**，文档头警告（`docs/stage-6-migration-export.md`），不是日志。 |
| Windows 和 Android 都有自动化测试及可重复构建脚本 | **DONE** | 当场数字：Android 411/0/1 skip；Windows 191 + 33。脚本：`scripts/build-windows.ps1`、`scripts/build-android.ps1`、`scripts/validate-protocol.ps1`、`scripts/package-release.ps1`、`scripts/static-analysis.ps1`、`scripts/run-e2e-stage4.ps1`。无 instrumentation 目标（阶段 5 记录）。 |
| 便携 Windows 包和 signed APK 可以在目标设备安装运行 | **PARTIAL** | 出包：`scripts/package-release.ps1`，`docs/stage-7-change-log.md`：win-x64 ZIP 已从临时目录启动（`listener_started`）；signed APK `ClipSync-Android-0.1.0.apk`，证书 SHA-256 已写入 notes。**测试机现装 debug 签名包**；release 签名不同，不能覆盖安装。10 分钟全新 Windows 计时演练未做（阶段 7「待人工」）。 |
| 所有不做的功能仍然保持在明确的范围之外 | **DONE** | 冻结清单：`docs/product-scope.md`「明确不做」。`THIRD_PARTY_NOTICES.md`：Syzygy grep 仅命中该文件与 `plan.md`。无 iOS/macOS、账号、云库、公共 Relay、文件传输、默认遥测。SQLCipher 评估后明确不阻塞 MVP（`docs/stage-6-migration-export.md` §3）。 |

**§7 计数**：DEVICE-VERIFIED 4 · DONE 5 · PARTIAL 7 · IN-FLIGHT 0（30 分钟浸泡记在「断线补同步」证据栏）· NOT_TESTED 0 · N/A 0 · NO_EVIDENCE 0（本表条目均能指到实现或记录；缺口在 PARTIAL 内）。

---

## 6.1 Windows

| 条目 | 结论 | 证据 |
|---|---|---|
| Windows 10 22H2 | **NO_EVIDENCE** | `docs/product-scope.md` 写「Win10 22H2+」；阶段 2 为 Win10 schannel 允许 TLS 1.2（`docs/stage-2-change-log.md`）。**仓库没有任何 22H2 安装、版本号或实测记录。** |
| Windows 11 | **NO_EVIDENCE** | 开发/联测用过本机 Windows（阶段 5 记 PC WLAN `192.168.2.135`），**未记录 SKU/内部版本**。不能把「能编译 win-x64」写成 Win11 矩阵通过。 |
| x64 | **DONE** | `scripts/package-release.ps1` `dotnet publish` self-contained `win-x64`；产物 `ClipSync-Windows-0.1.0-win-x64.zip`（`docs/stage-7-change-log.md`）。无 ARM64 承诺。 |
| 长文本、中文、Emoji、换行、空文本 | **DONE** (xUnit) | 中文/换行/Emoji：`ClipboardCapturePolicyTests.EvaluatePreservesUnicodeAndLineEndings`、`ClipboardDataAccessorTests.ReadRetriesBusyClipboardAndPreservesUnicodeText`、`ClipRepositoryTest`「unicode and line endings」。空文本：`EvaluateRejectsMissingOrEmptyText`。长文本：恰好 1 MiB 接受、+1 字节拒绝不截断。实机同步只核 hash，**未按字符类逐项记录。** |
| 剪贴板被其他进程占用 | **DONE** (xUnit) | `ClipboardDataAccessorTests.ReadRetriesBusyClipboardAndPreservesUnicodeText`。无实机「另一进程锁剪贴板」。 |
| 复制应用快速退出 | **NO_EVIDENCE** | 无对应测试名、无变更记录、无源码对「源进程已退出」的专门路径（仅有占用重试与序列号去重）。 |
| 睡眠/唤醒 | **NOT_TESTED** | 阶段 6 明确「Windows 睡眠/唤醒未测」。仓库无电源事件/`WM_POWERBROADCAST` 处理。需人工：睡眠 → 唤醒 → 复制一次，断言恰好一条且库未损坏。 |
| 锁屏/解锁 | **NO_EVIDENCE** | 无 `WTS` / 会话锁定监听，无测试。与 Android 锁屏不是同一条实现。 |
| 普通用户运行 | **PARTIAL** | 便携 ZIP 声明免管理员（`docs/distribution.md`、`docs/stage-7-change-log.md` 临时目录启动成功）。无「标准用户 / 非管理员账户」正式矩阵行。 |
| 开机启动 | **PARTIAL** | `scripts/install-windows.ps1` `-EnableAutostart` 写 `HKCU\...\Run`（免管理员）。**未做 Windows 重启后托盘自行拉起的实测。** 计划任务方案未选，属 N/A（阶段 7：便携 ZIP + Run 键）。 |
| 托盘退出和重新启动 | **PARTIAL** | 托盘：`App.xaml.cs` `TaskbarIcon`；阶段 1 smoke 断言启动无主窗口。**Windows 进程杀掉再启动：DEVICE-VERIFIED**（阶段 6）。托盘菜单「退出」本身无自动化测试。 |

**§6.1 计数**：DONE 3 · DEVICE-VERIFIED 0（进程重启并入托盘行）· PARTIAL 3 · NOT_TESTED 1 · NO_EVIDENCE 4 · N/A 0。

---

## 6.2 Android

设备行（全部对照同一台机，除非标明）：**Redmi Note 11T Pro** `22041216C`，Android 13 / API 33，MIUI 14 `V14.0.5.0.TLOCNXM`，Shizuku 13.5.4（13.6.0 不可用）。见 `docs/stage-5-change-log.md`、`docs/stage-6-change-log.md`。

| 条目 | 结论 | 证据 |
|---|---|---|
| Android 10 | **NO_EVIDENCE** | 无该 API 实体机或 ROM 记录。`minSdk 29` 只证明编译目标。 |
| Android 12 | **NO_EVIDENCE** | 同上。 |
| Android 13 | **DEVICE-VERIFIED** | 上述 MIUI 14 / API 33。覆盖的是这一台，不是「所有 Android 13」。 |
| Android 14 / 更高 | **NO_EVIDENCE** | `targetSdk 35` ≠ 实机。无 API 34+ 设备记录。 |
| AOSP / Pixel | **NO_EVIDENCE** | `docs/device-validation-matrix.md` D1 仍为待提供。阶段 5/6 未出现 Pixel。 |
| OneUI | **NO_EVIDENCE** | 矩阵 D2 空。 |
| MIUI / HyperOS | **DEVICE-VERIFIED**（单机） | 仅 MIUI 14 一台；不是 HyperOS，不是「MIUI 族」。 |
| ColorOS / OriginOS | **NO_EVIDENCE** | 矩阵 D4 空。阶段 0 已提醒 OriginOS 日志可能不可用，从未实测。 |
| 干净安装 | **DEVICE-VERIFIED** | 阶段 5 侧载 debug APK；MIUI 需手点「继续安装」（`INSTALL_FAILED_USER_RESTRICTED`）。 |
| 首次配对 | **DEVICE-VERIFIED** | 阶段 5：测试钩子 payload + 自动确认，Windows `devices` 出现 `Xiaomi 22041216C`。钩子默认关闭（`CLIPSYNC_AUTO_APPROVE`）。扫码 UI 无 instrumentation（阶段 3）。 |
| Shizuku 启动 / 授权 | **DEVICE-VERIFIED** | 13.5.4 授权后事件落库 `source_app=shizuku`；重启后授权保留、daemon 需 `start.sh`（阶段 5/6）。`scripts/android-bootstrap.ps1` 只打印命令。 |
| Shizuku 断连 | **PARTIAL** | 重启后 daemon 消失已观察。无「运行中断开 UserService / binderDied 后自动重绑」的实机记录。JVM：`ShizukuClipboardBackendTest`。 |
| adb `READ_LOGS` 授予 | **DEVICE-VERIFIED**（降级） | 授予后 probe 仍 `DEGRADED` / `ADB_LOG_NO_HEALTHY_SIGNAL`：该 ROM 只有 `Denying clipboard access…`，解析器按设计不触发（阶段 5）。这是有效设备结论，不是 READY。 |
| adb `READ_LOGS` 撤销 | **NOT_TESTED** | JVM：`AdbLogOverlayBackendTest`、`AdbLogMinimizationInvariantTest`。实机未做 revoke 后 10 s 内状态翻转。 |
| 悬浮窗授予 | **PARTIAL** | 阶段 5：`SYSTEM_ALERT_WINDOW` 后 overlay probe **READY**，无需可触摸窗口。**未用该模式做复制→同步。** |
| 悬浮窗撤销 | **NOT_TESTED** | JVM：`OverlayPollingBackendTest`「permission revoke on tick…」、`OverlayLifecycleInvariantTest`。实机未撤销。 |
| 开机恢复 | **DEVICE-VERIFIED** | 未授自启动：70 s 无进程，打开应用即恢复。授 MIUI 自启动后：FGS 自行拉起，未开 App，同步恰好一次。WorkManager 失败兜底仅 JVM（`BootHealthCheckTest`），真机 FGS 直接成功未触发。 |
| 屏幕关闭 | **NOT_TESTED** | JVM：`OverlayPollingBackendTest`「health … degraded when screen is off」/ `canPollNow`。实机阶段 5 列为未测。 |
| 锁屏 / 解锁 | **NOT_TESTED** | 同上。无锁屏策略字段记录（矩阵要求的「锁屏策略」为空）。 |
| 网络切换 | **DEVICE-VERIFIED** | 阶段 6：`svc wifi disable/enable`，恢复后 11 s 重连，恰好一次。这是开关 Wi‑Fi，不是换 SSID / 蜂窝。 |
| 进程被杀 | **NOT_TESTED** | Android 侧无「强制停止 / 划掉最近任务」单独记录。整机重启更强，但不能替代杀进程后磁盘与 FGS「需要恢复」文案。Windows 杀进程已测。`ServiceOrchestratorTest` 为 JVM。 |
| 通知权限拒绝 | **NOT_TESTED** | 代码：`InboundClipNotifier` / `NotificationPermission`；`InboundNotifyPolicyTest`「permission denial does not throw」。向导可跳过通知。**无实机拒绝后仍能看历史的记录。** |
| 电池优化开启 / 关闭 | **NOT_TESTED** | 向导有电池卡（`WizardViewModelTest` `batteryMayKillProcess`）。阶段 5：「电池优化长期驻留」未测。 |
| `SHIZUKU_EVENT`：上行、入站、远端自动写入、回环抑制、降级 | **DEVICE-VERIFIED**（主链路） | 上行+入站+公开写+回环：阶段 6 实机表。降级：13.6.0 起不来已记录；未测「运行中撤授权、仅 FGS 存活」——服务侧健康循环缺失（`docs/stage-6-security-audit.md` F 节）。 |
| `ADB_LOG_OVERLAY`：同上五项 | **PARTIAL** | 实现+JVM：`AdbLogOverlayBackendTest`、`ClipboardLogParsersTest`、`LogcatClipboardEventReaderTest`。实机：无法 READY，五项功能路径未走。fixtures 在 `android/app/src/test/resources/`。 |
| `OVERLAY_POLLING`：同上五项 | **PARTIAL** | 实现+JVM：`OverlayPollingBackendTest`、`OverlayFocusControllerTest`、`OverlayLifecycleInvariantTest`。实机：仅 probe READY。无 instrumentation（阶段 5：overlay 生命周期只有 fake）。 |
| `FOREGROUND_ONLY`：同上五项 | **PARTIAL** | `ForegroundClipboardBackendTest`。分享路径实机已走（上行）。磁贴、前台读、该模式下的入站/回环未单独记。无特殊权限时不应被自动模式阻塞——策略在 `CapturePolicy`，JVM 有，实机未刻意关 Shizuku 回归。 |
| 分享面板 | **DEVICE-VERIFIED** | 见 §7 分享行。 |
| 锁屏和息屏：不能安全读写时自动模式暂停、入站留收件箱；解锁后恢复；不得把系统限制伪装成已自动应用 | **NOT_TESTED** | 设计：`docs/android-background-clipboard.md`、`OverlayPollingBackend` 熄屏停轮询。**无锁屏/息屏实机记录，无「伪装已应用」反例测试。** |

**§6.2 计数**：DEVICE-VERIFIED 10 · DONE 0 · PARTIAL 7 · NOT_TESTED 8 · NO_EVIDENCE 6 · N/A 0。

---

## 6.3 网络和数据

| 条目 | 结论 | 证据 |
|---|---|---|
| 同一 Wi-Fi | **DEVICE-VERIFIED** | 阶段 5：手机 `192.168.2.250/23`，PC `192.168.2.135`，双向约 1 s。 |
| 不同网段 | **NO_EVIDENCE** | 无跨网段、无静态路由/端口转发实测。协议本身不假设同网段，但矩阵要求未做。 |
| Tailscale / WireGuard | **PARTIAL** | 设置可加额外绑定地址（`PeerSyncHost`，`docs/stage-2-change-log.md`）。**无 Tailscale/WireGuard 接口上的配对或同步记录。** |
| 无网络 | **IN-FLIGHT** | 短中断已在 Wi‑Fi 开关中 DEVICE-VERIFIED。计划验收「断网 30 分钟后恢复且只出现一次」：阶段 6 标 NOT_TESTED；**编排端 2026-08-18 浸泡进行中**，本文件不以完成论。 |
| 重复连接 | **PARTIAL** | `PeerServer.MaxConcurrentSessions`（默认 8，超限 503）；重连会话：`SyncSessionEngineTest`、阶段 4 修 `MESSAGE_OUT_OF_ORDER` 后 `run-e2e-stage4.ps1` 两会话干净结束。无「双拨号抢同一设备」实机。 |
| 乱序消息 | **DONE** (JVM/xUnit) | `OriginReceiveStateTests.AcceptOutOfOrderSequenceDoesNotAdvanceCursorPastGap`；`ClipRepositoryTest`「seq 12 with 11 missing」；`SqliteSyncStoreTests.RemoteEventOutOfOrderKeepsContiguousCursorBelowGap`；`SyncSessionEngineTest`「payload without a matching announce is MESSAGE_OUT_OF_ORDER」。无实机乱序注入。 |
| 任一 peer 重启 | **DEVICE-VERIFIED** | Windows 进程重启；Android 整机重启+开机恢复（阶段 6）。两端均恰好一次。 |
| 客户端强制杀进程 | **PARTIAL** | Windows 杀进程：**DEVICE-VERIFIED**。Android 强制停止：**NOT_TESTED**（见 6.2）。存储重开：`SqliteClipboardEventStoreTests` / `ClipRepositoryTest`「survive reopening」为进程内重开，不是 OS 杀进程后的磁盘文件。 |
| 1,000 条历史的查询和清理 | **PARTIAL** | `LoopSuppressionStressTest` 末态 `search("").size==2000`（查询 2000 行，约 1.3 s，JVM）。Windows 清理测试只用 **2 条** 上限（`SqliteClipboardEventStoreTests` 保留策略）。**没有「先灌 1000 条再 purge」的用例。** |
| 10,000 条历史的查询和清理 | **NO_EVIDENCE** | 无 10k 夹具、无测试、无变更记录。 |
| 非法 JSON | **DONE** | `protocol/v1/fixtures/invalid/`（含 `malformed.json` 等）+ `scripts/validate-protocol.ps1`；`ProtocolReaderTests`、`ProtocolFixtureTests`、`ProtocolFixtureTest`（Kotlin）。阶段 6：12 valid + 15 invalid 协议、5 valid + 7 invalid 配对。 |
| 过大帧 | **DONE** (xUnit) | `PeerSyncIntegrationTests.OversizedTextFrameIsRejectedWithPayloadTooLarge`；fixture `oversized_payload.json`；阶段 2 帧上限 7 MiB。 |
| 错误令牌 | **DONE** (xUnit) | `PairingServiceTests`（错误猜测不烧票、烧票重试、过期 410）；`PairingHttpTests`；Android `PairingConfirmClientTest` 403 Denied。 |
| 错误证书 | **DONE** (xUnit) | `PeerSyncIntegrationTests.WrongCertificatePinBlocksTheConnection`；`PairingConfirmClientTest` pin 不匹配则 **server 零请求**。 |
| 过期设备 | **DONE** (xUnit) | 配对票过期：`PairingServiceTests` 410 `PAIRING_TOKEN_EXPIRED`。已配对设备「过期」在实现里是撤销 / epoch：`RevocationDropsLiveSessionAndBlocksNewOnes`、`TrustEpochMismatchIsRejected`。无单独的「设备租约到期」字段。 |

**§6.3 计数**：DEVICE-VERIFIED 2 · DONE 5 · PARTIAL 4 · IN-FLIGHT 1 · NOT_TESTED 0 · NO_EVIDENCE 3 · N/A 0。

---

## 剩余缺口（按风险）

1. **多 ROM / 多 API 矩阵几乎空白（最高产品风险）**  
   AOSP/Pixel、OneUI、ColorOS/OriginOS、Android 10/12/14 **NO_EVIDENCE**。已声明四档模式里，只有 MIUI 14 上的 `SHIZUKU_EVENT` 能叫 DEVICE-VERIFIED。`docs/device-validation-matrix.md` 未回填，容易让后续读者误以为「仍零设备」或反过来误以为矩阵已过。P95 正式统计不存在（只有约 1 s 抽查）。

2. **Windows 电源与会话（睡眠/唤醒、锁屏/解锁）**  
   睡眠：NOT_TESTED，且无电源事件处理。锁屏：连代码痕迹都没有（NO_EVIDENCE）。个人日用里这是最容易「早上打开发现丢/重」的路径。

3. **断网 30 分钟浸泡 — IN-FLIGHT**  
   短 Wi‑Fi 开关已恰好一次。计划阶段 6 验收「30 分钟后全部未过期事件到达且只一次」尚未落盘。本审计不以进行中的测试预填通过。

4. **服务侧健康循环缺失**  
   `ClipboardHealthLoop` 只在 `MainActivity` resumed 时跑（`MainActivityOverlayHealthWiringTest`、`ClipboardHealthLoopTest`）。仅 FGS 存活时撤销 Shizuku / overlay / `READ_LOGS`，要等下次打开界面才降级（`docs/stage-6-security-audit.md` 收口段、阶段 6 变更记录）。这直接削弱 §7「READY 时后台自动」与阶段 6 验收「一个健康周期内更新」。

5. **写路径与模式覆盖不对称**  
   公开写已实机；Shizuku 写回退从未在「公开写失败」下实机触发。`OVERLAY_POLLING` 未做真正同步；`ADB_LOG_OVERLAY` 在唯一设备上不能 READY。锁屏/息屏「暂停自动模式、入站留收件箱、禁止伪装已应用」无实机。Android 杀进程、通知拒绝、电池优化长期驻留均 NOT_TESTED。

其余（风险较低，但仍是计划债）：

- **真实 Room `Migration(1,2)` / Windows v3 步骤未写**；现有是 `VERSION=1` + 空 `MIGRATIONS` + `RoomSchemaContractTest` + Windows `PRAGMA user_version` seam（`docs/stage-6-migration-export.md` 清单仍大量未勾）。升级不丢历史只有「没开破坏性回退」这一层保护。
- **SQLCipher 已评估、明确延期**（`docs/stage-6-migration-export.md` §3）——计划允许，不阻塞 MVP；落地库仍明文。
- **导出**：JSONL 编码器有测（`ClipExportTest`、`ClipboardExportTests`）；SAF / 保存对话框、导入、tombstone 字段缺口未做。
- **10,000 条历史查询/清理：NO_EVIDENCE**；1,000 条只有回环压测带出的 2000 行 `search`，没有清理夹具。
- **不同网段、Tailscale/WireGuard 真连通：无实测。**
- **复制应用快速退出、Windows 10 22H2 / 未记录的 Win11 SKU：NO_EVIDENCE。**
- **signed APK 未装上当前测试机**（debug 与 release 签名不同，覆盖安装会丢手机侧历史）。10 分钟全新 Windows 计时未做。
- **无 overlay Instrumentation**；静态分析基线庞大（detekt 249 / ktlint 1822）；SQLite CVE-2025-6965 已接受（不可达，仍在扫描里）。

---

## 无证据条目（最优先补）

这些计划句在仓库里找不到实现痕迹、测试名或变更记录中的一次尝试：

| 条目 | 所属 |
|---|---|
| Windows 10 22H2 实测 | §6.1 |
| Windows 11 SKU/版本号记录 | §6.1 |
| 复制应用快速退出 | §6.1 |
| Windows 锁屏/解锁（含会话事件代码） | §6.1 |
| Android 10 / 12 / 14 实体机 | §6.2 |
| AOSP/Pixel、OneUI、ColorOS/OriginOS | §6.2 |
| 不同网段同步 | §6.3 |
| 10,000 条历史查询与清理 | §6.3 |
| 正式 P95 样本（n、P50/P95、失败数） | §7 模式验收 + §6.2 |
| Overlay Instrumentation 测试目标 | 阶段 5.7，支撑 §6.2 overlay |
| Android 按龄过期清理作业 | §7 历史过期 |
| 仅 FGS 存活时的健康循环 | 阶段 6 验收，支撑 §7 后台 READY |

Windows 睡眠/唤醒有「未测」记录，故标 NOT_TESTED 而非 NO_EVIDENCE；实现侧同样没有电源处理，补测时大概率还要补代码。

---

## 审计员备注

第一版 **代码面** 的核心承诺（配对、先落库再补洞、MIUI+Shizuku 双向约 1 s、回声抑制、开机自启、便携包与签名 APK、测试与脚本）有证据。  
第一版 **计划字面** 的 DoD（四 ROM、四模式 READY 实测、P95、30 分钟断网、Win 睡眠/锁屏）没有齐。把当前仓库称为「阶段 0–7 代码完成」可以；称为「plan.md §7 已全部满足」不可以。
