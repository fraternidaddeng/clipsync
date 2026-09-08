# 人工 QA 记录 — 2026-08-25

- 操作者：DENG 本机会话（Cursor 代操作，真机 + 真 Windows）
- 日期：2026-08-25
- 分支：`main`（`git pull` 后）
- 构建 commit：`41aa1d263446dd99aaebb06d16d296afb404315b`
- 设备：Windows `DENG`（WLAN `192.168.2.135`，SSID `OVL-5G`）+ 红米 Note 11T Pro `22041216C`（`192.168.2.250`，同 SSID）
- 构建：Windows Debug `net8.0-windows10.0.19041.0`；Android `app-debug.apk`（`adb install -r`）
- **结论：本轮不能出 RC。** 第 0 节 Windows 脚本未绿、CI 未核实；第 2 节配对仪式/撤销未重做；第 3 节压力/延迟/切网未跑满；第 6–9 节多项未测。

截图落在工作区 `tmp-qa-*.png`，未入库。历史截图含既有剪贴条目，本文不摘录用户正文。

## 0. 环境与构建

| 项 | 结果 | 记录 |
| --- | --- | --- |
| 两端同一 commit 构建 | 部分通过 | Android `scripts/build-android.ps1` BUILD SUCCESSFUL。Windows `scripts/build-windows.ps1 -Configuration Debug` **编译成功但脚本 exit 1**：`ClipSync.App.Tests` 失败 `ImageThumbnailTests.EnsureThumbnailKeepsOpaquePixels`、`LoadForListRegeneratesACorruptCachedThumbnail`（`Assert.NotNull`）。`ClipSync.Tests` 471 通过。 |
| CI 三作业全绿 | 未核实 | 本环境无 `gh`；GitHub Actions API 404（私仓）。 |
| 同一 Wi-Fi、无 AP 隔离 | 通过 | 双端 `OVL-5G` /23。两端均开 Clash/VPN（手机状态栏 VPN；Windows Clash Verge TUN on），**IP 同步仍接通**。次要 Wi-Fi/热点切网 **未测**。 |
| 防火墙 47654 放行提示 | 跳过 / 环境已有 | 本轮启动无新提示。进程监听 `192.168.2.135:47654`（另绑 WSL/127.0.0.1）。未找到名为 ClipSync 的防火墙规则；连通性以手机已连为准。 |

## 1. 安装与首次运行

| 项 | 结果 | 记录 |
| --- | --- | --- |
| Windows 全新首次启动 | 跳过 | 沿用 `%LOCALAPPDATA%\ClipSync`，未清库。本轮冷启动为托盘进程，随后打开主窗：自绘标题栏「剪剪相传」、历史/通路/偏好、暂停/私密开关可见，无崩溃。字体未用量测证明「绝不回退」。 |
| 日/夜主题三窗 | 未测 | 未切系统主题。 |
| 标题栏拖动 / 最小化 / 关到托盘 | 部分通过 | 「关闭」后进程仍在（pid 22436）、主窗 handle=0、**47654 仍 Listen 且对手机 Established**。拖动/最小化未专门点。 |
| Android 全新首次引导 | 跳过 | `firstrun.onboarding_seen=true`，`adb install -r` 保留数据。 |
| `POST_NOTIFICATIONS` 拒绝路径 | 未测 | 已有通知渠道。 |
| 三 tab + 空状态 | 部分通过 | 历史 / 通路 / 偏好均可进入。非空库，空状态文案未验。 |

## 2. 配对

既有配对，**未做撤销、未重扫 QR**（避免拆掉日常配对）。

| 项 | 结果 | 记录 |
| --- | --- | --- |
| Windows QR 30 cm 可扫 | 未测 | 未打开配对窗；无实体举机。 |
| 指纹逐组核对 + 双向确认 | 未测 | 沿用 2026-08-25 已有配对。 |
| 设备列表名称 | 通过（带问题） | Android 偏好/通路显示对端 **DENG**。Windows 通路列出 **两台** 均名 `Xiaomi 22041216C`：现行 `06693d21-…`（今日 last-seen），旧档 `80d29726-…`（约 8/21，未撤销）。 |
| 一次性令牌不可复用 | 未测 | |
| Windows 撤销立即断开 | 未测 | |
| Android 反向解除 | 未测 | |
| UDP 发现抓包 | 未测 | |

**发现：** 旧配对未撤销时，Windows outbox 对 `80d29726-…` 积压 **42 条 pending**，通路「待发 41」来自这台幽灵设备，**不是**现行手机。现行 peer cursor 已到 Windows origin seq 203。

## 3. 双向同步

合成标记（仅此正文）：`QA-41aa1d2-w2a-1` / `QA-41aa1d2-a2w-1`。

