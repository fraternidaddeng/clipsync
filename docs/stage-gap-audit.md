# Stage 4–5 后端功能缺口审计（stage gap audit）

- 日期：2026-08-24
- 审计分支：`cursor/implement-charter-ui-1991`
- 对照基准：
  - `plan.md` 阶段 4（Android 伴侣端、协议和手动基线）与阶段 5（Android 后台自动剪贴板能力）
  - `origin/feature/stage-4`（该分支实际已推进到阶段 8：完整 Shizuku/特权宿主、ADB 日志、悬浮窗、开机恢复、图片同步、导出导入等）
  - 当前分支的 `android/` 与 `windows/`

审计方法：对两个分支做全量文件树 diff（stage-4 有 349 个源文件，当前分支 231 个），再逐一读取当前分支的同步引擎、前台服务、能力后端、入站交付、设置存储、系统入口和 Windows 端 App 装配代码，确认每项能力是「真实实现」「诚实探针占位（stub）」还是「完全缺失」。

> **状态更新（2026-08-25，对照 main `d573080` 核对）**：本审计发现的可执行缺口已全部收口（审计分支已经 PR #5 于 `9129ce5` 并入 main）——A1 Shizuku 三件套自 stage-4 移植（`9b361c8`/`b25c5e2`，含自带特权宿主）并完成接线（`459b038`），读协调器归前台服务持有（`36fc47e`）；A2 前台自动捕获接线（`42c6ee7`）；A3 悬浮窗与 A4 ADB 日志随同一移植系列真实落地；A7 暂停/私密逐层执行（Android `d767905`，Windows 各入口闸门 `7f5e51e`）；W4 睡眠/唤醒恢复（`09a2d6b` + Modern Standby `25d2788`）；W5 `SlidingWindowRateLimiter` 已移植用于预认证连接/配对确认限流（`25d2788`），**会话内帧级速率限流仍未做**；W6 的导出/导入（Windows `5fd7461`、Android `3c51350`、图片感知 v2 格式 `87c0016`）、详情窗（`81db525`）、图片同步双端（Windows `fed1b6f`…`2ae5513`，Android `8275ffa`）、E2eHost（`f5c1efb`）均已落地。**以上全部是代码 + JVM/回环测试级收口；真实 ROM/实体机验证见 `docs/device-validation-matrix.md`——S0–S4 槽位仍全 `NOT_TESTED`。** 表格各行已加收口注记，其余审计文字保留作历史记录。

## 结论摘要

*（以下为 2026-08-24 审计时点的结论，其中缺口现已收口，见上方状态更新与表格行内注记。）*

当前分支的**协议、存储和网络层是完整且真实的**：Android 侧 protocol v1 拨号引擎（challenge/auth、known_vector、want/announce/fetch/payload/ack、重放窗口、outbox 排空）、Room 存储（序号分配、接收向量、outbox 扇出同事务提交）、pinned-TLS WebSocket、connectedDevice 前台服务、分享面板/快捷磁贴/通知复制入口都在。Windows 侧监听端、自动写回、回环抑制、策略执行和保留清理都在。

真正的缺口集中在**阶段 5 的后台读取链路**（三个后台读取后端都是"诚实探针"，探到授权也只报 DEGRADED，没有任何真实读取代码）、**阶段 4 的前台自动捕获**（后端类真实存在但没人启动它）、**开机恢复**（整链缺失）、以及**偏好开关没有接线**（暂停/私密/保留期在 Android 侧只是 UI 状态）。`auto_apply_remote` 的入站自动写入在本次审计中已修复（见文末）。

## 功能状态表

