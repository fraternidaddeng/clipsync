# ClipSync 竞品分析与功能缺口评估

- 日期：2026-08-24
- 分支：`cursor/implement-charter-ui-1991`（PR #5，draft）
- 依据：`docs/product-scope.md`、`plan.md`、`docs/stage-gap-audit.md`、`docs/verification-without-device.md`、`docs/design/ui-gap-audit.md`、分支源码实读，以及对 KDE Connect、ClipShare、SyncClipboard、ClipCascade、UniClipboard、LocalSend、Syncthing、CopyQ、Espanso 等项目的公开资料核对（2024–2026 年状态）。
- 读法提醒：本文遵守仓库一贯的诚实纪律——「代码在」「JVM 测试绿」「实体机验证过」是三件事，下文严格区分。

> **状态更新（2026-08-24）**：本文指出的执行欠账由并行任务在本分支上推进，当前状态——
> - **CI：已解决（commit `c456080`）**。工作流重构为三作业（协议 schema/fixture 校验、Windows 构建 + 全部测试、Android 单元测试 + debug APK 组装），在 `cursor/**` / `feature/**` 分支和 PR 上运行。
> - **打包分发（阶段 7 裁剪版）：已解决（commit `eedf009`）**。`scripts/package-windows.ps1`（自包含 win-x64 便携 ZIP + SHA-256）、`scripts/package-android.ps1`（环境变量签名的 Release APK + SHA-256，密钥库不入库）、`docs/install.md` 一页安装/配对/授权文档；三条打包路径（签名 Release / Debug / 未签名校验）已在 Linux 上实跑验证。发布产物上传（GitHub Releases / CI 自动发布）仍未做。
> - **历史导出/导入：进行中，未合入**（自 `feature/stage-4` 移植）。
>
> 后两项落地后，请把本节与下文各处「进行中」标记改为「已解决（commit `<sha>`）」；在此之前，第五、六、七节的相关结论维持原判。

---

## 一、我们是什么（一句话定位）

**ClipSync 是一个只做 Windows ↔ Android、无账号无云无中转的私有直连剪贴板同步工具：复制即落库、在线即近实时互通、断线后按序号补齐不重不漏，并把 Android 后台读取这件「系统不让做的事」拆成四档能力阶梯诚实交付。**

## 二、已实现能力清单（按平台）

### 2.1 协议与双端共享（真实现，回环集成测试覆盖）

- 协议 v1 全消息集：`hello` → `challenge/auth`（HMAC 挑战响应 + 重放窗口）→ `known_vector`/`want_ranges` → `clip_announce/fetch/payload` → `ack_ranges`。
- 对等事件模型：每台设备自持 `origin_seq`，连续游标不越缺口（收到 seq=12 缺 seq=11 时游标停在 10），幂等键 `origin_device_id + origin_seq`。
- exactly-once 断线补齐：离线捕获重连补投不重复、删除以 tombstone 传播不带正文——由真实 Kestrel + 真实 TLS（证书 pin）+ 真实 WebSocket 的回环集成测试证明。
- 安全边界测试：错误密钥被节流、撤销即断连、错误证书无法建连、超大帧/超限拉取有稳定错误码、会话日志不含正文/密钥/nonce。

### 2.2 Windows（.NET 8 + WPF，真实现）

- 后台捕获（Win32 `AddClipboardFormatListener`）、SQLite 落库、去重、1 MiB 上限不静默截断。
- 历史/搜索/删除/清空、暂停、私密模式、来源进程黑名单、保留期清理——全部真实执行。
- Kestrel 直连监听端（TLS 1.3 + 指纹固定）、QR 配对、双向确认、撤销 + trust epoch、UDP 发现广播（只含设备 ID/端口/指纹）。
- 入站自动写回 + 回环抑制、认证节流 + 锁定通知、托盘诊断查看器。
- 设计宪章 UI：令牌化日/夜主题运行时切换、随包字体、自绘标题栏、托盘四态图标 + 440px 托盘浮窗、通路页真实会话状态。
- 测试：185 个核心/对端用例（跨平台跑）+ 39 个 Windows 专属应用层用例（WPF/DPAPI/Win32）。

