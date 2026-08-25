# Changelog

本文件记录 ClipSync 的用户可见变更，格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。尚无正式发布版本；首个发布时将 Unreleased 内容归入 `v0.1.0`。发布叙述与验收声明见 `docs/release-notes-template.md`。

## [Unreleased]

分支：`main`（自 Stage 0–3 基线 `768fd1c` 以来；早期变更曾在 `cursor/implement-charter-ui-1991` 上推进，该分支已全部并入 main，后续变更直接落在 main）。

### 新增

- [Windows] 基础设置第一批（`docs/settings-roadmap.md` 的 Windows 半边，P0-1/P0-3/P0-5 + P1-7/P1-9/P1-15；外观手动覆盖暂缓）：
  - **历史字号**（偏好 · 显示，`ui_history_font_scale`，小/标准/大 = 0.9/1.0/1.15）：只缩放剪贴内容文字——历史预览正文、详情窗正文、托盘浮窗正文；组头、注记盒、元信息与按钮的纲领类型阶不动。历史区字号收敛为 `DynamicResource`，改动即全窗生效。
  - **预览行数**（偏好 · 显示，`ui_preview_lines`，2/4/6 行，默认 4）：历史列表每条的正文行数上限；行高显式化后截断永远落在整行边界，托盘浮窗保持两行封顶。
  - **开机自启 · 静默入托盘**（偏好 · 运行，`launch_at_startup`，默认关）：开关写入/删除当前用户 `HKCU\…\CurrentVersion\Run` 项（不用计划任务、不提权），自启带 `--minimized` 参数只落托盘；启动时重申注册表项，程序挪位后自愈。相应地，**手动启动现在直接打开主窗口**（此前一律纯托盘启动），自启路径保持安静。
  - **呼出浮窗快捷键**（偏好 · 运行，`hotkey_flyout`，默认关）：`RegisterHotKey` 全局组合键（需含 Ctrl/Alt/Win），在任意应用按下即呼出托盘浮窗；被其他程序占用时如实报「已被占用」（赭色事实行），输入框内按组合即设、Backspace 清除。
  - **清空历史**（偏好 · 数据 + 历史页，两步确认）：一次性删除本机全部条目（含图片 blob），确认文案明示「建议先导出」与本地删除语义（不远程撤回）；完成后在数据组陈述清空条数。
  - **保留条数可调**（偏好 · 存留，`retention_max_entries`，100–2000 步进 100，默认 2000）：一直存在的 2000 条清理上限从硬编码接入 settings 表与两处清理调用，超出上限时最旧条目先过期。
- [Android] 基础设置第一批（`docs/settings-roadmap.md` 的 Android 半边，P0-1/P0-4/P0-5 + P1-7/P1-8/P1-15；存储键与视图模型已先行落地，本批补齐偏好页 UI 与接线）：偏好页按路线图 §4.1 重组为 显示 · 同步 · 捕获 · 历史 · 运行 · 数据 · 设备 七组，每组超椭圆卡片 + 分组头：
  - **历史字号**（偏好 · 显示，`ui.history_font_scale`，小/标准/大 = 0.9/1.0/1.15，就地分段选择）：只缩放历史列表预览正文（在 sp 之上叠乘，系统字号继续叠加），组头、注记盒、元信息与按钮的宪章类型阶不动。
  - **预览行数**（偏好 · 显示，`ui.preview_lines`，2/4/6 行，默认 4）：历史列表每条正文的行数上限，即改即生效。
  - **跳过敏感内容**（偏好 · 捕获，`capture.skip_sensitive`，默认开）：开关落 UI，描述如实说明依赖来源应用的敏感标记、分享面板主动发送不受限。
  - **收到内容通知**（偏好 · 运行，`notify.inbox`，默认开）：应用内总开关；关闭只静默通知面，同步与历史照常。关闭时通路页以灰面事实条如实陈述后果（「收到内容通知已关闭」，指路偏好 · 运行），与系统级「通知已关闭」横幅同一形制、二者只出现其一。
  - **保留时长 / 保留条数**（偏好 · 历史，−/+ 步进行，与 Windows 步进器同形制）：保留时长 1–3650 天随「自动过期清理」联动置灰（关闭时值槽如实显示「永久保留」），保留条数 100–10000 步进 100 一直生效。
  - **清空历史**（偏好 · 数据，两步就地确认）：首点换成灰面确认条（本机删除、不可撤销、建议先导出），确认后一次删除全部条目含图片 blob，完成后在数据组陈述清空条数。
