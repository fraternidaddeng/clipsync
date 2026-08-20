# Windows-Android Clipboard Sync 实施计划

> 状态：文本第一版基线已落地；内置特权宿主代码已落地，MIUI 14 单机已复测授权与本机 `SHIZUKU_EVENT` 落库（见 `docs/stage-8-change-log.md` 2026-08-20）；剪贴板图片扩展仅完成计划，尚未实施
>
> 目标：创建一个仅面向 Windows 和 Android、个人小范围使用、无需账号和云服务的“复制即同步”工具。安装、配对并完成一次 Android 后台能力引导后，Windows 和 Android 都能在支持的权限模式下后台捕获剪贴板并近实时同步；没有特殊权限时仍可通过分享面板、快捷磁贴或前台模式发送。工具保留可搜索的剪贴板历史，并能在断线后自动补同步。
>
> 本文件新增的 Stage 9 只定义静态剪贴板图片扩展；在该阶段完成前，现有协议 v1、数据库和客户端仍按纯文本契约运行。

## 0. 先冻结产品边界

### 0.1 必须实现的用户体验

1. 用户在 Windows 上正常按 `Ctrl+C`，无需打开应用、无需选择设备，文本自动进入本地历史并推送到已配对的 Android 设备。
2. Android 在收到新文本后，可以在应用内查看；开启自动应用且公开写入/回退能力可用时自动进入系统剪贴板，否则点击通知或历史条目复制。
3. 用户在 Android 中通过系统分享面板选择“发送到 Windows”，不需要进入复杂的文件传输流程。
4. Windows 和 Android 短暂离线后重新连接，之前产生的内容能够补齐，且不会重复。
5. 用户可以查看、搜索、删除历史，暂停同步，撤销设备，并排除敏感应用。
6. 文本第一版默认只同步纯文本；图片扩展必须由用户显式开启。不尝试猜测某段文本是否为密码，使用已知敏感应用黑名单、私密模式和暂停快捷键控制敏感内容。
7. 开启图片同步后，用户在 Windows 或 Android 复制受支持的静态栅格图片，图片先进入本地历史，再按同一套来源序号、去重和断线补同步规则传给已配对设备。
8. 收到图片时先保存到收件箱并校验完整性；开启图片自动应用且目标端写回能力为 `READY` 时才写入系统剪贴板，否则提供通知或历史条目的“复制图片”操作。
9. 图片历史显示缩略图、格式、尺寸和编码大小；列表和日志不能因为预览而加载或记录原始图片正文。

### 0.1.1 方向能力矩阵

| 方向 | 第一版保证 | 触发方式 | 离线行为 |
|---|---|---|---|
| Windows -> Android | 是，在线时近实时推送 | Windows 系统剪贴板事件，用户无需打开主界面 | Windows 和 Android 各自落库，重连后按缺失范围补齐 |
| Android -> Windows | 是 | 后台自动模式；无授权时分享面板或 Quick Settings Tile | Android 写入本地 outbox，重连后上传 |
| Android 后台被动捕获 -> Windows | 是，但须先完成能力引导 | 内置特权宿主（用户显式执行 adb shell/root 的 `start.sh`）、ADB/`READ_LOGS` + 悬浮窗，或悬浮窗轮询 | 监听模式失效时按能力阶梯降级，不丢本地事件 |
| Android 收到后自动改写系统剪贴板 | 是；先尝试公开写入，厂商拒绝时再用特权回退 | `ClipboardManager.setPrimaryClip` 优先；必要时内置宿主/悬浮窗 | 收到内容先进入 Android 收件箱，待写能力恢复后按策略应用 |

因此本项目是**双向同步协议**，并把“Android 复制后自动上行”列为核心工作流。这里的“保证”定义为：在设置页显示为“可用”的读取模式下，事件能被捕获、落库并同步；写回能力单独探测并显示，不把网络在线误报成剪贴板可写。首次安装必须明确显示所选模式及其所需授权。没有任何特殊授权的设备仍可使用完整的分享/通知降级流程，但不宣称普通 `ClipboardManager` 能绕过 Android 的后台读取限制。

### 0.1.2 Android 后台剪贴板能力阶梯

Android 10 以后主要限制的是**后台读取剪贴板内容**，不是网络协议本身；AOSP 的剪贴板服务允许无焦点应用写入，但锁屏和厂商实现可能不同。成功项目普遍把“检测复制”和“取得内容”拆成两步，再按权限选择实现。第一版把读取和写回拆成独立能力，由启动向导探测并选择最高可用档位：

| 优先级 | 模式 | 检测复制变化 | 读取/写回内容 | 用户需要做什么 | 典型稳定性/代价 |
|---:|---|---|---|---|---|
| 1 | Shizuku 特权事件（内置宿主） | `IOnPrimaryClipChangedListener`（ClipSync UserService） | 通过 `IClipboard` Binder 读取；写回仅在公开写入探测失败时使用 | 打开 ClipSync 生成 `start.sh`，用户自行以 adb shell（uid 2000）或 root 执行；官方 Shizuku 不再作为回退 | 事件驱动、无悬浮窗，首选；宿主或系统 Binder 重启后需恢复 |
| 2 | ADB 事件 + 悬浮窗 | 解析 `ClipboardService` 系统日志 | 读取用透明 `TYPE_APPLICATION_OVERLAY` 窗口短暂取得焦点；写回先走公开 API | 尝试 `adb shell pm grant ... READ_LOGS`，开启悬浮窗 | 低功耗；日志标签受 ROM 影响，需解析器和轮询兜底 |
| 3 | 悬浮窗轮询 | 定时读取并比较哈希 | 读取用同一透明悬浮窗切换焦点；写回先走公开 API | 开启悬浮窗、通知和电池不受限 | 不需电脑，覆盖面广；有极短焦点干扰和电量成本 |
| 4 | 前台/手动 | 用户触发分享、磁贴或打开 App | 普通 `ClipboardManager` 读写 | 无特殊权限 | 最可靠的降级，不提供复制后零操作 |

实现规则：

1. `SHIZUKU_EVENT` 优先使用原生 Binder 监听。内置宿主和 UserService 死亡、系统 `clipboard` Binder 重启时自动重连；不可用时进入下一档，不循环报错。官方 Shizuku 应用不是必需依赖。
2. ADB 模式只把日志当作“变更信号”，正文仍通过悬浮窗读取；为 AOSP、MIUI/HyperOS、ColorOS、OneUI 等维护可版本化的日志解析器，未知格式直接降级轮询。`READ_LOGS` 的授权状态不能直接当作 READY，必须实际读到匹配信号。
3. 悬浮窗空闲时使用 1x1、透明、`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` 窗口；读瞬间只移除 `FLAG_NOT_FOCUSABLE`，保留 `FLAG_NOT_TOUCHABLE`，完成后立即恢复。若某 ROM 只有可触摸窗口才能读取，则该模式标为不可用，不默认牺牲触摸安全。轮询间隔默认 800～1,000 ms，并在屏幕关闭或设备锁定时暂停。
4. 所有模式共享同一去重、回环抑制、来源记录和事件日志；切换读取模式不会重复上传，也不会删除历史。
5. `ClipboardWriteCoordinator` 先调用公开 `ClipboardManager.setPrimaryClip()` 并记录实际结果；只有厂商拒绝或锁屏策略阻断时才调用内置宿主/官方 Shizuku/悬浮窗写入回退。读取和写回状态必须分开显示。
6. “常驻前台服务”只负责保持网络连接和调度，不等于获得剪贴板权限；UI 必须分别显示网络状态、进程存活状态、后台读取状态和后台写回状态。
7. 第一版不实现完整输入法。默认 IME 确实是 Android 的系统豁免项，但要求用户把本应用设为键盘，会改变日常输入工具，收益不抵偿复杂度；作为后续针对特殊 ROM 的独立插件评估。

### 0.2 明确不做的内容

以下功能不进入第一版，也不允许实现 agent 在没有变更记录的情况下顺手加入：

- iOS、macOS、Linux 客户端。
- 账号、登录、订阅、云端容量、团队空间、多租户权限。
- 公共分享链接、社交、评论、协作编辑。
- AI 摘要、翻译、OCR、自动分类和推荐。
- 远程桌面、键鼠共享、通知同步、短信同步。
- 文件夹同步、媒体图库、通用文件传输。Stage 9 的对象仅限“剪贴板当前项中的静态栅格图片”，不是文件传输；独立文件继续使用 LocalSend。
- 公网 NAT 穿透、公共 Relay、自建云控制面板。
- 完整输入法、远程键鼠和无关的无障碍功能。Android 后台剪贴板所需的内置特权宿主、官方 Shizuku（可选）、ADB 授权和悬浮窗属于用户明确开启的本地能力，不作为隐式绕过。内置宿主不要求 Root，普通 adb shell（uid 2000）即可启动，root 只是等价的显式启动身份；应用不得调用 `su`、静默执行 adb 或自动授予权限，也不降低 Android target SDK。无障碍服务不进入第一版核心链路，因为它只能间接识别“已复制”语义，受语言、应用和 ROM 差异影响大。
- 默认遥测、剪贴板内容日志、云端崩溃上报。

### 0.3 产品形态

第一版采用 **无后端的直接 P2P** 模型：

- 每台设备都有自己的本地历史和追加式事件日志。
- Windows 长驻监听并提供一个直连 peer endpoint；Android 主动连接它。这里的“监听端”只是通信角色，不是云端后端，也不保存任何账号或全局服务状态。
- 数据只在已配对的两台设备之间传输；一台 Windows 可以配对多个 Android，每个配对关系独立认证。
- Android 断线时继续写入本地 outbox；重新连通后双方交换缺失事件，而不是向第三方服务器拉取。
- 两台设备必须网络互通：同一局域网、Tailscale/WireGuard、用户自建 VPN 或手工端口转发均可。没有网络可达性时，第一版不承诺同步。
- 第一版仍不实现 Windows 之间的全网状同步；但协议使用对等事件模型，不把 Windows 的序号当作全局权威。

这种设计保留了“复制即同步”的体验，同时避免自建账号、云服务、数据库后端和 Relay。Android 以主动维持到 Windows 的出站连接为主，这是移动端进程生命周期和 NAT 下更可靠的连接角色选择；它与 Android 是否允许后台读写剪贴板是两件独立的事。

### 0.4 对“直接 P2P、类似蓝牙配对”说法的工程化解释

作者的说法可以转化为四个可验证结论：

1. **不用后端**：没有第三方账号、云数据库或中心同步服务；但至少有一个 peer 进程必须监听端口，监听端不是后端业务服务。
2. **网络互通即可同步**：同一局域网、VPN 或手工端口转发是前提；如果两台设备都在不可达的 NAT 后面，没有 Relay 就不能连接。
3. **类似蓝牙认证**：本质是首次配对时交换设备身份、证书指纹和一次性秘密，之后做挑战响应；不意味着使用 Bluetooth 协议。
4. **细粒度规则和优化**：必须落实为可测试的策略字段、事件范围交换、哈希去重、批量发送和背压，不能只以“完善”作为验收标准。

本计划实现的是功能等价的独立协议，不假设能够兼容作者的私有线协议，也不把截图中的宣传性描述当作安全审计结论。

## 1. 总体技术决策

### 1.1 客户端技术栈