| # | 功能 | 状态 | 严重度 | 说明 | 建议下一步 |
|---|---|---|---|---|---|
| A1 | Shizuku UserService / `IClipboard` 特权读写（SHIZUKU_EVENT） | ~~Stub（仅探针）~~ → **已收口（移植 `9b361c8`/`b25c5e2`，接线 `459b038`，读协调器归属 `36fc47e`）** | **Critical** | 审计时点：`ShizukuClipboardBackend` 只探测安装/运行/授权三态并按计划报 DEGRADED；没有 UserService、Binder 反射适配器、事件监听、`readText()`，也没有特权写回退。stage-4 分支有完整实现（含自带特权宿主 `shizuku/host/**`，摆脱官方 Shizuku 管理器依赖）和整套 JVM 测试。**2026-08-25 注：完整链路（UserService、`IClipboard` 反射适配、自带特权宿主、特权写回退）已移植落地并有 JVM 测试；真实 ROM 可用性未验证。** | 已移植完成；余项为真机矩阵 S1 档验证（`docs/device-validation-matrix.md`）。 |
| A2 | 前台自动捕获（App 可见时 `ForegroundClipboardBackend` 自动上行） | ~~**Missing（未接线）**~~ → **已收口（`42c6ee7`，读协调器归前台服务 `36fc47e`）** | **Critical** | 审计时点：`ForegroundClipboardBackend` 的 listener 实现是真实的，`ClipboardAccessCoordinator.start()` 也实现了基线哈希/回环抑制，但全工程无人调用 `start()` 接采集管线。**2026-08-25 注：捕获已接入 outbox（`42c6ee7`），后由 `36fc47e` 把读协调器归前台服务持有（plan 5.2）；全链路有 `WindowsAndroidSyncChainTest` 级 JVM 集成测试，真机触达率未实测。** | 已接线完成；余项为真机 S4 档验证。 |
| A3 | `OverlayFocusController` + 悬浮窗读写（路线 2/3 的正文读取） | ~~Stub（仅探针）/ Missing~~ → **已收口（移植 `9b361c8`/`b25c5e2`，接线 `459b038`）** | **High** | 审计时点：`OverlayPollingBackend` 只有授权探针。**2026-08-25 注：`overlay/OverlayFocusController`（焦点 flag 纪律、串行化、生命周期不变量测试）与轮询后端已移植落地；真实 ROM 可用性未验证。** | 已移植完成；余项为真机 S3 档验证。 |
| A4 | `LogcatClipboardEventReader` / ADB 日志模式 | ~~Stub（仅探针）/ Missing~~ → **已收口（移植 `9b361c8`/`b25c5e2`，接线 `459b038`）** | **High** | 审计时点：`AdbLogOverlayBackend` 只探 `READ_LOGS`+悬浮窗授权。**2026-08-25 注：logcat 读取器、按 ROM 版本化的 `ClipboardLogParsers`（AOSP/OneUI/MIUI-HyperOS/ColorOS-OriginOS）、匿名化 fixture 与数据最小化不变量测试已移植落地；真实 ROM 日志形态未验证。** | 已移植完成；余项为真机 S2 档验证。 |
| A5 | `auto_apply_remote` 入站自动写入 | ~~Stub~~ → **本次已修复** | **Critical** | 修复前：偏好项存在（默认开）且 Windows 侧已实现，但 Android 的 `InboxDelivery` 只落收件箱+发"复制"通知，写协调器从未被入站路径调用。修复后：先落收件箱，再对批次中最新一条走公开写入（与 Windows 行为一致），成功发不含正文的"已自动写入"状态通知，失败回退"复制"通知；`originEventId` 传入写入器供未来回环抑制。特权写回退仍待 A1。 | 采集管线（A2）落地时，把 `InboxDelivery.writerFactory` 换成进程共享的 `ClipboardWriteCoordinator`，使回环抑制覆盖自动写入。 |
| A6 | 开机恢复（`RECEIVE_BOOT_COMPLETED` + 恢复健康检查） | ~~Missing~~ → **本次已实现** | Medium-High | 新增偏好「开机恢复」（默认关，计划 5.2 显式开启）；开关直接启停 manifest 中默认禁用的 `BootCompletedReceiver` 组件，boot 时再核对偏好+配对。单次启动尝试，失败发不含正文的「需要恢复」通知；`BootHealthCheckWorker`（WorkManager）做有上限（3 次观察）的启动后健康检查，只观察不重启。前台服务自身的 `startForeground` 被拒时同样降级为「需要恢复」+ `START_NOT_STICKY`，错误码上报通路页。 | 实机核验 Android 15 / OEM 的 BOOT FGS 行为（计划 5.2 遗留验证项）。 |
| A7 | 暂停 / 私密模式执行 | ~~Stub（仅 UI）~~ → **已收口（Android `d767905`，Windows 入口闸门补强 `7f5e51e`）** | **High** | 审计时点：`SyncSettingsStore.syncPaused/privateMode` 只有 PreferencesViewModel 读写，入队/排空/入站交付都不查它们。**2026-08-25 注：暂停/私密现于捕获管理器、设置闸门队列、引擎出站逐层执行（enqueue、announce、auto-apply 全覆盖），恢复后补投无丢失有集成测试证明；Windows 侧也补齐了每个同步入口的闸门。** | 已完成，含 JVM 测试。 |
| A8 | 内容大小上限 / 保留期清理 | ~~Partial~~ → **本次已接线** | Medium | 保留清理：服务启动即跑一次 `ClipSyncRepository.cleanup`，之后每 6 小时一次，偏好变更（保留期/自动过期）立即再跑一次（对齐 Windows 启动+保存设置的行为）；`effectiveRetentionPolicy()` 在自动过期关闭时仍保留条数上限、只停用按龄过期。用户上限：`effectiveMaxSyncTextBytes`（不越过协议 1 MiB）现于 outbox enqueue 与 `recordLocalClip` 两处按次重读执行。 | 偏好页暂无调整保留条数/单条上限的输入控件（显示为只读行），需要时补 UI。 |
| A9 | `POST_NOTIFICATIONS` 运行时请求与状态展示 | ~~Missing~~ → **已完成（请求 + 状态行）** | Medium | Android 13+ 在「启用同步」的时刻请求（配对完成、点「启动服务」、打开「开机恢复」），拒绝不阻塞服务且不再纠缠（系统两次拒绝后自然静默）；已配对的日常打开不弹窗。`SyncNotifications` 的 `areNotificationsEnabled` 前置检查保持诚实降级。通路页的「通知已关闭」状态行已落地：`CapabilityWiring.notificationsEnabled` 每次 refresh/回到前台重探，关闭时诚实陈述后果并提供系统设置深链（`HealthViewModelTest` 覆盖）。 | 无。 |
| A10 | 入站通知策略（`InboundNotifyPolicy` 去重/静音） | Missing | Low | stage-4 有独立 notify 策略与测试；当前按事件 ID 更新式通知（不会无限堆叠），收件箱上限 50 条。可接受。 | 出现通知洪泛问题时再移植。 |
| A11 | 收件箱 Room 化 | Partial | Low | `KeyValueClipInbox` 是 SharedPreferences JSON 占位（代码注释已声明），事件正文本身在 Room 里不丢。 | 顺手迁移即可，不紧急。 |
| A12 | 双语资源（values-zh-rCN） | ~~Missing~~ → **被 P1#16 多语言超额收口** | Low | 审计时点：当前 base strings 即中文；stage-4 是双语。**2026-08-25 注：`docs/settings-roadmap.md` P1#16 落地后，Android 359 键 / Windows 225 键文案全部资源化，19 种语言逐键齐全（缺省/中立资源即 zh-Hans），双端语言选择器可用；未经真机人工 QA 确认。** | 无需动作。 |
| W1 | Windows 出站 `PeerSyncClient` | **Present-by-design（仅测试使用）** | None | 计划 §4.1 明确第一版由 Android 主动连 Windows。已验证 Windows 监听端会话内 `SyncSessionEngine.RunOutboxLoopAsync` 双向排空 outbox，Windows→Android 推送不需要出站客户端。**不是缺口。** | 无需动作。 |
| W2 | Windows 入站自动写回 + 回环抑制 | Present | — | `App.OnRemoteClipsCommitted` 按 `auto_apply_remote` 写批次最新一条，`SuppressNextWrite` 防回环。 | 无。 |
| W3 | Windows 捕获/策略/保留（阶段 1–3 基线） | Present | — | 捕获服务、暂停/私密/进程黑名单、保留清理、UDP 发现、配对/撤销齐全。 | 无。 |
| W4 | Windows 睡眠/唤醒协调（`SessionPowerCoordinator`） | ~~Missing（对比 stage-4）~~ → **已收口（`09a2d6b` + Modern Standby `25d2788`）** | Medium | 审计时点：挂起/恢复只能靠 socket 超时+Android 侧退避重连。**2026-08-25 注：`PeerSyncHost` 已接系统睡眠/唤醒与网络变化事件快速恢复，含 Modern Standby；真机挂起恢复时延未实测。** | 已完成；真机睡眠/唤醒时延待人工 QA。 |
| W5 | WebSocket 帧速率限制（`SlidingWindowRateLimiter`） | ~~Missing（对比 stage-4）~~ → **部分收口（`25d2788`）** | Low-Medium | 审计时点：认证节流 `AuthThrottle` 在，帧级限流缺失。**2026-08-25 注：`SlidingWindowRateLimiter` 已移植，用于预认证的连接接受与配对确认 per-IP 滑窗限流；会话内（认证后）帧级速率限流仍未做——现有护栏是 want_ranges 受限拉取、超大帧 PAYLOAD_TOO_LARGE 与 Android 入站帧大小上限（`479eba0`）。** | 帧级速率限流仍属阶段 6 硬化残项。 |
| W6 | 导出/导入、详情窗、图片同步（protocol v2）、E2eHost、特权宿主打包 | ~~Missing~~ → **已收口（超出本审计范围，但已全部落地）** | Out of scope | 审计时点属 stage-4 阶段 6–8 功能。**2026-08-25 注：导出/导入 v1（Windows `5fd7461`、Android `3c51350`）与图片感知 v2 格式（`87c0016`）、详情窗（`81db525`）、图片同步双端（Windows `fed1b6f`…`2ae5513`，Android `8275ffa`，默认双端关）、E2eHost + 压力套件（`f5c1efb`）、自带特权宿主（随 A1 移植系列）均已在 main 落地；真机图片互拷与导出往返未人工验证。** | 代码侧已完成；真机验收见 manual-qa-checklist。 |