| 项 | 结果 | 记录 |
| --- | --- | --- |
| Win 复制 → 手机历史；手机 → Win 历史+来源 | 通过 | Win→Android：本地 seq 203，手机库与「历史」顶栏可见，来源徽标 DENG。Android→Win：分享面板 `ShareReceiverActivity`，Windows 入库 origin=`06693d21-…` seq 6，`source_app=android.share_sheet`，历史可见。 |
| 手机入站默认自动写剪贴板；关 `auto_apply_remote` 只进历史 | 部分通过 | 手机「自动写入剪贴板」开着；`dumpsys clipboard` **未**读到 w2a 标记（可能 dumpsys 受限或未写系统剪贴板）。Windows `auto_apply_remote=True`，分享到达后 **系统剪贴板即为 a2w 标记**。关闭自动应用 **未测**。 |
| 回环抑制 | 通过（本样本） | a2w 写回 Windows 剪贴板后，**没有**新增 Windows origin 行；最新 Windows seq 仍停在此前本地复制。 |
| 相同文本二次复制仍上行 | 通过 | `QA-41aa1d2-w2a-1` 再次 Set-Clipboard 后新增 seq 206，库内该正文 2 条。 |
| 断线补齐 3 条 × 两向 | 未测 | |
| 100 次循环互拷 | 未测 | |
| 同 Wi-Fi P95 ≤ 2 s（n≥50） | 未测 | 单次 w2a 约数秒内入库+列表可见，不足样本。 |
| >1 MiB 本机保留、明确提示、不静默截断 | 部分通过 | `QA-OVERSIZE-` + 1 MiB 写入系统剪贴板后，库内 **0 条**（拒绝、未截断入库）。主窗 **无**「不同步」横幅/对话框。 |
| Wi-Fi 切换 / 弱网 / 睡眠唤醒重连 | 未测 | |

**发现：** 通路「部分接通」是因为 Android「对端写入 = 未探测」，不是 IP 断线。网络段文案为「已连接 · 与 DENG 保持连接」。Windows 通路：监听 :47654 · 已连 1 台；蓝牙备援未启用。手机「图片同步」关、电脑开，图同步本轮未作为双向验收。

## 4. 暂停与私密

| 项 | 结果 | 记录 |
| --- | --- | --- |
| Windows 暂停捕获 | 通过 | 标题栏开关真实点击后 `settings.is_paused=True`；`QA-41aa1d2-pause-2` **未入库**。恢复后 `is_paused=False`。UIA `TogglePattern` 不触发 `OnSettingToggled`，只拨绑定不够，须鼠标点击。 |
| Windows 私密 / 进程黑名单 | 未测 | 黑名单默认 `1password, bitwarden, keepass, keepassxc`。 |
| Android 暂停/私密/磁贴/通知复制 | 未测 | 偏好页开关可见，均关。 |
| 一端暂停不影响另一端收其余设备 | 未测 | 当前只有一对有效设备。 |
| 托盘/通知图标随暂停 | 未测四态 | 关到托盘后未再点开浮窗核对图标。 |

## 5. Windows 托盘

| 项 | 结果 | 记录 |
| --- | --- | --- |
| 四态图标 | 未测 | 溢出区可见「剪剪相传 · 监听中」。 |
| 440px 浮窗 | 未稳定取证 | 左键约定为浮窗且 3 s 自动隐藏；本轮截图未抓住。菜单「打开剪剪相传」可开主窗。 |
| 菜单：主窗 / 诊断 / 退出 | 部分通过 | 右键菜单三项存在。诊断查看器未打开。**未点退出**（会断同步）。关闭主窗 ≠ 退出。 |
| 诊断日志无正文/密钥 | 未测 | 文件 sink 仅当 `CLIPSYNC_DIAGNOSTICS_PATH` 有值；查看器未开。 |

## 6. 通知

| 项 | 结果 | 记录 |
| --- | --- | --- |
| Windows 认证锁定通知 | 未测 | |
| Windows 捕获降级横幅 | 未测 | |
| Android `connectedDevice` 常驻 | 部分通过 | `dumpsys notification`：`clipsync.sync`（名称「同步状态」）`mFgServiceShown=true`；另有 `clipsync.inbox`（「收到的文…」），组 `clipsync`。未读通知正文、未测锁屏/DND/「复制」动作。 |
| 自动写入 / 开机恢复通知 | 未测 | 开机恢复关。 |
| 渠道可单独关闭 | 未测 | 渠道已分组。 |

## 7. 历史与数据管理

| 项 | 结果 | 记录 |
| --- | --- | --- |
| 搜索 / 单删 / 清空 / tombstone | 未测 | 双端均有搜索框与过滤丸。未删用户数据。 |
| 保留期限 | 未测 | Windows `retention_days=30`。 |
| 关自动写入后收件箱手取 | 未测 | |

**观察（非清单项）：** 合成标记 `QA-41aa1d2-*` 在手机历史被标成「密码」丸。Windows 历史有图片缩略图、链接灰丸「链接」、统一行高，与 `41aa1d2` 描述一致。

## 8. 导出

功能已在 Windows 偏好页（`ExportHistoryCommand`），**不是**清单头注的 N/A。本轮 **未执行** 导出/导入往返。

## 9. 发布收尾

| 项 | 结果 | 记录 |
| --- | --- | --- |
| 版本号 / CHANGELOG / 发布说明 | 未完成 | `CHANGELOG.md` 仍为 `[Unreleased]`，分支名仍写 `cursor/implement-charter-ui-1991`。 |
| SHA-256 发布产物 | 未做 | 本轮是 Debug，不是 package 脚本产物。 |
| 设备矩阵槽位 | 未改 | 按清单：未测不得改绿。 |
| 已知限制写入发布说明 | 未做 | 见下方。 |

## 通过标准对照

发布阻断项（配对仪式、撤销、日志卫生、断线补齐/去重）**本轮未完整证明**。已证明：现行配对上的双向文本同步、回环抑制（单样本）、同文二次复制、暂停捕获、关到托盘不停监听。

## 本轮已知限制 / 缺陷

1. `scripts/build-windows.ps1` 因两条缩略图单测失败非 0 退出（`41aa1d2` 引入）。
2. Windows 设备表残留未撤销的旧 Android 配对，outbox 对其 pending 42，通路「待发」被带高。
3. Android「对端写入」一直「未探测」→ 顶栏「通路部分接通」。
4. 超限文本拒绝入库，但无用户可见提示。
5. 双端图片同步开关不一致（Win 开 / Android 关）。
6. CI 状态未知。

