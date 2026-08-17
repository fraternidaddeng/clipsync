# 阶段 3 变更记录

日期：2026-08-17  
状态：**已完成并通过验收**（Windows Debug/Release 全量 0 警告 0 错误，Tests 169/169、App.Tests 33/33；Android `testDebugUnitTest` 47/47 全绿 + `assembleDebug` 成功；协议 fixtures 12 valid + 15 invalid、配对 fixtures 4 valid + 7 invalid 全部按预期）。

## 阶段目标（plan.md 阶段 3）

二维码配对和设备管理：一次性配对二维码、Android 扫码与双端确认、
长期设备 ID/pair secret/证书指纹保存（DPAPI/Keystore）、设备列表/重命名/撤销/重新配对、
证书变化告警、二维码过期/重复使用/取消测试。

## 构建环境（后续 agent 必读）

- .NET SDK 8.0.419 仍在 **`D:\paste-tools\dotnet`**（仓库外，不在系统 PATH）。每个新会话先执行：
  `$env:PATH = 'D:\paste-tools\dotnet;' + $env:PATH; $env:DOTNET_ROOT = 'D:\paste-tools\dotnet'`
- 阶段 0 的临时 Android SDK 此前已被清理；本阶段将其重装到 **`D:\paste-tools\android-sdk`**
  （cmdline-tools latest、platform-tools、platforms;android-35、build-tools;34.0.0/35.0.0，许可证已接受）。
  构建命令：`$env:ANDROID_HOME = 'D:\paste-tools\android-sdk'; pwsh scripts/build-android.ps1`
- JDK 17.0.20（Microsoft OpenJDK）安装在系统 `C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`，`java` 在 PATH。
- 协议校验需要 Python 3 + jsonschema 4.x（系统已有 Python 3.14.3 + jsonschema 4.26.0）：`pwsh scripts/validate-protocol.ps1`
- Maven Central 偶发 TLS 握手中断导致 Gradle 依赖下载失败（`SSL peer shut down incorrectly`）；重跑即可，无需改配置。

## 交付内容

### 协议（冻结于协议文档第 9 节 + `protocol/v1/pairing.schema.json`）

- 四类配对文档：`pairing_qr`、`pairing_confirm_request`、`pairing_confirm_response`、`pairing_error`，
  全部走第 2 节 JSON 规则（严格 UTF-8、大小写敏感、拒绝未知字段/null/重复属性、深度 16、8 KiB 上限）。
- 共享 fixtures `protocol/v1/fixtures/pairing/`（4 valid + 7 invalid），`scripts/validate-protocol.py` 已纳入 pairing schema 校验。
- 错误码：`SCHEMA_VIOLATION`(400)、`PAIRING_TOKEN_INVALID`/`PAIRING_REJECTED`/`PAIRING_TIMEOUT`(403)、`PAIRING_TOKEN_EXPIRED`(410)。

### Windows（ClipSync.Peer + ClipSync.App）

- `Peer/Pairing/PairingService.cs`：单活动票据模型——32 字节随机 token、5 分钟过期、常量时间比较、
  匹配即烧（无论后续批准/拒绝/超时）、错误猜测不烧票（256-bit 熵）、重新签发或取消即失效旧票；
  批准后生成 32 字节 pair secret（DPAPI 保护落库、响应中 base64url 单次下发、内存即刻清零）；
  对已配对设备的 confirm 是重配对：清除撤销、trust epoch 单调递增。审批等待默认 90 秒，超时即 `PAIRING_TIMEOUT`。
- `Peer/Pairing/PairingJson.cs`/`PairingModels.cs`：与协议第 2 节相同的严格 token 级读取器（复用 `ScanStrictJson` 思路），
  QR 载荷构造校验 hosts 1..8。
- `Server/PeerServer.cs`：`POST /v1/pair/confirm` 仅在注入 `PairingService` 时注册（纯同步端点部署没有配对面）；
  沿用 `X-Protocol-Version: 1` 网关、8 KiB 请求上限。