## 必须恢复的 Top 5（Shizuku UserService 由专责 agent 处理，不计入）

*（2026-08-25 注：下列四项已全部收口——A2 `42c6ee7`、A7 `d767905`、A3/A4 随 `9b361c8`/`b25c5e2`/`459b038` 移植系列落地；原文保留作历史记录。）*

1. **A2 前台自动捕获接线** — 后端和协调器都是现成的，缺一段生命周期接线；这是阶段 4 验收项，也是当前"手机复制→电脑"唯一断掉的自动路径。
2. **A7 暂停/私密模式执行** — 用户可见的安全开关目前是假的，属于诚实性问题；改动小（enqueue/drain 处加闸门）。
3. **A3 OverlayFocusController + 悬浮窗轮询** — 路线 2/3 的正文读取前提，阶段 5 的无电脑兜底；stage-4 有带完整测试的实现可移植。
4. **A4 ADB 日志模式（logcat 读取器 + ROM 解析器 + fixtures）** — 阶段 5 三档能力之二，stage-4 有含数据最小化测试的完整实现。

（A5 `auto_apply_remote` 原本是第 1 位，审计当轮已直接修复；A6 开机恢复、A8 保留清理/大小上限、A9 `POST_NOTIFICATIONS` 运行时请求已在后续提交中落地，见状态表。）