### 修复附注（2026-08-25，本记录定稿之后）

- **缺陷 3 已修复（main `a42183f`）**：Windows `/v1/peer/health` 现自报 `clipboard_apply_text`（off/paused/unverified/applied/failed，姿态 + 本会话真实写入证据）；Android 通路「对端写入」据此显示已验证/已开启（就绪）或对端关闭/已暂停/写入失败（降级，注明依据）。对端未上报时仍如实显示「未探测 · 对端未上报」，不做负面猜测。
- **缺陷 4 已修复（main `0b8d916`）**：超限拒绝现有用户可见提示——Windows 历史页事实条（「知道了」可关，下一次成功捕获自动退场）+ 主窗隐藏时的托盘气泡；Android 自动捕获路径 Toast。均只说尺寸事实（本机保留、未截断、不同步），不含剪贴内容。
- **缺陷 5 已修复（main `7466f52`）**：经核对为环境差异而非出厂默认分歧（两端默认均关；QA 机 Windows 的「开」是持久化的手动设置），另修掉了 Windows 库层两处未接线即放行的 fail-open 默认——详见下方「更新：限制 5」小节。
- **缺陷 1 已修复（main `9519716`）**、**缺陷 2 已修复（main `6d38e1b`）**：前者待真 Windows 主机重跑 `scripts/build-windows.ps1` exit 0 方可闭环（见下方更新）；后者为重配对时自动替换同名同平台旧档（同一事务内撤销旧记录、作废密钥、清空其 outbox 积压）+ 通路残留设备赭色标记与一键清理。缺陷 6（CI 核实）仍开放，见下方更新。

## 未做（若要出 RC 还需）

- 清库或新机走第 1 节首次运行；相机 30 cm 扫 QR；指纹核对；旧票复用失败；双向撤销再配。
- 断线补齐、100 次互拷、n≥50 延迟、切网/睡眠。
- 私密模式、密码器黑名单、Android 暂停全入口、托盘四态+浮窗+诊断卫生。
- 通知锁屏/DND/复制动作、历史删除 tombstone、导出往返。
- 绿 CI + Windows 缩略图单测 + Release 打包校验。

## 跟进（2026-08-25，QA 后文档收尾）

针对第 9 节与「本轮已知限制」的**文档侧**跟进已在 main 上完成：

- `CHANGELOG.md`：Unreleased 的分支引用由 `cursor/implement-charter-ui-1991` 改为 `main`（该分支已全部并入 main，后续变更直接落在 main），闭掉第 9 节「分支名仍写 cursor/implement-charter-ui-1991」一项。版本号与发布说明仍未启动，`[Unreleased]` 保持不变。
- `docs/device-validation-matrix.md`：补记本轮会话为「非矩阵执行」交叉引用（执行记录新增小节）；按「未测不得改绿」，D3 及所有槽位维持 `NOT_TESTED`。
- `docs/settings-roadmap.md`：新增状态行——提案定稿、P0/P1 全部未动工（对照本轮 QA 时点的 main 核实存储键均不存在）。

**代码侧**缺陷不在本次跟进范围，当时仍按「本轮已知限制」清单开放；此后限制 1–5 已陆续在 main 修复——`9519716`（限制 1，缩略图单测）、`6d38e1b`（限制 2，幽灵设备）、`a42183f`（限制 3，对端写入未探测）、`0b8d916`（限制 4，超限无提示）、`7466f52`（限制 5，图同步默认值），逐条说明见上方「修复附注」。仅限制 6（CI 状态）仍开放，最新进展见下方更新。

### 更新（2026-08-25）：缩略图单测阻断已修复；CI 工作流存在但零运行

- **限制 1 — Windows 缩略图单测失败：已在 main 提交 `9519716` 修复，待真 Windows 主机重跑 `scripts/build-windows.ps1` 确认后方可闭环。** 两条失败单测系两个独立成因，分别处理：`EnsureThumbnailKeepsOpaquePixels` 一类的逐通道像素全等断言改为共享的 `AssertCenterPixelIsSolid`（alpha ≥ 250、每通道相对编码色 ±3），容忍 WIC Fant 缩放器的定点误差；`LoadForListRegeneratesACorruptCachedThumbnail` 一类的损坏缓存自愈不再依赖静默 TryDelete，改为经唯一临时文件 + 覆盖式 `File.Move` 强制重写缓存，无法产出可解码缓存时 `LoadForList` 返回 null 路径而非坏路径。该提交已在 Linux 验证：解决方案编译干净（`EnableWindowsTargeting`，0 警告）、跨平台套件 471/471 通过；WPF 测试体仍需 Windows 执行，**在真 Windows 主机上 `scripts/build-windows.ps1` exit 0 之前不算验证通过**。
- **限制 6 — CI 状态：工作流存在，但仓库从未有过任何 Actions 运行。** 仓库有且仅有一个工作流 `.github/workflows/ci.yml`（名称 CI，API 状态 active），含三作业：`validate-protocol`（ubuntu-latest）、`build-windows`（windows-latest，执行 `scripts/build-windows.ps1` 含 App.Tests）、`build-android`（ubuntu-latest）。截至本更新，Actions API 运行总数为 **0**——包括 `9519716` 在内的近期 main 推送均未触发任何运行；仓库级 Actions 权限查询对当前凭证返回 403，无法确认是否在仓库/组织设置中被禁用。因此第 0 节「CI 三作业全绿」仍不可核实，`9519716` 的修复也尚无 CI 验证，目前只能依靠 Windows 主机手动重跑兜底。

