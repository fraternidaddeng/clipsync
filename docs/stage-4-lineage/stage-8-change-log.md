# 阶段 8 变更记录

日期：2026-08-19（内置特权宿主）/ 2026-08-18

## 内置特权宿主（2026-08-19）

`SHIZUKU_EVENT` 不再要求安装官方 Shizuku 应用。ClipSync APK 自带 `clipsync_priv_server`（`PrivilegedHostService`）：用户用已是 shell 的 adb/root 执行本包 `start.sh` 后，宿主把 binder 推进 `ClipSyncShizukuProvider`，再孵化 `:clipsync-clipboard`。官方 Shizuku 回退**不再维护**。未实现 `newProcess` / `transactRemote` / rish。向导改为「重新检查 / 授权特权宿主」；`android-bootstrap.ps1` 只打印本包启动命令。UserService `destroy` 事务码改为官方约定 `16777115`。

初版合入 `eff1a4a`。其后未提交的实机修补见下一节。内置宿主授权卡与本机 `SHIZUKU_EVENT` 落库的真机结论见「真机复测（2026-08-20）」，不要把 08-19 各节的「仍未复测」抄成当日结论。

## 内置宿主实机修补（2026-08-19，未提交）

设备：Redmi Note 11T Pro `22041216C` / xaga / Android 13 / MIUI 14。**当日**：宿主进程 `clipsync_priv_server` 与 `:clipsync-clipboard` 曾拉起；向导 binder 卡可到「就绪」；用户点「授权特权宿主」卡片不变；编排端不能代跑 `adb shell` 重启宿主。真机结论见「真机复测（2026-08-20）」。

### UserService：避开 MIUI `ActivityThread.systemMain()`

`PrivilegedUserServiceStarter` 不再走 `ActivityThread.systemMain()`。该 ROM 作为 shell 读 `/data/system/theme_config/theme_compatibility.xml` 会 `FileNotFoundException`，子进程退出。现路径：构造 `ActivityThread` + `attach(true)`；失败则 `Class.forName` 无 Application 实例化 `ClipboardUserService`（该类不依赖 Application）。

### 授权按钮无反应

Shizuku 客户端 `checkSelfPermission()` 只读本地 `permissionGranted`，该字段只在 `bindApplication` 回包 `shizuku:attach-reply-permission-granted` 写入。宿主原先：

1. `attachApplication` 用 `getPackagesForUid` 校验；查询空集则抛 `package not owned by caller`。客户端缓存 binder，不再重试 attach。
2. 未 attach 时 `checkSelfPermission` / `requestPermission` 再抛 `Permission Denial`。`AndroidShizukuRuntime` 整段吞掉后 `onResult(false)`，向导刷新仍是「需要你操作」。内置宿主不弹官方确认框，看起来像按钮坏了。

修补：

- `PrivilegedHostAccess`：仅本包包名可 attach。空 `getPackagesForUid` **不再**放行任意应用 uid；须为本包 / 宿主自身 / 已解析的 ClipSync `ApplicationInfo.uid`（见下一节）。
- `checkSelfPermission` 按 uid 判定，不再因未 attach 抛异常。
- `requestPermission` / `bindApplication` 经宿主主线程 `Handler.post` 回结果，避免嵌套 binder。
- `requestAuthorization` 在 `requestPermission` 之后再读一次 `checkSelfPermission`；未立刻成功则等 binder 重试，最多 2 s。
- 向导 `AUTHORIZE_PRIVILEGED_HOST` 不再在点击时额外 `refresh()`（`onStepAction` 已 refresh，授权结果仍走回调再 refresh）。
- `ClipSyncShizukuProvider`：官方 Provider 在 binder 仍存活时丢掉后续 `sendBinder`。首次 attach 失败后同一对象会永远不再 attach。现对「换了 binder」或「同一 binder 仍未授权」先 `onBinderReceived(null)` 再交给官方路径重试。

JVM：`PrivilegedHostAccessTest`、`WizardViewModelTest`（含授权回调后卡片变 READY）、`PrivilegedHostScriptTest`、`ShizukuClipboardBackendTest` 已过。debug APK 已覆盖安装到 `22041216C`。**当日**不能代跑 `adb shell` 重启宿主，授权卡片与剪贴板事件当时未复测。旧 `clipsync_priv_server` 必须重新执行本包 `start.sh` 才加载新宿主。真机结论见「真机复测（2026-08-20）」。

