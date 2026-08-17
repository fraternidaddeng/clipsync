# 阶段 2 变更记录

日期：2026-08-13  
状态：**已完成并通过验收**（Debug/Release 全量构建测试 0 警告 0 错误，Core 140/140、App 26/26，协议 fixtures 12 valid + 15 invalid 全部按预期）。

## 阶段目标（plan.md 阶段 2）

Windows 直连 P2P peer endpoint：TLS 证书、`/v1/peer/health`、WebSocket 挑战认证、
`known_vector`/`want_ranges`/`clip_announce`/`clip_fetch`/`clip_payload`/`ack_ranges`、
事务化 outbox/游标、pair secret 与撤销、协议兼容性测试、局域网绑定与 UDP 发现广播、
`auto_apply_remote` 写回（复用阶段 1 回环抑制）。

## 构建环境（后续 agent 必读）

- 系统全局只有 .NET 运行时没有 SDK。阶段 0/1 使用的临时 SDK 目录已被清理。
- 本阶段把 .NET SDK 8.0.419（global.json 固定）重新安装到 **`D:\paste-tools\dotnet`**（仓库外，未加入系统 PATH）。
- 每个新 shell 会话先执行：
  `$env:PATH = 'D:\paste-tools\dotnet;' + $env:PATH; $env:DOTNET_ROOT = 'D:\paste-tools\dotnet'`
- 基线验证（2026-08-13）：`scripts/build-windows.ps1 -Configuration Debug` 通过，0 警告 0 错误，Core `35/35`、App `22/22`。

## 进度

- [x] 恢复构建环境并跑通阶段 1 基线测试
- [x] Core：范围/游标数学（`Core/Sync/SequenceRange.cs`、`OriginReceiveState.cs`；seq=12 缺 11 时游标停在 10 有单元测试）
- [x] Core：协议消息强类型 DTO 与语义校验（`Core/Protocol/SyncMessages.cs`、`ProtocolValidation.cs`、`ProtocolReader.cs`；
      全部 12 类消息、token 级重复属性/深度/null 拒绝、canonical UUID/base64url/哈希/范围不变量；27 个共享 fixture 全部按预期通过/拒绝）
- [x] Core：HMAC-SHA256 挑战响应认证原语（`Core/Security/PairAuthProof.cs`）；
      跨端测试向量由独立 Python 实现生成并冻结在 `protocol/v1/fixtures/auth/vectors.json`（生成器 `scripts/generate-auth-vectors.py`），
      C# 逐位复现，阶段 4 Kotlin 必须消费同一文件
- [x] Core：SQLite schema v2 迁移（devices、outbox、peer_cursors、origin_receive_state、clips.terminal_reason；
      v1 库原地迁移并从 local_sequences 回填接收状态）。新增 `Storage/SyncStorageModels.cs`、
      `SqliteClipboardEventStore.Sync.cs`（partial）：本地捕获在同一事务内推进接收状态并 fan-out 到 outbox；
      远端事件落库幂等 + 三类身份冲突检测（同 origin/seq 不同 event_id、同 event_id 不同内容、同 event_id 复用别的 seq）；
      terminal marker 只推游标不覆盖正文；ack 推进 peer_cursors 并删 outbox 行；announced 状态可整体重置（新会话用）；
      撤销设备 bump trust_epoch、清 secret、清 outbox，重配对 epoch 单调递增；BeforeCommit 故障注入验证无半提交状态。
      测试 `Storage/SqliteSyncStoreTests.cs` 12 个场景全绿（Core 合计 125/125）。