### 更新（2026-08-25）：限制 1 与限制 6 闭环——CI 已启用，首次 Windows 运行暴露真因并已修复，三作业全绿

- **限制 6 — CI 已启用并有运行。** Actions 启用后的首次手动运行 32827123288（commit `c71626e`）：协议、Android 两作业绿，`build-windows` 失败——`ClipSync.App.Tests` 首次在真 Windows 执行，6 失败 / 145 通过。
- **限制 1 的真因与 `9519716` 的误诊。** 6 条失败里 5 条同源，且与 QA 机上当时的失败同一机制：`BitmapFile.TryLoad` 的 `CreateOptions` 含 `IgnoreImageCache`，流式加载（无 `UriSource`）下 WPF `BitmapImage.FinalizeCreation` 调 `ImagingCache.RemoveFromImageCache(null)` 抛 `ArgumentNullException`，被 `TryLoad` 当解码失败吞掉——**每次调用在真 Windows 上都静默返回 null**（列表出图一直靠 `BitmapDecoder` 解 blob 兜底；缩略图缓存从不被绑定）。`9519716` 的 Fant 容差修的是这些测试从未到达的断言，其「返回 null 路径」改动反而把隐藏的缓存解码失败暴露成 4 条新断言失败。真因无法在 Linux 定位（WPF 测试体不可执行），系临时提交 `f6b6dde` 的不吞异常诊断测试在 windows-latest 取到异常栈后确认。第 6 条失败独立：`HistoryDisplayOptions.StoredScaleFor` 把「标准」档存成 `"1"` 而非键契约的 `"1.0"`（`f6b6dde` 修复）。
- **修复与验证。** `e9601cc` 移除该旗标（`IgnoreColorProfile` 保留）并撤下诊断测试；push 触发的 CI 运行 32830318703 **三作业全绿**：`ClipSync.App.Tests` 172/172、`ClipSync.Tests` 481/481、Android 与协议作业通过。限制 1 与限制 6 至此闭环；第 0 节「CI 三作业全绿」自本次运行起可核实。

### 更新（2026-08-25）：限制 5「双端图片同步开关不一致」已按「双端默认关」对齐并钉死

- **产品默认取「关」而非「开」**：ADR 0004（「图片自动同步默认关闭」）与设计宪章 §5.9（`settings_image_sync_hint` =「默认关闭。仅 PNG/JPEG。原始字节含 EXIF。…」）都把图片同步定为显式开启的隐私承诺，故对齐方向是**双端默认关**，不是双端默认开。
- **出厂默认核实**：Android `SyncSettingsStore.imageSyncEnabled`（`sync.image_sync`）默认 `false`；Windows 偏好页 `image_sync` 设置读取为 fail-closed（未存值/坏值一律解析为关），`MainViewModel.imageSyncEnabled` 字段默认亦为关。**QA 机上 Windows 显示「开」是该机此前手动开启后的持久化状态**（该机历史里已有图片缩略图即为旁证），不是出厂默认——两端出厂默认本就一致为关。
- **真正修复的缺口（Windows 库层 fail-open 默认）**：`SyncSessionOptions.ImageSyncEnabled` 与 `PeerSyncHost` 的未接线兜底闸原为 `() => true`——生产 App 有接线所以未暴露，但任何忘记接线的宿主（如新增的承载进程）会静默参与 image_clip_v2 收发图片，与「默认关」相悖。现双双改为 `() => false`（未接线宿主表现为纯 v1 文本对端）；测试套件在 `PeerPair.DefaultSessionOptions` 显式开启以继续覆盖 v2 图片路径。
- **测试钉死**：Windows 新增 `ImageSyncGateTests.ImageSyncGateDefaultsOffMatchingTheAndroidDefault`（默认闸关、v2 路由下 `ImageClipEnabled` 仍为假）；Android 新增 `ImageSyncDefaultAlignmentTest`（`imageSyncEnabled` 默认关且可往返、`auto_apply_images` 独立于文本闸默认关、坏持久化值回落为关）。图片同步的**双向真机验收**仍未做（见第 3 节），本更新只闭「默认值不一致」一项。

### 更新（2026-08-28）：图片同步产品默认值经产品裁决改为「双端默认开」（限制 5 的对齐方向反转，对齐本身仍钉死）

- **产品裁决**：「图片同步这种功能应该默认打开，这是产品的完整体验，而不是蓝牙那种备选方案」。ADR 0004 据此修订（见其「修订记录」）：Android `sync.image_sync` 与 Windows `image_sync` 的产品默认由关改开；缺失/坏持久化值按「开」解析（与 `auto_apply_remote` 同规则），用户显式关闭的持久化「False」仍被尊重。上一节钉死的「双端默认一致」不变，只是方向从「双端关」变为「双端开」。
- **安全语义不动**：上一节修掉的 Windows 库层 fail-open 缺口**不回退**——`SyncSessionOptions.ImageSyncEnabled`、`PeerSyncHost` 兜底闸与 Android `SyncSupervisor` 构造缺省仍为 `false`（未接线宿主没有征询过用户设置，必须继续表现为纯 v1 文本对端）；MIME 魔数/尺寸/哈希校验、v1 会话拒图照旧。`auto_apply_images`（远端图片自动写入剪贴板）维持默认关（隐私）。
- **测试随裁决更新**：Android `ImageSyncDefaultAlignmentTest` 改钉「默认开、坏值回落为开、显式 False 仍关、`auto_apply_images` 独立默认关」；Windows 上一节的闸测试改名 `UnwiredImageSyncGateStillFailsClosedEvenThoughTheProductDefaultIsOn`（库层闸继续 fail-closed），`MainViewModelBasicSettingsTests` 新增「默认开 + 显式退出跨重启存活」两例；Windows 偏好页 19 语 `Prefs_Sync_ImageSync_Desc` 由「默认关闭」改「默认开启」。