| 部分 | 选择 | 使用理由 |
|---|---|---|
| Windows UI/后台程序 | .NET 8 + WPF | 只支持 Windows；WPF 对托盘、隐藏窗口、开机启动和 Win32 消息循环成熟，避免 Flutter 原生桥接层。 |
| Android UI | Kotlin + Jetpack Compose | Android 生命周期、通知、分享面板、Foreground Service 和快捷磁贴使用原生 API，行为可控。 |
| Windows 剪贴板监听 | Win32 `AddClipboardFormatListener` + `WM_CLIPBOARDUPDATE` | 事件驱动，不需要轮询；低占用且能在后台工作。 |
| Windows 图片剪贴板 | Win32 标准图片格式（优先 PNG、`CF_DIBV5`、`CF_DIB`）+ WIC 有界解码/编码 | 读取多个剪贴板表示，复制出独立字节后立即释放剪贴板锁；不把原生句柄或任意文件路径带入同步层。 |
| Android 剪贴板能力层 | `ClipboardManager`、`WindowManager` Overlay、Shizuku API 兼容内置宿主、`READ_LOGS`、分享 `Intent`、Quick Settings Tile | 用读取 `BackgroundClipboardBackend` + `ClipboardWriteCoordinator` 分别封装后台读取和写回；不把某一 ROM 行为硬编码进同步逻辑。 |
| Android 图片剪贴板 | `ClipData` MIME/URI + `ContentResolver` 流式物化 + app-private blob + `FileProvider` 写回 | URI 授权可能短暂有效，捕获事件后立即复制到应用私有存储；不通过 UserService 或 Binder 传输整张图片。 |
| Android 常驻运行 | 原生 Kotlin `ForegroundService`（`connectedDevice`）+ `WorkManager` | `connectedDevice` 对应“与已配对 Windows 外部设备持续网络交互”；前台服务维持连接和即时调度，`WorkManager` 只做有界恢复。必须满足该类型的 manifest 权限和网络前提，不能只靠类型名规避系统限制。 |
| Android 特权桥 | `dev.rikka.shizuku:api:13.1.5` 客户端协议 + APK 内置 `PrivilegedHostService`/`ClipboardUserService` | 用户显式启动内置宿主后，以 shell/root 宿主孵化受限 UserService 访问系统 Clipboard Binder 并注册事件；不依赖额外 Shizuku APK，不提供任意 shell、`newProcess` 或远程 transact。 |
| P2P peer endpoint | ASP.NET Core Kestrel + HTTPS/WebSocket | 作为 Windows 进程内的直连监听端；没有独立后端、账号服务或云数据库，WebSocket 用于双方交换事件。 |
| Android 网络客户端 | OkHttp WebSocket + Kotlin Coroutines | 连接、心跳、重连、取消和 Foreground Service 集成成熟。 |
| Windows 数据库 | SQLite + `Microsoft.Data.Sqlite`/SQLitePCLRaw | 单机、事务、WAL 和迁移足够；不引入服务端数据库。 |
| Android 数据库 | Room + SQLite | Android 官方方案，支持迁移、事务和 Flow 查询。 |
| 图片本体存储 | 应用私有的 content-addressed 文件 + SQLite/Room 元数据 | 大图片不直接塞入 `clips` 表；临时文件完成 hash 和图片校验后原子改名，数据库只保存引用。 |
| Windows 状态/UI | CommunityToolkit.Mvvm | 减少 WPF 属性和命令样板，便于单元测试。 |
| Android 状态/UI | ViewModel + StateFlow | 与 Compose 和生命周期配合自然。 |
| 协议格式 | 版本化 JSON；图片 v2 使用控制消息加 base64url 分块 | 保持 C# 与 Kotlin 解析模型一致；先不修改 v1 的纯文本和“拒绝二进制帧”契约，二进制帧留给后续协议版本。 |
| 二维码 | Windows `QRCoder`；Android ML Kit Barcode Scanning | 配对只发生一次，依赖成熟库即可。 |
| 密钥保护 | Windows DPAPI；Android Keystore | 秘密不写入明文配置文件，不自行实现密钥存储。 |
| 传输安全 | TLS 1.3 + 证书指纹固定（certificate pinning） | 个人私网场景足够；不需要第一版实现复杂的 Relay/E2EE 协议。 |
| 应用层 E2EE | MVP 不单独实现 | 第一版没有第三方 Relay，TLS 已覆盖直连链路；若以后加入 Relay，再以独立协议版本增加端到端 payload 加密。 |
| 日志 | `Microsoft.Extensions.Logging`；Android Logcat | 只记录状态、错误码和计数，禁止记录剪贴板正文。 |

### 1.2 为什么不使用 Flutter + Rust

项目只有 Windows 和 Android，真正困难的是两端的系统集成，而不是界面复用。Flutter 仍然需要分别编写 Windows 剪贴板插件、Windows 托盘插件、Android Foreground Service 和后台通信桥。第一版使用两个原生客户端可以减少桥接不确定性。

如果未来协议稳定、需要增加第三个平台，再把纯业务层抽取为 Rust 或 Kotlin Multiplatform；这不是第一版的前置条件。

### 1.3 运行版本

- Windows：Windows 10 22H2 及以上，优先 x64；ARM64 作为后续构建目标。
- Android：`minSdk 29`（Android 10）；`targetSdk` 使用当前安装的稳定 SDK。
- Android 基础网络和前台服务权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`。其中 `CHANGE_NETWORK_STATE` 满足 `connectedDevice` 类型要求的至少一个网络前提；启动时仍须把 `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` 传给 `startForeground`/`ServiceCompat.startForeground`。
- Android 按需权限和设置引导：`POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`RECEIVE_BOOT_COMPLETED`。`POST_NOTIFICATIONS` 被拒绝不阻止前台服务启动，但会使普通通知栏入口不可见；必须在应用内单独显示并解释。`READ_LOGS` 虽可声明但不能通过普通运行时权限对话框授予，只能由用户显式通过 adb 等特权路径授予并由应用实测验证。
- 内置特权宿主启动边界：应用打开后才会在外部文件目录生成 `start.sh`；用户自行执行 `adb -s <serial> shell sh /storage/emulated/0/Android/data/com.clipsync.android/start.sh`（或等价 root 命令）。安装、升级、重启或宿主代码更新后必须重新执行并重新 probe；应用不自动启动、提权或替用户确认。
- JDK：17 LTS。
- .NET SDK：8 LTS。
- Android Studio：稳定版，包含 Android SDK、Platform Tools 和 Build Tools。
- 构建工具：Git、PowerShell 7（可选）、Visual Studio 2022 Desktop C++ workload 只在需要 Win32 原生辅助代码时使用。

## 2. 仓库和模块布局

以一个新仓库为基准，建议目录如下：

```text
win-android-clip-sync/
  docs/
    product-scope.md
    threat-model.md
    protocol-v1.md
    adr/
  protocol/
    v1/
      envelope.schema.json
      messages.schema.json
      fixtures/
  windows/
    ClipSync.sln
    ClipSync.App/
      App.xaml
      MainWindow.xaml
      Tray/
      Clipboard/
      Sync/
      Storage/
      Pairing/
      Security/
      Settings/
    ClipSync.Tests/
    ClipSync.IntegrationTests/
  android/
    settings.gradle.kts
    app/
      src/main/java/.../ui/
      src/main/java/.../sync/
      src/main/java/.../storage/
      src/main/java/.../pairing/
      src/main/java/.../platform/
      src/main/java/.../platform/clipboard/
        BackgroundClipboardBackend.kt
        ClipboardAccessCoordinator.kt
        ClipboardWriteCoordinator.kt
        PublicClipboardWriter.kt
        ShizukuClipboardBackend.kt
        AdbLogOverlayBackend.kt
        OverlayPollingBackend.kt
        ForegroundClipboardBackend.kt
        OverlayFocusController.kt
        ClipboardCapabilityStore.kt
      src/main/java/.../service/
      src/test/
      src/androidTest/
  scripts/
    build-windows.ps1
    build-android.ps1
    run-e2e.ps1
    install-windows-startup.ps1
    android-bootstrap.ps1
  README.md
  LICENSE
```

Windows 和 Android 不共享源代码，但共享 `protocol/v1` 下的 JSON Schema、示例消息和测试向量。任何协议修改都必须同时更新两个客户端和兼容性测试。

### 2.1 Android 后台能力模块契约

不要让网络同步层直接调用 Android `ClipboardManager`。它只依赖受测试的读、写协调器，避免内置特权宿主/官方 Shizuku、悬浮窗和手动模式在业务代码中分叉：

```text
ClipboardAccessCoordinator
  -> BackgroundClipboardBackend (选中的读取实现)
      probe() -> CapabilityReport
      start(onChanged)
      stop()
      readText() -> ClipboardReadResult
      health() -> BackendHealth

ClipboardWriteCoordinator
  -> PublicClipboardWriter
      writeText(text, originEventId) -> ClipboardWriteResult
  -> 内置宿主/官方 Shizuku/Overlay writer fallback（只在公开写入失败时）
```

`CapabilityReport` 只包含读取模式、`read_state`、`write_state`、系统版本、授权状态、最近一次成功时间和稳定错误码，绝不包含剪贴板正文。读取协调器保存 `requested_read_mode`、`active_read_mode`、`auto_fallback_allowed`、`last_error_code` 和 `last_health_at`；每次切换模式先停止旧 listener，再刷新当前内容哈希，最后启动新 listener，防止同一份文本被上传两次。写协调器独立保存公开写入和特权回退的探测结果，不能因读取模式变化而错误关闭可用的公开写入。

各实现的职责固定如下：

- `ShizukuClipboardBackend`：通过 Shizuku API 13 客户端协议绑定 ClipSync 内置宿主孵化的 UserService。官方 Shizuku 不再作为后端。UserService 以 shell UID 访问 `IClipboard` Binder，注册/注销变化回调并读取文本；宿主、UserService 或 Clipboard Binder 死亡，或 API 形状不兼容时返回明确错误并重新探测。隐藏 Binder 反射只封装在该模块，按 Android API 版本写适配测试，不能散落到 UI 或同步层。它也可向写协调器提供回退写入，但不是 Android 后台写回的默认路径。
- `AdbLogOverlayBackend`：运行受 `READ_LOGS` 授权保护的 logcat reader，只将识别到的系统复制信号送入去抖队列；读取委托 `OverlayFocusController`。日志格式不匹配、权限被收回或 10 秒无健康心跳时退到候选下一档。
- `OverlayPollingBackend`：由 `ForegroundService` 存活期间的原生 `Handler`/协程定时触发；通过同一个 `OverlayFocusController` 读取，和最后成功哈希比较。后台没有自动开启悬浮窗权限，用户必须明确选择该模式。
- `ForegroundClipboardBackend`：App 在前台、分享目标或快捷磁贴时使用普通公开 API 读取；它是所有自动读取模式不可用时的无损手动出口。
- `PublicClipboardWriter`：始终先尝试公开 `ClipboardManager.setPrimaryClip()`，在后台、锁屏和各 ROM 上分别记录成功、被系统拒绝、被厂商丢弃和超时；只有失败时才请求下一写入 backend。
- `OverlayFocusController`：单线程串行化所有 overlay 读写，先获得焦点、短重试读取、立即释放焦点；读写期间暂停本地 listener，并使用 `originEventId`/哈希抑制回环。它不采集触摸、键盘、屏幕内容或其他应用 UI。

读取模式切换策略必须可见且可控：默认优先 `SHIZUKU_EVENT`（内置宿主或官方 Shizuku）；用户可选择“自动降级到悬浮窗轮询”或“只提醒，不切换”。由于悬浮窗可能影响游戏、全屏视频或输入法，应用不得在用户未开启它的情况下偷偷启用该模式。写回策略单独设置，公开写入可用时不得因为特权宿主/官方 Shizuku/悬浮窗失效而错误降级为“只能手动复制”。

### 2.2 内置特权宿主当前状态（2026-08-19）

这部分是当前实现事实。MIUI 14 单机的授权与本机落库已复测；详细变更和未覆盖项见 [`docs/stage-8-change-log.md`](docs/stage-8-change-log.md)。代码沿用 `SHIZUKU_EVENT` 名称以保持现有协调器和协议兼容，但“Shizuku”在这里表示 API 兼容的客户端协议，不再表示必须安装官方 Shizuku 管理器。

**已落地的代码路径**：

- ClipSync APK 在主进程启动后生成可读、可执行的 `start.sh`。脚本只接受 uid 2000（adb shell）或 uid 0（root），通过 `pm path` 定位本 APK，并用 `/system/bin/app_process` 启动 `clipsync_priv_server`；不包含 `su`、`pm grant`、`setenforce` 或任意提权步骤。
- `PrivilegedHostService` 实现 Shizuku API v13 所需的受限 Binder 子集，把宿主 Binder 经受 `INTERACT_ACROSS_USERS_FULL` 保护的 `ClipSyncShizukuProvider` 送回应用，再孵化单一 `:clipsync-clipboard` UserService。`newProcess`、`transactRemote`、系统属性写入和 rish 均明确不实现。
- UserService 只提供剪贴板文本读/写、变化监听注册/注销、health/ping 和销毁事务；不接收网络包、不保存配对密钥、不执行任意 shell。应用回调 Binder 死亡时 UserService 退出，避免孤儿进程。
- 客户端具备宿主/UserService/Clipboard Binder death 监听、指数退避重绑、重绑后的哈希基线刷新和授权等待。官方 Shizuku 不再作为可选后端。
- 向导已改为“重新检查特权宿主”和“授权特权宿主”。建立能力的前提是用户显式启动宿主；应用内授权卡只确认/请求这个已启动的宿主，内置宿主不会弹官方确认对话框。`android-bootstrap.ps1` 只检查并打印命令，绝不替用户执行。

