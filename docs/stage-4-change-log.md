# 阶段 4 变更记录

日期：2026-08-17  
状态：**Android 伴侣端代码面完成、端到端联测由 wave 3 收口**（Android `testDebugUnitTest` 182/182、27 个测试类、0 failures；Windows Tests 169/169、App.Tests 33/33 为阶段 3 已记录基线，wave 3 在 E2eHost 落地后重跑，不在本记录中冒充复测；协议 fixtures 12 valid + 15 invalid、配对 fixtures 4 valid + 7 invalid 沿用阶段 3 校验结果。实体 Android ROM 覆盖仍为 `NOT_TESTED`）。

## 阶段目标（plan.md 阶段 4）

Android 伴侣端、协议和手动基线：历史列表/搜索/详情/设置、Room（收件箱/outbox/游标/本地删除标记）、
OkHttp WebSocket 客户端（认证、心跳、指数退避）、`known_vector`/缺失范围/正文交换与 `ack_ranges`、
通知复制、`ACTION_SEND` 分享目标、Quick Settings Tile、`ForegroundClipboardBackend` + `PublicClipboardWriter` 基线。
不实现阶段 5 的 Shizuku / ADB 日志 / 悬浮窗轮询 backend，也不引入账号、云、文件传输或剪贴板正文日志。

## 构建环境（后续 agent 必读）

- .NET SDK 8.0.419 仍在 **`D:\paste-tools\dotnet`**（仓库外，不在系统 PATH）。每个新会话先执行：
  `$env:PATH = 'D:\paste-tools\dotnet;' + $env:PATH; $env:DOTNET_ROOT = 'D:\paste-tools\dotnet'`
- Android SDK 在 **`D:\paste-tools\android-sdk`**
  （cmdline-tools latest、platform-tools、platforms;android-35、build-tools;34.0.0/35.0.0，许可证已接受）。
  构建命令：`$env:ANDROID_HOME = 'D:\paste-tools\android-sdk'; pwsh scripts/build-android.ps1`
- JDK 17.0.20（Microsoft OpenJDK）安装在系统 `C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`，`java` 在 PATH。
- 协议校验需要 Python 3 + jsonschema 4.x（系统已有 Python 3.14.3 + jsonschema 4.26.0）：`pwsh scripts/validate-protocol.ps1`
- Maven Central 偶发 TLS 握手中断导致 Gradle 依赖下载失败（`SSL peer shut down incorrectly`）；重跑即可，无需改配置。

## 端到端联测(wave 3)

**结论：真实 C# 监听端与真实 Kotlin 拨号端在本机 TLS 上互通验证通过（E2E-PASS），双向各一条、恰好一次，重连会话干净结束。**

联测基础设施（全部入库，可重复执行）：

- `windows/ClipSync.E2eHost/`：net8.0 控制台宿主（已加入 `ClipSync.sln`）。临时目录建库、自签证书、loopback `PeerServer`、预置一台配对 Android 设备（随机 32 字节 secret、epoch 1），就绪后向 stdout 输出一行 JSON（端口/指纹/双方设备 ID/secret），随后接受 `capture`/`list`/`quit` stdin 命令；日志仅进 stderr，不含正文与秘密。
- `android/.../e2e/CrossClientSyncE2eTest.kt`：JVM 联测，仅在 `clipsync.e2e.enabled=true` 时执行，平时 skip（全量套件里表现为 1 skipped）；用 fake KeyValueStore/SecretProtector 构造 `PairingStore`、`TestClipRepositories` 构造仓库，走真实 `createSyncController` + OkHttp 拨号。
- `scripts/run-e2e-stage4.ps1`：编排宿主与 Gradle（`--no-daemon`），断言 Windows 侧最终恰好含 Android 的那条文本，输出 `E2E-PASS`/失败原因。
- `android/app/build.gradle.kts`：新增仅转发 `clipsync.e2e.*` 系统属性进测试 JVM 的增量配置。

覆盖路径：TLS 指纹固定 -> `hello`/`challenge`/HMAC `auth` -> 权威 `known_vector` -> `want_ranges` 补齐拨号前已捕获的 Windows 积压 -> announce/fetch/payload/`ack_ranges` 双向 -> outbox 清空即已确认。