### 2.3 Android（Kotlin + Compose，真实现，实体机未验证）

- Room 存储（序号分配、接收向量、outbox 扇出同事务）、pinned-TLS OkHttp WebSocket 同步引擎、指数退避重连。
- `connectedDevice` 前台服务、分享面板 / 快捷磁贴 / 通知复制三个无权限入口、入站先落收件箱 + `auto_apply_remote` 公开写入 + 失败回退通知。
- 前台自动捕获已接线（App 可见时复制即上行）、暂停/私密在捕获、队列、引擎每一层执行、开机恢复链（默认关）、保留清理、`POST_NOTIFICATIONS` 引导。
- **三档后台读取后端代码已落地**：Shizuku 特权事件（UserService + `IClipboard` 反射适配 + 自带特权宿主）、ADB 日志 + 悬浮窗（logcat 读取器 + AOSP/OneUI/MIUI-HyperOS/ColorOS 四族解析器 + 匿名化 fixture）、悬浮窗轮询（焦点控制器 + 不可触摸不变量）。纯逻辑（状态机、解析、数据最小化）有 JVM 测试；**真实 ROM 上能否读到内容一律未验证**。
- 设计宪章 UI：三 tab 壳、通路页能力向导 + 真实探针、随包三字族、超椭圆/动效令牌、首次运行引导、全套空状态。
- 测试：444 个 JVM 用例（Robolectric，含 Windows↔Android 全链路脚本化集成测试）。

### 2.4 状态如实声明

| 层 | 状态 |
|---|---|
| 协议、存储、认证、断线补齐 | 实现 + 回环集成测试证明 |
| Windows 捕获/历史/策略/写回 | 实现 + 测试；真实剪贴板生态（RDP/Office/密码管理器共存）未实测 |
| Android 手动/前台链路 | 实现 + JVM 测试；真机触达率未实测 |
| Android 三档后台读取 | **代码实现 + 纯逻辑测试；0 台实体机验证（矩阵全 `NOT_TESTED`）** |
| 图片/文件同步 | **Windows 端图片同步已落地**（protocol v2：CF_DIB 捕获 → PNG 编码 → 分块传输 → 缩略图历史/详情，默认关闭）；Android 端 v2 图片链路未接线；文件同步未做 |
| 打包分发（阶段 7） | **最小分发链已落地（commit `eedf009`）**：便携 ZIP + 签名 APK 打包脚本 + SHA-256 + 一页安装文档；Releases 上传/商店上架未做 |
| 睡眠/唤醒快速恢复、帧级限流（阶段 6 硬化） | 未做（靠超时 + 退避重连，功能可用恢复慢） |

---

## 三、开源竞品概览