**当前验证边界和发布前置条件**：

- `PrivilegedHostScriptTest`、`PrivilegedHostAccessTest`、`ShizukuClipboardBackendTest`、授权回调相关 `WizardViewModelTest` 等 JVM 测试已通过；这证明协议/状态和脚本约束，不证明 ROM 上的真实 Binder 链路。
- Redmi Note 11T Pro（Android 13 / MIUI 14，2026-08-20）：本包 `start.sh` 拉起 `clipsync_priv_server` 与 `:clipsync-clipboard`，`attach api=13`，运行时 `SHIZUKU_EVENT`/`READY`，本机落库 `source_app=shizuku`。单机 **DEVICE-VERIFIED**；不是官方 Shizuku。未覆盖整机重启后再跑 `start.sh`、杀 UserService 后监听恢复、运行中撤授权。旧宿主必须重新执行本包 `start.sh` 才会加载修补。不能把 binder 存活单独写成 `READY`。
- `PrivilegedHostAccess` 现为 fail-closed：空 `getPackagesForUid` 不再放行任意应用 uid，须为本包 / 宿主自身 / 已解析的 ClipSync uid。
- 安装、升级、重启或宿主代码更新会使运行中的旧宿主失效或需要重启。验收脚本必须记录“执行 `start.sh` -> binder 到达 -> 授权 -> UserService -> 实际复制事件”的完整链路，并把缺失任一步标为 `NOT_TESTED`/`PARTIAL`，不能以代码编译或授权字段为替代。

## 3. 数据和同步模型

### 3.1 Windows 数据库表

使用 SQLite，开启 WAL 和外键约束。第一版数据库包含：

```sql
CREATE TABLE devices (
  device_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  platform TEXT NOT NULL,
  certificate_fingerprint TEXT NOT NULL,
  pair_secret_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at INTEGER
);

CREATE TABLE clips (
  event_id TEXT PRIMARY KEY,
  origin_device_id TEXT NOT NULL,
  origin_seq INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK (kind = 'text'),
  content TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  source_app TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER,
  deleted_at INTEGER,
  UNIQUE(origin_device_id, origin_seq)
);

CREATE TABLE peer_cursors (
  peer_id TEXT NOT NULL,
  origin_device_id TEXT NOT NULL,
  received_seq INTEGER NOT NULL DEFAULT 0,
  acked_at INTEGER NOT NULL,
  PRIMARY KEY(peer_id, origin_device_id)
);

CREATE TABLE local_sequences (
  device_id TEXT PRIMARY KEY,
  next_seq INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE tombstones (
  event_id TEXT PRIMARY KEY,
  origin_device_id TEXT NOT NULL,
  origin_seq INTEGER NOT NULL,
  deleted_at INTEGER NOT NULL,
  expires_at INTEGER
);

CREATE TABLE outbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  peer_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  state TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER NOT NULL,
  last_error TEXT
);

CREATE TABLE sync_policies (
  policy_id TEXT PRIMARY KEY,
  peer_id TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  direction TEXT NOT NULL DEFAULT 'both',
  content_kind TEXT NOT NULL DEFAULT 'text',
  min_chars INTEGER,
  max_chars INTEGER,
  min_bytes INTEGER,
  max_bytes INTEGER,
  source_app_pattern TEXT,
  action TEXT NOT NULL DEFAULT 'sync',
  updated_at INTEGER NOT NULL
);

CREATE TABLE settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
```

### 3.2 Android 数据库表

Android 使用 Room，保存：

- 已同步的 `clips` 缓存。
- `outbox` 待发送内容。
- `peer_cursors` 中每个来源设备的最大连续已接收序号；高于该序号的零散事件必须以 ranges 单独记录，不能用“最大见过序号”掩盖中间缺口。
- `settings`、通知状态和本地删除标记。

Android 和 Windows 使用相同的事件 ID 规则。每台设备为自己的 `origin_device_id` 维护单调递增的 `origin_seq`；Android 本地创建的事件不需要等待 Windows 分配序号。

### 3.3 事件规则

1. 复制事件必须先写入本地数据库，再进入每个 peer 的网络队列，避免网络故障导致数据丢失。
2. 连续复制相同内容时，使用内容哈希和时间窗口去重；默认 2 秒内相同文本只保留一条。
3. 事件的幂等键是 `origin_device_id + origin_seq`；任一 peer 重复收到时直接返回已有状态。
4. 双方先交换 `known_vector`（每个来源设备的最大**连续**已持久化序号）和可选 `received_ranges`，再计算缺失范围；不能把最大见过序号当成无缺口证明。乱序收到 `seq=12` 而 `seq=11` 缺失时，连续游标仍停在 10。
5. 事件正文持久化后才发送 `ack_ranges`；未确认的事件继续保留在对应 peer 的 outbox。
6. 第一版的“删除历史”是本地操作：清除本机正文并保留最小 tombstone/事件 ID，防止同一已确认事件再次显示；未发送/未确认的本地事件同时从 outbox 取消，已经到达其他设备的内容不尝试远程撤回。跨设备删除和 remote wipe 不属于 MVP，避免误删其他设备数据和引入额外一致性协议。
7. 不依赖设备墙上时钟决定一致性；展示排序使用逻辑序号、创建时间和设备 ID 的稳定组合。
8. 默认上限为 2,000 条或 30 天，两个条件任一满足就清理旧内容；设置中可调整。
9. 文本大小上限为 1 MiB，超出内容只保留本地并提示“未同步”，不能隐式截断。

### 3.4 细粒度同步规则

作者所说的“根据尺寸、大小、隐私模式”必须落成可测试的本地策略引擎，而不是留在宣传文案中。策略在事件进入 outbox 前执行：

1. 全局状态：启用、暂停、私密模式。
2. 方向：仅 Windows 到 Android、仅 Android 到 Windows 或双向。
3. 类型：协议 v1 只有 `text`；Stage 9 通过协议 v2 增加 `image`，并明确 `mime_type`、编码字节数、像素宽度和像素高度。未协商 v2 的 peer 不能收到图片正文。
4. 文本规则：最小/最大字符数、最小/最大 UTF-8 字节数。
5. 来源规则：进程名精确匹配或通配符黑名单；无法识别来源时按“未知”处理，不误判为可信来源。
6. peer 规则：可对不同 Android 设置不同方向、大小上限和保留期限。
7. 私密模式：当前内容不写历史、不加入 outbox、不推送；退出私密模式后不补发过去内容。
8. 超限策略：`local_only`（仅本地保存）或 `drop`（完全丢弃），不得静默截断。

策略执行顺序固定为：暂停/私密模式 -> 来源黑名单 -> 类型 -> 字符/字节/尺寸 -> peer 方向 -> 保留期限。图片额外检查 MIME、编码字节数、解码像素数和最大单边尺寸。每条被拒绝的事件只记录原因码，不记录正文。

### 3.5 同步优化

- **Header-first**：先交换事件 ID、哈希、大小和时间，再按需请求正文。
- **Hash dedup**：相同内容在同一 peer 上只传一次，历史仍可保留多个来源记录（设置可选择合并展示）。
- **Batching**：短时间内的多个小事件合并为一个帧，减少 Android 唤醒次数。
- **Debounce**：Windows 对剪贴板连续变化做 100～200 ms 防抖，但不能影响最终内容捕获。
- **Backpressure**：Android 电量低或网络差时降低发送批量，不阻塞 UI。
- **Compression**：只对超过阈值的正文压缩；小文本不为压缩增加延迟。
- **No unnecessary polling**：Windows 使用剪贴板事件；网络使用 WebSocket 心跳和系统网络变化回调。Android 只在“悬浮窗轮询”能力档启用本地轮询，且仅在屏幕可交互、前台服务健康时运行。

### 3.6 Stage 9 图片数据模型

图片扩展不把大字节串写入 `clips.content`。文本基线表保持可读和可迁移；图片使用独立的媒体表和应用私有文件目录：

```sql
CREATE TABLE media_blobs (
  content_hash TEXT PRIMARY KEY,
  mime_type TEXT NOT NULL,
  encoded_bytes INTEGER NOT NULL,
  pixel_width INTEGER NOT NULL,
  pixel_height INTEGER NOT NULL,
  storage_key TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE clip_media (
  event_id TEXT PRIMARY KEY REFERENCES clips(event_id),
  content_hash TEXT NOT NULL REFERENCES media_blobs(content_hash),
  storage_state TEXT NOT NULL
);
```

实现约束：

1. `clips.kind` 迁移为允许 `text` 和 `image`；文本行继续要求 `content`，图片行的 `content` 必须为 `NULL`，具体组合约束由代码和迁移测试共同保证。由于当前 `content` 是 `NOT NULL`，SQLite 迁移必须采用新表复制/重命名或等价的事务方案，保留所有 event、tombstone、outbox 和索引，不能只改 CHECK 文本。
2. 图片文件按 SHA-256 内容寻址，先写同目录临时文件，再完成 MIME 魔数、尺寸、编码字节数和 hash 校验后原子改名。
3. `clip_media`、outbox、未完成下载和历史行都算 blob 引用；只有引用数为零时才允许垃圾回收。软删除只清除可见内容，不删除同步所需的事件终止记录。
4. 进程启动时扫描未完成临时文件和数据库孤儿引用；恢复或清理都必须有大小上限，不能把损坏文件当作空图片。
5. Windows 使用新的 `user_version` 迁移（预计 v3）；Android 使用相邻 Room migration（预计 v2），两端版本号独立，均不得启用 destructive migration。

Stage 9 的初始限制固定为：单张编码图片最多 16 MiB，解码后最多 32 MP，任一边最多 8192 px，单批最多 16 MiB，分块大小 256 KiB，同时下载最多 2 个，未完成下载保留 24 小时，缩略图最长边 512 px。超限只能按 `local_only` 或 `drop` 处理，不能静默截断。

图片事件的 `content_hash` 是经过捕获端校验并实际存储/传输的编码字节 SHA-256，不是图片文件名、URI 或解码后像素的 hash。像素摘要只能作为本地回环抑制辅助，不能替代协议完整性校验。

## 4. 协议 v1

协议 v1 已冻结为纯文本协议。`kind`、文本 hash 规则、JSON text frame 和现有 fixtures 不因图片功能而放宽；任何支持图片的字段或传输语义都必须进入协议 v2。文本 peer 仍必须在图片扩展上线后正常同步文本。

### 4.1 连接方式

- Windows peer 在随机可配置端口监听 HTTPS；这只是直连端点，不是独立后端。
- WebSocket 路径：`/v1/peer/sync`。
- REST 路径：`/v1/peer/health`、`/v1/pair/confirm`。同步策略和应用设置只保存在各端本地，不暴露远程修改接口。
- 所有请求带 `X-Protocol-Version: 1`。
- 任一已配对 peer 都可以发起 WebSocket；第一版实际由 Android 主动连 Windows，以减少 Android 后台监听端口、NAT 和进程恢复的不确定性。
- WebSocket 每 30 秒发送 ping；连续 3 次无 pong 断开并指数退避重连。
- 重连等待：1、2、4、8、16、30 秒，最大 5 分钟，网络恢复后立即触发一次重连。

### 4.2 消息包格式

所有 WebSocket 消息使用：

```json
{
  "version": 1,
  "type": "hello",
  "request_id": "uuid",
  "body": {}
}
```

必须实现的消息类型：