**联测发现并修复一个真实缺陷**（首轮 E2E 即暴露，属 sync 包）：首个会话完成后控制器重连，第二个会话被 Windows 以 `MESSAGE_OUT_OF_ORDER / payload_without_fetch` 断开。根因四条链路叠加：`resetOutboxToPending` 把已交付但未及删除的 announced 行复活；outbox drain 在收到对端 `known_vector` 之前就开始重播；对端按哈希去重后只 ack 不 fetch，而引擎仍可能发出未被 fetch 的 `clip_payload`；`SyncController.runLoop` 用外层 scope 判活导致 `stop()` 后仍能再拨。修复（仅 sync 包）：重置后先按持久化 peer cursor 重放 ack 清行、首次 drain 门控在对端向量之后、`clip_payload` 仅允许响应本会话内收到的 `clip_fetch`、会话拆除即禁发、重连判活改用当前协程上下文；并新增 4 个重连场景单元测试固化行为。

wave 3 复跑（编排端独立执行，2026-08-17）：

- `run-e2e-stage4.ps1`：`E2E-PASS`，宿主 stderr 两个会话均 `authenticated=True code=none detail=cancelled`，无 `MESSAGE_OUT_OF_ORDER`；`list count=2` 恰好一次。
- `build-android.ps1`：BUILD SUCCESSFUL，**186 个测试 0 失败**（1 skipped 为门控的 E2E 用例本体）。
- `build-windows.ps1 -Configuration Debug`（sln 含 E2eHost）：0 警告 0 错误，Tests `169/169`、App.Tests `33/33`。

## 交付内容

下列路径均已在仓库中核对存在；未列出的包（例如 `service/` 前台服务）本阶段没有创建。

### storage（Room schema v1 + JVM 内存持久化）

Room `@Database(version = 1, exportSchema = false)`，库名 `clipsync.db`。表：`clips`、`outbox`、`origin_receive_state`、`peer_cursors`、`local_sequences`、`settings`。没有 `devices` 表、没有独立 `tombstones` 表（本地删除写在 `clips.deleted_at` + `terminal_reason=deleted`）。

- `storage/ClipEntities.kt`：六张实体；`peer_cursors` 在契约列 `received_seq` + `acked_at` 之外另有序列化 `received_ranges`。
- `storage/ClipDaos.kt`：各表 DAO。
- `storage/ClipDatabase.kt`：`persistent()` 与 `inMemory()`（后者给设备/instrumentation；JVM 单元测试不走 Android SQLite）。
- `storage/ClipPersistence.kt`：事务门面；注释写明 JVM 上可用同 schema 的内存实现。
- `storage/ClipRepository.kt`：本地捕获与远端 ingest 在同一事务分配/提交 `origin_seq` 再 fan-out outbox；可选 `peerId`，缺省读 settings 键 `paired_peer_id`。
- `storage/OriginReceiveState.kt`：连续游标 + 缺口 ranges；`seq=12` 而 11 缺失时 contiguous 停在 10 并记录 `{12,12}`。
- `storage/ClipModels.kt`：`SETTING_PAIRED_PEER_ID`、拒绝原因、terminal reason、仓库公开类型。

JVM 单元测试驱动 `ClipRepository` 走内存 `ClipPersistence`（`InMemoryClipPersistence`），不引入 Robolectric。Room KSP 仍编译并类型检查 DAO SQL。`Room.inMemoryDatabaseBuilder` 留在 `ClipDatabase` 上供日后 instrumentation 使用。

### protocol（PairAuthProof 向量 + SyncMessages 解析 + SyncMessageWriter 编码）

- `protocol/PairAuthProof.kt`：移植 Windows `PairAuthProof.Compute/Verify`；算法小写 `hmac-sha256`；UUID 字节按 RFC 4122 大端从规范 hex 解析，不用 Java UUID MSB/LSB 布局；消费 `protocol/v1/fixtures/auth/vectors.json`。
- `protocol/SyncMessages.kt`：hello/challenge/auth/known_vector/want_ranges/clip_announce/clip_fetch/clip_payload/ack_ranges/error/ping/pong 的类型化 body，经已有 `ProtocolJson.parseEnvelope` 解析。
- `protocol/SyncMessageWriter.kt`：与 parse 对称的编码路径；可选字段省略、不写 null。
- 既有、本阶段未改所有权的：`protocol/ProtocolJson.kt`、`protocol/ProtocolEnvelope.kt`。

### sync（engine / dialer / controller）

Android 永远是拨号方。信任数据复用阶段 3 `PairingStore`（hosts、port、证书 pin、pair secret、trust epoch、本机 device id）；持久化只走 `ClipRepository`。