| 项目 | 形态 | 平台 | Android 10+ 后台读取方案 | 备注 |
|---|---|---|---|---|
| **KDE Connect** | 设备联动套件，剪贴板是其中一个插件 | Linux 一等，Win/macOS 次之，Android，iOS 有限 | 仅 READ_LOGS(adb) + 隐形窗口一档；默认只能手动「发送剪贴板」磁贴/通知；2026 年还因把手动按钮藏进菜单引发用户抗议（bug 521052） | GPL；功能广（文件/通知/遥控/短信）但每项都不深；无历史/补齐语义 |
| **ClipShare（aa2013，Syzygy 同源思路的开源系）** | 剪贴板同步 + 历史 | Android/Win/Linux/macOS，iOS 未充分测试 | 仅 Shizuku 或 Root 一档 | Flutter；文本/图片/文件/短信、标签/统计/Excel 导出、局域网直连 + 公网中转 + WebDAV/S3 中转、应用密码；功能最全的中文系竞品 |
| **SyncClipboard（Jeric-X）** | **服务器中心**（内置/Docker/WebDAV/S3） | 桌面三平台实时；移动端为配套 App | 移动端「有限后台同步」，以手动触发/磁贴为主 | 无服务器就不工作；短信验证码自动上传是特色 |
| **ClipCascade** | **自托管服务器**（P2S 为主，P2P 模式基于 WebRTC 信令） | Win/macOS/Linux/Android | 仅 READ_LOGS(adb) + overlay 一档 | E2EE（AES-256-GCM，salt/hash 需各端手工一致）；文本/图片/文件；Web 控制台、多用户 |
| **UniClipboard** | 桌面 P2P + 加密中转兜底 | 桌面三平台一等；**移动端只是 HTTP companion，非 P2P** | 文档描述悬浮窗/ADB/Shizuku 三档（本仓库计划曾引用其文档） | 跨网穿透是卖点；移动端跨网要自建节点或 Tailscale |
| **LocalSend** | 文件/文本一次性发送 | 全平台 | 无后台剪贴板同步 | 邻接品类；本项目明确「文件继续用 LocalSend」 |
| **Syncthing** | 文件夹持续同步 | 全平台 | 不做剪贴板；官方 Android 应用 2024 年底停维（社区 fork 续命） | 只能靠脚本拼剪贴板方案 |
| **CopyQ** | 单机剪贴板管理器 | 桌面三平台 | 无 Android | 历史/脚本极强，无跨设备同步 |
| **Espanso** | 文本展开器 | 桌面三平台 | 不相关 | 非同步工具，仅作范围参照 |
| （闭源参照）Syzygy、微软 Win+V 云剪贴板 + SwiftKey | 闭源应用 / 账号 + 微软云 | — | — | Syzygy 是本项目的灵感参照但闭源不可审计；微软方案要账号、内容过云 |

## 四、相对开源竞品的优势

1. **Android 后台读取是「四档能力阶梯」，而不是单招赌博。** KDE Connect 和 ClipCascade 只有 READ_LOGS+悬浮窗一招（要电脑、要 adb，ROM 日志格式一变就哑），ClipShare 只有 Shizuku/Root 一招（用户必须先跑通 Shizuku）。我们是调研范围内唯一同时实现 Shizuku 事件、ADB 日志（含四族 ROM 版本化解析器）、悬浮窗轮询（无电脑兜底）、前台/手动四档并带自动降级状态机的项目；且「读」与「写回」拆成独立能力分别探测显示，不把网络在线谎报成剪贴板可用——这是所有竞品都没有的诚实度。

2. **断线补齐有协议级保证，竞品基本是「在线才同步，错过即丢」。** 我们的对等序号 + `known_vector`/`want_ranges`/`ack_ranges` + outbox 事务给出 exactly-once 补投语义，且有真实 TLS/WebSocket 回环集成测试证明「离线复制 → 重连 → 补齐不重复」。KDE Connect 剪贴板插件是即时推送无历史；ClipCascade/SyncClipboard 是推送/轮询服务器模型；ClipShare 有历史但未见连续性游标这类补齐保证的文档。对「手机在地铁里复制了三条，回家开电脑要全都在」的场景，我们是设计上最强的。

3. **真正的零服务器、零账号、零中转。** SyncClipboard 没服务器就不工作；ClipCascade 要自托管 Docker；UniClipboard 桌面间 P2P 但移动端是 HTTP companion、跨网靠中继或自建节点。我们的信任模型是设备对设备：TLS 1.3 + 证书指纹固定 + 每对独立 secret + trust epoch 撤销 + 二维码一次性令牌，配对像蓝牙一样一次完成，内容永远不经过第三方进程。攻击面和运维成本都是最小的。

4. **工程可验证性和隐私纪律是产品级的。** 668 个自动化用例（444 Android JVM + 185 跨平台对端 + 39 Windows 应用层）覆盖协议错误路径、事务不变量、回环抑制、日志卫生（正文/密钥永不入日志有专门测试）；「绿测 ≠ 兼容」的边界写成文档；能力探针不把「权限存在」当 READY。竞品普遍是「能跑就发」，issue 区大量 ROM 兼容性互相试错。对个人长期使用，这种可预期性本身是功能。

