# 二次独立评审结果（图片同步 / 开关 / 导出 / 分类器 / CI）

- 日期：2026-08-24
- 分支：`cursor/implement-charter-ui-1991`（评审基点 `709273c`，性能与审计代理的提交已全部拉取）
- 性质：与性能/审计代理独立的第二次交叉评审。逐项给出 PASS / FAIL 与证据；发现的快速可修项已在本次评审中直接修复（见 §7）。

## 0. 结论总览

| # | 评审项 | 结论 |
|---|---|---|
| 1 | 图片同步端到端链路，双端接线完整 | **PASS** |
| 2 | 暂停 / 私密 / 图片开关处处生效 | **FAIL → 已修复**（Android 缺独立的图片自动写入开关） |
| 3 | 导出不泄露机密；图片按格式文档正确处理 | **PASS**（附一处文档措辞修正） |
| 4 | 分类器与图片条目的交互 | **PASS** |
| 5 | CI 工作流有效 | **PASS**（步骤名更正为 v1 + v2） |

## 1. 图片同步端到端链路 — PASS

双端从捕获到写回的每一跳都已接线，且互为镜像：

**Windows 捕获 → 存储 → 发送**

- `Win32ClipboardAdapter` / `ClipboardDataAccessor.TryReadImage`：优先读 PNG，回退 `CF_DIBV5`/`CF_DIB` 经 `DibCodec.TryDecodeToPng` 有界解码（拒绝压缩、超大调色板、整数溢出），并计算像素摘要供回环抑制（DIB 重编码后字节 hash 会变，ADR 0004 预言的坑已处理）。
- `App.OnClipboardChanged` → `ClipboardCaptureService.CaptureAsync` → `ClipboardCapturePolicy`（暂停/私密/来源黑名单先行，图片再过 `ImageSyncEnabled` + `ImageCodec.TryInspect` 校验）→ `SqliteClipboardEventStore.StoreImageAsync`：blob 内容寻址提交 + clips 行（`kind=image`、空正文、blob hash）+ 接收向量 + outbox 扇出，同一事务。
- `SyncSessionEngine`：v2 会话双向 `clip_payload_begin/chunk*/end`（乱序/越界/hash 不符各有稳定错误码）；同 hash 活 blob 走免传重放路径但仍提交事件行；v1 会话把图片按 `unavailable + local_only` 终止标记推进游标（`BuildHeader`），绝不在 v1 发图片正文。ACK 严格晚于 blob+clip 同事务提交。

**Windows 接收 → 写回**

- `App.OnRemoteClipsCommitted`：批次仅最新一条、先过 `!IsPaused`，图片再过独立的 `AutoApplyImages`（默认关）→ `WriteImage`（CF_DIB）+ `SuppressNextImage` 回环抑制；文本的 `AutoApplyRemote` 永远不会自行写入像素字节。

**Android 捕获 → 存储 → 发送**

- 前台捕获：`MainActivity` 构造 `ClipboardCaptureManager` 时注入生产 `imageSink = ImageClipSink.submit`（默认桩为 `{ false }`，已确认生产端没有漏接）；相册分享走 `ShareReceiverActivity` 图片路径 + FileProvider。
- `ImageClipSink.submit`：图片开关/私密/暂停三闸同步判定 → `ImageCodec.tryInspect` 校验 → blob 提交 + `recordLocalImageClip`（Room 同事务：clips 行 + `media_blobs`/`clip_media` + outbox 扇出）→ nudge 引擎。
- `SyncSupervisor`：仅当 `imageSyncEnabled` 时先拨 v2（`/v2/peer/sync`，`image_clip_v2` capability），被拒回落 v1；`SyncEngine` 的收发分块、临时文件、hash 校验、并发下载上限（2）与 Windows 引擎逐条对应，v1 会话上图片同样只发 `local_only` 终止标记。

**Android 接收 → 写回**

- `ClipboardSyncService` → `InboxDelivery.deliverImage` → `ClipboardWriteCoordinator.writeImage`（按编码字节 SHA-256 登记抑制，捕获管线据此丢弃回声）。图片永不进文本收件箱；写回失败仅留在历史供手动复制。

**交叉验证**：`WindowsAndroidSyncChainTest` + 共享媒体 fixtures 的 v2 图片往返测试（`2d4ff7d`）双端消费同一份 `protocol/v2/fixtures`。

## 2. 暂停 / 私密 / 图片开关 — FAIL → 已修复

