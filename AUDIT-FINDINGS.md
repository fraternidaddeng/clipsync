# ClipSync / 剪剪相传 —— 代码审查交接文档

审查日期：2026-08-21 · 审查范围：功能与后端逻辑（不含前端 UI）· 分支：`feature/stage-4`

本文件是给实施方（Grok Build）的自包含任务清单。读者没有审查过程的上下文，所有必要信息都写在这里。

---

## 0. 项目结构速览

这是一个 Windows ↔ Android 点对点剪贴板同步项目，**同一套线协议有两份完全独立的实现**：

| 层 | Android (Kotlin) | Windows (C#) |
|---|---|---|
| 协议校验 | `android/app/src/main/java/com/clipsync/android/protocol/ProtocolJson.kt` | `windows/ClipSync.Core/Protocol/ProtocolValidation.cs` + `ProtocolReader.cs` + `ProtocolReaderV2.cs` |
| 同步状态机 | `android/.../sync/SyncSessionEngine.kt` (1308 行) | `windows/ClipSync.Peer/Sessions/SyncSessionEngine.cs` (1606 行) |
| 存储 | Room：`android/.../storage/ClipRepository.kt` 等 | SQLite：`windows/ClipSync.Core/Storage/SqliteClipboardEventStore*.cs` |
| 媒体 | `android/.../media/MediaBlobStore.kt` | `windows/ClipSync.Core/Media/MediaBlobStore.cs` |

第三份实现：`scripts/validate-protocol.py`（Python + JSON Schema），只用于校验 `protocol/v1` 和 `protocol/v2` 下的 fixture。

规范文档：`docs/protocol-v1.md`（**normative**）、`docs/protocol-v2.md`、`docs/threat-model.md`。

角色：**Android 永远是 dialer，Windows 永远是 listener**。Windows 发 `challenge`，Android 回 `auth`。Android 不对 Windows 做密码学验证，反向认证依赖 TLS 证书 pin。协议版本由客户端选择的 URL 路径决定（`/v1/peer/sync` vs `/v2/peer/sync`），服务端跟随。

---

## 1. 贯穿全局的结构性问题（先读这一节）

本次审查发现的所有 bug 都可归因到五个切面，它们是同一件事：**缺少"双端对等"和"覆盖对等"的强制机制**。

**(1) 修复系统性地只打一边。** 已确认的单边能力：

| 能力 | Android | Windows |
|---|---|---|
| retention 保护未发送的 outbox 行 | ✅ `ClipDaos.kt:129` | ❌ 缺失 |
| 重连时重放已持久化的 peer ack | ✅ `SyncSessionEngine.kt:375` | ❌ 缺失 |
| 硬删除过期 tombstone | ✅ `ClipDaos.kt:134` | ❌ 全仓库无 `DELETE FROM clips` |
| 媒体 blob 垃圾回收 | ❌ API 齐全但零调用方 | ✅ `CollectUnreferencedBlobsAsync` |
| 会话结束清理未完成的图片临时文件 | ❌ 缺失 | ✅ `Dispose()` |
| by-event-id 查询 | ❌ 退化为全表扫描 | ✅ `GetSyncableEventsByIdsAsync` |
| 删除传播给对端 | ❌ 删掉 outbox 行 | ✅ 保留并 announce tombstone |
| 严格 JSON 扫描器 | 只在配对路径有 | 同步 + 配对都有 |

**(2) fixture 语料的空洞形状 = bug 的形状。** 两端确实都跑同一套 fixture（`ProtocolFixtureTest.kt` / `ProtocolFixtureTests.cs`），但 15 个 v1 invalid fixture 覆盖的消息类型是：`clip_payload`×5、`known_vector`×3、`ping`×2、`clip_fetch`/`ack_ranges`/`want_ranges`/`hello`/`malformed` 各 1。**`challenge`、`auth`、`clip_announce`、`error` 零覆盖**——而这四个恰好是 Kotlin 侧校验缺失的全部位置。

**(3) 断言强度不足。** Kotlin 只断言 `runCatching{}.isFailure`；C# 只断言 `Assert.Contains(failure.ErrorCode, ProtocolErrorCodes.All)`（即"是 47 个已知码之一"）。两端因完全不同的理由拒绝同一个 fixture 也测不出来。

**(4) 测试 fake 的语义 ≠ 真实实现的语义。** 见 §7，这是存储层 bug 能长期存活的原因。

**(5) CI 看不见当前分支。** `.github/workflows/ci.yml` 只触发 `[main, develop]`，而 `develop` 分支不存在。Stage 4→9 的每个提交都在 CI 视野之外。

**实施要求：修复任何一条 bug 时，必须同时检查另一端是否有同样的问题。** 不要只修一侧。

---

## 2. 施工前置条件（必须先做，否则后续无法验证）

### 2.1 当前提交树编译不过

95 个文件未被 git 跟踪且未被忽略，而**已跟踪的文件依赖它们**：

- 整个 `android/app/src/main/java/com/clipsync/android/media/` 包未跟踪（`ImageChunks.kt`、`ImageCodec.kt`、`ImageThumbnail.kt`、`MediaBlobStore.kt`、`MediaLimits.kt`），6 个已跟踪 Kotlin 文件 import 它
- `protocol/v2/` 整个目录未跟踪（2 个 schema + 30 个 fixture + 媒体二进制），`scripts/validate-protocol.py:19,233` 无条件要求它
- `android/app/src/main/res/xml/file_paths.xml` 未跟踪，`AndroidManifest.xml:118` 引用它
- `windows/ClipSync.App/Converters/` 未跟踪，已跟踪的 `MainWindow.xaml` 绑定它
- `android/app/schemas/com.clipsync.android.storage.ClipDatabase/2.json` 未跟踪，`RoomSchemaContractTest.kt` 断言它的 identityHash
- v2/图片的一致性测试全部未跟踪：`ProtocolFixtureV2Test.kt`、`ProtocolReaderV2Tests.cs`、`ClipSync.Tests/Media/MediaBlobStoreTests.cs`、`ClipSync.App.Tests/Media/ImageThumbnailTests.cs`

**动作**：`git add` 这棵 Stage 9 树（或回滚依赖它的已跟踪文件）。在此之前，干净克隆无法构建，任何修复都无法验证。

### 2.2 工作区含真实剪贴板内容且未被 gitignore

- `tmp-img-test/uidump-hist.xml` —— 剪贴板历史界面的 UI 层级 dump，包含逐条捕获的正文。`git check-ignore` 确认 **NOT IGNORED**，一次 `git add -A` 就会提交上去。违反 `docs/threat-model.md:36-37` 的「禁止正文」。
- `tmp-img-test/{and-live,and-rev,and-work}/clipsync.db` 等 4 个真实 SQLite 剪贴板库（被 `*.db` 忽略，但在工作区）。
- `emulator-pixel34.log`（143 KB，QEMU 启动输出，不含剪贴板内容/密钥，只泄漏本机路径）、`emulator-pixel34.err.log`、`null/`、`PowerShell 7.6.5/`（后两个是环境变量展开失败留下的空 jadx 缓存目录）。

**已查证：`git log -S'uidump' --all` 无结果，`git ls-files tmp-img-test/` 为空——从未有剪贴板内容进入提交历史。** 这是待爆的雷，不是已发生的泄漏。

**动作**：删除上述目录/文件；在 `.gitignore` 增加 `tmp-img-test/`、`*.log`、`uidump*.xml`。注意现有 `.gitignore` 只有 `*-diagnostics.log`，不覆盖 `*.log`。

### 2.3 CI

- 建 `develop` 分支，或把 `ci.yml` 的 trigger 改为 `feature/**`
- 协议任务缺依赖：`validate-protocol.py` 需要 `jsonschema` 和 `referencing`，CI 里没有 `setup-python`、没有 `pip install`、仓库无 `requirements.txt`
- CI 装了 .NET 8 却从不使用

---

## 3. 第一梯队：静默丢数据 / 隐私控制失效

> 排在崩溃类问题前面，因为崩溃用户看得见，丢数据看不见。

### 3.1 Windows 的保留策略销毁尚未发送的剪贴板内容

**位置**：`windows/ClipSync.Core/Storage/SqliteClipboardEventStore.cs:419-443`（`CleanupAsync`）

**机制**：CTE `cleanup_candidates` 选出所有超龄或超出 `MaximumEntries` 的 live clip，然后 `UPDATE` 清空 `content`/`content_hash` 并打上 `terminal_reason='expired'`。**从不查 `outbox` 表。** outbox 行本身存活（UNIQUE 键是 peer/origin/seq，与 clip 内容无关），`GetOutboxBatchAsync`（`.Sync.cs:514-560`，`JOIN clips` 在 `:531`）仍返回它，`ReadSyncableEvent`（`.Sync.cs:915-927`）把非空 `terminal_reason` 映射成 `content=null`。

`MaximumEntries` 默认 **2000**（`ClipboardStorageModels.cs:57`），且 `MainViewModel.cs:124-126`/`:339-341` 只传 `maximumAge`，所以永远调不高。

**失败场景**：手机关机过周末。Windows 期间捕获 2100 条（该计数**包含从手机收到的**，所以手机积压会把 Windows 自己未发送的捕获顶过阈值）。用户保存设置或重启 → `CleanupAsync` tombstone 最旧的 100 条 → 手机回连 → `DrainOutboxAsync` 把这 100 条 announce 成 `unavailable/expired` → Android `ingestTerminalMarker` 写入 100 个永久墓碑。**内容在两台设备上同时消失，而它从未送达过。**

**修复**：把 Android 的守卫抄过来。`android/.../storage/ClipDaos.kt:129` 是 `AND event_id NOT IN (SELECT event_id FROM outbox)`。在 `cleanup_candidates` CTE 的两个 `SELECT` 上都加等价条件。

**验证**：新增测试——插入 N 条 clip 并入 outbox，跑 `CleanupAsync` 且 `MaximumEntries < N`，断言 outbox 中的 clip 的 `content` 未被清空。

### 3.2 历史超过 999 条后「清空」静默失败

**位置**：`android/.../storage/ClipRepository.kt:311-316`；`ClipPersistence.kt:133-139`；`ClipDaos.kt:72-73` 和 `:174-175`

**机制**：
```kotlin
suspend fun clear(nowMs: Long): Int = persistence.transaction {
    val ids = softDeleteAllVisible(nowMs)   // SELECT event_id FROM clips WHERE deleted_at IS NULL，无 LIMIT
    deleteOutboxForEvents(ids)              // DELETE ... WHERE event_id IN (:eventIds) → N 个 host parameter
    ids.size
}
```
Room 把 `IN (:eventIds)` 展开成 N 个绑定参数。Android 10–13 平台 SQLite 的 host parameter 上限是 **999**（Android 14+ 才提升到 32766，但 `minSdk = 29`）。两条语句在同一事务内，`SQLiteException` 会把 soft-delete 一起回滚，异常在上层被吞掉，UI 无任何提示。

Android 的保留策略**只按时间**（`RetentionPolicy.kt:4`，30 天）没有条数上限，所以一个月攒过 999 条是正常使用量。

**失败场景**：约 1500 条历史的用户点「清空」（`HistoryViewModel.kt:186`）→ 什么都没删掉，无提示。**隐私控制恰好对最需要它的用户失效。**

**修复**：`deleteOutboxForEvents` 按 ≤900 分批；`visibleEventIds()` 也应分页。整条路径目前无任何分批。

**验证**：新增测试——插入 1500 条可见 clip，调 `clear()`，断言返回 1500 且全部被软删。

### 3.3 导入永不推进接收状态 → 每次重连全量重传

**位置**：`android/.../storage/ClipRepository.kt:546-596`（只推进 `local_sequences`，`:590-592`）；`checkIdentity` 在 `:402` 返回 `AlreadyPersisted`；唯一的接收状态推进在 `:193-194`（仅 `Stored` 分支）。Windows 侧形状相同：`.Import.cs:110-124`、`.Sync.cs:738`。

**机制**：`AlreadyPersisted` 是唯一**不调用** `upsertReceiveState` 就返回的 ingest 结果。引擎 `handleClipAnnounce`（`SyncSessionEngine.kt:500-503`）只在 `localState.contains(seq)` 时跳过，否则落到 `ingestAvailable`（`:577-606`），后者把 `AlreadyPersisted` 当成功并**发出 ack**（`:556`）。ack 推进的是**对端**游标、删的是对端 outbox 行，本地 vector 纹丝不动。

**失败场景**：重装应用 → 导入含 800 条来自 Windows peer 的记录 → 重新配对。Android 的 known vector 对该 origin 报 contiguous 0 → `sendWants`（`:419-445`）请求整个区间 → Windows 从 `GetSyncableEventsAsync`（outbox 无关，所以 ack 驱动的 outbox 删除拦不住）送 800 条 announce → 全部命中 `AlreadyPersisted` → 本地状态仍为 0 → **每次重连重复一遍，永久。**

**修复**：导入时按导入的 `(origin, seq)` 推进 `origin_receive_state`（Android 走 `OriginReceiveState.accept` 等价路径，Windows 走 `OriginReceiveState.Accept`）；并让 `AlreadyPersisted` 分支也调用 `upsertReceiveState`。两端都改。

**验证**：导入 N 条远端 origin 记录后，断言 `knownVector()` 对该 origin 的覆盖包含这些序号。

---

## 4. 第二梯队：崩溃与功能失效

### 4.1 首次同步会确定性地把自己打死（两端对称）

**位置**：`android/.../sync/SyncSessionEngine.kt:542-544` 和 `:560-561`；`windows/ClipSync.Peer/Sessions/SyncSessionEngine.cs:734-735` 和 `:773-774`

**机制**：`handleClipAnnounce` 无条件累加 fetch 请求：
```kotlin
outstandingFetches[header.eventId] = header   // map 覆盖，不会重复
fetchIds.add(header.eventId)                  // list 会重复
```
而 `clip_payload` 处理端在 `outstandingFetches` 取不到时**直接杀会话**（Kotlin `:722-726` / C# `:873-877`，`MESSAGE_OUT_OF_ORDER`）。

同一个 clip 会被 announce **两次**：`drainOutbox` 一次、`handleWantRanges` 一次——这两条路径的触发条件恰好相同（未被 ack 的 clip = 对端 vector 里缺的 clip）。

**失败场景**（固定顺序，非竞态，`sendLock` 保证发送串行）：
```
Win: EnterReady → 发 known_vector，同时启动 outbox drain loop
And: 收 known_vector → 发 want_ranges
Win: outbox drain → announce#1 (E1..En)
And: 收 announce#1 → 未存 → 发 clip_fetch#1
Win: 收 want_ranges → announce#2 (同一批 E1..En)
And: 收 announce#2 → payload 还没到，仍未存 → 发 clip_fetch#2   ← 重复
Win: 收 clip_fetch#1 → 发 payload#1
And: 收 payload#1 → 落库、ack、从 outstandingFetches 移除
Win: 收 clip_fetch#2 → 发 payload#2
And: 收 payload#2 → remove 返回 null → MESSAGE_OUT_OF_ORDER → 会话终止
```
**全新配对后的第一次同步必然命中。** 反向（Android→Windows）同理，代码结构完全对称。

**修复**（两端各一行）：
```kotlin
if (outstandingFetches.put(header.eventId, header) == null) fetchIds.add(header.eventId)
```
C# 侧用 `TryAdd`：`if (outstandingFetches.TryAdd(eventId, header)) fetchIds.Add(header.EventId);`

**同时修复**：Windows `EnterReadyAsync`（`SyncSessionEngine.cs:495-501`）缺少 Android `enterReady`（`SyncSessionEngine.kt:369-378`）里的 `applyPersistedPeerAcks()`。Android 的注释说明了原因："resetOutboxToPending re-pends 'announced' rows. Re-apply persisted peer cursors so a prior ack that raced session close still deletes those rows before the outbox loop can re-announce them."。缺这一步会放大上面的重复 announce。

**验证**：集成测试——Windows 侧预置 N 条 clip，Android 全新配对接入，断言会话保持 READY 且 N 条全部同步完成。

### 4.2 三条独立的进程崩溃路径，根因是同一个异常泄漏

**位置**：`android/.../platform/clipboard/overlay/OverlayFocusController.kt:86-95`

**机制**：
```kotlin
applyWindow(idleSpec())        // 第 86 行，在 try 之外
try {
    applyWindow(readSpec())
    return readWithRetries()
} catch (_: RuntimeException) { ... }
finally {
    applyWindow(idleSpec())    // 第 94 行，在 catch 之外
}
```
两次 `applyWindow` 都在 `catch` 覆盖范围外。`applyWindow` 会调 `WindowManager.addView`/`updateViewLayout`，抛出的 `BadTokenException`、`CalledFromWrongThreadException` 都是 `RuntimeException`——**恰好是那个 catch 想拦的类型，却拦不到**。

三个放大路径：

**(a) ADB 信号路径在无 Looper 的线程上碰窗口。** `LogcatClipboardEventReader` 的 `flightExecutor` 是裸 `Thread`（`adblog/LogcatClipboardEventReader.kt:65-67`），`runFlights` 在其上调 `callback.invoke(match)` → `overlayController::readText` → `addView`/`updateViewLayout` → `CalledFromWrongThreadException`（窗口已存在）或 `new Handler()` 失败（窗口不存在）→ 逃逸出 executor → 进程被杀。
触发条件是向导里写明的路径：开 overlay 权限 + adb 授 `READ_LOGS` → **用户复制任意文字，应用死**。
注意 `capture/ClipboardCaptureRuntime.kt:104-106` 的注释说明这个 bug 在 *rebuild* 路径上已修，但 ADB 信号路径没跟着搬到主线程。

**(b) 主线程路径。** 用户在 `canDrawOverlays()` 检查（第 77 行）和 `addView` 之间撤销悬浮窗权限（MIUI 的「后台弹出界面」开关 `canDrawOverlays` 根本反映不出来）→ `BadTokenException` → 崩溃，而非设计意图的 `NEEDS_USER_ACTION` 降级。
现有测试 `OverlayLifecycleInvariantTest` 覆盖不到：`FakeOverlayPlatform.attachOrUpdateWindow` 没有抛异常的钩子，只有 `throwOnRead`。

**(c) `stop()` 之后不能再 `start()`。** `LogcatClipboardEventReader` 的 `flightExecutor` 和 `scheduler` 都在**构造函数**里创建（`:65-67`），`stop()` 把它们 `shutdown()`/`shutdownNow()`（`:103-106`），而 `start()`（`:71-85`）只重开 source 和 thread，**从不重建 executor**。下次匹配到日志行 → `acceptLine`（`:115-121`）→ `scheduler.schedule(...)` → `RejectedExecutionException` → `drain`（`:134-143`）只 catch `InterruptedException` → 逃逸 → 进程被杀。
注意 `requestFlight`（`:158`）**已经**加了 `catch (RejectedExecutionException)`——说明有人踩过这个坑，但只补了一半。
触发路径：`ClipboardAccessCoordinator.switchTo`（`:186-201`）在重选同一个已激活模式时会对**同一实例**先 stop 再 start。具体地：已回退到 `ADB_LOG_OVERLAY` → 用户在向导里把首选模式也设成 `ADB_LOG_OVERLAY` → 下次复制 → 崩溃。
所有测试都注入 `ManualScheduler`，`stop()` 里 `if (scheduler is ThreadTaskScheduler)` 分支在测试中永不执行。

**修复**：
1. `doReadText` 把两处 `applyWindow` 收进 try/catch（或整体包一层）
2. `LogcatClipboardEventReader` 的两个 executor 改为 `start()` 时创建、`stop()` 时销毁
3. ADB 信号路径切回主线程 dispatcher（与 rebuild 路径一致）
4. 给 `FakeOverlayPlatform` 加 `throwOnAttach` 钩子并补测试

### 4.3 Shizuku 卡在 DEGRADED 时永不降级，捕获静默死亡

**位置**：`android/.../platform/clipboard/shizuku/ShizukuClipboardBackend.kt:68,162-190`；`shizuku/ShizukuErrorCodes.kt:25-30`；`ClipboardAccessCoordinator.kt:96-99,136,297-308`

**机制**：`probeReadState` 把 `USERSERVICE_DEAD` 和 `CLIPBOARD_BINDER_DEAD` 映射为 `DEGRADED` 而非 `FAILED`；`health()` 在 `session == null` 且带这些码时返回 `DEGRADED`；`probe()` 对 `BindResult.Binding` 返回 `DEGRADED`。而 `checkHealth` 的回退条件是 `health.state == FAILED || probe.readState == NEEDS_USER_ACTION`——**两个条件都永远不成立**。
更糟：`canStartWhileDegraded` 会主动把 DEGRADED 的 Shizuku 提交为活跃 backend，`commitReadySwitch` 随后把 `lastReadState` 置为 `READY`。

**失败场景**：特权 host 活着且已授权，但 `app_process` UserService 子进程起不来（SELinux/OEM 策略挡掉 `shizuku/host/PrivilegedHostService.kt:294` 的 `Runtime.getRuntime().exec("sh -c …")`，或子进程死亡）→ `AndroidShizukuRuntime` 的 35 秒绑定超时触发 `USERSERVICE_DEAD` → DEGRADED → **即使 `autoFallbackAllowed = true` 也永不回退到 ADB/overlay/前台**。状态卡片仍显示 Shizuku 正常。一条 clip 都抓不到，只能靠用户手动换模式。
`WizardChoices.preferredReadMode` 的默认值就是 `SHIZUKU_EVENT`——**这是默认配置下的失效模式**。

**反向也坏**：`AdbLogOverlayBackend.probe()` 只在自己的 reader 运行时才可能报 READY；一旦被降级，`lastMatchAtEpochMillis` 10 秒后过期，`tryRecoverRequestedMode`（`ClipboardAccessCoordinator.kt:260-277`）永远看不到它 READY → **降级后基本不可能升回来**。

**修复**：`USERSERVICE_DEAD`/`CLIPBOARD_BINDER_DEAD` 在会话为空时判为 `FAILED`（`Binding` 保持 DEGRADED 但需要超时上限）；给降级后的 backend 设计一条不依赖"自己正在运行"的可用性探测，否则升级路径永久闭死。

### 4.4 通知栏点「停止」不会停止捕获

**位置**：`android/.../service/ClipboardSyncService.kt:43-48`；`capture/ClipboardCaptureManager.kt:166`

**机制**：`ACTION_STOP` 只调 `ClipboardSyncRuntime.stopControllerIfUnneeded()`——那是**同步控制器**，不是捕获运行时。全仓库 grep 确认：`ClipboardCaptureRuntime` 只暴露 `ensureStarted` 和 `currentAccess`，**没有任何公开的 stop**；`ClipboardCaptureManager.stopLocked` 是 private，只能从设置重建路径进入。

**失败场景**：用户点「停止」、通知消失、前台服务退出之后，overlay 轮询 Handler、`logcat` 子进程、Shizuku UserService 绑定、10 秒健康循环**全都继续跑到进程死为止**。这不只是耗电——在没有前台服务通知的情况下持续读剪贴板是知情同意问题，也踩 Android 14+ 的后台限制。

**修复**：给 `ClipboardCaptureRuntime` 加公开的 `stop()`，`ACTION_STOP` 调用它。

### 4.5 回环抑制会吞掉真实复制，也会漏掉真实回环

**位置**：`android/.../platform/clipboard/ClipboardWriteCoordinator.kt:8,89-126,159-167`；`AndroidPublicClipboardWriter.kt:96-122`；`capture/ClipboardCaptureRuntime.kt:71`

**机制**：唯一的防回环机制是按 `hash(text)` 建的 5 秒标记，`shouldSuppressCaptureHash` 匹配**任意 origin** 且首次命中即移除。

三个失效方向：
1. **吞掉真实复制**：对端发来 "meeting at 5"，applier 写入剪贴板；5 秒内用户从刚读到的消息里复制同一串文字 → 在 `ClipboardCaptureRuntime.kt:71` 被丢弃，既不入库也不同步。
2. **真回环漏网**：`AndroidClipboardWriteOs.setPrimaryClip` 跑在游离的 `clipsync-public-write` 线程上带 2 秒超时；超时后协调器返回 `Failure` 并调 `clearSuppression(originEventId)`（`:124`），但那个孤儿线程还活着，随后仍可能把内容写进剪贴板——**此时标记已被清除** → 被当成本地新 clip 捕获 → 分配新 event id → 回传给对端。
3. **计数错配**：`InboundClipApplier.onCommitted` 按批处理，两条内容相同的 inbound clip 注册两个同哈希标记却只产生一次剪贴板变更 → 多出来的标记会在 5 秒内吞掉一次无关的用户复制。

`docs/threat-model.md:31` 写的是「`originEventId` 与内容哈希抑制；所有 backend 共用去重逻辑」——实际只有内容哈希在起作用。

**修复**：抑制标记以 `originEventId` 为主键并与实际写入结果绑定；超时路径不能在孤儿写线程仍可能落地时清除标记（应改为等待/取消写线程，或延长标记至写线程确定退出）；批量 apply 时按实际剪贴板变更次数注册标记。

### 4.6 重连退避永不重置

**位置**：`android/.../sync/SyncController.kt:151`

**机制**：每次会话结束无条件 `failures += 1`，全函数没有任何 `failures = 0`。健康跑了几小时、正常结束的会话也算一次失败。

**失败场景**：手机息屏/换网 6 次之后，退避永久卡在 30 秒（`reconnectBackoffMs` 的 steps 数组末值）。用户复制一段文字要等最多 30 秒才同步。与 4.1 的会话终止叠加会显著恶化。

**修复**：会话以 `authenticated == true` 且运行时长超过阈值（如 > 30s）结束时，把 `failures` 归零。

### 4.7 v1 会话会永久毒化图片事件

**位置**：`android/.../sync/SyncSessionEngine.kt:956-960`；`windows/.../SyncSessionEngine.cs:1095-1103`

**机制**：`buildHeader` / `BuildHeaderAsync` 在 v1 会话上遇到图片时调 `markLocalUnsupportedMedia` / `StoreLocalUnsupportedMediaAsync` **写库**。

**失败场景**：一次临时的版本不匹配（例如旧版 Android 客户端，或 `SyncSessionOptions.protocolVersion` 默认值为 1）会把 outbox 里所有图片**不可逆地**标成 `local_only`，之后双端都升到 v2 也再也不会同步。

**附带问题**：这是在一个名为 "build header" 的函数里做持久化写入，副作用位置错误。

**修复**：把降级标记改为会话内的临时状态，不落库；或落库时记录"因协议版本降级"并在 v2 会话中允许重新提升。

### 4.8 主线程阻塞（ANR 面）

- **overlay 轮询在主 Looper 上 `Thread.sleep`**：`HandlerOverlayPollScheduler` 用 `Handler(Looper.getMainLooper())`（`overlay/OverlayPollingBackend.kt:142-180,219-234`）。每次 `onTick` 做三次同步 `WindowManager` 事务（idle→read→idle）外加最多两次 `platform.delay(35ms)`，而 `AndroidOverlayPlatform.delay` 就是 `Thread.sleep`（`OverlayFocusController.kt:301-303`）。向导默认 800ms 轮询（最小 500ms）→ 主线程每次轮询睡约 70ms + 3 次 binder 往返。`readText()` 还是 `synchronized(lock)`，与 ADB flight 线程和 `detach()` 共享。
- **无超时的 binder 事务在主线程上、还持着锁**：manager scope 是 `Dispatchers.Main.immediate`，`startLocked` 和健康 tick 都在 `synchronized(lock)` 内调 `remote.transact(code, data, reply, 0)`（`shizuku/ShizukuClipboardBinder.kt:48,139`）——同步、非 ONEWAY、无超时——打进 shell uid 的 host，host 再反射调进 system_server。host 被 OEM 后台策略冻结或 system_server 剪贴板锁被占 → 主线程无界阻塞。每 10 秒一次。
- **`CopyClipReceiver` 在 `Dispatchers.IO` 上构建整个捕获栈**：`ensureStarted` 在调用方线程同步执行 `startLocked`，从 IO 线程直达 `WindowManager.addView`，违背 `ClipboardCaptureRuntime.kt:104-106` 的设计注释。异常被 `switchTo` 的 `catch (_: Exception)` 吞掉，表现为冷启动后点通知的「复制」把捕获卡在 `CLIPBOARD_MODE_SWITCH_FAILED`，直到下一次健康 tick 从主线程恢复。

**修复**：轮询调度移出主 Looper（保留 `addView` 在主线程，读取与 sleep 移到专用 HandlerThread）；binder 事务加超时并移出主线程与锁；`ensureStarted` 内部 hop 到 manager scope。

---

## 5. 协议层：Kotlin 侧 v1 语义校验缺失

`docs/protocol-v1.md` 是 normative 文档。以下都是 Kotlin 侧违反规范、且 C# 侧已正确实现的条款。**修复时请以 C# 侧为参照，逐条对齐。**

### 5.1 `challenge` / `auth` 完全没有语义校验

**位置**：`ProtocolJson.kt:76-81` 只有 `body.requireKeys(...)`（仅检查键存在/无未知键）。

C# 对照实现：`ProtocolValidation.cs:160-183`（`ValidateChallenge`）、`:185-210`（`ValidateAuth`）——校验 `algorithm == "hmac-sha256"`、nonce 是 32 字节 unpadded base64url、device id 是规范 UUID、`trust_epoch >= 1`、`expires_at_ms >= 0`、`proof` 是 32 字节 unpadded base64url。

Android 会接受 `{"algorithm":"md5","proof":"AA",...}` 一路进到状态机。引擎里补了部分检查（`SyncSessionEngine.kt:328-345`），但这是把协议层职责漏到状态机里。

### 5.2 v1 的 `clip_announce` 几乎不校验

**位置**：`ProtocolJson.kt:295-312`（v1 `validateClipHeaders`）对比同文件 `:150-203`（v2 `validateClipHeadersV2`）。

v1 版本**不查**：event_id/origin_device_id 是否为 UUID、event_id 是否重复、`(origin, seq)` 是否重复、`origin_seq >= 1`、`content_hash` 格式、`utf8_bytes` 范围、`created_at_ms`、`reason` 是否在允许集合内。C# `ValidateAnnounce`（`ProtocolValidation.cs:312-393`）全查。

**v2 写对了，v1 没回填。** 直接证据：`ClipUnavailableReasons.ALL`（v1 集合）在 `android/app/src/main` 里**零引用**，只有 `ALL_V2` 被用（`ProtocolJson.kt:198`）。

### 5.3 v1 的 `error.code` 不校验枚举

**位置**：`ProtocolJson.kt:93` 只有 `requireKeys`。而同文件 v2 路径 `:133` 有 `requireProtocol(body.string("code") in ProtocolErrorCodes.ALL_V2)`。C# v1 有 `ValidateError`（`:545-563`），还额外校验 `failed_type` 和 `retry_after_ms` 范围。

`ProtocolErrorCodes.ALL`（v1 集合）在 Kotlin main 中零引用。

### 5.4 `clip_payload` 批量上限未接线

`docs/protocol-v1.md:123`：「A payload batch is also capped at 1,048,576 decoded content bytes.」

C# 累加 `totalContentBytes` 并拒绝（`ProtocolValidation.cs:476-480`）。Kotlin 只查单条 ≤1 MiB、最多 32 条（`ProtocolJson.kt:317,337`）→ **接受 32 MiB 的一帧**。

常量 `ProtocolLimits.MAX_PAYLOAD_BATCH_CONTENT_BYTES` 在 Kotlin 侧存在，但只用在**发送**路径 `SyncSessionEngine.kt:1012`，接收侧未用。

### 5.5 `ProtocolLimits` 在 Kotlin 侧基本是装饰品

以下常量在 `android/app/src/main` 中**一次都没被引用**（已全量 grep 确认）：
`MAX_SOURCE_APP_LENGTH`、`MAX_CLIENT_VERSION_LENGTH`、`MAX_RETRY_AFTER_MS`、`MAX_JSON_DEPTH`、`MAX_CONTENT_UTF8_BYTES`、`MAX_ORIGINS_PER_MESSAGE`、`MAX_RANGES_PER_ORIGIN`

校验器里全是硬编码字面量（`128`、`256`、`32`），还自建了两个私有重复常量：`ProtocolJson.kt:432` 的 `MAX_DEPTH = 16` 和 `:433` 的 `MAX_PAYLOAD_BYTES = 1_048_576`。`ProtocolJson.kt:438-451` 还重复定义了一份 `MESSAGE_TYPES`，而不是用 `ProtocolMessageTypes.ALL`。

直接后果：`source_app` 长度、`client_version` 格式与长度、`retry_after_ms` 范围在 Android 侧完全不校验。

**修复**：把 Kotlin 校验器里所有字面量替换为 `ProtocolLimits` 常量，删除私有重复常量，用 `ProtocolMessageTypes.ALL` 替换重复的 `MESSAGE_TYPES`。这样两端常量表就成为唯一真相源。

### 5.6 不拒绝重复 JSON key / null 值，深度检查是后置的

`docs/protocol-v1.md:39`：「Parsers reject duplicate object property names, malformed UTF-8, lone Unicode surrogates, and JSON nesting deeper than 16.」
`docs/protocol-v1.md:38`：「Optional fields are omitted. They are never sent as `null`.」

C# 有 `ProtocolReader.ScanTokens`（`ProtocolReader.cs:185-231`），在解析前做 token 级扫描，拒绝重复属性名和所有 null。

Kotlin 的 `rejectExcessiveDepth`（`ProtocolJson.kt:404-428`）是**先完整解析、再把整个文档 `toString()` 重新序列化一遍**才查深度——既无防护作用（深嵌套已递归解析完），又让 7 MiB 帧的峰值内存翻倍。kotlinx.serialization 的 `parseToJsonElement` 对重复 key 静默保留最后一个，对 null 无统一拒绝。

**修复所需的代码仓库里已经有了**：`android/.../pairing/PairingJson.kt:140-334` 有一个完整手写的 `StrictScanner`，重复 key、null、深度、控制字符、孤立代理对全查，注释明确写着 "mirroring the Windows ScanStrictJson pass"。它被用在 8 KiB 的配对文档上，却**没用在 7 MiB 的同步帧上**。

**修复**：把 `StrictScanner` 提取为共享组件，在 `ProtocolJson.parseEnvelope` / `parseEnvelopeV2` 的最开头调用，替换 `rejectExcessiveDepth`。

### 5.7 v2 的 `content_hash` 只查长度不查格式

**位置**：`ProtocolJson.kt:184`（v2 announce 的 image 分支）、`:245`（`validatePayloadEnd`）—— `requireProtocol(clip.string("content_hash").length == 64)`。

C# 用 `IsLowercaseSha256`（`ProtocolValidation.cs:569-585`），校验 64 位小写十六进制。Kotlin 会接受 64 个 `Z`。v2 announce 的 text 分支更是完全不校验 content_hash。

### 5.8 信任模型两端不同，且 Android 违反规范

`docs/protocol-v1.md:121`：允许转发第三方 origin 的事件，只要接收方已有该 origin 的配对信任记录；未知第三方 origin 才拒绝。

- Windows 照此实现：`SyncSessionEngine.cs:1033-1037`（`IsTrustedOriginAsync` 查库）+ `:653`、`:552`
- Android 是 `originDeviceId == peer.deviceId`（`SyncSessionEngine.kt:914-915`）

三设备场景下 Windows 会不断 announce 第三方 origin 的 clip，Android 静默丢弃（`logger.event("untrusted_origin_skipped")`）且**永不 ack** → 该 outbox 永远排不空。

**修复**：Android 侧改为查询配对信任记录（需要 `PairingStore` 支持多 peer），或明确把产品限定为双设备并在 Windows 侧同样收紧 + 更新规范文档。**两端语义必须一致。**

### 5.9 撤销检查强度不同

Windows 每条数据消息都重查数据库（含 trust epoch 漂移）：`SyncSessionEngine.cs:1021-1031`（`EnsurePeerStillTrustedAsync`）。
Android 是注入的 lambda `isPeerTrusted`，**默认值为 `{ true }`**（`SyncSessionEngine.kt:76`）。`SyncController.kt:116-121` 传了实现，但默认值不安全，且不检查 epoch 漂移之外的撤销状态。

### 5.10 证书 pin 失败的检测靠字符串匹配

**位置**：`android/.../sync/PeerSyncDialer.kt:162-171`

**机制**：遍历 cause 链找 `current.message == PIN_MISMATCH_MARKER`（一个字符串常量 `"clipsync.pin.mismatch"`）。一旦 OkHttp/Conscrypt 换了包装方式或改写了 message，就会掉进 `:135` 的 `else -> HostOutcome.NotReachable` 分支。

**后果**：中间人攻击会表现为「连不上，无限重试」而不是弹出证书告警。`SyncController` 只有在收到 `CertificateMismatch` 时才会停止并上报 `CERTIFICATE_MISMATCH` 状态。**安全告警被降级成静默重试。**

注意：Android 从不密码学验证 Windows 身份（它只发 `auth`，从不发 `challenge`，且在 `SyncSessionEngine.kt:265-270` 直接拒绝收到的 `auth`）。反向认证**完全依赖 TLS 证书 pin**，所以这条是唯一防线上的裂缝。

**修复**：用专用异常类型（而非 message 字符串）标记 pin 失败；`isConnectivityFailure` 对 `SSLException` 返回 false 后，未识别的 SSL 失败应归类为 pin/TLS 失败而非 `NotReachable`。

### 5.11 补 fixture（这是防复发的关键）

新增 v1 invalid fixture，覆盖当前零覆盖的四类消息：
- `challenge`：错误 algorithm、nonce 非 32 字节 base64url、device id 非规范 UUID、`trust_epoch = 0`
- `auth`：错误 algorithm、proof 长度错误、`challenge_request_id` 非 UUID
- `clip_announce`：重复 event_id、重复 `(origin, seq)`、`origin_seq = 0`、`content_hash` 非小写十六进制、`reason` 不在枚举内、unavailable 头携带内容元数据
- `error`：未知 code、未知 `failed_type`、`retry_after_ms` 越界

同时补：payload **批量**超过 1 MiB（现有 `oversized_payload.json` 只测单条）、重复 JSON 属性名、显式 null 值、嵌套深度 > 16、`source_app` 超长、`client_version` 格式非法。

**并且给每个 invalid fixture 加 `expected_error` 字段**，在两端断言实际错误码等于该值。现状是 Kotlin 只断言 `runCatching{}.isFailure`（`ProtocolFixtureTest.kt:37`），C# 只断言错误码属于 47 个已知码之一（`ProtocolReaderTests.cs:35`）——两端因完全不同的理由拒绝也测不出来。

再补一个 Kotlin 侧的 writer round-trip 测试，对照 C# 的 `ValidFixtureSurvivesWriterRoundTrip`（`ProtocolReaderTests.cs:40`）。目前「Kotlin 发出的帧 C# 能接受」完全未测。

v2 侧的语料完整性守卫也缺失：`SyncMessageParseTest.kt:13-17` 断言 v1 valid fixture 集合等于 `ProtocolMessageTypes.ALL`，但 v2 两端都没有同类守卫。

---

## 6. 存储层：其余问题

### 6.1 Android 媒体 blob 垃圾回收是整块死代码

**位置**：`android/.../media/MediaBlobStore.kt:131-134`（`deleteBlob`）、`:154-173`（`deleteUnreferenced`）；`storage/ClipPersistence.kt:47-50` 声明 / `:175-184` 实现

`deleteUnreferenced`、`deleteBlob`、`deleteOrphanedClipMedia`、`allBlobHashes`、`referencedBlobHashes`、`deleteMediaBlob` —— **全部零生产调用方**（已全树 grep 确认，只有测试 fake `InMemoryClipPersistence.kt` 引用）。`purgeExpired`（`ClipRepository.kt:529-540`）只硬删 clip 行；`softDelete`（`ClipPersistence.kt:125-131`）只删 `clip_media` 行。

即使接上，`ClipMediaDao.deleteOrphaned`（`ClipDaos.kt:278-286`）也是基于**仍存在的** `clips` 行来判定（`deleted_at IS NOT NULL OR content_hash = ''`），所以**硬删**掉的 clip 对应的 `clip_media` 行对它不可见。

**失败场景**：同步 200 张截图（约 1 GB）→ 在历史里全部删除 → 等过 30 天保留期。`clips` 空了，`media_blobs` 还有 200 行，约 1 GB 永久躺在 `files/media/blobs`，**只有清除应用数据能回收**。

**修复**：把 GC 接进 `purgeExpired` 和 `clear`/`delete` 路径；修正 `deleteOrphaned` 使其能识别硬删遗留的孤儿行。参照 Windows 的 `CollectUnreferencedBlobsAsync`。

### 6.2 Windows blob GC 与事务前的 blob 提交存在竞态

**位置**：`SqliteClipboardEventStore.cs:197`（`media.CommitBytes` 在事务打开之前）vs `:199-202`（事务开始）；`CollectUnreferencedBlobsAsync` 在 `cs:952-1007`，被 `DeleteAsync:364`、`ClearAsync:393`、`CleanupAsync:450` 调用

**机制**：`CollectUnreferencedBlobsAsync` 开**自己的连接**、在调用方事务**之外**运行，计算 `keep = SELECT DISTINCT content_hash FROM clip_media`，然后 `MediaBlobStore.DeleteUnreferenced(keep)` 删除 `blobs/` 下所有不在 `keep` 中的 64-hex 文件。从 blob 落盘到 `clip_media` 插入之间，blob 在磁盘上但不在 `keep` 中。

**两个失败**：
- 入站：图片传输 `Commit` 到 `blobs/<hash>`；用户删除一条无关历史 → `DeleteAsync` → GC 删掉新 blob → `StoreRemoteEventAsync` 抛 `MEDIA_STORAGE_FAILED`（`.Sync.cs:264`），传输丢失。
- 本地：`StoreImageAsync` 在 `:197` 提交 blob；GC 触发；`:199` 的事务照样提交一条 `kind='image'` 的 `clips` 行 + `media_blobs` + `clip_media`，全部指向已删除的文件。**永久损坏的历史项**，对端 fetch 它会从 `RequirePath` 抛 `FileNotFoundException`。

**修复**：blob 提交移入事务边界内，或 GC 加一个"最近 N 分钟内创建的 blob 不回收"的宽限期，或 GC 与写路径共用锁。

### 6.3 Android 无 by-event-id 同步读，退化为全表扫描

**位置**：`android/.../sync/SyncSessionEngine.kt:882-912`（注释 `:883-885` 明说了设计取舍）

`ClipRepository` 有 `findVisibleEntry(eventId)`（`:275-287`），但返回 `ClipEntry`——无 `terminalReason`，且过滤掉墓碑和无正文行——引擎用不了。没有 Windows `GetSyncableEventsByIdsAsync`（`.Sync.cs:461-493`）的对应物。

**成本**：id 缺失时 `found.size` 永远到不了 `eventIds.size`，`:897` 的 `while` 会一直跑到 `clipsInRange` 返回空——**遍历每个 origin 的完整覆盖**，每次 Room 查询 50 行，每个图片行还有一次 `findMediaBlob` 点查（`ClipRepository.kt:358`）。2 万行时约 400 次查询、2 万行物化。

这在**每次 `drainOutbox()`**（`:863`）都会跑——outbox pending Flow 每次发射 + 每个 `outboxDrainIntervalMs`（2 秒）tick 各一次——外加每次 `handleClipFetch`（`:655`）。

id 缺失是常态：retention 硬删行时**故意保留** `origin_receive_state` 覆盖（`ClipRepository.kt:527-528`），所以旧序号仍"被覆盖"却没有行。

**修复**：给 `ClipRepository` 加一个返回 `SyncableClipEvent`（含 terminalReason、含墓碑）的 by-event-ids 批量查询，对齐 Windows。

### 6.4 outbox 行的 clip 行缺失时永远清不掉，≥50 条会永久卡死 drain

**位置**：`android/.../sync/SyncSessionEngine.kt:856-880` —— `?: continue` 在 `:867`，`if (headers.isEmpty()) return` 在 `:871-873`

没有任何代码路径能清除这种行：`deleteOutboxInRange`（`ClipDaos.kt:177-192`）只通过 `clips` 子查询匹配，看不见它；`deleteByEventId`/`deleteByEventIds` 只从 `delete()`/`clear()`（`ClipRepository.kt:302-316`）到达，都要求 clip 行存在；`markAnnounced` 因为条目在 `announced.add` 之前就被跳过而永远到不了。**没有任何对账清扫。**

**行为**：少于 `MAX_ANNOUNCE_CLIPS`(50) 条悬空行时，新捕获仍能 drain（`pending` 按 id 排序，批次里既有陈旧头部也有活行）。一旦 50+ 条悬空行堆在头部，`batch` 全部不可解析，`headers.isEmpty()` → **return**，此后**再也不会 announce 任何新 clip**。
另有互锁：`hardDeleteExpiredLive` 的 `event_id NOT IN (SELECT event_id FROM outbox)` 守卫意味着一个排不掉的 outbox 行**也会永久钉住它的 clip 行**不被 retention 回收。

**可达性**：retention 守卫使其目前无法经由 `purgeExpired` 触发，属于**潜伏陷阱**而非活跃 bug。但 `outbox.event_id` 上没有外键、没有完整性清扫、除清除应用数据外无恢复手段。任何未来移除 clip 行的路径（迁移、部分应用的恢复、手动删除）都会永久卡死 drain。

**修复**：`drainOutbox` 对无法解析的条目应删除该 outbox 行（或标记为已处理）而非 `continue`；给 `outbox.event_id` 加外键或加一次启动时对账。

### 6.5 Windows 本地捕获伪造连续覆盖

**位置**：`SqliteClipboardEventStore.cs:792-808`（`AdvanceLocalReceiveStateAsync`）

```sql
INSERT INTO origin_receive_state (origin_device_id, contiguous_seq)
VALUES ($device_id, $seq)
ON CONFLICT(origin_device_id) DO UPDATE SET contiguous_seq = excluded.contiguous_seq;
```

直接把新分配的序号写成 `contiguous_seq`，从不碰 `received_ranges`，**绕过 `OriginReceiveState.Accept`**（`Sync/OriginReceiveState.cs:39-74`）——而其他所有写路径都用它（`AdvanceRemoteReceiveStateAsync`，`.Sync.cs:752-790`），Android 的本地捕获也用它（`ClipRepository.kt:89-90`、`:129-130`）。

两个计数器会分叉：`TryInsertImportedLocalAsync`（`.Import.cs:110-124`）把 `local_sequences.next_seq` 抬到 `MAX(next_seq, imported_seq+1)` 但不碰 `origin_receive_state`；`ImportJsonLinesAsync`（`ClipboardImport.cs:79-104`）静默 `skipped++`，包括**未提供媒体目录时的每一个图片行**（`:114-117`）。

**失败场景**：用户 `clipsync.db` 丢失但 `device.id` 存活（在独立文件里，`App.xaml.cs:49`）。恢复 `backup.jsonl` 但没有配套媒体目录：本地 origin 序号 1..500 到达，其中 40 个图片行被跳过。`next_seq` → 501，`contiguous_seq` 仍为 0。下一次复制分配 501 并把 `contiguous_seq` 设为 501，**声称连续覆盖 1..501**。手机的 `missingFrom` 现在包含那 40 个死序号；`GetSyncableEventsAsync` 对它们返回空，`handleWantRanges`（`SyncSessionEngine.kt:471-472`）静默 break。因为 `SyncRangeMath.take`（`sync/SyncRangeMath.kt:49-66`）从**最低**序号开始填 want 预算，那段死掉的低区间会在每次重连时把后面所有积压序号饿死——**永久**。

**修复**：`AdvanceLocalReceiveStateAsync` 改走 `OriginReceiveState.Accept`；导入时同步推进 `origin_receive_state`（与 3.3 同一处修复）。

### 6.6 删除语义两端相反（隐私问题）

**位置**：`ClipRepository.kt:302-309` vs `SqliteClipboardEventStore.cs:338-374`

Android 的 `delete()` 在软删后调 `deleteOutboxForEvent(eventId)`，且不为产生的墓碑入队任何东西 → announce 前删除的 clip 被静默扣留，announce 后删除的 clip 让对端永久持有活副本。
Windows 的 `DeleteAsync` 从不碰 `outbox`，待发行存活，`GetOutboxBatchAsync` 的 `JOIN clips` 捞到已墓碑化的行并向对端 announce `deleted`。

**失败场景**：在手机上复制一个密码 → 同步到 PC → 在手机历史里删除它 → **PC 上的明文永久保留**。反向在 PC 上删，手机会收到墓碑。**同一逻辑模型，相反语义。**

**修复**：统一为"删除产生墓碑并入 outbox 传播"（Windows 的语义），Android 侧不要删 outbox 行而应替换为墓碑条目。同时更新 `docs/threat-model.md:52` 关于「本地清空不承诺删除其他设备已接收的副本」的表述使其与实现一致。

### 6.7 `expires_at` 全链路存储、传输、校验，但无处执行

**位置**：Android `ClipEntities.kt:30-31`、校验在 `ClipRepository.kt:142-143` 和 `ClipImport.kt:115-117`；Windows `cs:144`、`.Sync.cs:305-307`

两端**没有任何删除、清理或可见性查询引用过它**。Android `hardDeleteExpiredLive`（`ClipDaos.kt:124-132`）过滤 `created_at`；`searchVisible`（`ClipDaos.kt:20-33`）只过滤 `deleted_at`。Windows `CleanupAsync`（`cs:419-443`）过滤 `created_at`；`SearchAsync`（`cs:278`）只过滤 `deleted_at`。

**失败场景**：对端发来 `expires_at = now + 5min` 的 clip（协议校验 `expires_at > created_at`，发送方合理地认为它会被遵守）。接收方存储它、无限期展示、导出它、保留满 30 天。**发送方的过期契约被静默作废。**

**修复**：要么在可见性查询和清理路径中执行 `expires_at`，要么从协议中移除该字段。**一个实现了一半的契约比没有更危险。**

### 6.8 Windows 从不硬删 clip 行

全树 grep 确认 `windows/` 下不存在 `DELETE FROM clips`。`CleanupAsync`（`cs:403-458`）只有 UPDATE。Android 有 `hardDeleteExpiredTombstones`（`ClipDaos.kt:134-142`），Windows 无对应物。

`MaximumEntries = 2000` 因此只约束**可见**行数。`clips` 表按"曾经捕获或收到的每一条"单调增长，外加墓碑对应的 `outbox` 行。`GetSyncableEventsAsync` 的范围扫描（`.Sync.cs:438-446`，**不过滤** `deleted_at`）线性劣化。

### 6.9 Android 导出静默丢弃所有图片并截断在 2000 行；导入无大小上限

**位置**：`ui/settings/SettingsViewModel.kt:170-181`、`ui/settings/SettingsScreen.kt:52-59` 和 `:75`；`ClipExport.kt:44-45`；`ClipRepository.kt:546-549`、`:570-573`；`ClipImport.kt:72-75`

`exportTo` 调 `repository.search("", MAX_SEARCH_LIMIT)`（硬上限 2000，`ClipModels.kt:19`）和**单参**的 `ClipExport.encodeJsonLines(rows)` 重载，后者强制 `includeHeader = false` 且不写媒体目录。行里仍带 `media_file`，但 blob 从未被导出。导入时 `importJsonLines(payload)` 用默认 `mediaDirectory = null`，`ClipImport.resolveMediaFile` 在 `:73-75` 返回 null → **每个图片行都被计为 skipped**。

导入大小：`SettingsScreen.kt:75` 对用户选择的 SAF 文档做 `input.readBytes().toString(UTF_8)`，无任何上限；`ClipImport` 也没有单行上限。Windows 两者都有（`MainViewModel.cs:305` vs `ClipboardImport.MaximumImportBytes`；`ClipboardImport.cs:47` vs `MaximumLineChars`）。整个解码+插入循环在单个 Room `withTransaction`（`ClipRepository.kt:550`）内，全程持写锁。

**失败场景**：导出 5000 条含 300 张图片 → 文件里只有 2000 行，图片不可用 → 在新手机上重新导入 → 300 条静默跳过。另：一个 400 MB 的单行文件在解析前就 OOM。

**修复**：导出走带媒体目录的重载并复制 blob；分页导出而非 2000 硬截断；导入加总大小与单行上限（对齐 Windows 常量）；导入分批提交而非单一大事务。

**注**：路径穿越校验两端都正确，无需改动（`ClipImport.resolveMediaFile` 校验 64-hex + 规范化包含关系；`ClipboardImport.cs:119-125`、`:252` 校验 `GetFileName`、哈希相等、拒绝分隔符）。

### 6.10 换网后 Windows 变不可达

**位置**：`windows/ClipSync.App/Sync/PeerSyncHost.cs:81`（`ResolveBindAddresses` 只在启动时执行一次）、`:119-120`（`NetworkAddressChanged` 只重播 beacon）

`StartAsync` 在 `server is not null` 时提前返回，没有任何重新 bind 的路径。Wi-Fi 切以太网 / VPN 上线 / DHCP 换地址后，服务仍监听旧地址，`ReachableHosts` 也是陈旧的（新生成的二维码会写入打不通的 host）。只能重启应用。

另：`IsPrivateIpv4`（`:214-224`）只认 10/x、172.16-31/x、192.168/x，**完全不支持 IPv6**，也不认 Tailscale 的 100.64.0.0/10（靠用户手填 extras 兜底）。

**修复**：`NetworkAddressChanged` 触发重新解析并重新 bind（或重启监听器）；同步刷新 `ReachableHosts`。

### 6.11 Android 图片传输的临时文件泄漏

Windows 有 `Dispose()`（`SyncSessionEngine.cs:1517-1527`）清理 `incomingImages` 里的 `PendingMediaWrite`；Android 的 `run()` finally 块（`SyncSessionEngine.kt:174-185`）**没有任何对应清理**。每次中断的图片传输留一个临时文件。

**修复**：在 finally 中遍历 `incomingImages` 释放 `pending` 并删除临时文件。

---

## 7. 测试基础设施

### 7.1 Room 的 SQL 和迁移从未执行过

`android/app/src/androidTest` **不存在**，尽管 `build.gradle.kts:45` 配置了 `AndroidJUnitRunner`、`:142` 引入了 `room-testing`。

`RoomSchemaContractTest.kt` 靠**字符串 grep 源文件**来"验证"（`assertTrue(source.contains("version = 2"))`）并对 schema JSON 做子串匹配，**从不打开数据库、从不运行 `MIGRATION_1_2`**（`ClipDatabase.kt:44-74`）。所有存储测试都走 `createTestClipRepositories()` → `InMemoryClipPersistence`（`TestClipRepositories.kt:9`）。

**结论：没有任何一句 DAO SQL、没有任何迁移，在任何地方被执行过。** 一个坏掉的 v1→v2 迁移会在 Room 的 identityHash 检查处硬崩每一个升级用户，而没有任何东西能提前发现。（当前 `MIGRATION_1_2` 与 `2.json` 手工比对是正确的，但没有任何机制保证它继续正确。）

**修复**：建 `androidTest` 源集，用 `MigrationTestHelper` 真正执行 1→2 迁移；至少让关键 DAO 查询（`hardDeleteExpiredLive`、`deleteOutboxForEvents`、`searchVisible`、`findLiveContentByHash`）在真实 SQLite 上跑一次。§3.2 的 999 参数 bug 正是靠这个才能被发现。

### 7.2 测试 fake 建模了 Room 没有的语义

按影响面排序：

1. **`findLiveImageByHash` 的存活语义相反。** 真实实现（`ClipPersistence.kt:157-158`）只查 `media_blobs`，所以软删的 clip 仍报 `true`；fake（`InMemoryClipPersistence.kt:200-204`）还要求存在未删除的图片行。这是入站图片去重的判定门（`ClipRepository.kt:368-369`）——生产环境里"删掉一张图就永远收不回来"，测试说不是。
2. **fake 建模了不存在的 `clip_media` 级联删除。** `ClipMediaEntity`（`ClipEntities.kt:128-140`）**没有 `@ForeignKey`**，所以 `DELETE FROM clips`（`ClipDaos.kt:126-142`）会留下孤儿行；fake 显式清理了它们（`:396-400`）。`softDeleteAllVisible`（`ClipPersistence.kt:133-139`）不碰 `clip_media`，fake 的碰。**这正是 §6.1 那条"媒体 GC 是死代码"能长期存活的原因。**
3. **插入冲突类型不同。** fake 抛 `IllegalArgumentException`（`:115-116`），真实是 `OnConflictStrategy.ABORT` 产生的 `SQLiteConstraintException`（`ClipDaos.kt:11`）——按类型分支的回滚逻辑在重复 `event_id` 路径上两边不一致。
4. **`searchVisible` 大小写语义不同。** fake 用 Kotlin 的 Unicode 感知 `contains(ignoreCase=true)`（`:128-144`）；真实是 SQL `LIKE`，**只对 ASCII 大小写不敏感**——中文搜索行为不同。
5. **`findLiveContentByHash` 过滤不同。** 真实查询（`ClipDaos.kt:115-122`）无 kind 过滤、无 `ORDER BY ... LIMIT 1`，可能返回图片行的 `NULL` content；fake 过滤到 text（`:195-198`）。
6. **锁语义不同。** fake 的 `read()` 共用写互斥量（`:64-65`）；真实的 `read()` 无事务无锁（`ClipPersistence.kt:86`）——read-modify-write 竞态在测试中结构性不可见。

**修复**：逐条对齐 fake 与真实实现；对无法在内存中忠实建模的语义（如 SQL `LIKE`、外键缺失），改为在 `androidTest` 中用真实数据库覆盖。

### 7.3 Windows peer 集成测试固定 v1，生产跑 v2

**位置**：`windows/ClipSync.Tests/Peer/PeerTestInfrastructure.cs:212-216`

调用 4 参数的 `PeerSyncClient.ConnectAsync`，该重载硬编码 `protocolVersion: 1`（`PeerSyncClient.cs:21`）并拨 `/v1/peer/sync`（`:52`）。`DefaultSessionOptions()`/`DialerOptions()`（`:196-207`）从不设置 `ProtocolVersion`。生产设置 `ProtocolVersionV2`（`ClipSync.App/Sync/PeerSyncHost.cs:78`）。

**结果**：`/v2/peer/sync` 路由、`ProtocolReaderV2` 分派（`SyncSessionEngine.cs:224-226`）、`image_clip_v2` 能力广播（`:214-216`）、图片收发（`:810-818`、`:685-693`）**零集成覆盖**。`PeerPair.CaptureAsync`（`:238-243`）只能存文本，所以不扩展 harness 就写不出 v2 测试。

公平地说：这**不是**假传输层。它接了真实 `SqliteClipboardEventStore`、真实 Kestrel/TLS `PeerServer`、真实 WebSocket 客户端。唯一的替身是 `FakeSecretProtector`。**缺的是配置维度，不是桩。**

**修复**：把 `PeerTestInfrastructure` 按协议版本参数化；扩展 `CaptureAsync` 支持图片；加 v2 图片端到端集成测试。§4.1 的首次同步 bug 正是靠这个才能被发现。

### 7.4 唯一的真跨语言 E2E 默认关闭

`CrossClientSyncE2eTest.kt:34`（真实 Kotlin dialer → 真实 C# `ClipSync.E2eHost`）被 `Assume.assumeTrue("clipsync.e2e.enabled")` 跳过，常规运行中永不执行。驱动脚本 `scripts/run-e2e-stage4.ps1:7-9` 硬编码 `D:\paste-tools\dotnet` 和 `D:\paste-tools\android-sdk`，且不在 CI 里。无 v2/图片对应物。

### 7.5 入站图片写入在生产不可达，且无测试覆盖

`ShizukuClipboardWriter.kt:37` **只**覆写了 `writeText`，因此继承 `ClipboardWriter.kt:12-13` 的默认 `Failure(IMAGE_WRITE_UNAVAILABLE)`。`ClipboardWriteCoordinator.writeImage`（`:69-82`）探测到 fallback READY、调用它、**永远拿到失败**。

而 `FakeClipboardWriter.writeImage`（`FakeClipboardBackends.kt:84-95`）对任何输入都返回 `Success`——**并且没有任何测试调用过 `coordinator.writeImage`**。

叠加：`FakeBackgroundClipboardBackend.emit`（`:46-54`）无法构造带 `imageBytes` 的 `ClipboardChange`，全仓库没有任何测试这么做。所以图片捕获分支（`ClipboardCaptureRuntime.kt:83-91`）和回环抑制的图片分支（`ClipboardWriteCoordinator.kt:152-157`）在测试中是死的。**一个图片写入/捕获回环会毫无察觉地发布出去。**

### 7.6 写入从不验证；并发写无序列化

- `AndroidClipboardWriteOs.isUsable` 硬编码 `true`（`AndroidPublicClipboardWriter.kt:27-28`），`probe()` 永远返回 `READY`，`resolveWriteMode()` 永远选 `PUBLIC_API`——Shizuku 写回退只在 `setPrimaryClip` **抛异常**时可达。在后台静默 no-op 的 ROM 上，协调器记录 `publicLastSuccessAt` 并报告成功，而剪贴板纹丝未动。**整条写路径没有任何回读校验。**
- 并发写：同步 applier（`ClipboardSyncRuntime.kt:79`，`Dispatchers.IO`）和 `CopyClipReceiver.scope`（`notify/CopyClipReceiver.kt:38`，`Dispatchers.IO`）各自起 `clipsync-public-write` 线程，无序列化；最终内容取决于谁最后到达 OS。`publicLastSuccessAt`/`publicLastError`/`fallbackLast*` 是非 volatile 字段，从这些线程写入的同时 `persistWrite()` 在写 SharedPreferences。

### 7.7 `ClipboardAccessCoordinator` 无同步；去重逻辑是"开关"不是"过滤器"

**位置**：`ClipboardAccessCoordinator.kt:19-22,186-201,246-253`

`listener`、`activeBackend`、`baselineHash`、`state` 都是普通非 volatile `var`，却被 Shizuku binder 回调线程、`adblog-flight` 线程、主线程同时读写（`handleChange` 从三处进入，`switchTo`/`checkHealth`/`stop` 从 manager 主 scope 修改同一批字段）。无锁、无 `@Volatile`。

去重逻辑：
```kotlin
if (change.contentHash == baselineHash) { baselineHash = null; return }
baselineHash = change.contentHash
listener?.invoke(change)
```
命中时把 baseline 置 null 会导致交替：N 次连续相同事件会发出 ⌈N/2⌉ 次。`AdbLogOverlayBackend.onLogSignal`（`adblog/AdbLogOverlayBackend.kt:117-141`）是唯一自身没有去重的 backend，在一次复制打多条日志的 ROM 上会产生重复 clip 和重复同步事件——而且入站写之后的第 3 个事件会逃过（已被消费的）回环标记。

### 7.8 binder 死亡回调重复注册

**位置**：`shizuku/AndroidShizukuRuntime.kt:198,352-367`

`shizukuDeathLinked` 只守住了 `addBinderDeadListener`；`Shizuku.getBinder()?.linkToDeath(...)` 在每次 `bindUserService()` 时无条件执行，而 `ShizukuClipboardBackend.probe()` 在启动后每个健康 tick 都调它。Binder 允许重复注册，所以 host binder 的死亡接收者列表以约 6 次/分钟（约 8600 次/天）增长。host 一旦死亡，`deathListener?.invoke(BinderDeathKind.SHIZUKU)` 按注册次数触发。

全仓库找不到 `Shizuku.removeBinderDeadListener`。

---

## 8. 文档漂移与构建

### 8.1 README 落后约 5 个阶段

- `README.md:3` 称"已完成 **Stage 3**……端到端配对/同步待 wave 3……物理 Android ROM 覆盖仍为 `NOT_TESTED`"。实际：Stage 8 完成 + 已签名 0.2.0（`build.gradle.kts:43-44`，提交 `05cb358`）；`docs/device-validation-matrix.md:25,113` 记录了 Redmi Note 11T Pro / MIUI 14 的 **DEVICE-VERIFIED** 行；`docs/dod-status.md:32` 记录了硬件 Win→Android 约 1 秒。
- `README.md:38` 称 Android 应用"仍是 Stage 0 能力外壳"、"配对与网络从 Stage 2 才开始"。实际是 **114 个 `.kt` 文件**、13 个包，含完整 WebSocket 同步引擎、Room v2、带证书固定的二维码配对、快捷设置磁贴、捆绑特权 host。
- `README.md:58,73` 只提 `protocol/v1/` 和"v1 只处理纯文本"，完全没提 `protocol/v2/`、`docs/protocol-v2.md`、`docs/adr/0003-clipboard-image-v2.md`（状态"接受"，2026-08-20）。新贡献者永远不会知道 `/v2/peer/sync` 和 `image_clip_v2` 的存在。

### 8.2 plan.md 作为进度追踪已完全失效

- `plan.md:811` 写「计划已冻结，尚未实施」，`:485` 写「Stage 9 图片协议 v2（计划）」。Stage 9 的 22 个复选框（`:819-862`）全部 `- [ ]`，包括代码已存在的：`:820`（protocol/v2 schema）、`:827`（`MediaBlobStore`）、`:828`（Room 迁移，`ClipDatabase.kt:21,44,76`）、`:843`（`ClipboardMediaReader.kt`）、`:851`（`begin→chunk*→end` 状态机）。
- **根本没有 Stage 8。** 阶段表（`:519-529`）是 0–7 然后跳到 9，`:529` 说 Stage 9 依赖 Stage 7。而 `docs/stage-8-change-log.md` 有 180 行，描述的是全项目权限最高的代码（捆绑的 `PrivilegedHostService`）——**没有任何计划中的退出标准或验收测试**。`plan.md:963` 甚至提到了这个类名。
- 21 个 Definition-of-Done 复选框（`:903-926`）全部未勾，包括 `docs/dod-status.md` 判定为 DONE 的。Stage 0 复选框（`:543-553`）对明显已存在的东西（含 CI）也未勾。
- 文档互相矛盾：`docs/dod-status.md:9,36,137` 说设备矩阵是未回填的 Stage 0 占位符、「不以该矩阵为实机证据」；`docs/device-validation-matrix.md:3` 说它已于 2026-08-18 回填并**取代**该占位符。`dod-status.md:137` 还在同一文件内与 `:6`、`:36` 就 p95 统计是否存在自相矛盾。

**修复**：更新 README 到实际状态；给 plan.md 补 Stage 8 并同步复选框，或明确把它降级为历史规划文档、由 `docs/dod-status.md` 承担唯一进度真相源；消解 dod-status 与 device-validation-matrix 的冲突。

### 8.3 构建可复现性

版本锁定做得好：`global.json` 锁 8.0.419，`Directory.Build.props` 设 `Deterministic`，Gradle wrapper 锁 8.9，`build.gradle.kts:115-143` 的 Android 依赖全部精确版本。`.tools/` 正确 gitignore 且非必需（`build-android.ps1` 读 `ANDROID_HOME`/`ANDROID_SDK_ROOT`）。

问题：
- `scripts/run-e2e-stage4.ps1:7-9` 硬编码 `D:\paste-tools\dotnet` 和 `D:\paste-tools\android-sdk`——跨语言 E2E 只能在一台机器上跑。
- **`android/app/build.gradle.kts:18` 把 keystore 默认指向 `D:\paste-tools\clipsync-release.keystore`，且失败时静默降级**（`:32`、`:49-57`）：在别的机器上 `assembleRelease` 会安静地产出 debug 签名的 APK 而不报错。**一个悄悄不是 release 签名的 release 构建，比构建失败更糟。** 应改为缺少 keystore 时硬失败。
- 协议语义三处重复：`validate-protocol.py:27-30` 重新声明 4 个上限；Kotlin（`SyncMessages.kt:37-57`）和 C#（`SyncMessages.cs:41-59`）各约 20 个。Kotlin/C# 目前完全一致；Python 是更弱的子集（无 `MAX_CHUNK_COUNT`、无批量/announce 上限），所以 **CI 门槛比两个出货实现都松**。
- `Directory.Build.props` 的 `TreatWarningsAsErrors` + `AnalysisLevel latest-recommended` 配上 CI 的浮动 `dotnet-version: 8.0.x`，意味着新 SDK 的新分析器警告会直接打断构建。
- `tools/ProtocolValidator/` 是空目录且未被引用。

---

## 9. 已验证「不是问题」的部分（不要改动）

- **序号分配的原子性，两端都正确。** Windows `AllocateSequenceAsync`（`cs:553-569`）用原子 `ON CONFLICT DO UPDATE ... RETURNING next_seq - 1`，与 clip 插入、接收状态写入、outbox 扇出在同一个 `Serializable` 事务内（`cs:128-180`、`:206-232`），回滚会取消分配。Android `allocateOriginSeq`（`ClipPersistence.kt:195-199`）的每个调用方都在 `database.withTransaction` 内。崩溃不会导致重用/跳号/丢失。
- **认证密码学两端逐字节一致。** `PairAuthProof` 的消息构造（前缀、requestId、分隔符、nonce、两个 UUID 的 RFC 4122 大端序、trustEpoch 大端序、v2 的版本绑定）Kotlin 与 C# 完全对应；比较均为恒定时间（`MessageDigest.isEqual` / `CryptographicOperations.FixedTimeEquals`）；challenge nonce 是 `RandomNumberGenerator` 32 字节、单次使用、绑定 requestId。auth 向量在两端、v1 和 v2 都有断言。
- **配对流程。** token 256 位熵、恒定时间比较、单次消费、每 IP 每分钟 10 次限流、显式用户确认、二维码不含长期 pair secret、请求体读取有界（`PeerServer.cs:247-266`）。
- **TLS 证书固定本身是正确执行的**（`PeerSyncDialer.kt:190-206` 比较叶证书 SHA-256 并在不匹配时抛异常）。问题只在失败**检测**方式（见 §5.10）。
- **图片解码是先读头再有界解码**，两端都校验魔数、像素预算、编码字节上限，符合威胁模型。
- **Room 主线程查询**：`allowMainThreadQueries()` 只出现在内存测试 builder（`ClipDatabase.kt:89-92`），所有 DAO 方法都是 `suspend` 或返回 `Flow`。
- **Schema 迁移两端都 fail-closed**（`ClipDatabase.kt:78-82` 无破坏性回退；`ApplyOrderedMigrationsAsync` `cs:585-592`）。
- **导入的路径穿越与哈希校验两端都正确**（见 §6.9 附注）。
- **`SyncMessageWriter.kt` 正确省略 null**（`explicitNulls = false`），不会发出 C# `ScanTokens` 会拒绝的显式 null。
- **`PrivilegedHostService.kt:68,80,84,264` 的 5 个 `throw SecurityException("... is not implemented")` 是刻意的攻击面拒绝**，有 `:27` 的注释说明并由 `ShizukuUserServiceSurfaceAuditTest.kt` 断言。**不是未完成的功能，不要"实现"它们。**
- 全仓库 `android/app/src` 和 `windows` 下**零个** TODO/FIXME/HACK/XXX/`TODO()`/`NotImplementedException`。

---

## 10. 给实施方的执行约束

1. **每修一条，检查另一端。** §1 的表格说明本项目的历史缺陷就是单边修复。任何改动请显式确认对端状态并在提交信息中说明。
2. **不要削弱已有的严格性。** 修复方向一律是"把弱的一侧对齐到强的一侧"，不是放松强的一侧。特别是：不要为了让两端一致而移除 C# 的校验。
3. **`docs/protocol-v1.md` 是 normative。** 与之冲突的实现要改实现；确需改规范时单独提出并说明理由。
4. **日志纪律是硬约束。** `docs/threat-model.md:36-37` 禁止正文、原图字节、缩略图字节、URI、nonce、proof、pair secret、token、私钥进入日志。现有实现执行得很好（`clipsync-diag.log` 只有事件 token），不要破坏。
5. **先做 §2 的三项前置条件**，否则任何修复都无法构建和验证。
6. **建议的顺序**：§2 → §3（静默丢数据）→ §4（崩溃与功能失效）→ §5.11 + §7.1 + §7.3（防复发的测试）→ §5 其余 → §6 其余 → §8。
7. **验证方式**：`pwsh .\scripts\build-windows.ps1`、`pwsh .\scripts\build-android.ps1`、`pwsh .\scripts\validate-protocol.ps1`。注意 protocol 脚本需要 `pip install jsonschema referencing`。

---

## 11. 审查方法与置信度说明

- §3.1、§3.2、§4.1、§4.2、§4.4、§5.1–§5.8、§6.1、§6.3、§6.4、§6.10、§7.1（androidTest 不存在）、§2.1、§2.2 —— 由审查者逐行读代码确认。
- §4.1 的失败时序为静态推导（`sendLock` 保证发送串行、双方接收循环顺序处理），未经实机复现；建议实施时先写出复现测试再修。
- §4.3、§4.5、§4.8、§6.5、§6.9、§7.2、§7.5–§7.8 —— 来自专项深度分析，机制与行号具体，未逐条二次复验。
- §9 的"不是问题"结论均已验证。
- 未覆盖范围：前端 UI（Compose / WPF XAML）按要求排除；本地化文案未审查；性能未做实测。