5. **策略引擎逐层真实执行，而不是 UI 摆设。** 暂停/私密模式在 Android 侧的捕获管理器、设置闸门队列、引擎出站三层分别关断（有集成测试证明恢复后补投无丢失），Windows 侧同样真实执行黑名单/暂停/私密；1 MiB 超限「仅本地保留」而非静默截断。多数竞品只有全局开关，没有来源黑名单 + 方向 + 大小 + 私密的组合策略。

6. **双端原生（WPF + Compose）+ 完整设计语言。** ClipShare 是 Flutter、ClipCascade 桌面是 Python 托盘、SyncClipboard 移动端是配套壳。我们对托盘、FGS、生命周期、通知的控制是直接的系统 API；且有成文设计宪章、令牌系统、日/夜主题、随包字体和全中文文案——开源工具里 UI 品质属于罕见档。

## 五、短板 / 缺失（诚实分类）

### A. 做了但未验证（当前最大风险）

1. **实体机验证为零。** `docs/device-validation-matrix.md` 四个 ROM 槽位全部 `NOT_TESTED`：三档后台读取在真机上能否读到内容、FGS 在各 OEM 电池策略下的存活、开机恢复、锁屏写入、扫码配对、真实路由器上的 UDP 发现、P95 延迟——全部只有代码和 JVM 测试。竞品最大的护城河恰恰是多年真实用户踩坑史（KDE Connect 的 ROM 兼容经验、ClipShare 的 issue 区）。**在第一台真机跑通之前，我们的核心卖点（后台自动同步）只是「理论领先」。**
2. Windows 真实剪贴板生态共存（密码管理器、RDP、Office 抢占）、防火墙/多网卡场景未实测。

### B. 还没做（计划内欠账）

3. **打包分发（阶段 7）——最小分发链已解决（commit `eedf009`）。** `scripts/package-windows.ps1` 产出自包含 win-x64 便携 ZIP（内含运行时、许可与安装指南，附 SHA-256），`scripts/package-android.ps1` 产出环境变量签名的 Release APK（附 SHA-256，密钥库与密码不入库），`docs/install.md` 提供一页中文安装/配对/授权/排障文档；两个脚本已在 Linux 上实跑验证（Windows 端经 `EnableWindowsTargeting`，Android 端签名/Debug/未签名三路径 + apksigner 验签）。**仍未做**：GitHub Releases 产物上传与发布 CI、商店/F-Droid 上架。
4. **图片同步（Windows 端已落地，Android 端未接线）。** 协议 v1 只有纯文本（`kind` 固定为 `const: "text"`，未预留 MIME 字段）；protocol v2（含 image_clip_v2 能力、分块传输、v2 fixtures）与 Windows 端完整链路（CF_DIB 捕获 → SQLite schema 3 媒体存储 → 会话引擎 v2 传输 → 历史/详情缩略图 → 图片同步开关，默认关）已自 `feature/stage-4` 移植合入本分支。Android 端 v2 图片收发仍未接线，跨端「截图过去」场景要等 Android 侧补齐。
5. **阶段 6 硬化残项**：Windows 睡眠/唤醒会话快速恢复（现在靠超时 + 退避，恢复慢）、WebSocket 帧级限流、历史导出/导入。*（进行中：历史导出/导入由并行任务自 `feature/stage-4` 移植，截至 2026-08-24 未合入。）*
6. **小项**：收件箱仍是 SharedPreferences 占位（正文在 Room 不丢）、入站通知洪泛策略、Windows 衬线字体/空状态等 UI 残项（见 `ui-gap-audit.md` P1–P3）。

### C. 故意不做（边界，不是欠账，但要认清代价）