- [x] ClipSync.Peer 新项目（net8.0 + FrameworkReference Microsoft.AspNetCore.App，已入 sln）：
  - `Transport/`：`ISyncTransport` 抽象 + `WebSocketSyncTransport`（分片重组、7 MiB 上限、拒绝二进制帧、
    关闭用 `CloseOutputAsync` 防止对端卡死 close 握手）
  - `Sessions/SyncSessionEngine.cs`：**双端对称**会话引擎（Listener 发 challenge / Dialer 应答，认证后行为完全一致）。
    完整实现 hello→challenge→auth→known_vector→want_ranges→announce→fetch→payload→ack 状态机、
    请求 ID 重放窗口（同 ID 同字节=幂等跳过，不同字节=REPLAY_DETECTED）、challenge 单次使用+过期、
    trust epoch 三处校验（hello/challenge/auth）、乱序 payload=MESSAGE_OUT_OF_ORDER、身份冲突=EVENT_CONFLICT 断连、
    未知 origin 的 announce 静默跳过（见决策 4）、want 聚合限额超限回 retryable RATE_LIMITED、
    hash 去重（本地已有同哈希正文时不 fetch 直接落库）、payload 按 32 条/1 MiB 分批、
    outbox 周期 drain（默认 2s，可调）、应用层 ping/pong + 3 次未答关闭、
    会话内吊销检查（每条数据消息前重查 devices）、`RemoteClipsCommitted` 批量事件（auto-apply 用）
  - `Server/PeerServer.cs`：Kestrel HTTPS（自签 ECDSA P-256 证书、TLS 1.2+1.3）、`X-Protocol-Version: 1` 网关、
    `/v1/peer/health`（仅 version/device_id/port）、`/v1/peer/sync` WebSocket、并发会话上限（超限 503）、
    `AuthThrottle` 滑动窗口限流（30s 内 5 次失败 → RATE_LIMITED + retry_after）、`DisconnectDevice`（吊销时踢线）
  - `Client/PeerSyncClient.cs`：ClientWebSocket 拨号，`RemoteCertificateValidationCallback` 只认配对时钉住的
    证书 SHA-256 指纹（链和主机名一律不管）
  - `Discovery/UdpDiscoveryBroadcaster.cs`：广播 `{v, kind, device_id, port, cert_sha256}` 五个字段到
    255.255.255.255:47653 + 各网卡定向广播地址，测试可注入目标端点
- [x] 集成测试 `Tests/Peer/PeerSyncIntegrationTests.cs`（15 个，真实 Kestrel + TLS + 两个真实 SQLite 库）：
  双向收敛恰好一次、离线捕获重连补齐不重复、错误 secret 连续 5 次失败后第 6 次被限流、
  未配对设备拒绝、epoch 不匹配拒绝、吊销踢掉活动会话并拒绝重连、删除的剪贴板以 unavailable marker
  同步游标但不带正文、outbox 清空后 want_ranges 分批补量（每轮 3 条）收敛、超限 want 收 retryable
  RATE_LIMITED 且会话存活收敛、日志不含正文/secret/proof/nonce、health 版本头网关、证书 pin 不符拒连、
  协议版本头错误升级前 400、7 MiB+ 帧回 PAYLOAD_TOO_LARGE、UDP beacon 恰好 5 字段
- [x] 调试记录（三处，都有回归测试兜底）：
  1. 引擎写了 `HMAC-SHA256` 而协议冻结值为小写 `hmac-sha256`（`ProtocolValidation.HmacSha256` 已公开，引擎引用常量防再漂移）；
  2. dialer 的"已认证"语义改为收到 listener 认证后首条数据消息才置真（否则错 secret 时 dialer 结果是 authenticated=true）；
  3. **仅 Release 复现的竞态**：listener 发完 error 帧立刻 dispose socket 变成 TCP RST，把在途 error 帧冲掉，
     dialer 只看到断连拿不到错误码。修复为会话收尾时先发 close 帧再排水等对端 close（2 秒/64 帧上限）。
     另外 retryable 错误（如 RATE_LIMITED）后对端关连接时，dialer 结果保留最后收到的错误码。
