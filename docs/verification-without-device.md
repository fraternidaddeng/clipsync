# 无实体设备的自动化验证边界（verification without device）

- 日期：2026-08-24
- 分支：`cursor/implement-charter-ui-1991`
- 目的：明确「JVM/CI 自动化测试已经证明了什么」与「必须在实体设备上人工 QA 什么」，避免把绿色的测试结果误读为整机可用性声明。实体机验收标准见 `docs/device-validation-matrix.md`。

## 如何运行

| 套件 | 命令 | 运行环境 | 当前规模 |
|---|---|---|---|
| Android JVM 单元/集成测试 | `cd android && ./gradlew testDebugUnitTest` | 任意装有 JDK 17+ 与 Android SDK 的机器（无需模拟器/设备；Robolectric 提供 Android 框架） | 496 个用例 |
| Windows 核心/对端测试 | `cd windows && dotnet test ClipSync.Tests/ClipSync.Tests.csproj` | 任意 .NET 8 平台（Linux/macOS/Windows；真实 Kestrel + TLS + WebSocket 回环） | 378 个用例 |
| Windows 应用层测试 | `cd windows && dotnet test ClipSync.App.Tests/ClipSync.App.Tests.csproj` | 仅 Windows（WPF/DPAPI/Win32 剪贴板；CI 的 `windows-latest` 作业执行） | 59 个测试方法 |
| 协议 fixture 校验 | `python3 scripts/validate-protocol.py` 或 `scripts/validate-protocol.ps1` | 任意平台 | v1：12 valid + 37 invalid；v2：15 valid + 15 invalid；配对：5 valid + 7 invalid |

非 Windows 机器上可用 `dotnet build ClipSync.App.Tests/ClipSync.App.Tests.csproj -p:EnableWindowsTargeting=true` 做编译级检查，但 WPF 测试本体只能在 Windows 上执行。

## 测试已经证明的（不需要实体设备）

### 双端协议与存储（`windows/ClipSync.Tests`，可在任意平台运行）

`Peer/PeerSyncIntegrationTests` 用**真实的 Kestrel 监听端 + 真实 TLS（证书 pin）+ 真实 WebSocket** 在回环地址上运行两个 SQLite 存储（一个扮演 Windows、一个扮演 Android 角色），证明：

- 双向收敛恰好一次：离线捕获重连补投不重复；删除以 terminal 标记传播且不带正文。
- 认证边界：错误密钥失败并被节流、未知设备/信任纪元不匹配被拒、撤销配对立刻断开活跃会话并阻止新会话、错误证书 pin 无法建连、协议版本头不符在升级前被拒。
- 背压与限额：want_ranges 分轮受限拉取、超限 want 得到可重试的 RATE_LIMITED、超大帧得到 PAYLOAD_TOO_LARGE。
- 日志卫生：会话日志不含正文、密钥、proof、nonce。
- **通路页数据源在真实会话事件后的取值**（本次新增）：
  - `OutboxStatusDrainsToZeroWithAnAckTimestampAfterALiveSession`——发件队列深度与「对端确认至」时间戳（`MainViewModel.RefreshOutboxAsync` 读取的快照）在真实会话排空后归零并记录确认时间；
  - `ListenerRaisesRemoteClipsCommittedAndBumpsLastSeenForPhonePushes`——手机推送使监听端抛出 `RemoteClipsCommitted`（App 层自动写回与设备活跃刷新的输入）并盖章 last-seen；
  - `ConnectedDeviceSnapshotFollowsTheFullSessionLifecycle`——连接/断开全生命周期中 `SessionsChanged` 与去重后的已连接设备数（`UpdatePeerStatus` 的输入）。

### Android 端到端链路（`android` JVM 测试，Robolectric，无模拟器）

本次新增 `sync/WindowsAndroidSyncChainTest`：脚本化的传输层扮演 Windows 监听端，其余全部是生产件——`SyncEngine` 跑在 **Room 真库**（内存模式）上的 `RoomSyncRepository`，入站走 `InboxDelivery`（先落收件箱、再按设置自动写入假 writer），出站走 `ClipboardCaptureManager` → `SettingsGatedClipOutbox` → Room outbox → 引擎排空。接线方式与 `ClipboardSyncService.launchSyncStack` 一致。已证明：

- Windows 推送 → 握手/challenge/HMAC → announce/fetch/payload/ack → Room 落库、接收向量推进、收件箱记录、自动写入到达 writer（含 originEventId 供回环抑制）。
- 批量推送只有最新一条自动写入，全部条目进收件箱（与 Windows 行为一致）。
- `auto_apply_remote` 关闭、或 `sync.paused` 打开时：照常接收进收件箱，但绝不写系统剪贴板。
- 手机前台复制 → 捕获 → 闸门 → 共享队列 → 服务侧 drain 进 Room（分配序号）→ 引擎 announce → Windows fetch/payload/ack → outbox 清空，不重复通告。
- 暂停/私密在**每一层**关闭出站：捕获管理器、settings 闸门包裹的队列、引擎 outboundAllowed；恢复后下一次排空 tick 补投，无丢失。
- 自动写入的 Windows 剪贴不会回传（共享写协调器的一次性内容抑制），且相同文本的真实二次复制仍然上行。