- `sync/ISyncTransport.kt`：传输抽象，测试注入 fake。
- `sync/SyncSessionModels.kt`：默认应用层 ping 30s、连续 3 次无 pong 断开、握手超时 15s、退避上限 5 分钟。
- `sync/SyncSessionEngine.kt`：握手顺序 `hello → challenge → auth → known_vector → want_ranges → announce/fetch/payload → ack`；身份冲突/认证失败关套接字；新会话 `resetOutboxToPending`。
- `sync/PeerSyncDialer.kt`：`OkHttpSyncConnector` 连 `wss://host:port/v1/peer/sync`，带 `X-Protocol-Version: 1`；TLS pin 与 `PairingConfirmClient` 相同（忽略链和主机名，pin 不匹配立刻终止全部尝试）。
- `sync/SyncController.kt`：Activity/Application 作用域的 start/stop/重连循环，**不**拥有 ForegroundService。退避 1、2、4、8、16、30 秒，之后停在 30 秒，且不超过 5 分钟。
- `sync/SyncRangeMath.kt`：`want_ranges` 缺口计算，不另造第二套 Room schema。

撤销没有实时 revoke 表：会话中 `isPeerTrusted` 重新读取 `PairingStore.peer()`，比较 `deviceId` + `trustEpoch`；对不上则 `DEVICE_REVOKED`。

### platform/clipboard（前台读取 + 公开写入）

本阶段新增：

- `platform/clipboard/ForegroundClipboardBackend.kt`：`FOREGROUND_ONLY`；仅在 `isVisible()` 为真时用普通 `ClipboardManager`；`start`/`stop` 注册/注销 listener；空/非文本不发事件；probe/health **不**把后台读取标为 READY。稳定错误码：`FOREGROUND_READ_NOT_VISIBLE`、`FOREGROUND_READ_UNAVAILABLE`、`FOREGROUND_READ_FAILED`。JVM 无法构造真实 `ClipboardManager` 时抽出同文件内的 `ClipboardOs`。
- `platform/clipboard/AndroidPublicClipboardWriter.kt`：`ClipboardManager.setPrimaryClip`；成功 / 系统拒绝 / 超时 / 不可用。稳定错误码：`PUBLIC_WRITE_REJECTED`、`PUBLIC_WRITE_TIMEOUT`、`PUBLIC_WRITE_UNAVAILABLE`。同文件内 `ClipboardWriteOs`。从不记录剪贴板正文。

既有协调器未改 API：`BackgroundClipboardBackend.kt`、`ClipboardAccessCoordinator.kt`、`ClipboardWriteCoordinator.kt`、`ClipboardWriter.kt`（`PublicClipboardWriter` 别名）、`ClipboardModels.kt`、`ContentHasher.kt`。

### ui / share / tile / notify + MainActivity 装配

四个底部 tab：History / Status / Settings / Pairing。配对成功后 `PairedPeerIdSync` 把 `PairingStore.peer().deviceId` 写入 `SETTING_PAIRED_PEER_ID`，再 `SyncController.start()`。`POST_NOTIFICATIONS` 可选：拒绝不崩溃，入站复制通知不可见。

- `ui/history/HistoryScreen.kt`、`ui/history/HistoryViewModel.kt`：列表、搜索、复制（走 `ClipboardWriteCoordinator`）、删除、清空；空/超大/未配对/Windows 不可达用明确 notice。没有独立详情页，行内展示 preview（160 字符）+ 来源。
- `ui/settings/SettingsScreen.kt`、`SettingsViewModel.kt`、`SettingsKeys.kt`：暂停、私密、`auto_apply_remote`。
- `ui/settings/CapabilityStatusCards.kt`、`CapabilityStatusProvider.kt`、`SyncStatusProvider.kt`、`SyncControllerStatusAdapter.kt`：网络 / 服务 / 读 / 写四张卡分开着色，不把一项绿色画成全部可用。服务卡固定 “Not running”（ForegroundService 属阶段 5）。
- `ui/settings/PairedPeerIdSync.kt`、`LocalCapturePolicy.kt`、`ClipServices.kt`：进程级仓库/写协调器装配；不启动前台服务。
- `ui/HealthScreen.kt`：Status tab，复用同一组能力卡。
- `share/ShareReceiverActivity.kt`、`share/ShareCaptureHelper.kt`：`ACTION_SEND` `text/plain`，`captureLocalText` 带 `paired_peer_id`。
- `tile/SendClipboardTileService.kt`、`tile/TileClipboardSender.kt`：快捷设置“发送当前剪贴板”。
- `notify/InboundClipApplier.kt`、`InboundClipNotifier.kt`、`CopyClipReceiver.kt`、`InboundNotifyPolicy.kt`、`NotificationPermission.kt`：入站先落库；`auto_apply_remote` 开启时先公开写入，失败或关闭时发不含正文的“复制到剪贴板”通知。