### 更新（2026-08-28，同日随后）：`auto_apply_images`（远端图片自动写入剪贴板）经产品裁决也改为「双端默认开」

- **产品裁决**：「无所谓啊，本来截图就是我自己截的，默认开开」——与图片同步同一「产品完整体验」理由。ADR 0004 再增补修订记录：Android `sync.auto_apply_images` 与 Windows `auto_apply_images` 的产品默认由关改开；缺失/坏持久化值按「开」解析（与 `image_sync` / `auto_apply_remote` 同规则），用户显式关闭的持久化「False」仍被尊重。上两节「双端默认一致」的钉死不变。
- **拦截与安全语义不动**：暂停同步仍同时关断文本与图片的自动写入（Android `InboxDelivery.autoApplyImagesAllowed`、Windows `RemoteApplyDecision`），Windows 私密模式照旧一并停下自动写入；图片写入门与文本自动应用继续互相独立；回声抑制与 MIME 魔数/尺寸/哈希校验、未接线宿主库层 fail-closed 闸全部原样。
- **测试随裁决更新**：Android `ImageSyncDefaultAlignmentTest` 改钉「`auto_apply_images` 默认开、坏值回落为开、显式 False 仍关、与文本闸互不连坐」，`InboxDeliveryTest` 闸例改钉「默认即放行、显式退出关闸、暂停仍双杀」；Windows `MainViewModelBasicSettingsTests` 新增「默认开（与 Android 对齐）+ 显式退出跨重启存活」两例。偏好页文案（Windows `Prefs_Sync_AutoApplyImages_Desc` / Android `prefs_auto_apply_images_desc`，19 语）本就只描述行为、未声称默认值，无需改动。

## 2026-09-04 真机验证（第二批）

- 环境：Windows 11（`DENG`，WLAN `192.168.2.135`；另有 WSL 虚拟网卡 `172.17.0.1` 与 Clash Meta TUN 网卡）+ Xiaomi Redmi Note 11T Pro `22041216C`（Android 13），同一 /23 网段；**Windows 防火墙三个配置文件均关闭，故防火墙拦截 / 一键放行 UAC 流程未覆盖**。
- 对象：`CHANGELOG.md` `[Unreleased]` 第二批（信标、地址排序、超时 / 中止区分、双端状态事实行、路线回升、取消按钮、本批集成接线）。整个会话诊断日志无任何 `unhandled_*` 码。

### 通过

| 清单条目（`docs/manual-qa-checklist.md`） | 结果 | 证据（诊断码 / 界面文案） |
| --- | --- | --- |
| §2 二维码地址顺序：真实 Wi-Fi 在前、虚拟网卡在后 | 通过 | 二维码 `hosts` = `["192.168.2.135","172.17.0.1"]`（WLAN 在前，WSL 虚拟网卡在后） |
| §2 配对信标：手机配对页几秒内收到电脑信号 | 通过 | 手机「已在当前网络上收到「DENG」的信号」；电脑 `pairing_beacon_started` → 批准后 `pairing_beacon_stopped` |
| §2 等待页分阶段 + 秒计 | 通过 | 「等待批准… · 已等待 N 秒 · 取消」 |
| §2 / §5 配对请求到达 → 批准窗口 → 批准成功 | 通过 | 电脑批准窗口弹出并批准；`peer_pairing_confirm_received → peer_pairing_confirmed → pairing_beacon_stopped` |
| §2 等待页「取消」：手机回未配对态、电脑批准窗口随之关闭 | 通过 | 手机「已取消。这枚二维码已被使用…」；电脑批准窗口自动关闭（另见「发现并已修复」问题 3：诊断日志此前无结束码） |
| §3 双向文本同步 | 通过 | Windows → 手机、手机 → Windows 均入历史 |
| §3 PC → 手机图片同步 | 通过 | 手机收到图片并自动写入剪贴板 |
| §3 回环抑制 | 通过 | `capture_rejected_SuppressedWrite` |
| §1 通路页「防火墙」状态行 | 通过（防火墙关闭态） | 「已放行 TCP 47654 入站（公用网络）」——三配置文件关闭时判定为已放行，与 ADR 0006 三态一致 |
| [Unreleased] 语言下拉框闭合态 | 通过 | 闭合态显示「跟随系统」而非 record `ToString()` |
| [第二批] `Prefs_Subtitle` 去口号位 | 通过 | 偏好页副标题为本页事实 |
| §3 Windows 事实行：屏蔽进程 | 通过 | 「已生效 · 来自 4 个进程的复制不记录」 |
| §3 Windows 事实行：自动写入（文本） | 通过 | 「已开 · 最近一次收到的文本已写入本机剪贴板」 |
| §3 Android 读取路线降级 → 回升 | 通过 | 「当前读取路线：悬浮窗轮询。首选 特权直读 自 08:45 起未就绪（PRIVILEGED_CHANNEL_OFFLINE）」→ 执行 start.sh 后点「重新探测首选路线」→「已切回首选路线：特权直读」（原始错误码外露见「发现并已修复」问题 2） |
| §3 Android 事实行：设备行「最近同步」 | 通过 | 「DENG · Windows · 最近同步 09:13」 |
| §3 Android 偏好页事实行 | 通过 | 「运行中 · 已与电脑连接」「后台读取运行中 · 当前路线 悬浮窗轮询 · 本次运行已捕获 1 条」「本次运行尚未遇到标记为敏感的内容」 |