既有 Android 套件继续覆盖：`SyncEngine` 协议状态机全错误路径、`RoomSyncRepository`/`ClipSyncRepository` 事务不变量、重连退避、`SyncSupervisor` 生命周期、pinned-TLS 连接器（MockWebServer + 真 TLS）、配对存取、开机恢复链、通知策略、以及 Shizuku/ADB 日志/悬浮窗后端的**纯逻辑**部分（解析器、状态机、数据最小化不变量——注意这些后端的真实读取要靠实体 ROM 验证）。

### 图片同步（协议 v2，从 feature/stage-4 移植；双端 + 共享 fixture）

图片同步的自动化覆盖分四层，全部无需实体设备：

1. **共享 wire fixture（`protocol/v2/fixtures`，三个消费方）**——`scripts/validate-protocol.py`、Windows `ProtocolReaderV2Tests`、Android `SyncWireV2FixtureTest` 消费同一批 15 valid + 15 invalid v2 fixture（含 `clip_payload_begin/chunk/end`、图片头、超限/GIF/padded-base64 拒绝路径）；Windows 侧逐条核对 `expected_errors.json` 错误码，Android 侧验证全部 invalid fixture 被严格校验器拒绝、全部 valid fixture 类型化解码后可等价重编码。v2 认证向量（transcript 绑定协议版本 2）由 Windows `PairAuthProofTests` 验证。
2. **跨端图片往返（本次新增，双端镜像）**——`windows/ClipSync.Tests/Media/ImageClipRoundTripTests.cs` 与 `android/.../media/ImageClipRoundTripTest.kt` 用**同一批二进制小样本**（`protocol/v2/fixtures/media/` 下的 `png-1x1-transparent.png`、`png-2x2-quadrant.png`、`png-8x8.png`、`jpeg-1x1.jpg` + `manifest.json`）跑同样的断言：字节数/尺寸/MIME/SHA-256 逐项对上 manifest；`ImageChunks` 切块 → 严格 v2 编码/解码（每帧都过共享校验器）→ 重组 → hash 一致 → 内容寻址 blob store 落盘后逐字节读回。`clip_payload_*` fixture 与 `png-8x8.png` 的绑定（chunk 0 的 base64url、41+42 字节切分、begin/end hash）在两端都被逐字段验证，保证 C# 与 Kotlin 对同一份字节产生完全一致的线上表示。
3. **v1/v2 共存边界（双端）**——v1 解析器必须拒绝全部 v2 图片帧（两端各有专测），文本 fixture 与 v1 校验保持冻结；Windows 存储层测试覆盖 `local_only` 终止标记（v2 发图给 v1 peer 时推进游标的通道）的落库、传播与幂等。
4. **Windows 端到端集成（`Peer/PeerSyncIntegrationTests`）**——`ImageClipTravelsOverV2WithBytesIntact`：真实 Kestrel + TLS pin + WebSocket 的 v2 会话上，图片经 begin/chunk/end 分块传输、重组、内容寻址落库，字节精确一致；配套用例覆盖 ack 后删除的 tombstone 传播。存储层由 `MediaBlobStoreTests`（幂等提交、GIF 魔数拒绝、过期临时件回收）、schema-3 迁移测试与捕获策略测试补齐。

Android 侧的引擎级图片链路（`SyncEngine` 的 chunk 状态机在真实会话事件下的行为）目前依赖上述 wire 层往返测试与 Windows 集成测试间接覆盖，Android 自己的会话级图片集成测试尚未补齐——这是已知缺口，不是已证明项。

### Windows 应用层（`windows/ClipSync.App.Tests`，仅 Windows CI 执行）

- `ViewModels/MainViewModelConduitTests` 覆盖通路页三段（网络/本机服务/捕获）的 ViewModel 逻辑：`UpdatePeerStatus` 三态文案、发件队列/最后确认、历史来源标注、远端活跃刷新设备行。
- 本次新增 `LiveSessionEventsDriveTheConduitProperties`：真实 `PeerServer` + 真实拨号 `SyncSessionEngine`（第二个「手机」存储），按 `App.xaml.cs` 的接线消费 `SessionsChanged` / `RemoteClipsCommitted`，证明通路页属性在**真实会话事件**后取到正确值：已连接台数与文案、队列清零、「对端确认至」出现、远端剪贴带来源徽标进入历史、设备行离开「Never connected」，断开后回到「等待已配对设备连入」。
- 其余既有用例覆盖 DPAPI 往返、证书指纹稳定性、绑定地址解析、Win32 剪贴板适配器与消息窗口。