既有配对 UI（`ui/pairing/*`）本阶段只被 MainActivity 观察，不改配对密码学。

### Manifest 增补

相对阶段 3（`INTERNET` + `CAMERA`）：

- 可选 `POST_NOTIFICATIONS`（拒绝不得崩溃）。
- `ShareReceiverActivity`：`ACTION_SEND` / `text/plain`，透明主题、不进最近任务。
- `SendClipboardTileService`：`BIND_QUICK_SETTINGS_TILE`。
- `CopyClipReceiver`：`exported=false`。
- **没有** `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` / `CHANGE_NETWORK_STATE`（阶段 5）。

配套资源（已存在）：`res/values/strings.xml`、`res/drawable/ic_qs_send.xml`、`res/drawable/ic_notification.xml`、`res/values/styles.xml`（含透明分享主题）。

## 多 agent 协同方式

阶段 4 按 `docs/stage-4-contract.md` 的文件所有权并行，而不是单人顺序改同一棵树：

- **Wave 1**：Agent A（Room storage）、Agent B（PairAuthProof + SyncMessages parse）、Agent C（ForegroundClipboardBackend + AndroidPublicClipboardWriter）。互不改对方文件。
- **Wave 2**：Agent D（`sync/**` + 仅新增 `SyncMessageWriter.kt`）、Agent E（history/settings/share/tile/notify + `MainActivity.kt` + `AndroidManifest.xml` + 必要 strings）。
- **Wave 3**：编排端做端到端联测与文档收口；本记录的联测节只留占位，不编造结果。
- 子 agent 模型为 **grok 4.6**，各自只写契约允许的路径。
- 并行跑 `testDebugUnitTest` 时出现过跨 agent 的瞬时失败（同时写 build 目录、未完成类型被另一侧测试引用）。最终以一次合并后的全量绿跑为准：27 类、182/182、0 failures（见下方验证表）。不要把并行中途的红跑写进验收。

## 设计决策 / 相对 plan.md 与契约的偏差

均有意为之，来自 `docs/stage-4-contract.md` Agent A notes 与源码注释/实现，不是静默砍验收。

1. **没有 `devices` 表**：配对 peer 仍在阶段 3 的 `PairingStore`（单 peer 信任）。Room 只存 clips/outbox/游标/settings。`captureLocalText` 可带 `peerId`，否则用 settings 键 `paired_peer_id` fan-out outbox。
2. **`paired_peer_id` 设置**：MainActivity 观察 `PairingUiState.Paired` / Idle，由 `PairedPeerIdSync` 写入仓库；不改配对 JSON/客户端。
3. **JVM 内存持久化，不用 Robolectric**：Android SQLite 在 JVM 上是桩；Agent A 不能改 Gradle 加 Robolectric。契约要求的“reopen persistence”由内存 `ClipPersistence` 镜像表结构覆盖；磁盘 Room 路径留给 instrumentation / 阶段 5。
4. **`peer_cursors` 额外 `received_ranges` 列**：契约列 `received_seq` + `acked_at` 保留，另序列化缺口 ranges，使 gap ack 与协议 v1 / Windows `OriginReceiveState` 同形。另有 `origin_receive_state` 表维护本机连续游标。
5. **单 peer 信任**：Android 只钉一台 Windows（阶段 3 已定）；设置页 `pairedDeviceCount` 为 0 或 1。
6. **没有 live revoke 表**：不在 Room 维护 `revoked_at`。会话内每次数据消息前重读 `PairingStore` 的 device id + trust epoch；遗忘配对后旧连接按 `DEVICE_REVOKED` / epoch 不匹配关闭。
7. **Ping / 退避参数**：应用层 ping 30s、3 次未应答 pong 断开；重连 1、2、4、8、16、30s，封顶 5 分钟。另实现握手超时 15s（契约/注释，plan.md 未写死 15s）。
8. **QS tile `isVisible = { true }`**：`SendClipboardTileService.onClick` 是用户手势，系统此时允许读剪贴板。`ForegroundClipboardBackend` 在 `isVisible()==false` 时直接 `FOREGROUND_READ_NOT_VISIBLE`；磁贴不在 Activity 窗口焦点里，必须把这次手势视为可见，否则合法发送会被拒。MainActivity 前台路径仍用 `hasWindowFocus()`。
9. **暂停 / 私密只在 share/tile 层拦截**：`LocalCapturePolicy` 读 `is_paused` / `is_private_mode`。`ClipRepository.captureLocalText` 只做空文本、1 MiB、2 秒去重，**没有** plan §3.4 完整策略引擎（来源黑名单、方向、字符/字节规则、peer 规则、超限 `local_only`/`drop`）。完整引擎推迟到阶段 5。
10. **同步从 Activity 作用域启动**：`SyncController` 明确不拥有 `connectedDevice` ForegroundService；进程被杀后靠 Room 落库恢复，不伪造“服务在跑”。
11. **引擎不依赖 `get-by-event-id` 仓库 API**：`SyncSessionEngine` 用 `knownVector` 覆盖面 + `getSyncableEvents` 解析 fetch，避免第二套 schema。
12. **没有独立详情页**：历史“详情”落成列表行 preview + 复制/删除，不是单独的 Detail composable。

