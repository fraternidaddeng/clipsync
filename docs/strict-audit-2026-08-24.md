# 严格审计（strict audit）— 2026-08-24

- 分支：`cursor/implement-charter-ui-1991`（审计基准 tip：`709273c`，领先 `main` 131 个提交）
- 对照基准：`plan.md` 阶段 4–5、`docs/design/DESIGN-CHARTER.md` + `tokens.md`、`docs/protocol-v1.md` / `protocol-v2.md`、`docs/stage-4-merge-gap-audit.md`
- 方法：逐文件阅读同步引擎 / 前台服务 / 能力后端 / 通知 / 导出链路；对全部 UI 资产做十六进制色值 + HSL/OKLCH 色相扫描；grep 日志与通知内容卫生；在本机运行可运行的全部自动化测试（结果见 §8）。
- 本轮随手修复（同分支提交）：见 §7。

---

## 1. 功能完成度 vs plan 阶段 4–5

### 1.1 阶段 4（Android 伴侣端、协议和手动基线）

| 计划项 | 状态 | 证据 / 备注 |
|---|---|---|
| 历史列表、搜索、设置页 | ✅ | `ui/home/HomeScreen`（搜索 + 格式过滤）、`ui/prefs/PreferencesScreen`；五页折为 一屏/通路/偏好 三位（纲领 §4.2） |
| 历史**详情**页 | ❌ **缺失** | Android 历史卡 `maxLines = 4`，无展开/详情面（长文本无法完整查看）。Windows 侧有 查看详情 `DetailWindow`，Android 无对应物。计划原文「历史列表、搜索、详情和设置页面」 |
| Room 收件箱/outbox/游标/删除标记 | ✅（一处占位） | `storage/ClipSyncEntities/Daos/Repository`：序号分配、接收向量、outbox 扇出同事务；terminal tombstone 传播。`KeyValueClipInbox` 仍是 SharedPrefs JSON 占位（正文在 Room，不丢数据，低风险） |
| OkHttp WS 客户端、认证、心跳、指数退避 | ✅ | `SyncSupervisor`（退避 + 网络回调提前重连）+ `SyncEngine`（challenge/HMAC/ping/pong）+ pinned TLS `OkHttpSyncConnector` |
| known_vector / 缺失范围 / ack_ranges | ✅ | `SyncEngine.handleKnownVector/sendWants/handleAckRanges`；向量覆盖即 ack 证据；want 上限 + RATE_LIMITED 背压 |
| 「复制到系统剪贴板」+ 通知操作 | ✅（本轮修一 bug） | 历史卡复制走共享写协调器；通知 复制 动作原先绕过抑制表（回环 bug，已修，见 §7.2） |
| `ACTION_SEND` 分享目标 | ✅ | `platform/entry/ShareReceiverActivity`（文本 + 图片路径、FileProvider） |
| Quick Settings 磁贴 | ✅ | `SendClipboardTileService` → 透明 `SendClipboardActivity` 一次性读取；磁贴不随服务状态切 active/inactive（ui-gap-audit P2 已记录） |
| `ForegroundClipboardBackend` 前台自动捕获 | ✅ | `MainActivity.onStart` 启动协调器 → `ClipboardCaptureManager`（暂停/私密 → 回环 → 大小去重 闸门次序符合计划 3.4） |
| `PublicClipboardWriter` 后台写基线 | ✅ | `AndroidPublicClipboardWriter` + 通路页「写入测试」（随机 token、测后即清、结果持久化为能力状态） |
| 后台自动同步设置页 + 能力卡片分项显示 | ✅ | 通路页四段管道（本机读取/本机服务/网络/对端写入）分项显示 + 每路线独立卡片、错误码、修复动作 |
| 入站先落库；auto_apply 先公开写；状态通知不含正文；失败回退 | ✅（图片一处偏差） | `InboxDelivery`：收件箱永远先记；公开写成功 → 无正文「已自动写入」通知；失败 → 复制通知；特权回退经 `ClipboardWriteCoordinator`。**图片**在 auto-apply 关闭/失败时不发任何通知（文本会发复制通知）——已注释为刻意，但与 5.6「仍落收件箱并发通知」字面不符（P2） |
| 空内容/超大/权限缺失/Windows 离线的明确状态 | ✅ | `EnqueueResult`/`CaptureOutcome` 稳定码；通路页离线/节流/FGS 拒绝分项陈述 |