- `App/Pairing/`：`PairingQrWindow`（打开即签发 token、倒计时到期自动换码、无可达地址时取消 token 并提示、
  配对完成自动关窗、关窗销毁 token）；`PairingApprovalWindow` + `WpfPairingApprover`（Kestrel 线程与 WPF 调度桥接，
  显示请求方名称/平台/是否重配对，审批超时取消映射为 `PAIRING_TIMEOUT` 而非拒绝）；`PairingQrRenderer`（QRCoder 渲染 PNG）。
- `App/ViewModels/MainViewModel.cs` + `PairedDeviceViewModel`：设备列表（名称、平台、最后在线、epoch、撤销态）、
  重命名（1..64 字符）、撤销（清 secret、bump epoch、踢线由 `App.OnDeviceRevoked` 调 `PeerSyncHost.DisconnectDevice`）。
- QR 载荷 hosts 来自 `PeerSyncHost.ReachableHosts`，明确排除 loopback，手机端永远不会拿到只会失败的地址。

### Android（实现 + 本阶段补齐的测试）

- `pairing/PairingJson.kt`:与 Windows 逐条对齐的严格解析器（自写 token 级 StrictScanner：重复属性、null、深度 16、
  孤立代理项、控制字符、8 KiB 上限），四类文档的语义校验与 `pairing.schema.json` 一致。
- `pairing/PairingConfirmClient.kt`：`POST /v1/pair/confirm` 的 OkHttp 客户端，TLS 信任仅等于 QR 指纹
  （忽略链和主机名，pin 即身份）；hosts 按 QR 顺序连接失败转移；**pin 不匹配立刻终止全部尝试**（证书变化必须阻断）；
  等待审批期间的读超时不会转移到下一 host（token 已被消费，转移会造成误报）；响应体 8 KiB 有界读取。
- `pairing/PairingStore.kt`：单 peer 信任存储（设备 ID、名称、平台、pinned 指纹、trust epoch、hosts、端口、
  Keystore AES-GCM 保护的 secret），一次提交写入、明文 secret 即刻归零、部分损坏按未配对处理、`forgetPeer` 全清。
- `platform/KeystoreSecretProtector.kt`、`platform/SharedPrefsKeyValueStore.kt`：Keystore/SharedPreferences 实现。
- `ui/pairing/PairingViewModel.kt`：状态机 Idle -> Review -> Submitting -> Paired/Failed；
  扫到自己的设备 ID 拒绝；**同一 Windows 设备出示不同证书时 Review 界面强警告**（不静默替换 pin）；
  Approved 响应必须回声 QR 中的设备 ID 且 secret 可解码为 32 字节，否则按协议违规丢弃不落库。
- `ui/pairing/PairingScreen.kt`：ML Kit + CameraX 扫码（单发防重复投递）、相机权限被拒时保留手动粘贴载荷入口、
  确认页展示名称+分组指纹+地址、证书变化红色警告卡、全部失败态给出可执行指引。
- `MainActivity.kt`：Status/Pairing 两个 tab，装配真实 Keystore/SharedPreferences/ConfirmClient。
- Manifest：`INTERNET` + `CAMERA`（`android.hardware.camera.any` 非必需，无相机设备仍可配对）。

**本阶段（本次会话）补齐的 Kotlin 测试（31 个，全部 JVM 单元测试）**：

- `PairingFixtureTest`（4）：消费与 C# 完全相同的共享 pairing fixtures（valid 全解析、invalid 全拒绝），
  另加 QR 越界变异（9 hosts、重复 host、port 0、大写指纹、null 字段）和 8 KiB 超限拒绝。
- `PairingStoreTest`（8）：本地设备 ID 生成/自愈、savePeer 原子写入+明文归零+密文不含明文编码、
  32 字节强制、指纹匹配大小写不敏感、forgetPeer 全清、部分持久化按未配对、显示名 trim/64 截断。