## plan.md 阶段 4 任务清单对照

| plan.md 阶段 4 任务 | 结论 | 说明 |
|---|---|---|
| 历史列表、搜索、详情和设置页面 | **partially** | 列表/搜索/设置已有；无独立详情页，行内 preview + 复制/删除。 |
| Room 保存收件箱、outbox、游标和本地删除标记 | **done** | schema v1；删除为 tombstone（清空正文 + `deleted_at`），无远程 wipe。 |
| OkHttp WebSocket 客户端、认证、心跳、指数退避 | **done** | JVM 单测覆盖；TLS pin 与配对客户端同策略。 |
| 交换 `known_vector`、缺失范围、正文，落库后 `ack_ranges` | **done** | 引擎单测覆盖 gap/ack/乱序；双端联测见 wave 3 占位。 |
| “复制到系统剪贴板”按钮和通知操作 | **done**（JVM） | 历史 Copy + 通知 action；公开写入失败保留手动入口。实体机未测。 |
| `ACTION_SEND` 文本分享目标写入 outbox | **done** | `ShareReceiverActivity` + `ShareCaptureHelper`。 |
| Quick Settings Tile“发送当前剪贴板” | **done** | `ForegroundClipboardBackend.readText()` 后 `captureLocalText`。 |
| `ForegroundClipboardBackend` + `PublicClipboardWriter` 基线 | **done**（JVM） | 错误码稳定；实体 ROM 写回为 `NOT_TESTED`。 |
| “后台自动同步”设置页和能力卡片 | **partially** | 四卡分开（网络/服务/读/写）；无电池优化、最近成功时间、可执行修复动作向导。服务卡固定未运行。 |
| 入站先落库；`auto_apply_remote` 先公开写入再无正文通知 | **done**（JVM） | `InboundClipApplier`；特权回退属阶段 5。 |
| 空内容、超大文本、缺失权限、Windows 离线的明确状态 | **done** | History notice + share/tile Toast。 |
| ForegroundService `connectedDevice` | **deferred-to-stage-5** | 契约禁止本阶段伪造常驻后台服务。 |
| 完整 §3.4 策略引擎 | **deferred-to-stage-5** | 仅 share/tile 上的暂停/私密门闩。 |
| 仅通知路径打磨（无写权限时的产品化） | **deferred-to-stage-5** | 基本通知复制已有；常驻通知动作/故障卡未做。 |
| 实体设备验收（AOSP/Pixel 公开写入等） | **deferred-to-stage-5** / **NOT_TESTED** | 本阶段不把任何路径标为实体机已测。 |

plan.md 阶段 4 **验收**（2 秒收件箱、后台公开写入、杀进程不丢库、旋转/切网不重复、无特权仍双向可用）依赖 Windows↔Android 联测与实体 ROM，**不能**用 JVM 绿跑代替。结论交给 wave 3 占位节，不在此填写通过。

## 验证（2026-08-17）

- Android `testDebugUnitTest`（`android/app/build/test-results/testDebugUnitTest/*.xml`，时间戳 2026-08-17T04:06）：**182 tests / 27 classes / 0 failures / 0 errors / 0 skipped**。