**阶段 4 验收结论**：除「Android 详情页」外全部满足；引擎-存储-入口链路有 JVM 端到端测试背书（`WindowsAndroidSyncChainTest`）。

### 1.2 阶段 5（Android 后台自动剪贴板能力）

| 计划项 | 状态 | 证据 / 备注 |
|---|---|---|
| 5.1 读/写模式 + `BackendHealth` | ✅/部分 | `ClipboardReadMode` 四档 + `CapabilityState`/`BackendHealthState` 齐；**无独立 `ClipboardWriteMode` 枚举**——写档位隐含在协调器（公开→特权回退→手动），语义覆盖但不可单独持久化选择 |
| 5.1 授权向导 | ✅ | `CapabilityWizard` 三路线（纲领 §4.1），逐项说明用途/风险/跳过后果 |
| 5.1 用户选择：首选模式、自动降级 | ✅ | `ClipboardCapabilityStore.preferredReadMode/autoFallbackAllowed` 持久化，默认 SHIZUKU_EVENT |
| 5.1 用户选择：**轮询间隔** | ❌ 缺失 | `OverlayPollingBackend` 有 500–2000ms 夹取，但无偏好项、无 UI；生产始终用默认值 |
| 5.1 用户选择：是否后台自动上行 | ❌ 缺失 | 只有全局 暂停/私密；无独立「后台自动上行」开关 |
| 5.1 `CapabilityReport` 持久化（无正文） | ✅/部分 | 读/写最后**测试**时间 + 错误码持久化，内容卫生达标；**失败次数**未持久化 |
| 5.1 模式切换事务（epoch/回滚） | 部分 | `switchTo`：停旧 → 刷基线哈希 → 启新 ✓；无 mode epoch，失败时沿 fallback 阶梯下落而非显式回滚到已知可用模式 |
| 5.1 四项状态分开显示 | ✅ | 通路四段互不合并；「最多一段伸手」（single-beckon）有测试 |
| 5.2 FGS `connectedDevice` | ✅ | `ServiceCompat.startForeground(..., CONNECTED_DEVICE)`；FGS 拒绝 → 诚实「需要恢复」+ `START_NOT_STICKY`，错误码上通路页 |
| 5.2 **通知操作**（暂停全部/仅停捕获/立即同步/打开故障状态） | ❌ **缺失** | 常驻通知只有状态文案，无任何 action，也无 contentIntent。计划明文要求四个操作 |
| 5.2 服务持有 backend 协调器 | ❌ **见 P0** | 见下 |
| 5.2 POST_NOTIFICATIONS 与电池分开引导；权限非 FGS 前提 | ✅ | 启用时机才请求；拒绝后服务照跑、应用内诚实显示「通知已关闭」条 |
| 5.2 BOOT_COMPLETED 仅 opt-in + WorkManager 有界健康检查 | ✅ | `BootCompletedReceiver`（manifest 默认禁用）+ `BootHealthCheckWorker`（3 次观察不重启）+「需要恢复」通知；`BootRestoreTest` 覆盖 |
| 5.3 Shizuku 七类错误码 | ✅ | `ShizukuErrorCodes` 七枚齐全（NOT_INSTALLED/NOT_RUNNING/NOT_AUTHORIZED/BINDER_DEAD/USERSERVICE_DEAD/CLIPBOARD_BINDER_DEAD/API_MISMATCH） |
| 5.3 UserService 最小面 + `linkToDeath` 重绑 | ✅ | `ClipboardUserService` 仅剪贴板读/写/listener/health；`linkToDeath` 双层（Shizuku binder + clipboard binder）；重绑先刷哈希。另有自带特权宿主 `shizuku/host/**`（超出计划） |
| 5.3 一键测试读/写（随机文本即清） | ✅ | `HealthViewModel.runReadTest/runWriteTest`：`clipsync-test-<uuid8>` token，测后清除，不触碰用户剪贴板 |
| 5.4 READ_LOGS 探针每次重探；bootstrap 只显示命令 | ✅ | `AndroidRouteProbes` 每次 resume/refresh 重探；`android-bootstrap.ps1` 显示序列号/命令/撤销并需确认 |
| 5.4 logcat 内存内解析 + 150ms 防抖单飞 | ✅ | `LogcatClipboardEventReader`：`-T` 起始限流、行 parse 后即弃、150ms 防抖 + single-flight；ROM 标签覆盖 AOSP/OneUI/MIUI/HyperOS/ColorOS |
| 5.5 1×1 alpha0 overlay、永不 TOUCHABLE、≤3 次 25–50ms 重试 | ✅ | `OverlayFocusController` 全部满足；熄屏/锁屏停轮询（`canPollNow`） |
| 5.5 常驻通知显示「悬浮窗轮询已启用」 | ❌ | 常驻通知只有连接状态；且轮询不归服务管（见 P0），此项当前无处落 |
| 5.6 所有 backend 过同一策略引擎 | ✅ | 协调器 → `ClipboardCaptureManager` 单点闸门；分享/磁贴走 `SettingsGatedClipOutbox` 同规则 |
| 5.6 不实现 Root / 降 targetSdk / 输入法 / 无障碍 | ✅ | 全无 |
| 5.7 自动化 | 部分 | 见 §5 测试缺口 |

