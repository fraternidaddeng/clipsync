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

**代码侧**缺陷不在本次跟进范围，仍按「本轮已知限制」清单开放：幽灵设备 outbox 积压（限制 2）、Android「对端写入」未探测（限制 3）、超限文本无用户提示（限制 4）、双端图同步开关不一致（限制 5）。限制 1（缩略图单测）与限制 6（CI 状态）的最新进展见下方更新。

### 更新（2026-08-25）：缩略图单测阻断已修复；CI 工作流存在但零运行

- **限制 1 — Windows 缩略图单测失败：已在 main 提交 `9519716` 修复，待真 Windows 主机重跑 `scripts/build-windows.ps1` 确认后方可闭环。** 两条失败单测系两个独立成因，分别处理：`EnsureThumbnailKeepsOpaquePixels` 一类的逐通道像素全等断言改为共享的 `AssertCenterPixelIsSolid`（alpha ≥ 250、每通道相对编码色 ±3），容忍 WIC Fant 缩放器的定点误差；`LoadForListRegeneratesACorruptCachedThumbnail` 一类的损坏缓存自愈不再依赖静默 TryDelete，改为经唯一临时文件 + 覆盖式 `File.Move` 强制重写缓存，无法产出可解码缓存时 `LoadForList` 返回 null 路径而非坏路径。该提交已在 Linux 验证：解决方案编译干净（`EnableWindowsTargeting`，0 警告）、跨平台套件 471/471 通过；WPF 测试体仍需 Windows 执行，**在真 Windows 主机上 `scripts/build-windows.ps1` exit 0 之前不算验证通过**。
- **限制 6 — CI 状态：工作流存在，但仓库从未有过任何 Actions 运行。** 仓库有且仅有一个工作流 `.github/workflows/ci.yml`（名称 CI，API 状态 active），含三作业：`validate-protocol`（ubuntu-latest）、`build-windows`（windows-latest，执行 `scripts/build-windows.ps1` 含 App.Tests）、`build-android`（ubuntu-latest）。截至本更新，Actions API 运行总数为 **0**——包括 `9519716` 在内的近期 main 推送均未触发任何运行；仓库级 Actions 权限查询对当前凭证返回 403，无法确认是否在仓库/组织设置中被禁用。因此第 0 节「CI 三作业全绿」仍不可核实，`9519716` 的修复也尚无 CI 验证，目前只能依靠 Windows 主机手动重跑兜底。

### 更新（2026-08-25）：限制 5「双端图片同步开关不一致」已按「双端默认关」对齐并钉死

- **产品默认取「关」而非「开」**：ADR 0004（「图片自动同步默认关闭」）与设计宪章 §5.9（`settings_image_sync_hint` =「默认关闭。仅 PNG/JPEG。原始字节含 EXIF。…」）都把图片同步定为显式开启的隐私承诺，故对齐方向是**双端默认关**，不是双端默认开。
- **出厂默认核实**：Android `SyncSettingsStore.imageSyncEnabled`（`sync.image_sync`）默认 `false`；Windows 偏好页 `image_sync` 设置读取为 fail-closed（未存值/坏值一律解析为关），`MainViewModel.imageSyncEnabled` 字段默认亦为关。**QA 机上 Windows 显示「开」是该机此前手动开启后的持久化状态**（该机历史里已有图片缩略图即为旁证），不是出厂默认——两端出厂默认本就一致为关。
- **真正修复的缺口（Windows 库层 fail-open 默认）**：`SyncSessionOptions.ImageSyncEnabled` 与 `PeerSyncHost` 的未接线兜底闸原为 `() => true`——生产 App 有接线所以未暴露，但任何忘记接线的宿主（如新增的承载进程）会静默参与 image_clip_v2 收发图片，与「默认关」相悖。现双双改为 `() => false`（未接线宿主表现为纯 v1 文本对端）；测试套件在 `PeerPair.DefaultSessionOptions` 显式开启以继续覆盖 v2 图片路径。
- **测试钉死**：Windows 新增 `ImageSyncGateTests.ImageSyncGateDefaultsOffMatchingTheAndroidDefault`（默认闸关、v2 路由下 `ImageClipEnabled` 仍为假）；Android 新增 `ImageSyncDefaultAlignmentTest`（`imageSyncEnabled` 默认关且可往返、`auto_apply_images` 独立于文本闸默认关、坏持久化值回落为关）。图片同步的**双向真机验收**仍未做（见第 3 节），本更新只闭「默认值不一致」一项。