- `hello`：设备 ID、平台、客户端版本、已知来源序号向量。
- `challenge`：监听端发起随机挑战；连接方向不影响认证。
- `auth`：连接发起端使用配对密钥响应挑战。
- `known_vector`：交换每个来源设备的最大连续已持久化序号，并可附带非连续 `received_ranges`。
- `want_ranges`：请求一个或多个来源设备的缺失序号范围。
- `clip_announce`：事件头、哈希、大小、类型和策略相关元数据。
- `clip_fetch`：按事件 ID 请求正文。
- `clip_payload`：正文和校验哈希。
- `ack_ranges`：确认已持久化的来源序号范围。
- `error`：稳定错误码，不在日志中包含正文。
- `ping`/`pong`：保活。

同步握手的最小顺序为：`hello` -> `challenge/auth` -> `known_vector` -> `want_ranges` -> `clip_announce`/`clip_fetch`/`clip_payload` -> `ack_ranges`。双方都可以在同一连接上发送事件，不能把 Windows 特殊化为唯一写入者。

### 4.3 配对流程

1. 首次启动的监听 peer 生成设备 ID、随机 TLS 证书和一次性配对令牌。
2. 监听 peer UI 显示二维码，内容包含地址、端口、设备 ID、证书 SHA-256 指纹和一次性令牌。
3. 另一台设备扫描二维码后，只通过指纹匹配的 TLS 连接提交配对请求。
4. 双方都显示对方名称和指纹，要求用户明确确认；不能仅凭网络可达自动信任。
5. 配对双方各自保存对方证书指纹和独立 pair secret；secret 用 DPAPI/Keystore 保护。
6. 配对成功后一次性令牌失效，二维码重新生成。
7. 撤销任一 peer 后，双方删除对应 secret、关闭连接并增加本地 trust epoch；旧连接不能继续提交事件。

第一版不允许通过“只知道 IP 和端口”访问同步接口。未配对设备只能获得有限健康状态，不能读取内容或提交事件。这里的认证是设备对设备的信任，不是登录后端账号。

### 4.4 局域网发现

- 二维码是首选配对方式，避免先实现复杂发现协议。
- 已配对设备重连时使用保存的地址。
- 地址变化时，Windows 发送仅包含设备 ID、端口和证书指纹的 UDP 广播；广播不包含剪贴板内容和 pair secret。
- Android 收到广播后仍必须走 TLS 指纹验证和 pair secret 认证。
- Tailscale/WireGuard 地址由用户在设置中手动添加；不实现公网 Relay。
- 文档必须明确：没有 LAN/VPN/端口转发等网络可达性时，纯 P2P 不会同步；这不是客户端 bug。

### 4.5 Stage 9 图片协议 v2（计划）

v2 不是 v1 的隐式扩展，建议使用 `X-Protocol-Version: 2`、`/v2/peer/sync` 和 `version: 2` envelope。连接协商时必须声明 `image_clip_v2` 能力；认证 transcript 同时绑定协商出的协议版本，禁止把已声明支持图片的连接降级成 v1 后继续发送图片。

#### 图片头和能力

`clip_announce` 的 `image` header 至少包含：`event_id`、`origin_device_id`、`origin_seq`、`availability`、`kind=image`、`mime_type`、`content_hash`、`encoded_bytes`、`pixel_width`、`pixel_height`、`created_at_ms`，以及可选的来源和过期时间。只允许 `image/png`、`image/jpeg`；GIF、SVG、HTML、视频、未知 MIME 和任意文件 URI 在 MVP 拒绝。

不支持图片的 peer 收到图片事件时，发送方必须保存并发送合法的终止标记（例如 `unsupported_media`），使连续游标能够前进；不能发送空文本、data URI 或无限重试。已经拥有该图片的接收方不会因终止标记删除本地内容。

#### 图片正文传输

第一版 v2 保持 WebSocket JSON text frame，只新增严格的 base64url 分块消息：

```text
clip_payload_begin -> clip_payload_chunk* -> clip_payload_end
```

每个分块绑定 `transfer_id`、`event_id`、`chunk_index`、`chunk_count`、`chunk_bytes` 和无 padding 的 `data`。单块解码后不得超过 256 KiB，累计字节数不得超过 header 声明或 v2 全局上限。分块可以断线重试，但不能跨事件复用 transfer ID。二进制 WebSocket 帧、压缩协商和任意 MIME 转发暂不进入 v2；若 base64 开销经压测不能接受，另立 v3 binary-frame 设计。

流程仍为 `announce -> fetch -> begin -> chunks -> end -> verify -> persist -> ack`：只有在所有分块落入临时文件、字节数/MIME/尺寸/hash 全部通过，并且 blob 引用与事件在同一数据库事务提交后，才发送 `ack_ranges`。断线重连通过临时下载的已收分块范围恢复；超时或达到资源上限的传输必须可取消并清理。

#### 兼容和错误

v1/v2 混连时继续同步文本。v2 peer 使用 `unsupported_media` 原因；v1 schema 不认识该枚举时，发送方不得发送 v2 header，而是发送 v1 兼容的 `unavailable` + `reason=local_only` 终止标记，并在本地把真实原因记录为 `unsupported_media`，以便 v1 peer 推进游标而不误收正文。两种版本都不能发送空文本、data URI 或无限重试。新增 v2 错误码至少包括 `UNSUPPORTED_MEDIA`、`MEDIA_TOO_LARGE`、`MEDIA_DECODE_FAILED`、`MEDIA_HASH_MISMATCH`、`MEDIA_OUT_OF_ORDER` 和 `MEDIA_STORAGE_FAILED`。协议/认证/事件冲突仍是致命错误，大小、速率和暂时存储不足可返回可重试错误但不得分配无界内存。

## 5. 分阶段实施

每个阶段都必须先完成该阶段的单元测试和手工验收，不能把失败项带入下一阶段。每阶段结束后更新 `docs/` 中的决策和已知限制。

### 5.0 时间预算和依赖

以下按一名熟悉 C#、Kotlin 和 Windows/Android 平台的工程师估算；实际时间取决于实体设备测试和 Android 厂商行为。时间是执行预算，不是降低验收标准的理由。

| 阶段 | 预计时间 | 前置阶段 | 退出条件 |
|---|---:|---|---|
| 0 | 3～5 天 | 无 | 规格、协议 fixture、空工程、Android 能力验证骨架和 CI 可运行 |
| 1 | 1～2 周 | 0 | Windows 离线捕获、历史和测试稳定 |
| 2 | 1～2 周 | 1 | Windows 直连 peer endpoint 可认证、交换、恢复和持久化 |
| 3 | 3～5 天 | 2 | 二维码配对、证书固定和撤销闭环 |
| 4 | 2～3 周 | 3 | Android 历史、接收、分享发送和游标同步完成 |
| 5 | 3～5 周 | 4 | Android 自动上行/自动应用在至少三种能力档和实体机矩阵上通过 |
| 6 | 2～3 周 | 5 | 故障、隐私和安全测试通过 |
| 7 | 2～3 天 | 6 | 可重复安装、升级、回滚和个人分发 |
| 9 | 4～6 周 | 7；协议 v2 和媒体模型设计冻结 | 图片捕获、存储、跨端同步、写回和迁移验收通过；未声明支持的 ROM/格式保持明确降级 |

核心 MVP（阶段 0～5）预计 8～14 周。阶段 4 先确保协议和手动入口完整，阶段 5 才交付本项目真正区别于 LocalSend 的“手机复制即同步”；它不是可随意砍掉的实验性附加项。

阶段 9 是在文本第一版稳定后的可选扩展，不改变阶段 0～7 的 v1 文本 DoD；进入阶段 9 前必须先完成协议 v2、blob 生命周期和图片隐私默认值的设计评审。

### 阶段 0：规格、威胁模型和空仓库初始化

**技术栈**：Git、Markdown、JSON Schema、.NET 8、Android Studio、JDK 17、adb、Shizuku API 兼容客户端、APK 内置 `app_process` 特权宿主、Android Instrumentation。

**原因**：先固定协议和边界，避免两个客户端各自发明行为。

**任务**：

- [ ] 创建仓库和分支策略：`main`、`develop`、`feature/*`。
- [ ] 创建 Windows .NET 8 WPF solution 和测试项目。
- [ ] 创建 Android Kotlin/Compose 工程，`minSdk 29`。
- [ ] 创建 `protocol/v1`、`docs`、`scripts` 目录。
- [x] 写完产品范围、威胁模型和协议 v1 文档。
- [x] 写 `docs/android-background-clipboard.md`：记录 Android 10+ 限制、四档能力、权限、模式切换和已知 ROM 差异；附 KDE Connect、ClipShare、UniClipboard 的源码/文档依据链接。
- [x] 创建 `BackgroundClipboardBackend`、`ClipboardAccessCoordinator`、`ClipboardWriteCoordinator`、`CapabilityReport` 的实现和 fake backend，禁止同步层直接访问 Android 剪贴板；后台能力协调器的状态迁移与回环抑制测试已覆盖。
- [x] 写 `scripts/android-bootstrap.ps1`，只做显式、可回滚的 adb 检测和状态显示；现在额外打印内置宿主 `start.sh` 命令，但仍不自动执行宿主启动或 `READ_LOGS` 授权。
- [ ] 列出至少四台实体 Android 设备/系统版本和 ROM：AOSP/Pixel、OneUI、MIUI/HyperOS、ColorOS/OriginOS 中可获得的组合；为每台记录 API、锁屏策略、内置宿主（以及可选官方 Shizuku）、READ_LOGS 和 overlay 测试结果。
- [x] 确定项目许可证（MIT），列出第三方依赖许可证。
- [ ] 建立基础 CI：Windows `dotnet test`、Android `./gradlew test`、协议 JSON 校验。

**验收**：

- 新机器可以按 README 完成编译。
- 两端都能运行空白窗口和健康检查测试。
- 协议示例 JSON 能被 C# 和 Kotlin 解析。
- Android fake backend 的状态迁移、回环抑制和模式选择单元测试通过。
- 没有加入账号、云服务、文件传输依赖。

### 阶段 1：Windows 本地剪贴板核心

**技术栈**：WPF、Win32 `AddClipboardFormatListener`、CommunityToolkit.Mvvm、SQLite、xUnit。

**原因**：Windows 是整个系统的稳定后台主端，先验证“复制即捕获”而不是网络。

**任务**：

- [ ] 创建隐藏的 message-only window，注册 `AddClipboardFormatListener`。
- [ ] 处理 `WM_CLIPBOARDUPDATE`，读取 `CF_UNICODETEXT`。
- [ ] 处理空内容、重复通知、剪贴板被其他进程占用和异常关闭。
- [ ] 标准化为 UTF-8 文本，保留换行，不自动修改用户内容。
- [ ] 实现内容哈希、2 秒重复去重和 1 MiB 大小限制。
- [ ] 在写回剪贴板时设置内部 `suppress_next_event`，防止同步回环。
- [ ] 接入 SQLite 表和事务；先落库，再发送事件。
- [ ] 实现 WPF 历史列表、搜索、复制、删除和清空。
- [ ] 使用 `Hardcodet.NotifyIcon.Wpf` 实现托盘菜单。
- [ ] 实现全局暂停、私密模式和保留期限设置。
- [ ] 从进程名读取剪贴板来源；建立应用黑名单。

**验收**：

- 应用启动后不打开主窗口，复制文本会在 500 ms 内出现在历史。
- 关闭网络、重启程序、睡眠唤醒后历史不丢失。
- 100 次连续复制不出现重复回环。
- 黑名单应用内容不会写入数据库。
- 单元测试覆盖去重、大小限制、回环抑制和数据库事务回滚。

### 阶段 2：Windows 直连 P2P peer endpoint

**技术栈**：ASP.NET Core Kestrel、ASP.NET Core WebSocket、System.Text.Json、Serilog 或 `Microsoft.Extensions.Logging`。

**原因**：使用标准 HTTP/TLS/WebSocket 足以满足同网段和 Tailscale 直连；Kestrel 只作为进程内监听端，不引入独立后端、账号服务、QUIC、iroh 或 Relay。

**任务**：