- `PairingConfirmClientTest`（7，MockWebServer + okhttp-tls 真实 TLS）：pin 匹配完成交换并携带协议头、
  **pin 不匹配在请求发出前阻断（server 零请求）**、403 映射 Denied、未知状态/畸形体/9 KiB 超限为协议违规、
  不可达 host 按 QR 顺序失败转移、全部不可达时列出尝试清单、**审批等待停滞不转移 host（server 恰好 1 个请求）**。
- `PairingViewModelTest`（12）：垃圾载荷/自身设备拒绝、Review->Paired 全流程（验证请求身份与 secret 往返）、
  扫码会话只收首个载荷、同设备换证书触发 certificateChanged（不同设备不触发）、
  响应身份不符/secret 畸形不落库、五类 Denied 映射、CertificateMismatch/Unreachable 映射、取消回 Idle、遗忘配对。

### Windows 侧测试（本阶段此前已就位，随全量回归验证）

- `PairingServiceTests`（10）：QR 载荷无 secret、批准落库且 secret 往返、**token 单次使用**、**过期 410**、
  错误猜测不烧票、**重发/取消失效**、拒绝不留设备、审批超时不落库、重配对 bump epoch/清撤销/告知审批人、own-device 400。
- `PairingHttpTests`（6，真实 Kestrel+TLS）：扫码到首次同步收敛的全流程、重配对使旧凭证按 `TRUST_EPOCH_MISMATCH` 失效、
  无效 token/用户拒绝/烧票重试、畸形/超大/无版本头请求、未注入配对服务时 404、**日志不含 token 与 pair secret**。
- `PairingFixtureTests`：C# 消费共享 pairing fixtures（与 Kotlin 同一批文件）。
- `PairingAppWiringTests`（3）：QR PNG 渲染、指纹分组格式化、`PeerSyncHost` 端到端 confirm（QR 排除 loopback）。

## 设计决策（相对 plan.md 的明确化，均有意为之）

1. **单票据而非票据表**：同一时刻最多一个待用 token（重新生成/关窗即失效旧票），匹配即烧。
   个人设备配对不存在并发扫码需求，单票据消除了票据生命周期管理和清库任务。
2. **Windows 不记录 Android 的证书指纹**（`devices.certificate_fingerprint` 存空串）：v1 拓扑中 Android 永远是
   TLS 发起方、Windows 永远是监听方，Windows 对 Android 的信任由 pair secret + trust epoch 承担；
   Android 对 Windows 的信任由 QR 钉住的证书指纹承担。协议字段保留，跨 Android 互信推迟到需要时。
3. **Android 单 peer 存储**：plan 范围是"一台 Windows 配多台 Android"，Android 只需要一个 Windows 信任记录；
   重新扫码是显式替换（Review 界面确认，同设备换证书强警告）。
4. **等待审批的读超时不做 host 转移**（Kotlin 客户端）：confirm 到达监听端即消费 token,
   若在等待用户批准时因超时转移到下一 host，会得到必然的 `PAIRING_TOKEN_INVALID` 并掩盖真实状态。
   客户端读超时(100s) > 监听端审批窗口(90s)，正常路径总能等到真实结论。
5. **`/v1/pair/confirm` 是可选注册**：`PeerServer` 只在拿到 `PairingService` 时才挂端点,
   集成测试里未启用配对的服务器对该路径回 404，缩小未使用的攻击面。
6. **审批超时(90s) < 票据寿命(5min)**，且批准动作在 Windows 端弹独立窗口；
   超时映射为 `PAIRING_TIMEOUT` 而非 `PAIRING_REJECTED`，手机端提示语区分"没批"和"拒了"。

## plan.md 阶段 3 验收对照

- 从首次安装到配对成功不超过三步确认 → ① Windows 托盘打开配对窗口（自动出码）② Android 扫码后核对名称/指纹并确认
  ③ Windows 审批窗口确认，随即双端落库、QR 窗口自动关闭。