### 宿主策略与向导接运行时（2026-08-19，未提交）

官方 Shizuku 回退**不再维护**；内置特权宿主是唯一打算支持的后端。2026-08-18 的 0.2.0 冒烟仍是当时官方 Shizuku 路径上的历史记录，不代表本后端仍在跟。

- ACL 现为 **fail-closed**：`getPackagesForUid` 空集不再放行所有应用 uid（须为本包 / 宿主自身 / 已解析的 ClipSync uid）。上一节「空查询仍放行 ≥10000」已作废。
- `ClipSyncShizukuProvider`：权限已授予时不再丢掉存活 binder（避免授权后约 1 s 被拆掉）；未授予才 `onBinderReceived(null)` 以便下一次 `sendBinder` 重新 attach。
- `bindUserService` 在绑定进行中报告 in-flight `Binding`；未 start 的 probe 不得再 spawn + unbind UserService。
- 授权 settle 已拉长（等宿主主线程 `bindApplication` / 再投 binder）。**当日**不把授权卡或剪贴板事件标 DEVICE-VERIFIED；真机结论见「真机复测（2026-08-20）」。
- `CopyClipReceiver` 走进程级 write coordinator；捕获栈先赋给 `currentStack` 再开健康循环。
- 向导 auto-apply / auto-upload 现写入运行时：Room `SETTING_AUTO_APPLY_REMOTE`（入站 apply 读此键）与 `ServiceSettingsStore`（FGS / 开机读此后台开关）。upload 布尔相对上次持久化值变化才调用与设置页相同的 `onBackgroundSyncToggled`，避免无关保存重启 FGS。

本条不含阶段 9 图像工作。授权卡与本机落库的真机结论见「真机复测（2026-08-20）」。

### 后端逻辑收口（2026-08-19，未提交）

官方 Shizuku 回退已从运行路径、向导文案、`queries`、bootstrap 打印中拿掉；内置宿主是唯一特权后端。

- 点「授权特权宿主」**不再** `onBinderReceived(null)`。Shizuku 客户端对此会拆掉活 binder、触发 dead listener，随后 `requestPermission` 因 `requireService()` 失败。
- `ClipSyncShizukuProvider` 对同一 binder 的周期 `sendBinder` 不再拆缓存；仅未授权且 binder 对象不同，或宿主 `requestPermission` 无 client 时带 `FORCE_REATTACH` 才允许重 attach。
- `bindUserService` 超时（35 s）现在清 `binding`、unbind、并通知 `USER_SERVICE` 死亡，后端才能指数退避重绑。宿主 binder death 后允许再次 `linkToDeath`。
- `attachSession` 在 `addChangedListener` 失败时**不再** `cancelRebind()`；probe 同样失败时排队重绑。
- 宿主 `clipSyncUid()` 在 `getApplicationInfo` 失败时回退 `getPackageUid`，避免 MIUI 空查询把本包永久拒掉。
- 向导授权成功后立刻 `checkHealth()`，不等 10 s 健康 tick。
- 宿主 `requestPermission` 现在同时 `bindApplication(PERMISSION_GRANTED=true)` 与 `dispatchRequestPermissionResult`。客户端 `checkSelfPermission()` 只读前者写入的本地缓存；只回权限回调时向导卡会一直「需要你操作」。无 client 时按 uid 排队 requestCode，attach 后再派发。
- `clipSyncUid` 不再覆盖 `getPackagesForUid`：uid 匹配或包名在查询结果里都可放行。`matchClient` 不再回退到「同 uid 任意 pid」。
- UserService 用 `setsid` 拉起，启动前按 nice-name 清残留；`start.sh` 同时杀 `clipsync_priv_server` 与 `:clipsync-clipboard`。
- `addPrimaryClipChangedListener` 先挂 app callback 再注册系统监听；OEM 返回 `false`/`0` 视为失败。
- 进行中的 `Binding` 不再写成 `USERSERVICE_DEAD`。`health()` 在 ping 成功后清旧错误码。`readText` 采纳新 session 时会 `attachSession`。
- 通知「复制」先 `ClipboardCaptureRuntime.ensureStarted`，再走写协调器。向导授权点击不再立刻 `refresh()`。
- UserService 启动命令改为先 `export CLASSPATH=...` 再 `setsid /system/bin/app_process ...`。原先 `setsid CLASSPATH=... app_process` 会把赋值当成可执行文件，子进程立刻失败，`:clipsync-clipboard` 永远起不来。`UserServiceSlot.destroy()` 里扫 `/proc` 杀进程改到线程池，避免主线程持锁卡住 `sendBinder`/`findClient`。
- `addChangedListener` 在 ADD_LISTENER 事务返回前一直持有旧 `ChangeCallbackBinder`。UserService 把旧 callback 的 `binderDied` 当成应用进程退出并 `exitProcess`；probe/refresh 重注册时若先丢掉 Java 对象，GC 会误杀 `:clipsync-clipboard`。
- UserService 30s 启动超时在同一把锁里重读 `starting` 和 `binder`，避免刚 `attachBinder` 的槽被迟到的 timeout 拆掉。