### 下午续测（同日，临时开启 Windows 防火墙公用配置文件复现拦截）

用管理员 PowerShell 把公用配置文件设为 `Enabled=True / DefaultInboundAction=Block`（测试后由用户关回），同一手机、同一网段。

| 项 | 结果 | 证据 |
|---|---|---|
| §2 防火墙拦截下的配对：电脑不弹批准窗、手机分层提示 | 通过 | 电脑 `firewall_inspect_no_rule`，二维码窗口出现赭色「本机防火墙未发现 TCP 47654 放行规则…」；手机核对页仍收到信标，确认后约 15 秒失败：「无法连接到电脑… / 手机已收到这台电脑在当前网络上的信号，但连接超时——几乎可以确定是电脑的 Windows 防火墙没有放行 TCP 47654 入站」；电脑无 `peer_pairing_confirm_received` |
| §1 检测识别安全警报生成的阻止规则 | 通过 | 应用开始监听时 Windows 弹安全警报被取消，系统为 `clipsync.app.exe` 生成 TCP/UDP 两条 Public 入站 Block；通路页「存在针对本程序的阻止规则：ClipSync.App」 |
| §1 一键放行（含阻止规则清理）→ 已放行 | 通过（修复后） | 修复前：`firewall_rule_add_applied` 后仍 `firewall_inspect_no_rule`（Block 优先于 Allow）；修复后确认窗列出 delete ×2 + add 三条命令，一次 UAC，`firewall_rule_add_with_block_cleanup_applied → firewall_inspect_allowed`，`Get-NetFirewallRule` 仅剩 `ClipSync TCP 47654`（Private, Public） |
| §1 检测不被 Store 应用规则误导 | 通过（修复后） | 修复前：规则只放专用、当前公用，检测报「已放行」而手机实测超时——本机 ChatGPT / Microsoft Store / Xbox Game Bar 等打包应用规则（协议 Any、无端口、无程序）被算作全局放行；读取 `INetFwRule3.LocalUserOwner/LocalAppPackageId` 排除后报 `firewall_inspect_no_rule` |
| §3 Android 蓝牙权限被拒事实行 | 通过 | `BLUETOOTH_CONNECT` 未授予时打开备援开关：「蓝牙权限未授予 · 备援不可用，去系统设置授权」（赭色）+「蓝牙目标设备 · 未选择」 |
| §3 Android 路线状态行人话 | 通过（修复后） | 装入修复包后显示「首选 特权直读 自 12:18 起未就绪（特权通道未运行）」而非 `PRIVILEGED_CHANNEL_OFFLINE`；降级事件行「已于 10:25 因通道故障降级」在特权宿主死掉后如实出现 |

### 09-08 续测（开机自启、托盘退出竞态）

同一测试实例（`CLIPSYNC_DATA_DIR` 独立目录）。托盘操作用 UIA 定位任务栏通知区按钮 → 右键 → 调用菜单项「退出」→ 之后 4 秒内以约 130 毫秒间隔在图标位置连点左键。

| 项 | 结果 | 证据 |
|---|---|---|
| §5 开机自启开 / 关 | 通过（修复后） | 修复前开启后事实行为「未能登记启动项」而注册表已写入（状态按写入前快照计算）；修复后开 → HKCU `Run\ClipSync` = `"…\ClipSync.App.exe" --minimized`，行文案「已登记 · 下次登录随 Windows 启动」；关 → 键删除、行回落 |
| §5 退出期间连点托盘图标 | 通过 | 30 次连点全部落在守卫上：诊断只有十余条 `tray_open_refused`，无 `unhandled_*`，`%LOCALAPPDATA%\CrashDumps` 无 ClipSync 转储 —— 原报告的托盘退出崩溃在真机上确认已修 |
| §3 Android 悬浮窗轮询与前台输入法共存（用户报告 QQ 转发 / B 站分享时键盘闪烁） | **失败 → 修复后通过** | 复现：设置搜索框弹出键盘，`dumpsys window` 连续采样 80 次。修复前：焦点落在我方悬浮窗 6 次、输入法目标（`imeLayeringTarget/imeInputTarget`）落在我方悬浮窗 6 次，`dumpsys input_method` 中每秒一条 `startInput reason=WINDOW_FOCUS_GAIN targetWin=[com.clipsync.android] inputType=0x0`——每次获焦都把键盘从前台应用手里抢走再还回去。修复后（读取窗口 `FLAG_ALT_FOCUSABLE_IM` + `SOFT_INPUT_STATE_UNCHANGED`）：获焦 6–7 次、输入法目标落在我方 **0** 次、键盘弹出期间 `startInput` 0 条；复制 `markerA1005` 后手机历史 10:06 即出现，读取路线未受影响 |
| §5 退出后进程结束 | **失败 → 修复后通过** | 第一次（跑了 15 小时、跨 4 次 Modern Standby 的实例）点退出后进程 3 分钟仍在：`dotnet-stack` 显示 UI 线程停在 `App.OnExit → syncHost.DisposeAsync().GetAwaiter().GetResult()`，其余线程无任何 ClipSync 帧（即被等待的异步链在等一个永不完成的东西，而非线程互锁）。修复（释放改到线程池 + 15 秒上限；恢复通道加 30 秒有界超时）后重启实例复测两次：从调用「退出」到进程消失 110 毫秒，退出码 0，无 `exit_dispose_timeout_*` |

