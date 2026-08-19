# 阶段 8 变更记录

日期：2026-08-19（内置特权宿主）/ 2026-08-18

## 内置特权宿主（2026-08-19）

`SHIZUKU_EVENT` 不再要求安装官方 Shizuku 应用。ClipSync APK 自带 `clipsync_priv_server`（`PrivilegedHostService`）：用户用已是 shell 的 adb/root 执行本包 `start.sh` 后，宿主把 binder 推进 `ClipSyncShizukuProvider`，再孵化 `:clipsync-clipboard`。官方 Shizuku 若已在运行仍作回退。未实现 `newProcess` / `transactRemote` / rish。向导改为「重新检查 / 授权特权宿主」；`android-bootstrap.ps1` 只打印本包启动命令。UserService `destroy` 事务码改为官方约定 `16777115`。本机未再跑实体 ROM 验证。

日期：2026-08-18
状态：**post-audit wave 已合入；MIUI 14 / `SHIZUKU_EVENT` 单机实机通过本日清单。** 模拟器矩阵 **进行中，不标完成**。本记录覆盖 `f847281`、`fb9347b`、`abf3ef3`、`8735345`、`1ef53ab`、`eb07a9f`、`528cf17`，以及后补的 `02ec63c`（Modern Standby Win32 回调）。阶段 7 文档写完之后落地，不改阶段 0–7 合同。

## 发布

**0.2.0**（versionCode 2）已打包并签名：`releases/0.2.0/`（Windows zip + 签名 APK + SHA-256 校验和 + 中文 README），证书指纹与阶段 7 一致（`1A:D0:2D:4F…FD:BA:32:DD`）。0.2.0 实机（MIUI 14）冒烟：中文配对页、状态卡「Shizuku 就绪」、双向同步、来源标签、单一 UserService 全部通过。重装后曾观察到系统在 FGS 粘性重启时两次 `handleBindApplication` 资源竞态崩溃（发生于任何应用代码之前，框架层短暂现象，第三次自愈）。

## 阶段目标

把审计里已经点名、且本日实际合入的缺口收口：Android 按龄清理、双端全文详情与 JSONL 导出入口、进程级捕获/同步、简体中文、健康循环恢复、Windows 睡眠/锁屏感知、以及一批 UI 打磨。不把进行中的工作写成已交付。

## 交付内容

### 功能缺口（`f847281`）

- **Android 历史保留 purge**：`RetentionPolicy` 1–3650 天、默认 30；`ClipSyncApplication` 主进程启动时跑一次，之后每 6 h（`RETENTION_PURGE_INTERVAL_MS`）。`purgeExpired` 硬删过期 live clip 与 tombstone，**pending outbox 行不删**（`ClipRepository` / `OutboxDao` `NOT IN (… state = 'pending')`）。`:clipsync-clipboard` 宿主进程不建 Room、不跑循环。
- **全文详情**：Android `HistoryDetailDialog`（复制 / 删除 / 关闭，正文可选中滚动）；Windows `DetailWindow`。
- **JSONL 导出入口**：Android SAF `CreateDocument("application/x-ndjson")`；Windows 保存对话框。编码器与明文警告仍走阶段 6 的 `ClipExport` / `ClipboardExport`（导入、tombstone 字段仍缺）。

### A 级重构：进程级捕获（`fb9347b`）

- 捕获栈从 `MainActivity` 迁到进程级 `ClipboardCaptureManager` / `ClipboardCaptureRuntime`。Activity 销毁不再停捕获。
- `MainActivity` 与 `ClipboardSyncService` 都会 `ensureStarted`：开机只拉起 FGS、用户从未打开界面时也能捕获。
- 向导选择热更新；结构性改动（降级策略 / overlay 同意 / 轮询间隔）**600 ms debounce** 后重建，避免拖滑条反复重绑 Shizuku。
- Shizuku `ClipboardUserService` 在 app callback binder 死亡时退出，避免重装后孤儿进程堆积。`ClipSyncApplication` 用 `getProcessName() != packageName` 挡住 UserService 宿主里的 Room/循环。