**逐处核对（通过项）**

- Windows 捕获：`ClipboardCapturePolicy.Evaluate` 第一、二闸即 `IsPaused`/`IsPrivateMode`，文本图片一体拦截；`ImageSyncEnabled` 关闭时图片候选拒收（纯图片拒、图文混合回退文本）。设置经 `MainViewModel.ApplySettings` 即存即生效。
- Windows 出站：`SyncSessionOptions.OutboundAllowed`（暂停/私密）在 `want_ranges` 服务与 outbox 排空两处逐 tick 重读；暂停期间条目保持 pending，恢复后补投无丢失。
- Windows 入站写回：`!IsPaused` 包住整个写回分支；文本 `AutoApplyRemote` 与图片 `AutoApplyImages`（默认关）各自独立。
- Android 捕获：`SettingsGatedClipOutbox` 把私密/暂停包在队列本体上（分享面板、磁贴、前台捕获无一能绕过）；`ClipboardCaptureManager` 对图片先过 `imageSyncEnabled` 再过回环抑制；`ImageClipSink` 三闸重复判定（同步返回诚实结果供 toast）。
- Android 出站：`outboundAllowed = { !syncPaused && !privateMode }`，逐排空 tick 重读。
- Android 拨号：图片开关关闭 → 只拨 v1 → 不会收到图片正文。

**发现的缺陷（本次已修）**

Android 入站图片写回复用了文本闸 `autoApplyAllowed`（`auto_apply_remote`，**默认开**）。ADR 0004 与 stage-9 变更记录均要求「`auto_apply_images` 与文本自动应用独立」，Windows 端也是独立开关且默认关。原实现下：Android 开了图片同步后，远端图片会在默认设置里被静默写进本机剪贴板——与 Windows 行为不一致、违反 ADR。

修复内容（详见 §7）：新增 `SyncSettingsStore.autoApplyImages`（`sync.auto_apply_images`，默认关）、`InboxDelivery.autoApplyImagesAllowed`（暂停仍双杀）、`ClipboardSyncService` 图片分支改走新闸、偏好页新增「自动写入远端图片」开关、`InboxDeliveryTest` 固化独立性与默认关。

**记录在案的非对称（非缺陷）**

Windows 监听端恒接受 `/v2/peer/sync`（stage-9 lineage 明文：「Windows 监听默认 session options 为 v2」）。因此 Windows 图片开关关闭时，已开图片同步的 Android 对端仍可把图片送进 Windows **历史**（不会写剪贴板——`AutoApplyImages` 另有一闸）。Windows 的图片开关闸的是本机捕获；接收侧按既定设计不闸。行为与文档一致，故不判 FAIL，此处备案供产品复核。

## 3. 导出：不泄密 + 图片处理符合格式文档 — PASS

**不泄密**

- Windows `ExportHistoryAsync` 的 SELECT 与 Android `clips.exportAll()` 只触碰 `clips` 表列；配对密钥、TLS 证书/指纹、trust epoch、设备表、游标、outbox、设置一概不可能进入输出（`HistoryExportFormat` 的 DTO 也没有这些字段的容身之处）。
- header 仅含文档声明为 informational 的 `exporting_device_id`；导入/导出异常只带稳定错误码，永不携带剪贴内容；两端 UI 均标注「导出内容为明文」。
- 导入侧与同步协议同级的严格解析：未知字段/错类型拒收、逐条重算 `content_hash`、1 MiB 上限先于入库、`event_count` 不符整文件拒收、单事务全或无。

**图片处理**

- `docs/export-format-v1.md` 定义 v1 为纯文本（`kind` 仅 `"text"`，读端拒绝未知 kind）。两端实现一致：Windows `WHERE kind = 'text'`（连 `event_count` 也只数文本行），Android `filter { it.kind != ClipKinds.IMAGE }`；图片行（含其终止标记）整体不出现在导出中，而不是被写成会毁掉再导入的有损文本形状。导入插入时双端均显式落 `kind='text'`。
- 文档 §3 原文「写出全部行」写于图片落地之前，与实现出现措辞漂移——本次已在 §3 补充图片行排除条款（不改 schema，不动 `format_version`）。
- 图片感知的 `format_version: 2` 是 `stage-4-merge-gap-audit.md` §2 挂牌的 P1 待办，属已知欠账，非本分支回归。

## 4. 分类器与图片条目 — PASS