| 测试类 | tests |
|---|---:|
| `storage.ClipRepositoryTest` | 18 |
| `storage.OriginReceiveStateTest` | 6 |
| `storage.RoomSchemaContractTest` | 3 |
| `protocol.SyncMessageParseTest` | 13 |
| `protocol.PairAuthProofTest` | 6 |
| `protocol.ProtocolFixtureTest` | 6 |
| `sync.SyncSessionEngineTest` | 12 |
| `sync.SyncControllerTest` | 6 |
| `sync.PeerSyncDialerTest` | 2 |
| `sync.SyncRangeMathTest` | 4 |
| `sync.SyncMessageWriterTest` | 3 |
| `platform.clipboard.ForegroundClipboardBackendTest` | 18 |
| `platform.clipboard.AndroidPublicClipboardWriterTest` | 7 |
| `platform.clipboard.ClipboardWriteCoordinatorTest` | 5 |
| `platform.clipboard.ClipboardAccessCoordinatorTest` | 4 |
| `platform.clipboard.CapabilityReportTest` | 1 |
| `ui.history.HistoryViewModelTest` | 10 |
| `ui.settings.SettingsViewModelTest` | 6 |
| `ui.settings.PairedPeerIdSyncTest` | 3 |
| `ui.pairing.PairingViewModelTest` | 12 |
| `pairing.PairingStoreTest` | 8 |
| `pairing.PairingConfirmClientTest` | 7 |
| `pairing.PairingFixtureTest` | 4 |
| `notify.InboundNotifyPolicyTest` | 6 |
| `notify.InboundClipApplierTest` | 3 |
| `share.ShareCaptureHelperTest` | 5 |
| `tile.TileClipboardSenderTest` | 4 |

- Windows：阶段 3 变更记录为 Debug/Release 0 警告 0 错误，Tests **169/169**、App.Tests **33/33**。阶段 4 未在本 agent 会话重跑；wave 3 在 E2eHost 落地后重跑（见上方占位节）。
- 协议 fixtures：阶段 3 记录为 **12 valid + 15 invalid** 协议、**4 valid + 7 invalid** 配对，全部按预期。本阶段未改 schema/fixture 文件。
- `THIRD_PARTY_NOTICES.md` 已纳入 Room 2.6.1（runtime/ktx/compiler）、KSP 2.0.21-1.0.28、`room-testing` 2.6.1、`androidx.arch.core:core-testing` 2.2.0。
- **没有**实体 Android 设备或 ROM 测试记录。

## 未完成 / 已知限制（交接给阶段 5）

- **`ClipboardSyncService`（`foregroundServiceType=connectedDevice`）未实现**：同步随 `MainActivity` 生命周期 start/stop；退到后台或进程被杀后没有常驻连接。Room 是唯一可靠状态。
- **完整 §3.4 策略引擎未实现**：无来源黑名单、方向、字符/字节上限、peer 规则、超限 `local_only`/`drop` 的统一执行点。暂停/私密只挡分享和磁贴。
- **通知-only 路径未产品化**：无 FGS 常驻通知、无“暂停全部 / 立即同步 / 打开故障状态”动作；`POST_NOTIFICATIONS` 拒绝后只有应用内历史。
- **特权读/写 backend 全部未做**：Shizuku、ADB+overlay、overlay 轮询、`OverlayFocusController`、写回退。公开 writer 的 OEM 差异未在实体机记录。
- **能力卡片不完整**：无电池优化、`READ_LOGS`、Shizuku、悬浮窗、最近成功时间、一键修复。服务状态不会变绿。
- **JVM 测试不能证明杀进程后磁盘不丢**：仓库实现走 Room 磁盘 builder，但单测用内存 persistence。进程重建/旋转的 UI 层无 instrumentation。
- **历史详情页、扫码 instrumentation、实体 ROM 矩阵**仍缺。实体覆盖保持 `NOT_TESTED`，阶段 5 开始前必须按 plan §5.7 / §8.3 实测，不能把本阶段代码面完成写成能力 `READY`。
- 端到端（Windows 复制 → Android 2 秒内收件箱、分享无需打开 Windows 主界面、断线补齐）由 wave 3 填写，本文件不预填通过。

## 下一步（阶段 5 起点）

按 plan.md 阶段 5：读取/写入模式状态机、`connectedDevice` 前台服务、Shizuku / ADB+overlay / overlay 轮询、权限向导、以及至少四类实体 ROM 的 P95 与降级记录。先读 `docs/android-background-clipboard.md` 并按 §8.3 重新核对官方链接与当前 `targetSdk`。