### 简体中文（`abf3ef3`）

- Android `values-zh-rCN/strings.xml`：**160** 条 `<string>`（与默认 `values/` 对齐）。
- Windows 文案集中在 `ClipSync.App/Strings.cs`（**66** 个 `const string`），XAML 经 `x:Static` 引用。Windows 端目前就是简体中文，不是英文本地化包。

### 健康循环恢复（`8735345`）

- `ClipboardAccessCoordinator.checkHealth`：parked（例如进程启动时 Shizuku 未就绪）时重新 `selectAndStart`；已落在回退模式时，请求模式 probe 到 `READY` 则回升。
- `USER_SERVICE_VERSION` 调到 **2**（配合孤儿清理，避免 Shizuku 复用旧 version=1 进程）。
- 健康循环现挂在 `ClipboardCaptureManager` 上（10 s），不再只在 Activity resume 时转。

### B 级重构：单一 SyncController（`1ef53ab`）

- 进程级唯一 `SyncController`，放在 `ClipboardSyncRuntime`。`ControllerOwner` / `ControllerHandover` / `controllerTicks` 已删除。
- 后台同步开关不再拆掉控制器；开关只影响 FGS / `wantedRunning`。Activity 与 Service 操作同一实例。
- `PairingStore` 是 peer 身份的唯一真相源。捕获直接读它；Room `SETTING_PAIRED_PEER_ID` 镜像只在进程启动时对账（`ClipSyncApplication`）。

### Windows 睡眠 / 锁屏（`eb07a9f`）

- `SessionPowerMonitor` + `SessionPowerCoordinator`：`SystemEvents.PowerModeChanged`（suspend 拆 peer 会话；resume 经 `PeerSyncHost.NudgeReconnect` 重播 discovery 并刷新状态）与 `SessionSwitch`（`session_lock` / `session_unlock` 诊断；锁/解锁对捕获为 no-op）。
- 诊断标签：`power_suspend` / `power_resume` / `session_lock` / `session_unlock`。
- 后补 `02ec63c`：`Win32SuspendResumeNotificationSource`（`PowerRegisterSuspendResumeNotification`，DEVICE_NOTIFY_SUBSCRIBE_CALLBACK）与 SystemEvents 源并联；「已挂起」状态位保证每次逻辑转换只动作一次（PBT 18/7 双 resume 合并，无先行 suspend 的 resume 也催一次重连）。App.Tests 51/51。**运行期 S0 实测待自然待机累积诊断，不预填通过。**

### Android UI（`528cf17`）

- 历史复制失败提示：4 s 后自动消失，或下次复制成功即清（`HistoryViewModel.COPY_FAILURE_NOTICE_MS`）。
- History 横屏：通知与列表挤进同一个 `weight(1f)` 栏，列表不再被顶出可视区。
- 向导读/写卡显示「Last check HH:mm」/「上次检查 HH:mm」（`capability_last_check` + `formatLastCheckClock`）。

## 实机（2026-08-18，Redmi Note 11T Pro / MIUI 14 / API 33 / `SHIZUKU_EVENT`）

只核 token / hash，不读正文。

| 项目 | 结果 |
|---|---|
| 简体中文 UI | **PASS** |
| 详情对话框 | **PASS**（复制 / 删除 / 关闭） |
| W→A / A→W 令牌同步 | **PASS** |
| Activity 销毁后捕获 | **PASS**：`am stack remove` 后无 Activity，从系统设置搜索框复制仍送达 Windows |
| 健康循环恢复 | **PASS**：先开应用、后起 Shizuku，一个 10 s tick 内自动绑定 |
| 后台同步开关 OFF/ON | **PASS**：双向不中断（单一 controller） |
| 孤儿 UserService | **PASS**：跨重装计数保持 1（version=2 + callback 死亡退出） |
| Modern Standby / S0 idle | **观察，不是通过**：本机 S0 idle **不**抬 `PowerModeChanged`（内核 506/507 于 13:43–13:45，应用 0 条电源事件）；短 S0 期间 TCP 会话仍在（ping 未断） |