- [双端] 外观手动覆盖与多语言的共享底座（`docs/settings-roadmap.md` P1#6 / P1#16——2026-08-25 用户裁决翻案：#6 此前「主动跳过」为误、多语言从「明确不做」改列必做）：偏好键双端落地——`ui.theme` / `ui_theme`（`system`｜`day`｜`night`，默认跟随系统）与 `ui.language` / `ui_language`（`system` 或 BCP-47 标签，默认跟随系统），无法解读的存值一律回落「跟随系统」不报错；19 种语言目录（简体中文、繁體中文、English、日本語、한국어、Español、Français、Deutsch、Português (Brasil)、Русский、العربية、Italiano、Tiếng Việt、ไทย、Bahasa Indonesia、हिन्दी、Türkçe、Polski、Nederlands——各以母语名展示，永不翻译；仅阿拉伯语为 RTL）在 Android（`i18n.LanguageCatalog`）与 Windows（`Ui/LanguageCatalog.cs`）逐条对齐，双端测试钉死防漂移。主题/语言的 UI 开关、接线与全量文案提取待后续批次（RTL 布局策略见路线图 P1#16 注记）。
- [双端] 设计宪章 UI 全面落地：令牌系统、日/夜主题运行时切换、随包三字族（Noto Sans SC / Plus Jakarta Sans / JetBrains Mono）、超椭圆卡片与动效令牌、polyline 应用图标（Windows ICO + Android 自适应图标）、全中文文案。
- [Android] 完整同步栈：Room 存储（序号分配、接收向量、outbox 同事务）、pinned-TLS WebSocket 同步引擎、指数退避重连、`connectedDevice` 前台服务。
- [Android] 三个无权限入口（分享面板、快捷磁贴、通知「复制」动作）与前台自动捕获上行。
- [Android] 入站收件箱 + `auto_apply_remote` 自动写回 + 失败回退通知；回环抑制。
- [Android] 三档后台读取后端代码落地：Shizuku 特权事件（UserService + `IClipboard` 反射适配）、ADB 日志 + 悬浮窗（AOSP/OneUI/MIUI-HyperOS/ColorOS 四族版本化解析器）、悬浮窗轮询（焦点控制器 + 不可触摸不变量）。**真实 ROM 可用性未验证，矩阵全 `NOT_TESTED`。**
- [Android] 通路页能力向导与真实探针（读/写能力分离探测）、对端可达性周期重探、首次运行引导、全套空状态、`POST_NOTIFICATIONS` 引导与关闭状态行。
- [Android] 开机恢复链（默认关，含诚实恢复通知）、保留期清理、用户可调大小上限。
- [Windows] 托盘四态图标 + 440px 托盘浮窗（最近剪贴 + 暂停开关）、托盘诊断查看器 + 认证锁定通知。
- [Windows] 自绘宪章标题栏、配对 QR/确认窗口重皮肤、通路页接真实会话状态（连接数、发件队列、对端确认至）。
- [双端] 暂停/私密模式在捕获、队列、引擎逐层真实关断，恢复后补投无丢失。
- [双端] 蓝牙备援传输（ADR 0005，默认双端关闭；阶段 0 实体机 spike 已判定 GO——见「文档 / 测试」，产品路径整机验证仍待阶段 5）：IP 全部不可达时，已配对设备可经蓝牙 RFCOMM 继续同步文本（协议 v1 运行于 bt1 安全信道内，图片不过蓝牙）。Android：`BluetoothSyncConnector` RFCOMM 拨号 + `Bt1ClientHandshake`/`Bt1SyncTransport`，`SyncSupervisor` 只在所有 IP 候选失败后拨蓝牙（证书 pin 不符绝不降级）、蓝牙会话内持续探测 IP 并自动回切；偏好页开关 + bonded 设备选择器 + `BLUETOOTH_CONNECT` 授权引导，通知与通路页显示「蓝牙备援」。Windows：`ClipSync.Peer.Bluetooth` 双 TFM 程序集（可移植 bt1 监听栈 + WinRT `RfcommServiceProvider` 监听端），`BluetoothSyncHost` 单会话接受循环复用 `SyncSessionEngine` 与 `AuthThrottle`；偏好「蓝牙备援」开关 + 通路页网络段状态行（待命/同步中/适配器不可用）。双端单测经内存流覆盖握手正反例、帧层攻击负例与端到端双向同步；安装文档新增蓝牙配对指引，威胁模型新增近场攻击面条目。
- [双端] 蓝牙备援传输阶段 1——bt1 握手与帧层（纯逻辑，无平台蓝牙依赖）：`docs/protocol-bt1.md` 定稿安全信道协议（共享 `pair_secret` 的 HMAC-SHA-256 双向认证、HKDF-SHA-256 按方向派生 AES-256-GCM 会话密钥、4 字节大端长度前缀 + 计数器 nonce 帧、7 MiB 明文上限、`BT1_` 错误码）；`protocol/bt1/` 新增跨语言测试向量与消息 fixtures 并纳入 `scripts/validate-protocol.py` 校验；C#（`ClipSync.Core/Security/Bt1`）与 Kotlin（Android `sync` 包）双端实现，针对同一 fixtures 的单测含篡改/重放/乱序/截断/超限负例。尚无任何真实蓝牙 I/O；RFCOMM 传输、降级编排与 UI 均属后续阶段（见 `docs/bluetooth-fallback-plan.md`）。
- [分发] 最小分发链（阶段 7 裁剪版）：`scripts/package-windows.ps1` 产出自包含 win-x64 便携 ZIP（含运行时/许可/安装指南 + SHA-256，Linux CI 经 `EnableWindowsTargeting` 可产包）；`scripts/package-android.ps1` 产出 Release APK（签名只读 `CLIPSYNC_ANDROID_*` 环境变量，密钥库不入库，另有 Debug/未签名校验路径）；`docs/install.md` 一页中文安装/配对/通路/排障指南（并随 Windows ZIP 分发）。