- [ ] 生成并持久化本机 TLS 证书。
- [ ] 实现 `/v1/peer/health`，只返回版本、端口和设备 ID，不返回剪贴板内容。
- [ ] 实现 WebSocket 握手、挑战认证、版本协商和错误码。
- [ ] 实现连接注册、心跳、断开清理和并发连接上限。
- [ ] 实现 `known_vector`、`want_ranges`、`clip_announce`、`clip_fetch`、`clip_payload` 和 `ack_ranges`。
- [ ] 任一 peer 的事件都先写入本地数据库，再加入其他 peer 的 outbox；不能把 Windows 的数据库当作远端的唯一真相。
- [ ] Android 提交的远端事件落库后，按设置决定是否写回 Windows 系统剪贴板；写回必须复用回环抑制逻辑。
- [ ] 提供 `auto_apply_remote` 设置，默认开启纯文本写回，用户可以关闭并只保留历史。
- [ ] 用数据库事务更新来源序号、peer cursor 和 outbox，保证重启后可恢复。
- [ ] 实现每个 peer 独立的 pair secret 和撤销检查。
- [ ] 写协议兼容性测试：正常、重复、乱序、序号缺口、过期令牌、错误版本、超大消息；明确验证收到 `seq=12` 但缺 `seq=11` 时连续游标不会越过 10。
- [ ] 默认只绑定局域网地址；允许用户显式添加 Tailscale/WireGuard 地址。
- [ ] 增加 UDP 发现广播，只发送设备 ID、端口和证书指纹，不发送内容和密钥。

**验收**：

- 未配对客户端不能读取或提交内容。
- 任一 peer 断开后重新连接，能够按 `known_vector` 继续交换，不重复不丢失。
- 进程强制结束后重启，未确认 outbox 仍在。
- peer 日志不出现剪贴板正文、pair secret 或私钥。

### 阶段 3：二维码配对和设备管理

**技术栈**：Windows `QRCoder`、Android ML Kit Barcode Scanning、DPAPI、Android Keystore。

**原因**：二维码减少输入地址和密钥的摩擦；证书指纹固定防止局域网中间人攻击。

**任务**：

- [ ] 监听 peer 生成一次性配对二维码；第一版由 Windows 展示二维码，协议字段不绑定 Windows 角色。
- [ ] Android 扫码、解析版本、地址、指纹和令牌。
- [ ] Android 在连接前显示 Windows 名称和指纹，要求用户确认。
- [ ] Windows 显示待批准 Android 的名称和指纹，要求再次确认。
- [ ] 双方保存长期设备 ID、pair secret 和证书指纹。
- [ ] 实现设备列表、重命名、最后在线时间、撤销和重新配对。
- [ ] 实现证书变化告警；不能静默接受新证书。
- [ ] 实现二维码过期、重复使用和取消配对测试。

**验收**：

- 从首次安装到配对成功不超过三步确认。
- 复制二维码或查看日志不会泄露长期 pair secret。
- 撤销设备后旧 APK 无法继续同步。
- 修改 peer 证书时 Android 明确阻断并提示，而不是自动信任。

### 阶段 4：Android 伴侣端、协议和手动基线

**技术栈**：Kotlin、Jetpack Compose、Room、OkHttp、Coroutines、StateFlow、Android Notification API、`ClipboardAccessCoordinator`。

**原因**：Android 原生生命周期和系统入口比跨平台插件更可靠。

**任务**：

- [ ] 创建 Android 历史列表、搜索、详情和设置页面。
- [ ] 使用 Room 保存收件箱、outbox、游标和本地删除标记。
- [ ] 实现 OkHttp WebSocket 客户端、认证、心跳和指数退避重连。
- [ ] 实现与 Windows peer 交换 `known_vector`、缺失范围和正文，落库后发送 `ack_ranges`。
- [ ] 实现“复制到系统剪贴板”按钮和通知操作。
- [ ] 实现 `ACTION_SEND` 文本分享目标，将 Android 文本写入 outbox。
- [ ] 实现 Quick Settings Tile“发送当前剪贴板”。
- [ ] 先实现 `ForegroundClipboardBackend`，使 App 在可见时使用普通 `ClipboardManager` 自动捕获；另实现 `PublicClipboardWriter`，在应用退到后台后实测普通 `setPrimaryClip()` 写入，为全部特权写入回退提供基线。
- [ ] 实现“后台自动同步”设置页和能力卡片：当前模式、是否可读/可写、网络、前台服务、battery 优化、最近成功时间和可执行修复动作必须分开显示。
- [ ] 默认交付路径是“入站先落库”；`auto_apply_remote` 开启时先走公开写入，成功后再发不含正文的状态通知；公开写入失败才按用户已授权的内置宿主/官方 Shizuku/overlay 回退。所有路径都要写入明确的 apply 状态。
- [ ] 对空内容、超大文本、缺失权限和 Windows 离线显示明确状态。

**验收**：

- Windows 复制后，Android 连接在线时 2 秒内出现在收件箱。
- Android 主界面退到后台后，`PublicClipboardWriter` 在 AOSP/Pixel 基线设备能把随机测试文本写入系统剪贴板；失败的 OEM 必须返回稳定错误码并保留通知手动复制入口。
- Android 点通知操作后，文本能正确进入系统剪贴板。
- 通过系统分享面板发送文本时不需要打开 Windows 应用。
- Android 进程被杀后，已落库内容和 outbox 不丢失。
- Android 屏幕旋转、进程重建、网络切换不产生重复事件。
- 关闭所有特殊权限时，分享面板、快捷磁贴、前台复制和通知复制仍可完整双向工作。

### 阶段 5：Android 后台自动剪贴板能力

**技术栈**：Kotlin `ForegroundService`（`connectedDevice`）、AndroidX `ServiceCompat`、`NotificationCompat`、`ConnectivityManager.NetworkCallback`、Room、Kotlin Coroutines、`WindowManager.TYPE_APPLICATION_OVERLAY`、`Settings.canDrawOverlays`、Shizuku API 13 客户端协议、APK 内置 `app_process` 宿主/UserService、`ProcessBuilder("logcat")`、adb、Android Instrumentation、WorkManager。

**原因**：Android 10+ 不存在一个普通权限就能让后台 App 可靠**读取并监听**剪贴板的公开 API。成熟项目的通用解法是“特权事件通道优先，透明焦点窗口/轮询兜底，手动入口永远保留”；后台写入则应先使用公开 API 实测，不先引入特权。本项目是个人侧载使用，允许用户明确执行内置宿主的 adb shell/root 启动命令，或选择官方 Shizuku/adb/overlay 引导，因此应把后台读取层作为核心功能而不是演示功能。应用不自动提权，也不把官方 Shizuku APK 当作硬依赖。

#### 5.1 运行时、状态机和权限引导

- [x] 实现读取模式 `ClipboardReadMode`：`SHIZUKU_EVENT`、`ADB_LOG_OVERLAY`、`OVERLAY_POLLING`、`FOREGROUND_ONLY`；实现独立的 `ClipboardWriteMode`：`PUBLIC_API`、`SHIZUKU_FALLBACK`、`OVERLAY_FALLBACK`、`MANUAL_ONLY`；两者共享 `BackendHealth`：`READY`、`DEGRADED`、`UNAVAILABLE`、`NEEDS_USER_ACTION`。模式选择、失败回退和回环抑制已有 fake/JVM 覆盖。
- [x] 在首次配对后的向导中分别检查通知、前台服务、忽略电池优化、悬浮窗、`READ_LOGS`、内置宿主 Binder 和宿主授权。每一步说明其用途、风险和可跳过后果，不能把授权埋进设置深处。官方 Shizuku 不再作为后端。MIUI 14 单机授权与本机落库已复测（2026-08-20）。
- [x] 添加用户选择：首选读取模式、是否允许自动降级、轮询间隔、是否后台自动上行、是否后台自动应用远端内容；默认首选 `SHIZUKU_EVENT`，默认不在未经许可时启用 overlay。写回默认优先公开 API，不要求用户为写回额外开启 overlay。
- [ ] `CapabilityReport` 分开持久化读/写最后成功时间、失败次数和错误码，但不得记录文本、原始 logcat 行、目标 App 名称或宿主/Shizuku 命令输出。当前已持久化读/写状态、最近成功时间和错误码；失败次数仍是收口项。
- [ ] 编写模式切换事务：停止旧 backend -> 停止/释放 overlay -> 刷新当前内容哈希 -> 启动新 backend -> 写入 mode epoch。任何一步失败都回滚到已知可用模式或 `FOREGROUND_ONLY`。
- [ ] UI 用单独状态展示“网络已连通”“同步服务运行”“剪贴板后台读可用”“剪贴板后台写可用”；不能把其中任意一项绿色误显示为全部可用。

#### 5.2 前台服务和进程恢复

- [ ] 实现用户显式启动的 `ClipboardSyncService`：manifest 声明 `foregroundServiceType="connectedDevice"`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE` 和 `CHANGE_NETWORK_STATE`；启动时使用 `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`。捕获 `MissingForegroundServiceTypeException`、`SecurityException` 和 OEM 拒绝，状态页显示具体缺项。
- [ ] 服务持有 OkHttp WebSocket、网络回调、backend 协调器和有界重连；Room outbox/ack 仍是唯一可靠状态，服务重启不得依赖内存队列。
- [ ] 实现通知操作：暂停全部同步、仅暂停 Android 自动捕获、立即同步、打开故障状态；操作不显示剪贴板正文。
- [ ] 分开引导 `POST_NOTIFICATIONS` 和电池“不受限制/忽略优化”。通知权限不是启动 FGS 的前提；拒绝后服务仍可运行，但通知栏动作和入站提示受限，应用内必须清楚显示。各 ROM 记录“锁定最近任务/自启动”路径。
- [ ] `BOOT_COMPLETED` 只在用户打开“开机恢复”后注册。Android 15 官方当前列出的 BOOT FGS 禁止类型不含 `connectedDevice`，但仍必须在目标 API 和 OEM 上实测；失败时用 WorkManager 做有界健康检查并请求用户恢复，不能崩溃、无限重启或静默失效。
- [ ] `START_STICKY` 仅作为系统可用时的补充；服务被杀后显示“需要恢复”的状态，不伪造已在线。

#### 5.3 Shizuku API 兼容事件模式（首选）

- [x] 集成 Shizuku API 13 客户端、ClipSync Provider 和 APK 内置 `PrivilegedHostService`；保留“未安装、未启动、未授权、Binder 已死、UserService 已死、Clipboard Binder 已死、API 签名不匹配”七类稳定错误码。内置宿主不依赖官方 Shizuku APK，`NOT_INSTALLED` 仅保留为可选官方后端的兼容状态。
- [x] 实现独立 UserService：获取系统 `clipboard` Binder，按 Android API 29 到当前版本适配文本读写及 listener 参数签名；服务版本固定为 2，销毁事务使用 `16777115`。
- [x] UserService 以宿主提供的 shell 身份运行，Binder 面仅限文本读/写、listener 注册/注销、health/ping 和 destroy；不传输配对密钥、网络连接或任意 shell 命令。
- [x] 注册 `IOnPrimaryClipChangedListener` 后只把“可能变化”通知协调器，再由 backend 读取当前文本、算哈希、应用策略并落库；回调本身不直接发网络包。
- [x] 特权写入只注册为 `ClipboardWriteCoordinator` 的回退 backend；公开写入失败后才调用。任何写入前都设置短生命周期 `originEventId`/哈希抑制标记，回调到达时确认来源，防止 Android -> Windows -> Android 回环。
- [x] 对宿主、Shizuku/clipboard Binder 加 `linkToDeath`、指数退避重绑和健康探针；重新绑定后先刷新当前哈希，不把重绑误认为用户复制。
- [x] 提供一键“测试后台读取”和“测试后台写入”，只使用应用生成的随机测试文本并立即清除，不读取或上传用户现有剪贴板；JVM 覆盖已通过，内置宿主真实设备链路仍待复测。

#### 5.4 ADB 日志事件加透明悬浮窗

- [ ] 在 manifest 声明 `READ_LOGS`，但设置页明确它不能通过普通运行时权限对话框授予；`android-bootstrap.ps1` 只显示待执行命令、设备序列号和撤销命令，执行授权必须二次确认。安装、升级、重启或 ROM 策略变化后都重新 probe，不能把“曾经授予”当成永久有效。
- [ ] 实现 `LogcatClipboardEventReader`：从启动时间开始读取受限 logcat 流，按 ROM/API 版本解析 ClipboardService 变更信号；只保留内存中的最近状态，不把整行日志写入磁盘或上传。
- [ ] 将复制信号进行 150 ms 防抖和单飞合并，再交给 `OverlayFocusController.readText()`；没有正文、格式未知或 5 秒内无健康事件时标记 `DEGRADED`，按用户策略退到 overlay 轮询或手动模式。
- [ ] 不从后台启动透明 Activity。后台读取使用已获用户授权的 `TYPE_APPLICATION_OVERLAY`；远端写入仍先走公开 writer，只有公开写入实测失败时才使用 overlay 回退。
- [ ] 为 AOSP、OneUI、MIUI/HyperOS、ColorOS/OriginOS 的日志解析器建立版本化 fixture；解析器匹配不到时宁可不触发，也不能把无关系统日志误当复制事件。

#### 5.5 悬浮窗轮询模式（无电脑兜底）

- [ ] 实现 `OverlayFocusController`：创建 1x1、alpha 为 0、带 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` 的 `TYPE_APPLICATION_OVERLAY`；读取时只移除 `FLAG_NOT_FOCUSABLE`，始终保留 `FLAG_NOT_TOUCHABLE`，最多重试 3 次，每次 25～50 ms，完成后立即恢复 focus flag。公开写入失败时可复用同一控制器做特权写回测试。
- [ ] 用原生协程/`Handler` 驱动 800～1,000 ms 默认轮询，允许用户在 500～2,000 ms 范围调整；每次读取只比较哈希，内容变化后才进入事件日志。
- [ ] 屏幕熄灭、Keyguard 锁定、全局暂停或服务不健康时停止轮询和释放 focus；解锁/网络恢复/服务恢复时先做一次读取再恢复周期任务。
- [ ] Overlay 失败、被系统移除或读取耗时超阈值时记录错误码并降低频率；不能忙等、无限重建窗口、移除 `FLAG_NOT_TOUCHABLE` 或持续抢占键盘。透明 overlay 不被视为 Android 后台启动/FGS 资格的替代品。
- [ ] 在设置和常驻通知中显示“悬浮窗轮询已启用”；提供立即暂停按钮，避免用户在游戏、会议或全屏场景无法解释输入焦点异常。