## 已知限制 / 进行中

- **MIUI 开机投递**：未授「自启动」时 MIUI 仍可能不投 `BOOT_COMPLETED`（阶段 6 已记）。授自启动后 FGS 可自行拉起捕获（`fb9347b`），但 Shizuku daemon 每次重启仍要 `start.sh`。
- **Modern Standby `PowerModeChanged` 缺口已补**（`02ec63c`）：`PowerRegisterSuspendResumeNotification` 覆盖 S0 进出，单测锁行为；**运行期 S0 事件落盘待自然待机验证**。经典 S3 睡眠→唤醒→复制恰好一条未测。锁屏/解锁有代码与诊断，无实机。
- **多 ROM / 模拟器**：实体 AOSP/Pixel、OneUI、ColorOS/OriginOS 仍 **NO_EVIDENCE**。**API 34 AOSP 模拟器全量 PASS**（google_apis x86_64：装机、英文 UI、前台捕获、Shizuku READY + 自测、隔离回环 E2eHost@10.0.2.2 双向同步、**原生开机恢复**——`BOOT_COMPLETED` → +28 s FGS → 未开应用即捕获并送达；见 `docs/device-validation-matrix.md` P1–P7）。API 30/35 未开跑（AVD 基建已留存：emulator 37.1.11、`clipsync_api34`、SDK 增量约 6.2 GB）。模拟器附带观察：QR 只广播 LAN 地址不含回环（模拟器需手工指 10.0.2.2）、`READ_LOGS` 授予后进程内刷新滞后、重绑瞬间短暂双 `:clipsync-clipboard`、通用 AVD 320×640 会把自测按钮裁到不可点。
- **导出**：双端入口已接上；导入与 tombstone 字段仍缺。purge / 搜索 / 暂停无本日实机走查。
- **`OVERLAY_POLLING` 真同步：本机 FAIL（诚实结论，2026-08-18 下午实测）**——模式可达 READY 且轮询在跑，但 MIUI 14 在应用后台时系统级拒绝剪贴板读（`ClipboardService: Denying clipboard access … not in focus`），后台零捕获；前台同模式可用。该机上的悬浮窗模式定位降级为「前台辅助」。附带硬件确认：Shizuku 回归后健康循环约 21s 自动升级回 `SHIZUKU_EVENT`。实测挖出三个问题，**均已在 `bc13d81` 修复**：(1) 回归：捕获栈作用域回到主线程（IO 线程无法 `WindowManager.addView`，Shizuku 死后自动降级到悬浮窗曾失败），捕获落库仍走 IO；(2) 悬浮窗读拒绝在应用层不可探测（系统对「拒绝」与「空剪贴板」返回相同），改为向导明示文案「以自测为准」，不做假检测；(3) 状态页读卡按活动后端如实显示（Shizuku 就绪 / ADB 日志就绪 / 悬浮窗轮询就绪 / 前台就绪，实机验证显示「Shizuku 就绪」）。另：优雅替换（REMOVE_LISTENER）路径不触发 UserService 死亡自杀，重建时可能短暂遗留空闲进程（重启清除；候选后续加固）。
- **仍未测**：运行中撤 Shizuku/overlay/`READ_LOGS` 且仅 FGS 存活、Android 强制停止、通知拒绝、电池优化长期驻留、`ADB_LOG_OVERLAY` READY 上行。

## 对 DoD 审计的影响

见 `docs/dod-status.md` 本日修订。结论有翻转的只有 §6.1 睡眠/唤醒（NOT_TESTED → PARTIAL）和锁屏/解锁（NO_EVIDENCE → NOT_TESTED）。其余条目只补证据，不升级结论。