### 未覆盖

- 电脑端 90 秒不批准的超时气泡（本轮均在超时前批准或取消）。
- 呼出快捷键状态行（未开启，故无事实行可看）。
- Android 不可达失败中「连接被拒绝 / 无路由」两个分支（本轮只触发了「超时 + 已收到信标」分支）。
- 蓝牙开关崩溃修复后的装机复验（修复包已构建，手机 USB 调试授权在电脑重启后失效，待重新允许后安装）。
- **Modern Standby 下的睡眠/唤醒通知投递**：09-07 的实例存活 15 小时，内核电源日志（Kernel-Power 506/507）记了 4 次 Modern Standby，应用诊断里没有一条 `peer_suspend_sessions_gated` / `peer_resume_recovered_*`。`PowerRegisterSuspendResumeNotification` 注册本机成功（新码 `power_notify_registered`），投递与否留待下一次自然待机：修复后的实例已最小化常驻（诊断文件 `diag5.log`），届时看 `power_suspend_signal / power_resume_signal` 是否出现。

### 发现并已修复（2026-09-04，本节定稿同日）

1. **Android 自动写入结果只有一个槽位**：`InboxDelivery.lastApplyOutcomes` 只保存最后一次结果，收到图片再收到文本后偏好页图片行消失、文本行出现，同一时间只有一行。改为按类别各自保留（`InboxApplyOutcomes(text, image)`），`recordApply` 只更新对应槽，`preferencesRuntimeFacts` / `preferencesStatusLines` 改为两路读取；单测覆盖两类互不覆盖、门控投递不动任何槽。
2. **Android 路线状态行外露原始错误码**（`PRIVILEGED_CHANNEL_OFFLINE`）：新增纯函数 `ReadRouteReasons.phraseFor` 把全部可能进入 `shortfall.errorCode` / `lastErrorCode` 的码映射为短语（特权通道未运行 / 未授权 / 连接已断开、READ_LOGS 尚未授予 / 授权已失效、悬浮窗权限未开启、屏幕未亮起、电池优化未放行、尚未完成实测……），未知码改用不带括号的 `read_live_degraded_fallback_plain` / `read_live_degraded_not_ready_plain`；14 条短语 + 2 条 plain × 19 语；单测覆盖映射与回退。
3. **Windows 对端中止配对未留记录**：手机在批准前取消时诊断日志止于 `peer_pairing_confirm_received`。`PairingService.ConfirmAsync` 在 `ApproveAsync` 因非超时取消抛出时记 `PairingConfirmFailed(PAIRING_ABORTED)` 后继续抛（HTTP 响应已无人接收，协议与返回值不变）；`PairingErrorCodes.PeerAborted` 仅日志用、不入线格式，诊断码 `peer_pairing_confirm_failed_pairing_aborted`；单测覆盖中止路径与超时路径互不误报。
4. **Windows 设备行英文 "Last seen"**：`PairedDeviceViewModel` 的两处字面量改为 `Device_LastSeenFormat`「最近在线 {0}」/ `Device_NeverConnected`「尚未连接过」，19 语。
5. **Windows 图片同步状态在对端已支持图片时仍说「手机端也开启时才互传图片」**：`SettingStatusMapper.ImageSync` 在 `imageCapableDevices ≥ 1` 时改用 `Status_ImageSync_OnImageCapableFormat`「已开 · {0} 台设备已连接，图片互传可用」；`0` 保持 PeerTextOnly、`null` 保持原句；单测随改。
6. **Android 特权宿主附着后仍每秒重发 binder**（logcat `ShizukuProvider: sendBinder is called when already a living binder` 持续 ≥ 20 秒）：判定为我方 `PrivilegedHostService` 的重发循环——`attachApplication` 成功时把 `resendTicks` 归零，反而把节奏重置回 1 秒快档 30 次，之后才退到 10 秒。改为 `BinderResendPolicy`：无客户端附着时 1 秒 × 30 次再退 10 秒（宿主先于应用启动、或推送本身拉起应用的场景），已附着后改为 30 秒保活，附着即刻切换（经 looper 线程重排，不会留下两条循环），客户端死亡则归零重回快档以便替代进程一秒内拿到 binder；`start.sh` 只负责一次 spawn，无循环。单测覆盖三种节奏。

7. **Windows 一键放行对「安全警报点过取消」无效**：放行规则建好后仍被两条程序级 Block 压制。放行流程改为同一次 UAC 用 `netsh -f` 脚本先删本程序的阻止规则再建放行规则，确认窗与命令预览如实列出；规则已存在但仍被阻止时「放行」保持可用并先删旧规则再建。
8. **Windows 防火墙检测把 Store / 打包应用规则当成全局放行**（误报「已放行」，手机实测被拦）：读取 `INetFwRule3.LocalUserOwner` / `LocalAppPackageId`，带用户或包作用域的规则不再计入放行或阻止。
9. **Android 重建后打开「蓝牙备援」崩溃**（`IllegalStateException: Attempting to launch an unregistered ActivityResultLauncher`）：`PreferencesViewModel` 工厂 lambda 捕获了首个 Activity 的权限启动器；改为 ViewModel 发出 `HostRequest` 事件、当前 Activity 收集执行，同路径的通知权限请求、保留期清理、权限事实重采样一并脱离旧实例。