### 1.3 P0：后台读取链路没有接进前台服务（阶段 5 核心验收当前不可达）

Shizuku / adb-log / overlay 三条后台读取路线的**真实实现全部在**（含 linkToDeath、错误码、ROM 解析器、焦点控制器，测试齐全），但 `ClipboardAccessCoordinator` **只被 `MainActivity.onStart` 启动、`onStop` 停止**；`ClipboardSyncService` 完全不持有协调器（计划 5.2 明文「服务持有 OkHttp WebSocket、网络回调、**backend 协调器**」）。后果：

- App 一退到后台，读取协调器就被停掉——特权后端存在的意义（后台读）在运行时被架空；
- 阶段 5 验收第一条（Shizuku 模式下 Android 复制 → Windows P95 ≤ 1.5s，**前台服务运行、主界面不在前台**）今天不可能通过；
- 「悬浮窗轮询已启用」常驻通知项也因此无处安放。

`MainActivity.onStop` 的注释（「Android 10+ denies background reads anyway」）只对 FOREGROUND_ONLY 后端成立，对特权后端是错误依据。**修复不是 trivial**（需要：服务持有协调器 + 依据 preferredReadMode/verified 状态决定是否后台启动 + 前后台切换的所有权交接 + 熄屏策略 + 测试），本轮只记录不动刀。这是本分支相对 plan 阶段 5 的唯一 P0 级功能缺口。

---

## 2. UI 纲领符合度

### 2.1 绿色扫描（纲领 §二：色相 100–180 全禁）

- 对 `android/**/*.{kt,xml}`、`windows/**/*.{xaml,cs}` 的全部 `#RRGGBB`/`0xAARRGGBB` 做了 HSL 色相计算：**没有任何色相落在 100–180**。被否决的四枚旧绿（`#146C43`/`#72D69E`/`#00391F`/`#27844F`）与原青绿流动色（`#137a68`/`#4fbfa6`）只在设计文档的历史记录里出现，代码零残留。
- 最接近边界的是 dev-1 青灰（`#4F8288` HSL 186°、夜 `#81B5BC` 187°）——在禁区外；`tokens.md` §4 脚注明确以十六进制值为准（OKLCH 命名标号 195）。

### 2.2 硬编码颜色盘点