- [双端] 减弱动效跟随系统（`docs/settings-roadmap.md` P1#13，无应用内开关——系统的选择是事实）：系统开启「减弱动态效果/移除动画」时，2.6 s 赭色「需要你操作」脉动改为静态 act 描边。Android 观察 `ANIMATOR_DURATION_SCALE`（改设置即时生效，无需重启），同时定格通路管道的流动点、tab 切换 Crossfade 改硬切；Windows 以 `SystemParameters.ClientAreaAnimation` 门控通路捕获段脉动光环，静态描边独自承担提醒。
- [双端] 设备色手动改（`docs/settings-roadmap.md` P1#14，`ui-gap-audit.md` P2 项收口）：通路设备行新增五色点选色器，可把某台设备的邻近色固定为 dev-1..dev-5 中任意一档；点配对顺位默认色即恢复「跟随配对顺位」（存储保持最小——只存覆盖）。颜色属设备身份：撤销/重新配对不清除；历史与浮窗的来源注记盒随生效色。Android 存 `PairingStore`（`device.accent.<id>`），Windows 存 devices 表 `accent_override` 列（schema v4→v5 纯增列迁移）。
- [Windows] 通路「网络段 · 已配对设备」新增残留设备检测与一键清理：同名同平台的旧档（同一部手机换了身份重新配对留下的幽灵）与超过 14 天未连接的设备以赭色标记（标注各自积压的待发条数），列表上方横幅给出总数与总积压并提供「一键清理」——撤销全部残留、作废其密钥并清空其发件队列，「待发」计数即时回落。
- [双端] 历史「仅本机保留」标注（ADR 0005 §5 落地，收口 `docs/bluetooth-product-path-qa.md` 记录的缺口）：仅文本会话（蓝牙备援窗口，或对端图片开关关闭的 v1 路由）内被 `local_only` 终止的本机图片，从对端游标越过那一刻起在**本端**历史打上中性灰「仅本机保留」注记盒（Windows 历史列表徽章行 / Android 图片卡「图片」徽章旁）——事实陈述不是警报，不用错误红。标记随事件持久化（Windows clips 表 `local_only_at` 列，schema v5→v6；Android Room `local_only_at` 列，v2→v3，均纯增列迁移），重启不丢；首次降级时间为准，重连不刷新。若之后 v2 会话把同一事件以可用头重新公告或整体送达（如中断重播），过时标记自动清除。文本条目永不带此标注。Windows 主窗开着时标注即时可见：会话引擎在标记真正落库/清除时发 `LocalOnlyMarksChanged`（IP 监听与蓝牙备援两条宿主链都接到主窗历史刷新），不用等下一次捕获或远端提交；Android 由 Room 失效自动推给历史流，本就即时。