- 双端规则逐条镜像（`ClipContentClassifier.cs` ↔ `ClipContentFormat.kt`：Link → Email → OTP → Credential → Plain，年份让位、UUID 不当口令、可见 ASCII 限定等全部一致）。
- 图片条目**不进分类器**：Windows `entry.IsImage ? Plain : Classify(...)`，Android `if (isImage) PLAIN else classifyClipContent(...)`——空正文不会被误标，也不浪费分类计算。
- 形态筛选是文本词汇（ADR 0003）：两端筛选器均显式排除图片（Windows `item.IsImage || item.Format != filter` 跳过；Android `it.format == filter && !it.isImage`），图片只在「全部」出现，配独立「图片」徽标（`FormatLabel`/`HasFormatBadge` 与 Android `isImage` 分支）；不存在图片被「文本」chip 捞出或被 Plain 徽标污染的路径。

## 5. CI 工作流 — PASS

- `.github/workflows/ci.yml` 结构有效：三作业（协议校验 ubuntu / Windows 构建+全部测试 windows-latest / Android 单测+assembleDebug ubuntu），`cursor/**`、`feature/**`、PR、手动触发全覆盖；并发去重、超时、`permissions: contents: read` 均在。
- 引用的文件全部存在且路径正确：`scripts/validate-protocol.ps1`（转发 `validate-protocol.py`，v1+v2+pairing 全量校验）、`scripts/build-windows.ps1`（restore→build→test 全解决方案，含 App.Tests）、`global.json`（8.0.419 rollForward latestPatch）、`android/gradlew` + wrapper。
- 本地实跑协议校验通过：12 valid + 37 invalid（v1）、15 + 15（v2）、5 + 7（pairing）。
- 小修：步骤名「Validate protocol v1」更正为「Validate protocol v1 + v2」（脚本早已双版本校验，名字滞后）。

## 6. 本次评审执行的测试

| 套件 | 环境 | 结果 |
|---|---|---|
| 协议 schema/fixture 校验（v1+v2+pairing） | 本机 Linux, Python 3.12 | **通过**（12+37 / 15+15 / 5+7） |
| `dotnet test ClipSync.Tests`（Core+Peer 跨平台套件） | 本机 Linux, .NET SDK 8.0.424 | **378 通过 / 0 失败** |
| `gradlew testDebugUnitTest` + `assembleDebug`（Android 全量 JVM 单测，含本次新增用例） | 本机 Linux, JDK 21, SDK 35 | **501 通过 / 0 失败**，APK 组装成功 |
| `gradlew detekt` + `ktlintCheck`（分支自带静态分析） | 本机 Linux | **通过**（ktlint 基线因插行重新生成，diff 仅限本次触碰文件的行号平移与新增条目） |
| `ClipSync.App.Tests`（WPF 应用层） | 需 Windows；由 CI windows-latest 作业覆盖 | 交由 CI |

## 7. 本次评审内完成的修复

1. **Android 独立图片自动写入开关**（§2 的 FAIL 项）：
   - `storage/SyncSettingsStore.kt`：新增 `autoApplyImages`（`sync.auto_apply_images`，默认 `false`）。
   - `sync/InboxDelivery.kt`：新增 `autoApplyImagesAllowed(settings)`（`autoApplyImages && !syncPaused`，与 Windows 的 `!IsPaused && AutoApplyImages` 一致）。
   - `sync/ClipboardSyncService.kt`：入站图片分支改用新闸；文本分支不变。
   - `ui/prefs/PreferencesViewModel.kt` / `PreferencesScreen.kt` / `MainActivity.kt`：新增「自动写入远端图片」开关行（默认关，文案与 Windows「自动应用远端图片」对齐）。
   - `InboxDeliveryTest`：新增用例固化「独立于文本闸、默认关、暂停双杀、文本闸关闭不连坐」。
   - `android/config/detekt/baseline.xml`：`PreferencesScreen` 签名变化对应的三条基线条目同步更新。
   - `android/config/ktlint/baseline.xml`：基线按行号定位，插行后整体重新生成（diff 仅为触碰文件的行号平移与新增代码的对应条目）。
2. **`docs/export-format-v1.md` §3**：补充图片行排除条款，消除「写出全部行」与实现的措辞漂移。
3. **`.github/workflows/ci.yml`**：协议校验步骤名更正为 v1 + v2。
4. **`CHANGELOG.md`**：Unreleased「修复」段新增图片自动写入独立开关条目。