#### 5.6 手动降级、同步和隐私闭环

- [ ] 所有 backend 都必须在内容进入网络前调用相同的策略引擎、内容哈希、大小限制、来源/私密规则和 Room 事务。
- [ ] 当公开写入和已授权回退都不可用时，远端事件仍落 Android 收件箱并发通知；用户点通知即可用前台 API 写入剪贴板，不能丢事件或错误标为已应用。
- [ ] 当后台读取不可用时，分享面板、Quick Settings Tile 和前台自动捕获保持可用；设置卡片给出具体恢复动作，而不是笼统显示“同步失败”。
- [x] 不自动实现 Root 提权、降低 `targetSdk`、静默 adb、隐藏悬浮窗权限、完整输入法或无障碍监听。内置宿主允许用户显式以现有 shell/root 身份运行 `start.sh`，但脚本本身不提权；以后若增加输入法模式，必须作为独立权限模型和安全评审项。

#### 5.7 自动化和实体机验收

- [x] 为每个 backend 写 fake backend 单元测试：模式选择、模式切换、失败回滚、hash 去重、写回环抑制、outbox 原子落库、权限丢失；现有 Android JVM 测试已覆盖上述协调器行为。
- [ ] 为 overlay 写 Instrumentation 测试：权限缺失、窗口创建/释放、始终不可触摸、焦点恢复、并发读写串行化、屏幕关闭/解锁后不残留窗口；若某 ROM 必须移除 `FLAG_NOT_TOUCHABLE` 才能工作，该用例必须失败并标记 backend 不支持。
- [ ] 为内置宿主写可跳过的设备测试：用户执行 `start.sh` 后授权、读取、监听事件、远端写入、宿主/UserService/Clipboard Binder 重启后恢复；缺少对应运行时或无法重启宿主时必须标为 `NOT_TESTED`/`SKIPPED`，不能用 JVM 测试假通过。Redmi Note 11T Pro 已复测 `start.sh`、授权和本机监听落库（2026-08-20）；重启后再跑 `start.sh`、杀 UserService 后恢复、运行中撤授权仍未单独测。
- [ ] 为 adb 模式保存匿名化的“匹配/未匹配” fixture 和版本，不保存真实 logcat 或剪贴板文本。
- [ ] 在至少四种实体系统上跑完整表：AOSP/Pixel、OneUI、MIUI/HyperOS、ColorOS/OriginOS；每种至少验证一个可用档位，记录系统版本、锁屏、息屏、Wi-Fi 切换、杀进程、重启和电池优化场景。

**验收**：

- 内置宿主或官方 Shizuku 的 `SHIZUKU_EVENT` 模式在至少两种 API/ROM 组合上，Android 复制文本到 Windows 的 Wi-Fi P95 延迟不超过 1.5 秒；Windows 远端文本在 `auto_apply_remote` 开启时优先由公开 writer 写入 Android，公开 writer 失败时才验证对应特权回退，且 100 次循环不产生回传。
- ADB 日志 + overlay 模式在至少两种 ROM 组合上，复制事件 P95 不超过 2 秒；`READ_LOGS` 被收回或日志格式未知时，状态在 10 秒内变为 `DEGRADED`，并按用户选择降级。
- Overlay 轮询模式在至少三种 ROM 组合上可用，P95 延迟不超过“轮询间隔 + 1 秒”；测试中没有残留可见窗口、持续焦点占用或无界 CPU/电量唤醒。
- 前台服务运行时，Windows 推送可在不打开 Android 主界面的情况下进入收件箱；公开 writer 或已授权回退为 `READY` 时自动应用，不可写时明确留在收件箱。
- 应用被系统杀死、内置宿主或官方 Shizuku 重启、网络切换和设备解锁后，不丢已落库事件、不重复上传；恢复失败时用户能从通知或设置一眼看到下一步。内置宿主必须额外验证重新执行 `start.sh` 后的完整恢复链路。
- 无任何特殊权限的设备仍能完成分享、磁贴、通知复制和断线补同步，且不会被自动模式错误阻塞。

**当前阶段状态**：读取协调器、内置宿主/UserService、写回回退和 JVM 测试已落地；四类 ROM 的实体机矩阵以及 MIUI 内置宿主的授权/真实复制事件仍未完成。因此阶段 5 不能仅因 Binder 能到达或代码编译通过而关闭。

### 阶段 6：可靠性、安全和隐私硬化

**技术栈**：xUnit、JUnit、Android Instrumentation、Microsoft DPAPI、Android Keystore、SQLite WAL。

**任务**：

- [ ] 所有敏感设置和令牌使用 DPAPI/Keystore 保存。
- [ ] 校验文本大小、JSON 深度、WebSocket 帧大小和连接速率。
- [ ] 对数据库操作使用参数化查询。
- [ ] 剪贴板内容不进入普通日志、异常消息、崩溃转储和遥测。
- [x] 对内置宿主和 Shizuku UserService 做最小权限审计：只暴露剪贴板读、写、listener 注册/注销、health 和 destroy；它不接收网络包、不保存身份密钥、不执行任意 shell 命令。`ShizukuUserServiceSurfaceAuditTest` 已锁定 Binder 面，但这不替代实体机安全验收。
- [ ] 收口宿主调用方身份校验：`PrivilegedHostAccess` 当前在包管理器返回空集合时允许普通应用 uid（>=10000）作为 MIUI 兼容兜底；发布前必须获得可靠的包名/UID 归属证明并改为 fail-closed，或完成设备范围风险评估、可观测告警和明确的受限发布决策。
- [ ] 审计 `start.sh`、`app_process`、Provider 导出属性（包括 `INTERACT_ACROSS_USERS_FULL` 保护）和宿主自动授权路径：确认只有用户显式执行 shell/root 命令才能建立宿主，应用不调用 `su`/adb、不授予 READ_LOGS，且能说明停用、重启和撤销步骤。
- [ ] 对 overlay 做安全和可用性审计：窗口必须保留 `FLAG_NOT_TOUCHABLE`、透明、最小尺寸、仅在用户允许时创建；异常退出、切后台、暂停和权限撤销时保证移除。
- [ ] 对 `READ_LOGS` 路径做数据最小化审计：只在内存中识别复制信号，禁止持久化原始行、上传日志或将其写进崩溃报告。
- [ ] 对每个 backend 做 1,000 次“本地复制 -> 同步 -> 远端写回”压力测试，验证 hash/`originEventId` 回环抑制、Room 事务和切换模式期间的幂等性。
- [ ] 增加一键暂停和一键清空本机历史；明确提示该操作不远程删除其他设备历史。
- [ ] 增加密码管理器、银行软件和用户自定义进程黑名单。
- [ ] 设计数据库迁移和导出格式；升级不能静默丢历史。
- [ ] 对 Windows 睡眠/唤醒、Android 网络切换、任一 peer 重启进行故障注入。
- [ ] 使用静态分析：.NET analyzers、Ktlint/Detekt、依赖漏洞扫描。
- [ ] SQLCipher 作为可选硬化项评估；如果 native 构建稳定，再加入，不阻塞 MVP。

**验收**：

- 断网 30 分钟后恢复，所有未过期事件最终到达且只出现一次。
- 任意一端崩溃并重启，不发生数据库损坏。
- 撤销、清空、过期和黑名单行为有自动化测试。
- 安全测试不能通过伪造设备 ID、旧令牌或错误证书读取内容。
- 撤销内置宿主/官方 Shizuku、overlay 或 `READ_LOGS` 授权后，应用不再尝试对应调用，状态和降级路径在一个健康检查周期内更新；宿主重启前后的授权状态、UserService 孤儿清理和回退路径必须有测试记录。

**内置宿主安全门槛**：宿主运行在 shell/root 身份，虽只暴露剪贴板窄接口，但其权限高于普通应用。`package manager` 空查询的 fail-open 兼容分支、宿主自动回复授权和 Provider 重绑逻辑在完成代码审计及至少一台真实设备的拒绝/恢复测试前，均属于未关闭风险，不能标记为安全验收通过。

### 阶段 7：私下分发和运维

**技术栈**：MSIX 或便携 ZIP、PowerShell、Gradle APK、SHA-256 校验文件。

**原因**：不考虑应用商店，但仍要让个人设备可重复安装和回滚。

**任务**：

- [ ] Windows 提供便携版 ZIP，默认不需要管理员权限。
- [ ] 使用当前用户 `Run` 注册表或任务计划实现开机启动。
- [ ] 提供卸载脚本，先导出/询问是否删除历史和密钥。
- [ ] Android 生成 signed release APK，记录 keystore 指纹。
- [ ] 提供安装、配对、电池优化、内置特权宿主/可选官方 Shizuku、adb/`READ_LOGS`、overlay 和故障排查文档；说明首次打开应用生成 `start.sh`、用户如何自行执行、每次重启/升级后如何重新启动、如何停用和如何观察状态。
- [x] `android-bootstrap.ps1` 支持列出设备、检查开发者调试/授权状态、打印可复制的内置宿主启动命令以及 `READ_LOGS` 授予/撤销命令；默认不执行任何特权授权、宿主启动或下载，宿主代码随 ClipSync APK 提供。不打印官方 Shizuku 启动命令。
- [ ] 每个 APK/ZIP 生成 SHA-256 校验值。
- [ ] 保留最近两个可回滚版本，不实现自动静默更新。
- [ ] 发布前做依赖许可证清单；不复制 Syzygy 的闭源代码、协议实现、品牌和界面资源。

**验收**：

- 新 Windows 设备可在 10 分钟内完成安装、启动、配对和第一次同步。
- Android 侧载安装后能完成权限引导，拒绝通知权限时仍能查看历史。
- 用户能在 3 分钟内从“能力不可用”页面定位到具体缺失项（内置宿主未启动、可选官方 Shizuku 未启动、`READ_LOGS` 未授予、悬浮窗未开启、被电池优化限制或网络不可达），而不需要阅读日志；页面必须明确提示应用不会代执行 `start.sh`。
- 升级和回滚不会破坏配对密钥或历史数据库。
- 卸载行为清晰，不悄悄删除用户数据。

### 阶段 9：静态剪贴板图片扩展

**状态**：计划已冻结，尚未实施。该阶段只扩展剪贴板媒体能力，不把项目改造成通用文件传输工具。