- 复制二维码或查看日志不会泄露长期 pair secret → QR 载荷无 secret 字段（`qr-contains-pair-secret` 是 invalid fixture,
  两端解析器都拒绝）；`PairingLogsNeverContainTokensOrSecrets` 断言全部日志行不含 token/secret；
  secret 仅在 confirm 响应内经 pinned TLS 单次下发。
- 撤销设备后旧 APK 无法继续同步 → 撤销清 secret 并 bump epoch（阶段 2 存储/引擎语义），
  `RepairInvalidatesTheOldCredentials` 验证旧 secret+旧 epoch 连接收到 `TRUST_EPOCH_MISMATCH`。
- 修改 peer 证书时 Android 明确阻断并提示，而不是自动信任 → TLS 层 `PinnedTrustManager` 只认 QR 指纹,
  不匹配立刻 `CertificateMismatch` 终止（测试断言服务器零请求）；扫码层同设备换证书在 Review 强警告,
  需要用户显式选择"我已核实——替换配对"。

## 验证（2026-08-17）

- `pwsh scripts/build-windows.ps1 -Configuration Debug`：0 警告 0 错误；Tests `169/169`、App.Tests `33/33`。
- `pwsh scripts/build-windows.ps1 -Configuration Release`：0 警告 0 错误；Tests `169/169`、App.Tests `33/33`。
- `pwsh scripts/build-android.ps1`（`ANDROID_HOME=D:\paste-tools\android-sdk`）：BUILD SUCCESSFUL,
  `testDebugUnitTest` 47/47（pairing 31 + clipboard 10 + protocol 6），`assembleDebug` 生成 debug APK。
- `pwsh scripts/validate-protocol.ps1`：12 valid + 15 invalid 协议 fixtures、4 valid + 7 invalid 配对 fixtures 全部按预期。
- `THIRD_PARTY_NOTICES.md` 已更新到阶段 3 实际依赖（QRCoder、ML Kit、CameraX、OkHttp、mockwebserver/okhttp-tls 等；
  ML Kit 为闭源 Google 库，发布前必须复核分发条款；Hardcodet.NotifyIcon.Wpf 为 CPOL-1.02，发布前核对通知义务）。

## 未完成 / 已知限制（交接给阶段 4）

- **Android 还没有 WebSocket 同步客户端**：配对只建立并保存信任（设备 ID、secret、pinned 指纹、epoch、hosts、端口）。
  阶段 4 的 OkHttp 同步客户端必须复用 `PairingStore` 的信任数据与 `protocol/v1/fixtures/auth/vectors.json` 的 HMAC 测试向量。
- **配对成功后的 `Paired` 界面明确告知"同步在后续阶段开始"**，不伪造在线状态。
- 扫码 UI（CameraX/ML Kit 路径）没有 instrumentation 测试；配对逻辑层（Json/Store/Client/ViewModel）全部有 JVM 测试。
  相机权限被拒的手动粘贴路径可在无相机模拟器上手工验证。
- Android 端 `local.properties` 未入库（`sdk.dir` 由 `ANDROID_HOME` 提供）；实体 Android ROM 能力仍为 `NOT_TESTED`,
  与阶段 5 前的硬性实测要求不变。
- Git 仓库自阶段 0 起没有任何提交（全部文件 untracked）；分支策略（`main`/`develop`/`feature/*`）尚未实际建立。
  这不阻塞阶段验收，但建议在进入阶段 4 前建立首个提交基线，避免跨阶段回滚没有参照点。

## 下一步（阶段 4 起点）

按 plan.md 阶段 4：Android 历史列表/搜索/详情/设置、Room（收件箱/outbox/游标/删除标记）、
OkHttp WebSocket 客户端（认证复用 pair secret + `vectors.json` 向量、心跳、指数退避）、
`known_vector`/缺失范围/正文交换与 `ack_ranges`、通知复制、`ACTION_SEND` 分享目标、Quick Settings Tile、
`ForegroundClipboardBackend` + `PublicClipboardWriter` 基线。Windows 端 `SyncSessionEngine` 双端对称,
是 Kotlin 客户端的行为参考（见阶段 2 变更记录决策 6）。