| 端 | 结论 |
|---|---|
| Android Compose | `ui/` 全部色值只在 `Theme.kt`，与 `tokens.md` **逐字节一致**（含半透明变体的 alpha 字节：flow-bg 0x1A=10%、act-ln 0x47=28% 等逐一核对无误）；`token-migration-checklist.md` 的 Theme.kt 部分全部落实 |
| Android XML | `colors.xml` 仅 `cs_flow`（通知强调色，正当）；**`styles.xml`/`values-night/styles.xml` 直写状态栏/导航栏色 `#E2E9F2`/`#0C1116`**（ui-gap-audit 已记 P3）；launcher 背景渐变底 stop `#D6E0EC` 与 tokens `bg-grad` 底值 `#DAE3EE` 不一致（图标资产轻微偏差，P3） |
| Windows XAML | 三个视图窗口 + 托盘浮窗**零十六进制字面量**，全部经 `CharterTokens(.Night).xaml` 资源键（迁移清单「XAML 页面里不再出现任何十六进制字面量」达成）；夜间字典逐键对应 |
| Windows C# | 仅 `DiagnosticsWindow.cs` 用 `Consolas, Cascadia Mono`（未走随包 JetBrains Mono，P3）；其余命中为 CRC32 多项式/Win32 常量误报 |
| 红色纪律 | `MainWindow.xaml` 无 `CsErr` 引用；已吊销设备 = 灰色事实；err 只在配对重配对警示与窗控关闭钮悬停出现——「红色只留给 error」达标 |

### 2.3 其余纲领项

- **间距刻度违规（P2，系统性）**：纲领 §5.7 只允许 4/8/12/16/24/32/48，明令不出现 10/14/18/20。Android 屏幕代码 grep 到 **40+ 处** `10.dp/14.dp/18.dp/20.dp`（含 7dp、11dp、13dp 等刻度外值），遍布 Onboarding/Prefs/Home/Health/Wizard/MainActivity。属于系统性偏差，建议专轮收敛，不宜零散修补。
- **字体**：Android 随包五枚（Serif SemiBold、Sans SC 400/500、Mono 400/500）✅，但 **Plus Jakarta Sans 未随 Android 包**（tokens §6 打包清单含 PJS；Windows 侧已打包 400/500/600）——拉丁字形双端不一致（P3）。Mono 永不用于中文两端遵守。
- **颗粒**：Android 运行时生成 256×256 纯 alpha 噪声（固定种子、1 texel=1px、日 3.0% 染黑 / 夜 4.2% 染白、只铺应用背景）——与 tokens §5 规格等效（规格写的是 `drawable-nodpi/grain.png` 资产，实现是等价的程序化生成）；Windows 不铺 ✅。
- **超椭圆**：Android n≈4.4 `SuperellipseShape` ✅；Windows 明确裁决不做（`ui-gap-audit.md` §三，理由充分）✅。
- **动效**：`cubic-bezier(.16,1,.3,1)` 两端逐位一致；Android 260–320ms 接令牌；Windows 除赭色脉动外其余过渡未接统一时长档（ui-gap-audit P2 已记录）。
- **状态编码**：五态填充语汇、single-beckon、rail 三处复用均落地且有测试。

---

## 3. v1/v2 协议共存

| 检查点 | 结论 |
|---|---|
| 版本协商 | Android 拨号方决定：`imageSyncEnabled()` 开 → 逐主机先试 `/v2/peer/sync` 再回落 `/v1/peer/sync`；偏好逐次重读。Windows 监听两条路由并按路径钉住会话版本（`SessionOptions with { ProtocolVersion }`），会话中不变 ✅ |
| v1 冻结 | v1 fixtures 12 valid + 37 invalid 全部通过；Android 令牌级严格扫描器（`ProtocolStrictJson`）与 Windows `ProtocolValidation` 共享 `expected_errors.json` 断言；**两端各有「v1 解析器必须拒绝全部 v2 图片帧」专测** ✅ |
| 认证绑定版本 | `PairAuthProof.compute(..., protocolVersion)` 把版本写进 transcript——v2 会话不可能用 v1 proof 重放 ✅ |
| 图片过 v1 会话 | 发送侧把图片事件降为 `local_only` terminal 标记（游标照常推进，不断链）；接收侧 v1 收到图片头直接 `UNSUPPORTED_MEDIA` 断会话（belt-and-braces）✅ |
| 分块传输 | begin/chunk/end 状态机双端镜像：乱序/越界/超限/hash 不符各有稳定错误码；半途会话中断丢弃临时件、下次重新 announce ✅ |
| **P1：Windows 侧 image_sync 开关不辖入站** | 协议 v2 §3 规定「Image bodies may be sent only when **both peers** listed `image_clip_v2`」，但 Windows 监听端只要会话在 `/v2` 路由上就无条件在 hello 里广告 `image_clip_v2`（`SyncSessionEngine` L223–225），自己的 `image_sync` 设置只闸**捕获**。后果：Windows 关着图片同步，Android 开着 → 图片照样进 Windows 历史并显示缩略图。设置文案「双端需为 v2 会话」暗示双向 opt-in，与实现不符。建议：把 `ImageSyncEnabled` 传进 `SyncSessionOptions`，关闭时不广告能力（Android 会自动回落 v1） |