**前置**：阶段 7 完成；协议 v1、文本存储、配对认证和现有 Android 能力降级测试保持绿色。实现前先完成本节的设计评审和跨语言 fixture，不能边写平台代码边改变 wire contract。

**范围冻结**：只支持当前剪贴板项中的静态栅格图片。MVP 支持 `image/png`、`image/jpeg`，Windows 支持 PNG、`CF_DIBV5`、`CF_DIB`，Android 支持 `ClipData` 图片 MIME 和 `content://` URI。SVG、HTML、脚本、GIF 动图、视频、HEIC/HEIF、任意文件 URI、文件夹和图库同步不属于本阶段。一个剪贴板状态同时包含文本和图片时，优先产生一个通过校验的图片事件；图片不合格时才回退为文本事件，不自动拆成两个事件。

#### 9.1 规格、协议和 fixture

- [ ] 新增图片扩展 ADR、威胁模型补充和用户可见隐私说明；明确是否保留 PNG/JPEG 元数据。MVP 默认保留通过校验的原始 PNG/JPEG 字节，后续隐私模式再评估去 EXIF/重新编码。
- [ ] 创建 `protocol/v2` schema 和版本化能力协商；v1 schema、v1 fixtures 和现有纯文本 peer 行为不得改变。
- [ ] 固定 image header、`unsupported_media` 终止标记、`UNSUPPORTED_MEDIA`/`MEDIA_TOO_LARGE`/`MEDIA_DECODE_FAILED`/`MEDIA_HASH_MISMATCH`/`MEDIA_OUT_OF_ORDER`/`MEDIA_STORAGE_FAILED` 错误码及可重试性。
- [ ] 固定 JSON text frame 的 base64url 分块格式、256 KiB 单块上限、16 MiB 单图/单批上限、断点恢复和取消语义；二进制 WebSocket 帧另作为未来 v3，不在 v2 偷渡。
- [ ] 为 C#、Kotlin 和 Python 校验器共享 valid/invalid fixture：PNG 透明图、JPEG、DIBV5 正负高度、混合表示、坏魔数、截断、错误尺寸、超大编码字节、解码像素炸弹、hash/长度不一致、乱序/重复 chunk、payload-before-fetch、v1 peer 降级和精确重复投递。

#### 9.2 媒体存储和迁移

- [ ] 在 Core/Android storage 层定义 `MediaBlobStore`，提供流式写入、hash、大小/像素检查、原子提交、临时文件恢复、引用计数和垃圾回收；业务层不得直接拼接 blob 路径。
- [ ] Windows `user_version` 增加相邻迁移（预计 v2 -> v3），Android Room 增加相邻 migration（预计 v1 -> v2），新增 `media_blobs` 与 `clip_media` 并将 `clips.kind` 扩展为 `text|image`；现有 `content NOT NULL` 需要显式的 add-copy-rename/Room 等价迁移，且不得 `DROP clips`、清空 tombstone 或启用 destructive migration。
- [ ] 图片事件只在 blob 引用和事件行同一事务提交后进入 outbox；下载先写临时文件，验证通过后再提交引用，ACK 不得早于该事务。
- [ ] 实现启动恢复和 GC：清理过期临时下载、恢复可验证的孤儿 blob、保留仍被 outbox/历史/下载状态引用的 blob，并对每次清理设置有限额。
- [ ] 升级测试覆盖旧文本可见行、软删除行、未发送 outbox、未知未来 schema、迁移中断和磁盘空间不足；任何失败都应保留原数据库可恢复。

#### 9.3 Windows 捕获、写回和界面

- [ ] 在现有 STA `WM_CLIPBOARDUPDATE` listener 中枚举格式，优先读取 PNG，再读取 `CF_DIBV5`/`CF_DIB`；必要时单独评估 `CF_BITMAP`。复制出独立字节后立即关闭剪贴板，不把 HGLOBAL/HBITMAP 生命周期泄露给 Core。
- [ ] 使用有界 WIC 解码/编码检查 DIB header、stride、位深、负高度、整数溢出、编码大小、最大维度和最大像素数；拒绝坏格式、内嵌文件路径和超限输入。来源黑名单、私密模式、2 秒 hash 去重和 sequence 去重继续共用现有策略。
- [ ] 扩展写回为 PNG 加 DIB 兼容格式，处理剪贴板占用、HGLOBAL 所有权、重试和取消；写回失败只改变能力状态，不撤销已落库事件。
- [ ] 回环抑制同时使用 `origin_event_id`、短时 hash/pixel digest、剪贴板序号和写入窗口，不能假设 DIB 重新编码后字节 hash 仍相同。
- [ ] 历史列表只加载 512 px 缩略图，详情页再按上限解码原图；提供格式/尺寸/大小、复制图片、删除和不可用状态，不在日志、诊断或通知中放正文/原图。

#### 9.4 Android 捕获、写回和能力矩阵

- [ ] 为 `FOREGROUND_ONLY`、`SHIZUKU_EVENT`、`ADB_LOG_OVERLAY` 和 `OVERLAY_POLLING` 复用一个 `ClipboardMediaReader`：检查 MIME，流式读取 `content://`，立即物化到 app-private blob，验证魔数和尺寸；不把 URI 当作长期事件正文。
- [ ] 内置宿主/官方 Shizuku UserService 只传输监听信号和受控的 pipe/file descriptor，不传整张 Bitmap，不接触网络、密钥或 Room。URI grant 失效、provider 超时、读取被 ROM 拒绝时返回稳定能力错误并保留手动入口。
- [ ] `ClipboardWriteCoordinator` 增加独立的 `IMAGE_READ`/`IMAGE_WRITE` 状态和 `auto_apply_images` 设置；公开写回优先使用 `FileProvider` URI、`ClipData.newUri` 和最小读授权，失败后才按现有权限阶梯回退。
- [ ] 图片能力不得从文本能力推断为 `READY`。每个 backend 分别记录图片读、图片写、最近成功时间和稳定错误码；当前已知 MIUI overlay 后台读取缺口必须继续标为降级/未测试，不能用文本实测代替图片实测。
- [ ] Android 历史、详情、通知和分享入口支持缩略图/复制图片；通知只提供动作，不带图片正文。进程销毁、网络切换、锁屏和 URI 权限撤销后，已物化 blob 仍可恢复。

#### 9.5 同步、背压和兼容

- [ ] 实现 v2 `announce -> fetch -> begin -> chunk* -> end -> verify -> persist -> ack` 状态机，支持断线续传、乱序拒绝/重组、重复 chunk 幂等和单 transfer 超时。
- [ ] 图片传输不得阻塞文本同步：图片单独限流，最多 2 个并发下载，电量/网络差时降低批量；内存中只保留固定大小的 chunk，不一次性读取整图。
- [ ] 相同 hash 可跳过重复 blob 传输，但每个 event 仍保留自己的来源序号和历史关系；相同 `(origin_device_id, origin_seq)`、event ID 或 hash/尺寸/MIME 冲突必须按现有 `EVENT_CONFLICT` 规则处理。
- [ ] v1 peer 继续完成文本握手和同步；图片仅生成持久化终止标记，不产生空正文、无限 want loop 或隐式降级成文件分享。
- [ ] 跨 C# 与 Kotlin 做 Windows -> Android、Android -> Windows、乱序 12/11 类似的图片游标、断线恢复、重复投递、ACK-before-commit 和旧 peer 兼容测试。

#### 9.6 导出、隐私和发布

- [ ] 将本地导出升级为 `format_version: 2`：JSONL 保存事件元数据，图片放在 `media/<sha256>` 旁路目录或归档中；导入必须再次校验 hash、MIME、尺寸和大小，不能把缺失 blob 静默导入为空图片。
- [ ] 图片自动同步默认关闭，开启时显示大小、格式、元数据和本地保留风险；图片设置与文本方向、自动应用、私密模式和来源黑名单独立可见。
- [ ] 完成解码炸弹、恶意 URI、路径穿越、临时文件权限、磁盘耗尽、崩溃转储和日志泄露审计；普通日志只记录状态、稳定错误码、计数和尺寸统计。
- [ ] 更新 Windows 便携包、Android APK、升级/回滚说明和第三方许可证；图片扩展未通过实体机矩阵时，不在发布说明中宣称该 ROM 或 backend 支持。

**阶段 9 验收**：

- PNG/JPEG 能在 Windows 与 Android 双向捕获、落库、同步、校验并写回；写回失败准确进入收件箱并提供手动复制。
- 16 MiB/32 MP/8192 px 限制、坏图片、URI 失效、磁盘不足和取消均有稳定结果，不截断、不崩溃、不产生无界内存。
- 断线、进程重启和乱序传输可恢复；重复事件和相同 blob 不重复显示、不重复发送 ACK，不产生回环。
- v1 文本 peer 继续正常工作；不支持图片的 peer 能通过终止标记推进游标，且文本同步不受影响。
- Windows 至少完成 PNG/DIB 捕获和写回测试；Android 每一个被标记为 `READY` 的图片 backend 都有真实设备记录、权限前提、P95 延迟和降级结果。没有设备证据的组合必须标 `NOT_TESTED`。
- 图片历史、导出和日志不泄露未授权正文；迁移、导出和回滚不丢失已有文本或 tombstone。

## 6. 测试矩阵

### 6.1 Windows

- Windows 10 22H2、Windows 11。
- x64；长文本、中文、Emoji、换行、空文本。
- 图片：PNG 透明图、JPEG、PNG/JPEG 与文本混合表示、`CF_DIBV5` 正负高度、`CF_DIB`、坏魔数、截断、超大尺寸和剪贴板占用。
- 剪贴板被其他进程占用、复制应用快速退出、睡眠/唤醒、锁屏/解锁。
- 普通用户运行、开机启动、托盘退出和重新启动。

### 6.2 Android

- Android 10、12、13、14/更高版本；至少覆盖四类系统实现：AOSP/Pixel、OneUI、MIUI/HyperOS、ColorOS/OriginOS。设备不足时必须在测试报告中明确缺口，不能把模拟器结果写成 ROM 覆盖。
- 干净安装、首次配对、内置宿主 `start.sh` 启动/授权/断连、可选官方 Shizuku 启动/授权/断连、adb `READ_LOGS` 授予/撤销、悬浮窗授予/撤销、开机恢复、屏幕关闭、锁屏/解锁、网络切换、进程被杀、通知权限拒绝、电池优化开启/关闭。
- `SHIZUKU_EVENT`、`ADB_LOG_OVERLAY`、`OVERLAY_POLLING`、`FOREGROUND_ONLY` 和分享面板分别验证上行、入站、远端自动写入、回环抑制与降级。
- 图片：前台 `ClipData` URI、内置宿主/官方 Shizuku 后台读取、公开 `FileProvider` 写回、URI grant 失效、图片能力与文本能力分离；未实测的 ROM/模式必须标 `NOT_TESTED`。
- 锁屏和息屏场景分别记录预期：不能安全读写时，自动模式应暂停并保留入站到收件箱；解锁后恢复，不可把系统限制伪装成内容已自动应用。

### 6.3 网络和数据

- 同一 Wi-Fi、不同网段、Tailscale/WireGuard、无网络。
- 重复连接、乱序消息、任一 peer 重启、客户端强制杀进程。
- 1,000、10,000 条历史的查询和清理。
- 非法 JSON、过大帧、错误令牌、错误证书、过期设备。
- 图片 v2：分块乱序/重复/缺失、断点续传、16 MiB 编码上限、32 MP 解码上限、hash/尺寸/MIME 冲突、v1 peer 降级、磁盘空间不足和 ACK-before-commit。

## 7. Definition of Done

只有下面全部满足，才可称为第一版完成：