- [x] App 装配：
  - `App/Security/DpapiSecretProtector.cs`：`ISecretProtector` 的 DPAPI(CurrentUser) 实现（pair secret 与证书 PFX 落盘前都过它）
  - `App/Security/PeerCertificateProvider.cs`：自签证书 PFX 经 DPAPI 保护存 `peer-certificate.bin`，
    重启指纹不变（配对 pin 的前提），损坏或临近过期（<30 天）自动重生成
  - `App/Sync/PeerSyncHost.cs`：进程内启动 PeerServer，默认绑定 loopback + 私有网段 IPv4，
    设置里可加显式地址（Tailscale/WireGuard），首选端口 47654 被占则回退随机端口；
    UDP beacon 启动时 + 网络变化 + 每 5 分钟发送
  - `App.xaml.cs`：远端事件批量落库后只把批内最后一条写回系统剪贴板（`auto_apply_remote` 默认开启，可关），
    写回前 `SuppressNextWrite` 抑制回环；任何情况都刷新历史列表；peer 启动失败不影响本地捕获
  - 设置 UI 新增 Sync 区块：auto-apply 开关、额外绑定地址（重启生效）、运行状态（端口/设备 ID/指纹前 16 位）
  - App.Tests 新增 4 个：DPAPI 往返、证书指纹跨加载稳定、损坏文件触发重生成、绑定地址解析去重滤非法
- [x] 阶段验收（2026-08-13）：`build-windows.ps1` Debug 与 Release 均 0 警告 0 错误全测试通过；
  `validate-protocol.ps1` 12 valid + 15 invalid fixtures 全部按预期

## 设计决策（相对 plan.md 的明确化与偏差，均有意为之）

1. **pair secret 存储**：plan.md §3.1 的 `devices.pair_secret_hash` 无法用于 HMAC 挑战响应（双方都需要原始 secret）。
   实际列为 `pair_secret_protected`：32 字节 secret 经 `ISecretProtector` 保护后 base64 存储。
   App 层用 DPAPI(CurrentUser) 实现；Core 只依赖接口，测试用可逆 fake。
2. **tombstone 不建独立表**：阶段 1 已用 clips 软删除行（正文清空 + `deleted_at`）承担 tombstone 职责。
   v2 给 clips 增加 `terminal_reason` 列（`local_only`/`deleted`/`expired`/`policy_filtered`/`not_found`，NULL=正文可用），
   同一张表同时满足历史展示、同步游标计算和 `clip_announce` 的 unavailable 标记，避免双表一致性问题。
3. **接收状态表 `origin_receive_state`**：协议要求 known_vector 携带"最大连续已持久化序号 + 非连续 ranges"。
   每次远端事件落库在同一事务内维护该表，不在查询时全表扫描 clips 重算。
4. **转发策略**：协议规定"接收方只接受已有配对信任记录的 origin"。v1 配对拓扑是 Windows 为中心、Android 只信任 Windows，
   因此本阶段 outbox fan-out 按 plan 任务文本把任一事件加入其他 peer 的 outbox（排除 origin 自身），
   但接收侧对未知 origin 的 announce 只跳过不 fetch、不 ack（连接不中断）；未经 fetch 的 payload 一律 `MESSAGE_OUT_OF_ORDER` 关闭。
   错误码枚举里没有 UNKNOWN_ORIGIN，协议 v1 冻结不加码；跨 Android 设备互信如何建立推迟到阶段 3 配对设计。
5. **sync_policies 表推迟**：阶段 2 任务清单只要求 `auto_apply_remote` 设置与撤销检查；
   §3.4 完整策略引擎（方向/大小/peer 规则）不在本阶段建表，后续通过 schema v3 迁移加入，避免发布未使用的结构。
6. **新项目 ClipSync.Peer**（net8.0 + `FrameworkReference Microsoft.AspNetCore.App`）：
   Kestrel/WebSocket 依赖不进 Core；同步会话引擎设计为传输无关、双端对称（同一引擎既可当监听端也可当发起端），
   集成测试跑两个真实引擎 + 回环 TLS 验证收敛，也是阶段 4 Kotlin 客户端的行为参考。
7. **auto-apply 批量语义**：断线补同步一次可能落库多条远端事件，只把批内最后提交的一条写回系统剪贴板，
   全部事件仍进历史；写回前调用 `ClipboardCapturePolicy.SuppressNextWrite` 抑制回环。

## plan.md 阶段 2 验收对照

- 未配对客户端不能读取或提交内容 → `UnknownDeviceIsRejected`、`WrongSecretFailsAuthAndRepeatsGetThrottled`、
  认证前数据消息一律 `AUTH_REQUIRED` 断连（引擎状态机）
- 断开重连按 `known_vector` 继续、不重不丢 → `OfflineCapturesArriveAfterReconnectWithoutDuplicates`、
  `TwoWayConvergenceDeliversEverythingExactlyOnce`