---

## 4. 隐私

| 面 | 结论 |
|---|---|
| 通知 | **全部通知无正文**：收件箱通知固定标题 +「复制」动作按事件 id 应用内解析；自动写入/需要恢复/节流通知同样 content-free；`VISIBILITY_PUBLIC` 因无内容而正当。FGS 通知仅连接状态 ✅ |
| Android 日志 | `Log.*` 仅存在于 Shizuku 宿主链路（17 处），只含类名/uid/错误码，无正文、无 pair secret、无 logcat 原文 ✅ |
| Windows 日志 | `PeerLog` source-generated，模板参数只有 code/type/count/deviceId；集成测试显式断言会话日志不含正文/密钥/proof/nonce ✅；`BoundedDiagnosticsLog` 有界 |
| logcat 路径 | 只在内存中匹配已知 ROM 标签，parse 后行即弃、不落盘、不上传；fixture 匿名化内联 ✅ |
| `CapabilityReport` 持久化 | 只有模式/状态/稳定错误码/时间戳，无文本、无目标 App 名、无 Shizuku 输出 ✅ |
| 导出 | JSONL 明文**有意为之**且两端 UI 明示（「导出内容为明文，请妥善保管」）；不含密钥/证书/配对信息；导入强校验（逐行 schema + hash 重算 + 计数核对）✅。图片 blob 不随导出（见 §6 合并债务 P1，是功能缺口非隐私泄漏） |
| 密钥卫生 | `SyncEngine` 会话结束 `pairSecret.fill(0)`；Keystore/DPAPI 保管 pair secret；UserService 不接触密钥/网络 ✅ |
| 剩余风险 | 图片原始字节含 EXIF 已在设置文案里承诺告知（纲领 §5.9 的隐私文案完整保留）✅ |

---

## 5. 测试缺口（按风险排序）

1. **（伴随 P0）后台读取链路无任何运行时验证**——功能未接线，自然没有「服务在、App 退后台、复制到达 Windows」的自动化或器械测试。接线后必须补 FGS 生命周期 + 协调器交接测试。
2. **Overlay 器械测试缺失（plan 5.7 明文要求）**——`androidTest` 只有 Room 迁移 2 + DAO 3 + FGS 冒烟 1；窗口创建/释放、永不可触摸、焦点恢复、熄屏不残留等不变量只有 JVM 假件版（`OverlayLifecycleInvariantTest`），未在真 WindowManager 上验证。
3. **可跳过的 Shizuku 设备测试缺失（plan 5.7）**——授权后读取/事件/写入/Binder 重启恢复没有 on-device 套件（现只有 JVM 反射适配器与状态机测试）。
4. ~~器械测试从未真机执行~~ **已在模拟器执行，真机仍待**——6/6 用例在 API 35 模拟器（QEMU TCG 软件模拟）上全部通过（`android-instrumentation-test-report.md`）；进程/FGS 存活另在 API 29/33/35 三级实测（`emulator-survival-report.md`）。嵌套 KVM 宿主内核缺陷已留证。真机（OEM ROM）执行仍待办。
5. **Android 会话级图片集成测试缺失**——`SyncEngine` 的 chunk 状态机靠 wire 层往返 + Windows 集成测试间接覆盖（`verification-without-device.md` 已自认）。
6. ~~压力/幂等套件缺失~~ **已修复（`f5c1efb`）**——`LoopSuppressionStressTest`（1000 次混合方向零回声）、`ModeSwitchIdempotencyTest`、`AckIdempotencyTest` 均按本分支架构重写落地，随 `testDebugUnitTest` 常跑。
7. ~~跨端 E2E harness 缺失~~ **已修复（`f5c1efb`）并实测通过**——`windows/ClipSync.E2eHost` + `scripts/run-e2e-stage4.ps1` 落地；最终集成轮在本 Linux 环境实际执行，输出 **E2E-PASS**（真实 `SyncEngine` + pinned TLS WebSocket 对真实 Kestrel 宿主，双向各收敛恰好一次）。
8. **WPF 应用层测试只能在 Windows CI 执行**——本环境仅编译级检查（通过，0 警告）；59 个测试方法未在本轮执行。
9. **四 ROM 实机矩阵全空**——`device-validation-matrix.md` 全部待办；藕紫/灰粉夜值、MIUI 通知改写、Win10 chrome 等 P3 核验同样悬置。