- [ ] Windows 配对后可以完全后台捕获纯文本剪贴板。
- [ ] 数据只在已配对设备之间直连传输，不依赖账号、云端后端或公共 Relay。
- [ ] Windows 和 Android 都先本地落库，再通过来源序号向 peer 交换缺失事件。
- [ ] Windows 到 Android 的在线同步不要求打开主界面。
- [ ] Android 可以从通知、历史或分享面板完成复制/发送；无任何特殊权限时该路径仍完整可用。
- [ ] Android 在内置宿主或官方 Shizuku 支持的 `SHIZUKU_EVENT`（或其他读取模式）健康状态为 `READY` 时，复制后无需打开 App 即可自动落库并同步到 Windows；`READY` 必须有真实设备事件证据，不能只依据 binder 或授权字段。
- [ ] Android 的公开 writer 或已授权写入回退为 `READY` 且 `auto_apply_remote` 开启时，Windows 文本会自动进入 Android 系统剪贴板；不可写时准确进入收件箱并提供通知操作。
- [ ] 每种已声明支持的 Android 模式都有设备验证记录、P95 延迟、权限前提和降级结果；不能用“理论可行”替代实测。
- [ ] Android 通过分享面板发送的文本，在 `auto_apply_remote` 开启时能自动出现在 Windows 系统剪贴板。
- [ ] 断线补同步不丢失、不重复、不产生回环。
- [ ] 历史搜索、删除、过期和暂停行为稳定。
- [ ] 设备撤销后无法继续连接。
- [ ] 令牌、证书和日志不泄露剪贴板正文。
- [ ] Windows 和 Android 都有自动化测试及可重复构建脚本。
- [ ] 便携 Windows 包和 signed APK 可以在目标设备安装运行。
- [ ] 所有不做的功能仍然保持在明确的范围之外。

文本第一版以上 DoD 全部满足后，Stage 9 图片扩展还必须满足：

- [ ] 图片只作为剪贴板静态栅格项同步，不演变为通用文件传输、图库或文件夹同步。
- [ ] PNG/JPEG 的捕获、存储、v2 分块传输、断线恢复、hash 校验和 Windows/Android 写回均有自动化测试。
- [ ] 图片 blob 不直接进入普通日志、异常消息、崩溃转储或无确认导出；历史删除和 GC 不破坏事件终止状态。
- [ ] v1 文本 peer、旧数据库和文本能力阶梯不因图片扩展回归；图片能力状态单独显示。
- [ ] 每个声明为 `READY` 的图片读取/写回 backend 都有真实设备证据、P95 数据和权限/ROM 限制记录；其余组合明确为 `PARTIAL` 或 `NOT_TESTED`。

## 8. 源码与官方依据

本节只证明技术路径有公开先例，不代表所有 ROM 自动兼容。实现 agent 必须优先看官方平台约束，再看开源项目如何组合规避路径；第三方代码仅用于研究行为，不得在未核对许可证的情况下复制。

### 8.1 Android 官方依据

- [Android 10：后台应用不能读取剪贴板，默认 IME 或当前有焦点的应用除外](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data)。这是读取能力必须分档的根本原因。
- [Foreground service types：connected device](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)。该类型覆盖与外部设备的蓝牙、NFC、IR、USB 或网络交互，并要求 `FOREGROUND_SERVICE_CONNECTED_DEVICE` 及至少一个运行前提；本项目使用 `CHANGE_NETWORK_STATE` 满足网络前提。
- [Android 14：必须声明前台服务类型和对应权限](https://developer.android.com/about/versions/14/changes/fgs-types-required#connected-device)。实现必须覆盖缺类型和缺权限异常。
- [Android 15：BOOT_COMPLETED 启动 FGS 的限制](https://developer.android.com/about/versions/15/behavior-changes-15#boot-completed-fgs-launch-restrictions)。当前列出的禁止类型不含 `connectedDevice`，但 OEM 和未来 target SDK 仍须实测。
- [Android 通知运行时权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)。`POST_NOTIFICATIONS` 不是启动 FGS 的前提，但 FGS 仍必须提供通知；拒绝后通知栏可见性受限。
- [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY) 和 [WindowManager flags 的 AOSP 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/WindowManager.java)。`FLAG_NOT_FOCUSABLE` 控制键盘焦点，`FLAG_NOT_TOUCHABLE` 才能保证窗口不接收触摸。
- [AOSP Android 10 ClipboardService](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/services/core/java/com/android/server/clipboard/ClipboardService.java)。AOSP 对写入路径注明无焦点也允许，因此计划把公开写入作为默认并对 OEM 单独实测，而不是把特殊权限当成写回前提。

### 8.1.1 图片扩展待核对的官方依据

开始 Stage 9 前重新核对当前 Windows 和 Android SDK 文档，并把核对日期、API level 和实际设备结果写入阶段变更记录：

- [Windows standard clipboard formats](https://learn.microsoft.com/en-us/windows/win32/dataxchg/standard-clipboard-formats)：核对 PNG、`CF_DIBV5`、`CF_DIB` 的所有权、生命周期和兼容性，不假定每个应用都提供相同格式。
- [AddClipboardFormatListener](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-addclipboardformatlistener)：图片和文本共用 `WM_CLIPBOARDUPDATE` 事件，但捕获线程必须自行处理格式枚举、占用重试和有界读取。
- [Android `ClipData`](https://developer.android.com/reference/android/content/ClipData)：核对 MIME、`ClipData.Item` 和 URI grant 的行为，不把 URI 字符串当作长期可读的图片内容。
- [Android `ContentResolver`](https://developer.android.com/reference/android/content/ContentResolver)：核对流式打开、provider 异常和临时授权回收；捕获后立即物化到应用私有存储。
- [Android `ImageDecoder`](https://developer.android.com/reference/android/graphics/ImageDecoder)：核对有界解码、尺寸探测和 API 29+ 行为；低版本需明确替代实现和限制。

### 8.2 已核对的开源实现

- KDE Connect（固定到 `ddaa9d9`）：[ClipboardListener.kt](https://github.com/KDE/kdeconnect-android/blob/ddaa9d93804364f34ec8ab5ad467b019337866b6/src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardListener.kt) 使用 `READ_LOGS` 捕获 `ClipboardService` 信号；[ClipboardFloatingActivity.java](https://github.com/KDE/kdeconnect-android/blob/ddaa9d93804364f34ec8ab5ad467b019337866b6/src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardFloatingActivity.java) 通过获得焦点后读取。第一版不照搬后台透明 Activity，只把它作为“检测与读取分离”的证据。
- ClipShare（固定到 `a5a7fa3`）：[README](https://github.com/aa2013/ClipShare/blob/a5a7fa389f412dd25e09264f82fe84030b01b3f8/README.md#android-%E5%89%AA%E8%B4%B4%E6%9D%BF%E7%9B%91%E5%90%AC%E8%AF%B4%E6%98%8E) 明确区分系统日志方式和 shell/root 隐藏 API 方式，并承认部分 OriginOS 日志不可用。
- SyncClipboard Mobile（固定到 `05f3bfe`）：[clipboardProxy.ts](https://github.com/Jeric-X/syncclipboard-mobile/blob/05f3bfe750aca7041941290460dd3d61190fe048/src/utils/clipboardProxy.ts)、[ClipboardOverlayModule.kt](https://github.com/Jeric-X/syncclipboard-mobile/blob/05f3bfe750aca7041941290460dd3d61190fe048/modules/clipboard-overlay/android/src/main/java/expo/modules/clipboardoverlay/ClipboardOverlayModule.kt) 和 [ShizukuClipboardModule.kt](https://github.com/Jeric-X/syncclipboard-mobile/blob/05f3bfe750aca7041941290460dd3d61190fe048/modules/shizuku-clipboard/android/src/main/java/expo/modules/shizukuclipboard/ShizukuClipboardModule.kt) 展示了 Shizuku -> overlay -> 普通 API 的回退链和焦点 flag 切换。
- UniClipboard（固定到 `cc64c8a`）：[Android 剪贴板访问文档](https://github.com/UniClipboard/UniClipboard/blob/cc64c8acb92bbad5e72f0457a9efb1bfab7885ed/docs-site/content/docs/zh/mobile/android-access.mdx) 明确给出悬浮窗轮询、ADB 事件和 Shizuku 三档，并说明焦点、电量、ROM 和重启代价。
- ClipShare ClipboardListener（固定到 `21f25d2`）：[项目说明](https://github.com/aa2013/ClipboardListener/blob/21f25d2f831ebe340e81a2cd88872e813fe91f4d/README.md) 用于核对 Android 日志监听和隐藏 Clipboard Binder 的实现边界。

### 8.2.1 项目内 Shizuku 集成证据

- [`docs/stage-8-change-log.md`](docs/stage-8-change-log.md) 记录了内置宿主、MIUI 修补、JVM 测试，以及 2026-08-20 真机复测（授权与本机 `source_app=shizuku` 落库）。该记录优先于旧的“官方 Shizuku 已通过”描述。
- `android/app` 的 `dev.rikka.shizuku:api/provider:13.1.5` 是客户端协议和 Provider 兼容层；`PrivilegedHostService`、`PrivilegedUserServiceStarter` 和 `ClipboardUserService` 随 ClipSync APK 提供宿主及窄剪贴板服务，不应在计划中写成必须安装官方 Shizuku APK。
- `scripts/android-bootstrap.ps1` 是只读检查器，只打印内置宿主启动和 `READ_LOGS` 命令，不打印官方 Shizuku 启动命令；任何设备记录都必须区分“命令已打印”“宿主 Binder 已到达”“授权成功”和“实际复制事件成功”。

### 8.3 证据使用规则

1. 官方文档和 AOSP 源码决定平台基线；第三方项目只能证明某个组合在已测设备上可工作。
2. 开始或重新开启阶段 5 的实体机验收前重新核对上述链接和当前 `targetSdk` 的行为变化，并把核对日期写入 `docs/android-background-clipboard.md`；内置宿主后续改动也必须更新阶段变更记录。
3. 任一模式只有通过本计划的实体机矩阵和 P95 验收后才能标为 `READY`；权限存在、代码能编译或开源项目声称可用都不算验收。
4. 引用固定 commit 是为了让后续 agent 看到同一份依据；若上游改进，只能在记录差异后更新引用。

## 9. Agent 执行规则

1. 每次只实施一个阶段，先读本阶段任务、依赖和验收标准。
2. 编辑代码前先运行现有测试，并检查工作区是否有用户改动。
3. 阶段内先写失败测试或协议 fixture，再实现代码。
4. 每个阶段结束必须运行对应的 lint、单元测试和构建命令，并把结果写入变更记录。
5. 任何新增功能必须说明它服务于“捕获、传输、恢复、检索、信任/隐私”中的哪一项；否则拒绝加入。
6. 不为了赶进度静默降低安全校验、降低 Android `targetSdk`、静默调用 adb/特权服务或把剪贴板正文写入日志；内置宿主、官方 Shizuku、`READ_LOGS` 或悬浮窗授权都必须有用户可见的前提、状态和撤销路径。内置宿主的 `start.sh` 只能由用户显式执行，应用不得自行调用 `su` 或 adb。
7. 协议字段、数据库结构和配对行为一旦发布，后续只能通过版本化迁移修改。
8. 发现 Android 设备差异时，先把它归入能力档、记录 ROM/API/错误码和复现步骤，再做明确降级；多设备实测通过的能力阶梯可以成为核心保证，但单一设备技巧不能。
9. 不引入 LocalSend 的文件传输功能；本项目的价值是后台剪贴板同步和历史，不是重新做文件分享。
10. 如果某个阶段的验收失败，先修复并补测试，不进入下一阶段。
11. 图片扩展只能在 Stage 9 实施；在此之前不得把 v1 的 `kind=text` 放宽、把大图片写进文本字段、把任意 URI 当作长期正文，或以模拟器/文本 backend 结果宣称图片能力 `READY`。

## 10. 第一批实际执行命令

在新仓库根目录执行：

```powershell
New-Item -ItemType Directory -Force -Path .\windows | Out-Null
dotnet new sln -n ClipSync -o .\windows
dotnet new wpf -n ClipSync.App -f net8.0-windows -o .\windows\ClipSync.App
dotnet new xunit -n ClipSync.Tests -f net8.0 -o .\windows\ClipSync.Tests
dotnet sln .\windows\ClipSync.sln add .\windows\ClipSync.App\ClipSync.App.csproj
dotnet sln .\windows\ClipSync.sln add .\windows\ClipSync.Tests\ClipSync.Tests.csproj

dotnet --info
java -version
adb version
```

Android 工程由 Android Studio 创建，使用 Kotlin DSL、Compose、Room、OkHttp 和 Coroutines。完成阶段 0 后再开始阶段 1，不要先写 UI 画面或网络协议的临时代码。