7. **平台覆盖窄是主动取舍**：不做 iOS/macOS/Linux、不做 Windows↔Windows 网状。代价明确——用户设备组合一旦超出「1 台 Windows × N 台 Android」（例如添了 Mac 或 iPhone），必须换 ClipShare/ClipCascade 这类全平台工具。这是与「全能竞品」相比的永久短板，换来的是两端系统集成的深度。
8. **不做文件传输**（继续用 LocalSend）、**不做云中转/NAT 穿透**（不可达网络就是不同步，文档已声明这不是 bug）、**不做账号/遥测/AI 分类**。这些让我们在「功能清单对比」里永远输给 ClipShare，但符合产品定位。

## 六、竞品对照表

✅ 有且可用 ｜ ⚠️ 有但受限/未验证 ｜ ❌ 无 ｜ ➖ 范围外（故意不做）

| 能力 | ClipSync（本项目） | KDE Connect | ClipShare | SyncClipboard | ClipCascade | UniClipboard |
|---|---|---|---|---|---|---|
| Windows ↔ Android 文本同步 | ✅（实现，真机未验） | ✅ | ✅ | ⚠️ 需服务器 | ⚠️ 需自托管 | ⚠️ 移动端 companion |
| 无服务器 / 无账号直连 | ✅ 唯一模式 | ✅ LAN | ✅（也可选中转） | ❌ 必须服务器/网盘 | ⚠️ P2P 模式仍需信令 | ⚠️ 桌面 P2P，移动端非 P2P |
| Android 后台自动捕获档位数 | **4 档**（Shizuku/ADB 日志/悬浮窗轮询/前台）⚠️ 真机未验 | 1 档（READ_LOGS+隐形窗） | 1 档（Shizuku/Root） | ❌（手动/磁贴为主） | 1 档（READ_LOGS+overlay） | 宣称 3 档 |
| 读 / 写能力分离探测与诚实状态 | ✅ 独有 | ❌ | ❌ | ❌ | ❌ | ❌ |
| 断线补齐（exactly-once + 游标） | ✅ 集成测试证明 | ❌ 即时推送 | ⚠️ 有历史，补齐语义未见保证 | ⚠️ 服务器留档 | ⚠️ 推送式 | ⚠️ 未见保证 |
| 剪贴板历史 + 搜索 | ✅ 双端 | ❌（靠桌面 Klipper） | ✅ 强（标签/统计/导出） | ✅ | ⚠️ 基础 | ✅ 加密历史 |
| 策略引擎（暂停/私密/黑名单/大小/方向） | ✅ 逐层真实执行 | ❌ 开关级 | ⚠️ 部分 | ⚠️ 部分 | ⚠️ 部分 | ⚠️ 部分 |
| 传输安全 | TLS1.3+证书 pin+每对 secret | TLS+配对 | ⚠️ 可配密钥 | 依服务器 HTTPS | E2EE AES-256-GCM（手工对 salt） | E2EE |
| 图片同步 | ⚠️（Windows 端 v2 已实现，默认关；Android 未接线） | ❌ 明确不支持 | ✅ | ✅ | ✅ | ✅ |
| 文件传输 | ➖ 用 LocalSend | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| iOS / macOS / Linux | ➖ | ✅（深浅不一） | ✅（iOS 未充分测试） | ✅ 桌面 | ✅ | ✅ |
| 跨公网（NAT 穿透/中转） | ➖ 需 VPN/端口转发 | ❌ LAN | ✅ 中转可选 | ✅ 服务器天然跨网 | ✅ | ✅ 卖点 |
| 可下载的发布产物 | **❌ 未打包**（并行任务推进中，未合入） | ✅ 商店+F-Droid | ✅ | ✅ | ✅ | ✅ |
| 真实设备用户验证史 | **❌ 零** | ✅ 多年 | ✅ | ✅ | ✅ | ⚠️ 较新 |
| 自动化测试纪律 | ✅ 668 用例+边界文档 | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |

一句话总结对照结果：**协议正确性、能力阶梯设计、隐私边界、诚实度我们领先；真机验证、分发、图片支持、平台覆盖我们落后——前者是竞品补不动的架构差异，后者全部是我们自己可以补的执行欠账。**