---

## 6. 合并债务

### 6.1 分支拓扑

- 本分支领先 `main` **131 个提交 / 488 文件 / ~59.8k 行**，`main` 仍停在 Stage 0–3 基线——整个阶段 4–5（+移植的 6–8 部分）都未回主干，是最大的一笔合并债务。
- `feature/stage-4` 作为平行血统仍然存在；若继续在其上开发将再次发散（本分支已花大量工时做过一轮移植）。建议尽快裁决唯一主线。

### 6.2 stage-4 尚未移植项（沿用 `stage-4-merge-gap-audit.md` 编号，均复核仍开放）

| 项 | 优先级 | 状态（2026-08-24 最终集成复核） |
|---|---|---|
| 图片感知导出/导入（media blob 随 JSONL） | ~~P1~~ | **已裁决降级为文档化排除**：`48e0a14` 在 `docs/export-format-v1.md` 明文规定导出 v1 整体排除图片行（读写两端都拒绝非 `text` kind），缺失图片区间走正常同步补齐；image-aware `format_version: 2` 留作未来功能项，不再是移植缺口 |
| Windows Modern Standby（`PowerRegisterSuspendResumeNotification` + 睡前会话闸门） | ~~P1~~ | **已修复（`25d2788`）**：`SessionPowerMonitor` + `Win32SuspendResumeNotificationSource`，挂起闸门新会话（503）并断开活跃会话，恢复后解闸；三套测试覆盖 |
| PeerServer 认证前 per-IP 限流 | ~~P2~~ | **已修复（`25d2788`）**：`SlidingWindowRateLimiter` 接入 sync WebSocket accept + pairing confirm（429） |
| 跨端 E2E harness（`E2eHost` + 脚本） | ~~P2~~ | **已修复（`f5c1efb`）**，最终集成轮实测 **E2E-PASS**（本 Linux 环境） |
| 压力/幂等 JVM 套件 | ~~P2~~ | **已修复（`f5c1efb`）**：回环压力 / 模式切换幂等 / ack 幂等三套件随单元测试常跑 |
| 审计/安全文档（`AUDIT-FINDINGS`、stage-6 security audit 等） | P3 | **关闭（被取代）**：stage 4–9 变更日志已归档 `docs/stage-4-lineage/`；本分支自有审计集（本文、`review-checklist-results.md`、`performance-audit.md` 等）更新更准；stage-4 原文保留在 `feature/stage-4` 历史 |
| 发布打包 checksum + 回滚保留 | P3 | 保持开放：属真实发布时的 release-engineering 跟进项，非移植缺口（`stage-4-merge-gap-audit.md` 已同步裁决） |

### 6.3 文档漂移（审计文档自身欠账）

- `docs/stage-gap-audit.md` 状态表严重过期：A1（Shizuku）/A2（前台捕获）/A3（overlay）/A4（adb 日志）标注 Stub/Missing，实际真实实现均已落地接线。
- `docs/design/ui-gap-audit.md` P3「历史图片项…完全未做——当前阶段仅文本同步」已过期（图片缩略已落地）。
- ~~`stage-4-merge-gap-audit.md` §6「重复 stub 待删」~~：本审计进行期间已由 `8560b6e` 更正（顶层三枚同名文件是诚实探针适配器而非死代码），不再是漂移项。