## 自动化测试**不能**证明、必须人工 QA 的

以下每一项都超出 JVM/回环测试的能力范围（依赖真实 OS 行为、OEM 策略、硬件或人的观感）。上线前按清单逐项过，实体机记录格式见 `docs/device-validation-matrix.md`。

### Android（实体机，至少覆盖矩阵中的四个系统族）

- [ ] **前台服务实态**：`ClipboardSyncService` 在真机上的 FGS 授予/拒绝（`FGS_START_DENIED` 降级路径）、电池优化/后台限制下的存活、被杀后的表现。测试只验证了拒绝时的代码路径，拒绝本身由 OEM 决定。
- [ ] **开机恢复**：真实 BOOT_COMPLETED 到达、Android 15/OEM 对 boot 阶段 FGS 的限制、「需要恢复」通知是否真实出现（审计项 A6 的遗留验证）。
- [ ] **剪贴板真实读写**：Robolectric 的 ClipboardManager 是影子实现。真机上要验证：前台自动捕获的触达率、后台读取三后端（Shizuku / ADB 日志+悬浮窗 / 悬浮窗轮询）在各 ROM 的实际可用性与延迟档位、公开写入在锁屏/息屏下的行为。JVM 侧只证明了探针分类与解析逻辑。
- [ ] **图片剪贴板实态**：真实 `ClipData` 图片项（各家键盘/截图/相册 app 给出的 `content://` URI 与 MIME）的读取与物化、`ContentResolver` 流式拷贝、FileProvider 图库分享入链、真实 Bitmap 缩略图渲染（JVM 侧的编解码/切块/blob 存储已被 fixture 往返测试证明，但 Robolectric 的图形栈是影子实现）、16 MiB/32 MP 超限图片在真机上的拒绝与提示。
- [ ] **通知表面**：收件箱「复制」动作通知、自动写入状态通知、恢复通知在锁屏/横幅/免打扰下的实际展示；POST_NOTIFICATIONS 拒绝后的应用内状态行。
- [ ] **配对全流程**：摄像头扫 QR、局域网可达性（真实路由器/AP 隔离）、UDP 发现广播被网络放行。
- [ ] **真实网络鲁棒性**：Wi-Fi 切换、弱网、休眠唤醒后的重连时延（退避逻辑已被测试，真实时延未被证明）。

### Windows（实体机）

- [ ] **UI 渲染与交互**：通路页/历史/设置的 XAML 视觉（DataTrigger 文案与配色只做了 ViewModel 层验证）、托盘图标状态、QR 弹窗实际可扫。
- [ ] **真实剪贴板生态**：与密码管理器（黑名单进程）、RDP、Office 等的共存；`CaptureFaulted` 降级横幅在真实剪贴板抢占下的触发与恢复。
- [ ] **图片剪贴板实态**：真实 `CF_DIB`/`CF_DIBV5` 捕获（截图工具、Office、浏览器复制图片）与 DIB→PNG 重编码后的字节稳定性、剪贴板图片写回（自动应用远端图片）在真实应用中的可粘贴性、大图（接近 16 MiB/32 MP 上限）的传输时延与 UI 缩略图表现（JVM 侧只证明了编解码、切块、blob 存储与回环会话传输）。
- [ ] **防火墙/网络环境**：47654 端口放行提示、多网卡/虚拟网卡绑定选择、Tailscale 等额外绑定地址的真实可达。
- [ ] **睡眠/唤醒**：挂起恢复后的会话重建速度（对比 stage-4 的 `SessionPowerCoordinator` 硬化项，当前靠超时+重连）。

### 双端联调（一台 Windows + 至少一台 Android 真机）

- [ ] 端到端延迟验收：同 Wi-Fi 下复制→对端可粘贴 P95 ≤ 2 s（矩阵档位要求）。
- [ ] 100 次循环互拷无回环、无重复、无串位。
- [ ] 撤销/重配对在两端 UI 上的完整闭环。
- [ ] 图片互拷：Windows 截图 → 手机可保存/分享，手机相册分享 → Windows 可粘贴；PNG 与 JPEG 各测；混合文本/图片剪贴板状态下事件择一（图片优先、不合格回退文本）在真机上的表现；v2↔v1 混连时图片事件以 `local_only` 推进游标且文本照常同步。

## 阅读结果时的两条纪律

1. **绿测 ≠ 兼容**：本页第二节的所有结论都以「协议、存储、状态机、接线正确」为边界；任何涉及 OEM 策略、真实剪贴板、真实网络的声明都必须来自实体机记录。
2. **App 层测试只在 Windows CI 跑**：在 Linux/macOS 上开发时 `ClipSync.App.Tests` 不执行，提交前请确认 CI 的 `windows` 作业绿色，而不是只看本地 `ClipSync.Tests` 的结果。