### 变更

- [Android] 能力路线去品牌化：Shizuku 在 UI 中呈现为「特权直读」。
- [Windows] 三窗口全部令牌刷 `DynamicResource` 化以支持运行时换肤。
- [双端] IA 迁移（`docs/settings-roadmap.md` P1 #10–12）：连通性配置从偏好迁回通路网络段——它们改变「内容能不能到达对端」，按判据属通路。**蓝牙备援**（双端；Android 为网络段下方的备援卡片，含目标设备选择与 bonded 设备清单）、**额外监听地址**（Windows，「重启后生效」赭色说明照搬）、**本机证书指纹**（Windows，偏好「信任」卡随之取消）现住在通路页「网络段 · 连接」卡。设置键与行为不变，纯搬家；偏好页各留一个发布版本的链接行（「已移至通路 · 网络」）指路，下个版本删除。`docs/install.md` 的 Tailscale 与蓝牙备援路径说明同步更新。

### 修复

- [双端] 通路「对端写入」不再永远「未探测」（人工 QA 2026-08-25 缺陷 3）：Windows `/v1/peer/health` 新增 `clipboard_apply_text` 自报字段（`off`/`paused`/`unverified`/`applied`/`failed`——off/paused 是对端用户的姿态设置，其余是本会话最近一次真实剪贴板写入的证据，绝不「API 存在即就绪」）；Android 健康探测解析该字段并映射到对端写入段：已验证/已开启 → 就绪，对端关闭自动写入、对端已暂停、写入失败 → 降级并注明「依据：对端在 /v1/peer/health 的自报」。旧版对端或字段缺失时仍显示诚实的「未探测 · 对端未上报」而非负面猜测。IP 同步正常时顶栏不再停留「通路部分接通」。
- [双端] 超限文本（>1 MiB）拒绝不再静默（人工 QA 2026-08-25 缺陷 4）：Windows 主窗历史页显示事实条「刚复制的文本超过 1 MiB：内容保留在系统剪贴板，未截断，但不记录历史、不同步」（「知道了」可关闭，下一次成功捕获自动退场），主窗隐藏在托盘时补发气泡通知；Android 自动捕获同场景弹出 Toast「复制的文本超过 1 MiB：仅保留在本机剪贴板，不同步（未截断）」。提示只陈述尺寸事实，绝不含剪贴内容；暂停/私密/去重等预期内的拒绝保持安静。
- [Windows] 启动时不再遗留空控制台窗口：当进程被控制台方式拉起（如 `dotnet ClipSync.App.dll`，窗口标题为 dotnet.exe 路径）时，应用在 `OnStartup` 里检测并分离继承来的控制台（`GetConsoleWindow` + `FreeConsole`），保持纯托盘启动；应用运行时自身不派生任何子进程。
- [Android] 通知栏不再回落成系统默认图标（绿色机器人）：小图标去掉主题属性着色（SystemUI 跨进程解析不了主题属性时会整体回退），三个通知渠道归入统一「剪贴同步」渠道组，前台服务通知补齐宪章配色（polyline 图标 + 流动蓝 #215F8F）、低优先级与无时间戳。
- [Windows] 历史列表图片缩略图不再显示为空灰块：修复并发刷新下缩略图临时文件互相踢掉导致的静默失败（改为每次尝试独立临时名，输者复用赢者成品）；WIC 解码/编码故障（含 COMException）降级而非中断刷新；位图在 `FromEntry` 一次解码并冻结后绑定，容器回收不再重解码；确实无法出图时显示诚实的「无预览」占位而非空灰块。
- [Windows] 历史列表追加三处修复：(1) 缩略图管线自愈——缓存缩略图文件损坏（旧版本遗留、磁盘故障）时绑定即删除并从 blob 重建，重建仍失败则直接按 128px 有界解码原始 blob 兜底，「无预览」占位从此只在 blob 缺失或真不可解码时出现（新增诊断码 `thumbnail_cache_regenerated` / `thumbnail_blob_bind_fallback`），占位字形与文字由 text-4 提升为 text-3 保证可辨；(2) 来源应用无法识别时不再显示裸英文「Unknown source」——改为与设备/形态注记盒同形制的中性灰注记盒「未知来源」（accent 0 灰族令牌：`CsSurface3`/`CsLine2`/`CsText3`），元信息行只留时间，详情窗同步显示「来源：未知来源」；(3) 条目布局跨类型统一——所有条目共享固定 56px 左槽（图片=缩略图或「无预览」，文本=安静的三行文字线字形占位），右侧标题、注记盒、元信息行纵向对齐并整体居中，图片行与文本行不再左缘参差（链接/账号/验证码/密码/图片形态注记全部保留）。
- [Windows] `scripts/build-windows.ps1` 在真实 Windows 上不再因两条缩略图单测失败而 exit 1（2026-08-25 人工 QA 阻断项）：(1) 缩略图像素断言从逐通道全等改为 ±3 容差 + alpha≥250——`DecodePixelWidth` 走 WIC Fant 缩放器，其定点权重在纯色区域也可能带来每通道 1–2 的偏差，全等断言在真机上过严（网住的回归——透明/空白像素——仍远超容差）；(2) 损坏缓存自愈不再依赖能否删除旧文件：新增强制重建路径（唯一临时名 + 覆盖式 `File.Move`）绕过 `Ensure` 的 exists/length 门，旧「先删再 Ensure」在删除被暂时锁定（杀软/索引器）时会把损坏文件原样返回、缓存永不自愈；重建后仍不可解码时 `LoadForList` 返回空路径而非把不可解码的缓存路径交给详情窗绑定。
- [Windows] 位图文件加载（`BitmapFile.TryLoad`）在真实 Windows 上从未成功过，现已修复（首次 Windows CI 运行 32827123288 暴露，6 条 `ClipSync.App.Tests` 失败中的 5 条同源）：`CreateOptions` 里的 `IgnoreImageCache` 与流式加载（无 `UriSource`）组合时，WPF `BitmapImage.FinalizeCreation` 会调用 `ImagingCache.RemoveFromImageCache(null)` 抛 `ArgumentNullException`，而 `TryLoad` 把它当解码失败吞掉——于是每次调用都静默返回 null：历史列表一直靠 `BitmapDecoder` 兜底解码原始 blob 才有图（缩略图缓存路径从不被绑定），详情窗大图则直接落空。WPF 的图片缓存本就只作用于 URI 加载的位图，该旗标对流式加载纯属无效负担，予以移除（`IgnoreColorProfile` 保留）。同一批 CI 失败中的第 6 条是独立小缺陷：`HistoryDisplayOptions.StoredScaleFor` 用 `double.ToString` 生成存值，「标准」档写成 `"1"` 而非路线图键契约的 `"1.0"`，现改为字面拼写（读取侧 `double.TryParse` 两种拼写都认，旧存值透明迁移）。诊断过程：Linux 无法执行 WPF 测试体，靠一条临时的不吞异常诊断测试在 windows-latest 上取到真实异常栈后定位（已随修复移除）。
- [双端] 开启系统代理（Clash/Surge 等）时同步不再被劫持或断连：Android 端 OkHttp（同步、配对、健康探测）显式 `Proxy.NO_PROXY` 直连，Windows 端 `ClientWebSocket.Options.Proxy = null` 直连；TUN/VPN/全局模式的放行方法见 `docs/install.md` 第 5 节。
- [Windows] 合并 tray-diagnostics 后的 `DiagnosticsWindow` 构建错误。
- [Android] 自动写入通知配色与 Compose 弃用告警。
- [Android] 远端图片自动写入改为独立开关「自动写入远端图片」（`auto_apply_images`，默认关）：文本「自动写入剪贴板」不再连带把图片写进本机剪贴板，与 Windows 端及 ADR 0004（图片写入门独立于文本自动应用）对齐；暂停同步仍同时关断两者，图片照常进入历史可手动复制。
- [Windows] 同一部手机重新配对不再积累幽灵设备（2026-08-25 人工 QA 缺陷 2：设备表出现两台同名 `Xiaomi 22041216C`，未撤销的旧档 `80d29726-…` outbox 积压 42 条 pending，把通路「待发」带高）：配对确认时若列表已有同名同平台的活跃设备，视为同一部手机换了身份（如清除应用数据后）重新配对——确认窗以赭色提示「将替换旧记录」，批准后旧记录在同一事务内自动撤销（作废配对密钥、trust epoch +1）并清空其 outbox 积压，其在线会话被立即断开；设备撤销连带清空该设备发件队列为既有行为，本次补充测试锁定。
- [双端] 图片同步默认值双端对齐为**默认关**（2026-08-25 人工 QA 限制 5「双端图片同步开关不一致（Win 开 / Android 关）」）：产品默认按 ADR 0004 与设计宪章 §5.9（`settings_image_sync_hint`「默认关闭」）统一为**双端关闭、显式开启**——Android `SyncSettingsStore.imageSyncEnabled`（`sync.image_sync`）与 Windows 偏好页 `image_sync` 设置本就默认关（QA 机上 Windows 为「开」系该机历史上手动开启后的持久化状态，非出厂默认）；真正修掉的是 Windows 端两处**未接线即放行**的库层默认：`SyncSessionOptions.ImageSyncEnabled` 与 `PeerSyncHost` 的兜底闸原为 `() => true`（任何忘记接线的宿主会静默参与 image_clip_v2 收发图片，与双端「默认关」相悖），现改为 fail-closed `() => false`——未接线的宿主表现为纯 v1 文本对端（拒绝 `/v2` 升级、图片一律 `unsupported_media`/`local_only`）；测试套件在 `PeerPair.DefaultSessionOptions` 显式开启以继续覆盖 v2 图片路径，新增 Windows `ImageSyncGateTests.ImageSyncGateDefaultsOffMatchingTheAndroidDefault` 与 Android `ImageSyncDefaultAlignmentTest` 双向钉死默认值。