### AOSP 模拟器 API 34（2026-08-19，未提交）

AVD `clipsync_pixel34`（`google_apis/x86_64`，无窗口 + `swiftshader_indirect`）。debug APK 覆盖安装后打开应用写出 `start.sh`，以 uid 2000 执行 `adb shell sh /storage/emulated/0/Android/data/com.clipsync.android/start.sh`。

实测：

- `clipsync_priv_server` 与 `com.clipsync.android:clipsync-clipboard` 均存活。
- 宿主 `attach uid=10192 pid=<app> api=13 packages=[com.clipsync.android]`。
- 向导「Shizuku binder」「Shizuku authorization」均为 Ready；后台读/写 Ready。
- 自测：`Test background read: OK · SHIZUKU_EVENT`；`Test background write: OK · PUBLIC_API`。
- 系统设置搜索框复制 `emu_full_token_9921` 后，Room `clips` 落库 `source_app=shizuku`，正文完整。

这只是模拟器结果，**不标 DEVICE-VERIFIED**。真机见下一节。装过新 APK 后仍须用户自行再跑本包 `start.sh`。

### 真机复测（2026-08-20）

设备：Redmi Note 11T Pro `22041216C` / xaga / Android 13 / MIUI 14，serial `HUHYEYDQDMVONZDU`。覆盖安装当日 debug APK，打开应用写出 `start.sh`，以 uid 2000 执行本包 `adb -s HUHYEYDQDMVONZDU shell sh /storage/emulated/0/Android/data/com.clipsync.android/start.sh`。用户确认向导/授权路径无问题。

实测：

- `start.sh` 退出码 0：`info: spawned`。
- `clipsync_priv_server` PID 8608、`com.clipsync.android:clipsync-clipboard` PID 9656 在复测窗口内持续存活。
- 宿主 `attach uid=10417 pid=6231 api=13 packages=[com.clipsync.android]`。
- 运行时 `capability.read.active_read_mode=SHIZUKU_EVENT`、`last_read_state=READY`（健康 tick 持续到复测结束）。`bindUserService` 仅在 `isAuthorized()` 之后才会孵化 UserService，故 UserService 存活即授权已生效。
- 本机捕获：`origin_device_id=80d29726-3f49-427b-aa9b-08db70908351`，`origin_seq=184`，`source_app=shizuku`，`content_hash=359799ce9511a3276648bdb8456a65f4d2c08ba2a5a24d399ef1a9e91176316d`，`event_id=a7598940-82a3-4d00-a33f-f05dd6537b36`。logcat `ClipSyncSync: capture_stored background` 于 09:43:11。
- UserService 启动仍打 `activity-thread path failed: NullPointerException`，已走无 Application 备用路径，进程未退出。

本条标 **DEVICE-VERIFIED**（单机、内置特权宿主、`SHIZUKU_EVENT` 监听落库）。未覆盖：整机重启后再跑 `start.sh`、杀 `:clipsync-clipboard` 后监听恢复、运行中撤授权。官方 Shizuku 13.5.4 的阶段 5/6 记录仍是历史路径，不代表本后端。

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