---

## 7. 本轮修复（trivial、同分支提交）

1. **通知 ID 撞车**（`SyncNotifications`）：收件箱通知 id 区间为 `41_000 + (hash and 0x7FFF)` = `[41000, 73767]`，固定 id `RECOVERY=42_001`、`AUTH_THROTTLE=42_002` 落在区间**内**——事件 id 哈希撞上时会顶掉/误取消「需要恢复」或「节流」通知。已把两枚固定 id 迁至 `74_001/74_002`（区间外）并注释区间数学。
2. **通知「复制」动作绕过回环抑制**（`CopyInboxItemReceiver`）：原实现直接 new `AndroidPublicClipboardWriter` 写剪贴板，不经过程共享 `ClipboardWriteCoordinator` 的抑制表——App 在前台时点通知复制，前台捕获会把这条**远端**剪贴重新捕获并回传 Windows（正是抑制表要防的回环）。已改走 `SharedClipboardWrites.coordinator(...)`（顺带获得特权写回退，与 5.6 一致）。

§1.3 的 P0（服务不持有读取协调器）与 §3 的 P1（Windows 入站图片不辖于开关）**均非 trivial**，本轮只记录不实现。

---

## 8. 测试运行记录（本环境：Linux，JDK 21，.NET 8.0.424，Android SDK 35）

| 套件 | 结果 |
|---|---|
| 协议 fixture 校验（`scripts/validate-protocol.py`） | ✅ v1 12+37、v2 15+15、配对 5+7 全通过 |
| Windows 核心/对端（`dotnet test ClipSync.Tests`，net8.0，真 Kestrel+TLS+WS 回环） | ✅ **378/378 通过**（0 失败 0 跳过） |
| Windows 应用层（WPF，仅 Windows 可执行） | ✅ `dotnet build -p:EnableWindowsTargeting=true` 0 警告 0 错误（TreatWarningsAsErrors 生效）；59 个测试方法待 Windows CI 执行 |
| Android JVM（`./gradlew testDebugUnitTest`，Robolectric） | ✅ **500/500 通过**（0 失败 0 跳过；含 §7 两处修复后的复跑） |
| Android 器械测试 | ⏸ 需设备/KVM，本环境不可执行（既有报告见 `android-instrumentation-test-report.md`） |

### 8.1 最终集成复跑（2026-08-24，Linux，JDK 21，.NET 8.0.419，Android SDK 35）

E2E 压力套件与图片/性能提交全部落分支后的整轮复跑，全绿：

| 套件 | 结果 |
|---|---|
| `scripts/validate-protocol.ps1` | ✅ v1 12+37、v2 15+15、配对 5+7 全通过 |
| `scripts/build-windows.ps1`（restore + build + test；本轮起脚本在非 Windows 主机自动加 `EnableWindowsTargeting` 并只执行跨平台套件） | ✅ 解决方案 0 警告 0 错误；`ClipSync.Tests` **402/402 通过** |
| `./gradlew testDebugUnitTest`（Robolectric） | ✅ **507 用例 0 失败 0 错误**（1 跳过 = `CrossClientSyncE2eTest` 的 `clipsync.e2e.enabled` 闸门，见下一行的实际执行） |
| `scripts/run-e2e-stage4.ps1`（跨端 E2E：真实 `SyncEngine` + pinned TLS 对真实 `ClipSync.E2eHost`） | ✅ **E2E-PASS**（双向各收敛恰好一次，Windows 侧 list 确认 Android 上行恰好一条） |
| `./gradlew assembleDebug` | ✅ `app-debug.apk` 产出 |
| `./gradlew detekt ktlintCheck` | ✅ 通过（基线无新增违规） |
| Android 器械测试 | ✅ 6/6 已在 API 35 模拟器（TCG）通过，进程/FGS 存活在 API 29/33/35 实测（`android-instrumentation-test-report.md`、`emulator-survival-report.md`）；真机（OEM ROM）执行仍待办 |