### 文档 / 测试

- [双端] **蓝牙备援阶段 0 spike 实测完成，判定 GO**（2026-08-25，报告 `docs/bluetooth-phase0-report.md` 含双端完整日志）：Lenovo/Realtek Windows 25H2 × Redmi Note 11T Pro（Android 13/MIUI）bt1 模式 256 KiB 档连续 3 轮全通——未打包进程 WinRT `RfcommServiceProvider` 发布 SDP 成立（G-W1，无需 MSIX）、Android 授权流/bonded 枚举/建连成立（G-A1）、bt1 端到端成立（G-C1）、建连 0.7–2.2 s（G-P1 ≤5 s）、RTT 中位约 31 ms（G-P2 ≤500）、吞吐 150–180 KiB/s（G-P3 ≥50）、稳定性 3/3（G-S1）。随之更新：`docs/bluetooth-fallback-plan.md` 阶段 0 → 已完成（含结果摘要与阶段 3/5 缺口清单）、ADR 0005 状态与限制表改用实测校准值、`docs/device-validation-matrix.md` 新增蓝牙 spike 证据行、`docs/install.md`/spike 运行手册补充小米实测配对路径（Windows 扫不到手机时从手机「可用设备」发起）。**纠正 `docs/install.md` 第 7 节错误陈述**：蓝牙窗口内复制的图片「恢复 IP 后按序补传」为误——按 ADR 0005 §4，图片事件在蓝牙会话中以 `local_only` 终止标记推进游标，**事后不会补传**（历史中标注「仅本机保留」），现与 ADR/应用内文案一致。仍无任何 READY 声明：spike 走工具路径，应用内蓝牙备援的整机验证属阶段 5。
- [双端] 蓝牙备援阶段 0 可行性 spike 材料（仅证据收集，非产品功能，无 READY 声明）：运行手册 `docs/bluetooth-phase0-spike.md`（前置条件与 OS bonding/ClipSync 配对的区别、双端逐步操作、期望输出样例、GO/REVISE 门槛、排障表）与空白报告模板 `docs/bluetooth-phase0-report-template.md`；Windows 监听端 spike 控制台工具 `scripts/spike-bt1-windows/`（+ 包装脚本 `scripts/spike-bt1-windows.ps1`）——未打包进程经 WinRT `RfcommServiceProvider` 发布冻结服务 UUID、只收一个连接、复用 `ClipSync.Core` 阶段 1 bt1 实现做真实握手，独立于 `ClipSync.sln` 且经 `EnableWindowsTargeting` 可在 Linux 编译（`TreatWarningsAsErrors` 生效）；Android 客户端 spike 为 debug 构建独有的「ClipSync BT Spike」入口（`android/app/src/debug/`，声明 `BLUETOOTH_CONNECT` 仅入 debug manifest，release APK 无蓝牙权限与 spike 代码）——枚举 bonded 设备、`createRfcommSocketToServiceRecord` 连接、bt1 握手、RTT/上下行吞吐测量，结果以 `SPIKE_RESULT:` 结构化行输出（logcat 标签 `ClipSyncSpike`）便于本地代理采集；`docs/bluetooth-fallback-plan.md` 阶段 0 小节同步链接上述材料。
- CI 工作流重构为三作业：协议 schema/fixture 校验、Windows 构建 + 全部测试、Android 单元测试 + debug APK 组装；在 `cursor/**` / `feature/**` 分支与 PR 上运行。
- 测试规模：444 Android JVM + 185 跨平台对端 + 39 Windows 应用层用例；新增 Windows↔Android 全链路脚本化集成测试与真实会话事件驱动的通路页验证。
- 新增 `docs/verification-without-device.md`（绿测 ≠ 兼容的边界）、`docs/stage-gap-audit.md`、`docs/competitive-analysis.md`、`docs/design/ui-gap-audit.md`、`docs/manual-qa-checklist.md`、`docs/release-notes-template.md`；扩充 `docs/device-validation-matrix.md` 为脚本化检查清单。

### 已知欠账（进行中）

- 发布产物上传（GitHub Releases / 发布 CI）、历史导出导入——并行任务推进中，见 `docs/competitive-analysis.md` 状态更新。
- 实体机验证：蓝牙备援阶段 0 spike 已有一对真机 GO 证据（见「文档 / 测试」）；剪贴板通路矩阵（S0–S4）与蓝牙阶段 5 产品路径验证仍为零。图片同步（protocol v2）未做。