## 七、建议优先级（若继续开发）

### P0 —— 没有这两件事，产品等于不存在

1. **首台实体机验证闭环**：拿一台真机（优先 AOSP/Pixel 或手头任意一台），跑通「安装 → 扫码配对 → 双向同步 → 断线补齐 → 至少一档后台读取实测 READY」，把结果（含 P95、错误码）写进 `device-validation-matrix.md`。当前所有核心卖点都压在这一步上；也只有它能告诉我们三档后端的真实成色。
2. **最小分发链**（阶段 7 裁剪版）：Windows 便携 ZIP + Android 签名 APK + 一页安装/配对/授权文档 + SHA-256 校验。不求商店上架，求「换台设备 10 分钟能装起来」。*（已解决（commit `eedf009`）：打包脚本 + 签名流程 + `docs/install.md` + SHA-256 已落地并实跑验证；发布 CI 上传仍未做。）*

### P1 —— 补齐日常使用的体验底线

3. 真机上的 FGS 存活与电池优化引导实测（尤其国产 ROM 自启动白名单路径文案）。
4. Windows 睡眠/唤醒会话快速恢复（`SessionPowerCoordinator`，stage-4 分支有可移植实现）。
5. 真实弱网/Wi-Fi 切换重连时延测量与调参（退避逻辑已有测试，真实时延未知）。
6. 历史导出/导入（换机、备份场景；stage-4 分支有雏形）。*（进行中：并行任务移植，未合入。）*
7. **图片同步（protocol v2）——契约与 Windows 端实现均已自 stage-4 移植合入**：线上契约（`protocol/v2/` schema 与 fixtures、`docs/protocol-v2.md`、ADR 0004，验证脚本已覆盖 v2）与 Windows 端完整实现（媒体栈 `DibCodec`/`ImageCodec`/`ImageChunks`/`MediaBlobStore`、SQLite schema 3 媒体存储、会话引擎 v2 分块图片传输、CF_DIB 捕获、历史/详情缩略图、图片同步开关默认关）均已在本分支落地并有测试覆盖。**剩余欠账是 Android 端 v2 图片收发接线**（stage-4 commit `28e354a` 有双端参考实现）；补齐前图片只进 Windows 本机历史（回环集成测试已证明 v2 传输链路），跨端「截图过去」尚不可用。若裁决不做 Android 侧，应在 product-scope 里写明理由（如「截图走 LocalSend」）。

### P2 —— 硬化与打磨

8. WebSocket 帧级限流、收件箱 Room 化、入站通知洪泛策略。
9. UI 残项收尾（Windows 衬线字体随包、Windows 空状态/首次运行、GroupCard 深度、超椭圆、动效令牌其余部分）。
10. 多台 Android 并发配对实测、设备邻近色手动改色。
11. 四族 ROM 矩阵补全（P0 只要求第一台；矩阵全绿是 1.0 的门，不是起步的门）。

### Optional（单列，不排期）

- **敏感内容分类器**（正则/启发式识别密码、验证码并拦截同步）：用户已明确表示对这类安全分类不在意；现有「来源黑名单 + 私密模式 + 暂停」已覆盖同类需求的手动路径。仅当未来出现真实误同步事故时再评估。

---

## 附：对「我们 vs 世界」的一段大实话

这个项目的差异化不是「功能更多」——按功能清单我们永远赢不了 ClipShare。差异化是三件事：**①「手机后台复制自动到电脑」这件竞品都做得七零八落的事，我们用四档阶梯 + 诚实状态把它做成工程品；②断线补齐有协议保证而不是碰运气；③零服务器零账号的信任模型让隐私论证只有一句话：内容除了你的两台设备谁也碰不到。** 而当前最诚实的自我评价是：这三件事在代码和测试层面都已成立，但在任何一台真实手机上都还没被证明过。下一步的全部价值都集中在「验证」和「分发」，不在新功能。