#### 09-08 续测新增

10. **Windows 开机自启事实行误报「未能登记启动项」**：`OnLaunchAtStartupChanged` 在 App 写注册表之前就按旧快照算状态；App 在 `ReconcileLaunchAtStartup` 后调用 `RefreshStartupStatus()`，行文案改为陈述写入的实际结果。
11. **Windows 托盘退出后进程永不结束**：`OnExit` 在 UI 线程上同步等待 `DisposeAsync()`，被等待的链条里一次卡住的恢复回调（`SyncResilienceController` 门闸永不释放）让等待无界。`OnExit` 三处异步释放改到线程池、`Task.Wait` 上限 15 秒并记 `exit_dispose_timeout_<what>`；`SyncResilienceController` 加 `RecoveryTimeout`（默认 30 秒）——回调 token 超时取消、`WaitAsync` 兜底放弃不看 token 的回调并释放门闸（`peer_recovery_timed_out`），此前一次卡住会静默关掉之后所有唤醒/网络恢复；UDP 信标单次发送加 5 秒上限（`peer_beacon_send_timed_out`）。
12. **睡眠/唤醒通知注册结果与信号原本不可观测**：新增 `power_notify_registered / power_notify_register_failed_<code>`、`power_suspend_signal / power_resume_signal`，用来区分「系统没投递」和「注册失败」。
13. **Android 悬浮窗轮询让前台输入法每秒闪一次**：读取窗口切成可获焦时默认也是输入法客户端，系统每次都把它当成新的、没有编辑框的输入目标，键盘收起再弹出。`OverlayFocusController.readSpec` 加 `FLAG_ALT_FOCUSABLE_IM`，`AndroidOverlayPlatform` 的窗口参数加 `SOFT_INPUT_STATE_UNCHANGED`；空闲态不加该标志（与 `FLAG_NOT_FOCUSABLE` 叠加语义反转）。

### 测试方法备注

- 手机输入法会把 `adb shell input text` 的拉丁字符转成中文候选，合成标记建议用剪贴板写入或 base64 传递而非 `input text`。
- 在同一个 shell 里跑 `dotnet test` 时若仍设着 `CLIPSYNC_DIAGNOSTICS_PATH`，测试进程会把用例里的诊断码写进同一个文件（例如 `firewall_rule_remove_failed_InvalidOperationException` 与蓝牙/缩略图码在两秒内成串出现），不是真实操作。
- Windows 安全警报被取消后生成的 Block 规则被删除后，应用下次监听时警报会再次弹出；再取消就再生成——测试期间出现过两轮。
- `adb shell ime set …` 切换输入法会触发 Activity 重建，Compose `remember` 状态随之清空——不是应用缺陷。
- 退出挂起这类「进程还在但什么都不做」的问题，`dotnet tool install -g dotnet-stack` 后 `dotnet-stack report -p <pid>` 一条命令就能拿到全部托管线程栈，比猜快得多；本轮就是靠它把问题定位到 `OnExit` 的同步等待。
- 键盘闪烁这类「看得见但抓不住」的问题，`dumpsys input_method` 的 `mStartInputHistory`（谁、何时、因何原因成为输入目标）和 `dumpsys window` 的 `imeLayeringTarget / imeInputTarget` 是最直接的证据；每秒一条 `WINDOW_FOCUS_GAIN` 且 `inputType=0x0` 的记录几乎必然是某个悬浮窗在抢焦点。手机端拿不到剪贴板"复制"菜单时，`input keycombination 113 29`（Ctrl+A）/ `113 31`（Ctrl+C）可替代。
- 安全软件（火绒）会静默吞掉对 HKCU `Run` 键中它记住的「值名 + 路径」组合的写入（本轮用反射探针写过一次 `C:\probe\…` 后即被记住），真实 exe 路径的写入不受影响——排查开机自启时先确认这一点，不是应用缺陷。

## 签核（2026-08-26）：用户确认真机验证已全部完成

- 2026-08-26，仓库所有者确认：**真机验证已全部完成**（覆盖本记录「未做（若要出 RC 还需）」清单与 `docs/manual-qa-checklist.md` 的剩余项）。据此，2026-08-25 的「本轮不能出 RC」判定**不再构成发布阻断**，RC 门槛视为已过。
- 诚实注记：本签核以用户确认为准；逐项的结构化结果（操作者/日期/构建 commit/原始计数/P95 样本）尚未回填到本文件。用户提供明细后，应按本记录既有表格样式新增带日期的一节补录；在此之前，本节即为唯一签核凭据，`docs/device-validation-matrix.md` 的槽位单元格按「未测不得改绿」字面纪律维持原状、待明细回填。
- 首轮「本轮已知限制」1–6 此前已全部在 main 修复并闭环（见上方「修复附注」与三则更新）。发布收尾（把 CHANGELOG Unreleased 归入 v0.1.0、打 tag 触发 `release.yml`、定稿发布说明）为剩余步骤。**2026-08-26 同日更新**：CHANGELOG 已归档为 `[0.1.0] - 2026-08-26`、发布说明已定稿为 `docs/releases/v0.1.0.md`（原 `v0.1.0-draft.md`）；剩余仅打 tag——`v0.1.0-rc.1` 已于 2026-08-26 打出（`release.yml` 首次实跑成功、prerelease 已发布；因签名 secrets 未配置，产物为 unsigned APK），剩余仅打 `v0.1.0` 正式发布。