- 进程强制结束后未确认 outbox 仍在 → 存储层 `AcksAdvancePeerCursorRemoveOutboxRowsAndSurviveReopen`
  （关库重开验证）+ announced 状态新会话重置为 pending
- 日志不出现剪贴板正文、pair secret 或私钥 → `LogsNeverContainContentSecretsOrProofs`
  （收集全部日志行断言不含正文/secret/proof/nonce）
- seq=12 缺 11 游标不越过 10 → 单元 `OriginReceiveStateTests` + 存储 `RemoteEventOutOfOrderKeepsContiguousCursorBelowGap`

## 未完成 / 已知限制（交接给下一个 agent）

- **TLS 版本**：协议文档写"要求 TLS 1.3"，实现允许 TLS 1.2+1.3（Kestrel `SslProtocols.Tls12 | Tls13`）。
  原因：Windows 10 22H2 的 schannel 不稳定支持服务端 TLS 1.3，而 plan 面向 Win10+；
  安全性由强制证书指纹 pin 承担（客户端只认 pin，不认链/主机名）。阶段 4 Android(OkHttp) 会优先协商 1.3。
  若后续决定强制 1.3，只改 `PeerServer` 一处并更新协议文档。
- **转发信任**（见设计决策 4）：接收方对未知 origin 的 announce 静默跳过且不 ack，发送方 outbox 行保持
  announced 直到会话结束，重连会重新 announce 一次。v1 拓扑（Android 只配 Windows）下不会触发；
  跨 Android 互信在阶段 3 配对设计里解决。
- **绑定地址变更**需重启应用生效（NIC 热插拔不重绑，UDP beacon 会跟随网络变化重发）。
- 设备管理 UI（列表/撤销按钮）留给阶段 3 配对界面；存储与引擎的撤销语义（踢线、清 outbox、epoch 递增）已就绪，
  UI 只需调 `RevokeDeviceAsync` + `PeerSyncHost.DisconnectDevice`。
- Android 端（阶段 4）与配对 UI/QR（阶段 3）不在本阶段；实体 Android ROM 能力仍为 `NOT_TESTED`。
- CI 的 GitHub Actions 未在本机执行；本地以 `scripts/build-windows.ps1` + `scripts/validate-protocol.ps1` 为准
  （sln 已含 ClipSync.Peer，CI 脚本无需改动）。

## 新增文件清单（阶段 2）

- `windows/ClipSync.Core/Sync/`：`SequenceRange.cs`、`OriginReceiveState.cs`
- `windows/ClipSync.Core/Protocol/`：`SyncMessages.cs`、`ProtocolValidation.cs`、`ProtocolReader.cs`（含 Writer）
- `windows/ClipSync.Core/Security/`：`PairAuthProof.cs`、`ISecretProtector.cs`
- `windows/ClipSync.Core/Storage/`：`SyncStorageModels.cs`、`SqliteClipboardEventStore.Sync.cs`
- `windows/ClipSync.Peer/`：`Transport/`、`Sessions/`、`Server/`、`Client/`、`Discovery/`、`Diagnostics/`（新项目）
- `windows/ClipSync.App/`：`Security/DpapiSecretProtector.cs`、`Security/PeerCertificateProvider.cs`、`Sync/PeerSyncHost.cs`
- `windows/ClipSync.Tests/`：`Sync/`、`Protocol/ProtocolReaderTests.cs`、`Security/PairAuthProofTests.cs`、
  `Storage/SqliteSyncStoreTests.cs`、`Peer/`（含测试基础设施）
- `windows/ClipSync.App.Tests/Sync/PeerAppWiringTests.cs`
- `scripts/generate-auth-vectors.py`、`protocol/v1/fixtures/auth/vectors.json`

## 下一步（阶段 3 起点）

按 plan.md 阶段 3：一次性配对二维码（QRCoder）、`/v1/pair/confirm` 端点（schema 先冻结）、双端确认 UI、
设备管理界面。`PeerServer` 已预留 `/v1/pair/confirm` 路径未注册，pairing token 语义见协议文档第 9 节。