## 本次审计随手修复的内容

- `InboxDelivery`：远端剪贴事件落收件箱后，若 `auto_apply_remote` 开启则用公开写入器（`AndroidPublicClipboardWriter`）写系统剪贴板；成功发不含正文的"已自动写入剪贴板"状态通知（复用同一通知 ID，替换旧"复制"通知），失败回退复制通知。`writerFactory` 留作可替换缝，待 A1/A2 落地后换成进程共享的 `ClipboardWriteCoordinator`。
- `ClipboardSyncService`：每批入站提交时重读偏好（切换立即生效），只对批次最新一条自动写入（与 Windows 端行为一致），写入操作跳到主线程执行。
- 新增 Robolectric 测试 `InboxDeliveryTest`：开/关偏好、写入失败回退、`originEventId` 传递（回环抑制前提）。

## 移植提示

stage-4 分支与当前分支的包结构已分叉（`storage/ClipDatabase` vs `storage/ClipSyncDatabase`、`sync/SyncController` vs `sync/SyncSupervisor` 等），`git checkout origin/feature/stage-4 -- <path>` 无法直接落地；恢复上表各项时需按当前分支的仓储接口（`SyncRepository`/`ClipSyncRepository`）与能力模型（`BackgroundClipboardBackend`/`CapabilityReport`）做移植，测试与 fixture 通常可以少改直接带过来。
