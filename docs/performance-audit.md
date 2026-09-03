# 性能审计（performance audit）

- 日期：2026-08-24
- 分支：`cursor/implement-charter-ui-1991`
- 范围：历史检索的数据库索引、缩略图解码上限、Android 通路页（`HealthViewModel`）的重复探测触发、WPF 历史列表虚拟化。
- 纪律：**只做安全优化**。纲领的胶片颗粒、阴影层级、动效曲线与形态分类器（ADR 0003）一律不动——它们是设计承诺，不是性能债。

## 结论一览

| 审计项 | 结论 | 动作 |
|---|---|---|
| 历史检索索引（双端） | 已存在且命中查询 | 仅核实，未改动 |
| 同步去重哈希索引（Windows） | **缺失**（Android 自 v1 就有） | schema v4 补 `clips(content_hash)` 索引 |
| 缩略图解码上限（Android） | 已有界（inSampleSize + 512px 缩放） | 仅核实，未改动 |
| 缩略图解码上限（Windows 主路径） | 已有界（DecodePixelWidth/Height + 128px 绑定解码） | 仅核实，未改动 |
| 缩略图解码上限（Windows 兜底路径） | **无界**（回退解码器可全尺寸落盘） | 兜底帧强制缩至 512px |
| `HealthViewModel.refresh` 触发 | 每次刷新双倍探测；触发源成簇重复 | 单遍探测 + 突发合并 + 配对键控；**保留 onResume 探测** |
| WPF 历史列表虚拟化 | ListBox 默认已虚拟化，布局未破坏它 | 显式声明 + 开启容器回收（Recycling） |

---

## 1. 数据库索引：历史检索已覆盖，补齐 Windows 的哈希索引

### 已核实、无需改动的部分

**Windows**（`SqliteClipboardEventStore.SearchAsync`）：

```sql
WHERE c.deleted_at IS NULL AND (expires_at …) AND (… LIKE …)
ORDER BY c.created_at DESC, c.origin_seq DESC, …
```

`clips_visible_history_idx (deleted_at, created_at DESC, origin_seq DESC)` 自 v1 基线就在，前缀命中 `deleted_at IS NULL` 的过滤并直接给出排序顺序；`LIMIT/OFFSET` 分页在索引序上截断。`LEFT JOIN clip_media / media_blobs` 分别走主键与 `clip_media_hash_idx`。`LIKE '%…%'` 的中缀匹配本质上无法用 B 树索引加速（这是选择，不是遗漏：历史检索是子串语义）。

**Android**（`ClipEventDao.search / observeSearch`）：同构查询，`Index(deleted_at, created_at)` 已建；`(origin_device_id, origin_seq)` 唯一索引服务同步区间拉取；`content_hash` 索引服务去重。均已命中。

### 补齐的部分：Windows `clips(content_hash)` 索引（schema v3 → v4）

`FindLiveContentByHashAsync` 按 `content_hash` 查 `clips`，`SyncSessionEngine` 对**每一条**对端 announce 的文本剪贴都要跑它一次（哈希命中即免拉取物化正文）。Windows 侧此前**没有**这一列的索引——每次 announce 都是全表扫描，历史越长同步越慢。Android 的 Room schema 从 v1 起就带 `Index("content_hash")`，此为双端奇偶差。

- 迁移：`SqliteClipboardEventStore.SchemaVersion` 3 → 4，新增有序迁移步 `ApplyContentHashIndexAsync`，内容只有一句 `CREATE INDEX IF NOT EXISTS clips_content_hash_idx ON clips(content_hash);`。纯增量、不触碰任何行数据；迁移框架的「缺步拒开库」不变式保持。
- 测试：`InitializeCreatesTheHistoryAndContentHashIndexes` 断言两枚索引都在 `sqlite_master`；版本钉住测试更新为 4；v1 旧库迁移测试照常通过（迁移链 1→2→3→4）。

## 2. 缩略图解码上限

### Android：已有界（核实，未改动）

`media/ImageThumbnail.kt` 的三段防线完整：`inJustDecodeBounds` 先读尺寸不解码；`inSampleSize` 按 2 的幂预降采样到 ≤ 2×512；`scaleToMaxSide` 精确缩到最长边 512 后落盘 PNG。列表渲染（`decodePreview`）只解码这份 ≤512px 的缩略文件，原始 blob（上限 16 MiB / 32 MP）从不直接进 UI。

### Windows 主路径：已有界（核实，未改动）

- `ImageThumbnail.DecodeBounded` 用 `TryInspectFile` 免解码读尺寸，再以 `DecodePixelWidth/Height = 512` 绑定解码——WIC 在解码期直接降采样，不会先展开全幅位图。
- 历史列表的 `FilePathToImageConverter` 以 `decodePixelWidth: 128` 解码缩略文件（56px 显示盒），详情窗加载的也是缩略文件。原始 blob 不进绑定管线。

### Windows 兜底路径：本次加界

`ImageThumbnail.DecodeWithDecoder`（`BitmapImage` 解码失败后的 `BitmapDecoder` 回退）此前原样返回整帧：一张 8192×4096 的图会被全尺寸重编码成“缩略图”落盘，之后每个历史行都绑定这份大文件。现在超过 512px 的帧经 `TransformedBitmap` 等比缩至 512 再落盘（`BoundToThumbnailSide`），与主路径及 Android 的 512px 契约一致。新增测试 `EnsureBoundsOversizedSourcesToTheThumbnailSide`（700×300 → 512×219）在 Windows CI 执行。

## 3. Android `HealthViewModel.refresh` 的重复触发

### 触发源盘点（改动前）

| 触发 | 时机 | 评估 |
|---|---|---|
| `init { refresh() }` | ViewModel 构造 | 必要 |
| `onResume()` | 每次回到前台 | **必要，明确保留**——授权在应用外变化，恢复即重探是诚实性要求 |
| 30s 可达性 ticker | 已配对时周期性 | 必要，有守卫 |
| 特权直读权限监听（Activity 级） | 授权对话框应答 | 必要 |
| `requestShizukuAuthorization` 回调 | 同一次授权应答 | 与上一条**同拍重复**（回调有早退路径，两者都得留） |
| `LaunchedEffect(pairingState)` | **配对状态机每次流转** | **过宽**：扫码→确认→提交→成败，每步都触发全量探测 |

另外 `refresh()` 自身每遍跑 `clipboard.probe()` **加** `clipboard.probeAll()`——探针梯子上每个后端（特权直读 binder ping、悬浮窗/电池豁免设置读取、logcat 权限检查……）被探测**两次**。冷启动叠加起来：init + LaunchedEffect 初始发射 + onResume = 3 遍刷新 × 2 倍探测。

### 改动（探测语义一字未变，只去重）

1. **单遍探测**（`HealthViewModel.refreshOnce`）：有能力接线时只跑一遍 `probeAll()`，路由事实与头条报告共用同一批结果；头条经 `ClipboardAccessCoordinator.mostCapable`（与 `probe()` 完全相同的能力排序，先到先赢）导出。无接线时维持原 `probe()`。每遍刷新的后端探测次数减半。
2. **突发合并**：`refresh()` 在有 pass 在飞时只标记一次尾随 pass（`refreshQueued`），落地后补跑一遍——N 个同拍触发（恢复 + 配对变化 + 双路权限监听）收敛为最多 2 遍，且尾随遍总是探到最新状态，不存在丢新鲜度的窗口。
3. **配对键控**（`MainActivity`）：`LaunchedEffect(pairingState)` 改为 `snapshotFlow` 映射到**持久化的 peer**（完整 `PairedPeer`，含证书与信任纪元，重配同一设备也算变化）、`distinctUntilChanged()`、`drop(1)`（初始值由 init/onResume 覆盖）。审阅、提交、失败等中间态不再各触发一遍全量探测；配对完成与遗忘照旧即时反映。

### 明确没做的

- 没有移除 onResume 探测（任务红线，也与「授权可在系统设置中随时变化」的诚实性纲领一致）。
- 没有加缓存/TTL 去跳过探测——每遍刷新仍然真探，只是不再重复探。

新增回归测试：`refresh with capability wiring probes each backend exactly once per pass`、`a burst of refresh calls during a pass coalesces into one trailing pass`（用可控闸门把 pass 挂起在可达性探测里，证明「在飞期间」的突发恰好收敛为一遍尾随）。注意合并语义的另一半：pass **开始前**到达的触发被该 pass 直接吸收（旗标在 pass 起点清零、状态在其后读取），不产生额外遍数。既有 24 条 `HealthViewModelTest` 全部保持原断言通过，包括 ticker 计数与授权流转两条对时序敏感的用例。

## 4. WPF 历史列表虚拟化

核实：历史页的 `ListBox` 直接坐在 `Grid` 星号行里——没有外包 `ScrollViewer`、没有 `StackPanel` 祖先、没有替换 `ItemsPanel`、没有分组，默认的 `VirtualizingStackPanel` 虚拟化**并未被布局破坏**（这是 WPF 里最常见的虚拟化失效方式，此处不存在）。

改动：显式声明 `VirtualizingPanel.IsVirtualizing="True"`（防回归的声明式契约）并开启 `VirtualizationMode="Recycling"`——滚动时复用条目容器而不是持续创建/析构 `ListBoxItem`（每个条目是三层嵌套卡片模板），长历史下显著降低布局与 GC 压力。视觉与交互零变化；卡片阴影、悬停抬升、选中描边全部保留。

托盘弹窗的 `ItemsControl` 固定 4 条（`RecentHistoryLength`），不虚拟化是正确的。

## 5. 未动清单（红线核对)

- 胶片颗粒（`Grain.kt` / z0 渐变背景、XAML 对应物）：未触碰。
- 卡片阴影三层嵌套与 hover 抬升（`HistoryItemContainer`）：未触碰（Recycling 只复用容器，模板与触发器原样）。
- Crossfade / CharterMotion 动效：未触碰。
- 形态分类器（ADR 0003，渲染期分类不落库）：未触碰。

## 6. 验证

| 套件 | 结果 |
|---|---|
| `windows/ClipSync.Tests`（Linux，net8.0） | **379 通过 / 0 失败**（含新索引测试与 v1→v4 迁移链） |
| `android ./gradlew testDebugUnitTest` | **502 通过 / 0 失败**（含 2 条新增 refresh 回归测试） |
| `android ./gradlew ktlintCheck detekt` | 通过（基线随行内插入的行号漂移重刷，沿用分支既有惯例，见 c6adf20） |
| `ClipSync.App` / `ClipSync.App.Tests` 编译检查（`-p:EnableWindowsTargeting=true`） | 通过，0 error；WPF 测试本体（含新增缩略图上界测试）按惯例由 Windows CI 执行（见 `docs/verification-without-device.md`） |

## 7. 看过但判定不值得动的

- `outbox(event_id)` 无索引：`CleanupAsync` 的 `NOT IN (SELECT event_id FROM outbox)` 子查询每语句只物化一次，收益不成比例。
- `MainViewModel.RefreshAsync` 每次重建全部 `HistoryItemViewModel`：条目构造本身很轻（缩略图解码在绑定期、且已有 128px 上界），增量 diff 属结构性改动，超出「安全优化」边界；虚拟化 + 回收已消化大列表的渲染成本。
- `ScrollUnit="Pixel"`：会改变滚动手感（属于体验决策，不是性能修复），留给设计侧定夺。

---

# 2026-09-03 第二轮：协议编解码、图片分片与落盘、PNG 编码器

- 分支：`w4/perf-round`（起点 `3c2e029`）；提交 `ff9c94e`（Windows）、`e750c6b`（Android）。
- 范围：非 UI 层——Windows `ClipSync.Core/**`、`ClipSync.Peer/{Sessions,Transport}`；Android `media/**`、`protocol/**`。UI 层只给建议（见 §5）。
- 纪律：不改线格式（`scripts/validate-protocol.py` 通过）、不改用户可见行为、不引新依赖、不改 detekt/ktlint 基线。每处快路径都有一条以旧实现为 oracle 的等价性测试。

## 1. 测量方法

| 端 | 测试 | 触发 | 计时 |
|---|---|---|---|
| Windows | `ClipSync.Tests/Perf/*`（`[PerfFact]`） | `CLIPSYNC_PERF=1 dotnet test -c Release --filter FullyQualifiedName~ClipSync.Tests.Perf` | `Stopwatch` + `GC.GetTotalAllocatedBytes`，中位数 |
| Android | `app/src/test/.../perf/ProtocolAndMediaPerfTest` | `CLIPSYNC_PERF=1 ./gradlew testDebugUnitTest --tests '*PerfTest'` | `System.nanoTime`，纯 JVM，中位数 |

未设置环境变量时两组测试默认跳过，不进入 CI 时长。「改前」数字来自把同一组基准测试注入 `3c2e029` 源码后在同一台机器上独立复跑（不是沿用提交说明里的数字）；Android 的 JVM 数字只用于同机改前/改后比例，ART 上绝对值更大、比例相近。

## 2. Windows（`ff9c94e`）

### 2.1 数字（Release，同机同批）

| 场景 | 数据规模 | 改前 | 改后 | 改动位置 |
|---|---|---|---|---|
| `TryDecodeBase64Url` | 256 KiB 分片 | 3.76 ms / 5.16 MB | 0.38 ms / 0.79 MB | `Core/Protocol/ProtocolValidation.cs` |
| `EncodeBase64Url` | 256 KiB | 0.32 ms / 2.80 MB | 0.40 ms / 1.22 MB（时间持平，分配 −56%） | 同上 |
| `ProtocolReaderV2.Parse` 分片帧 | 350 K 字符 | 5.71 ms / 7.08 MB | 0.42 ms / 1.58 MB | `ProtocolValidation.TryGetBase64UrlDecodedLength`、`ProtocolReader*.ParseOwned` |
| 接收链路 Parse + `TryDecodeChunk` | 每片 | 11.01 ms / 12.24 MB | 0.79 ms / 1.84 MB | 校验只算长度，分片只解码一次 |
| `ImageChunks.Split` | 4 MiB 图 | 17.7 ms / 44.7 MB | 5.7 ms / 11.7 MB | 池化 base64 编码 |
| `ProtocolWriter.Serialize` 文本帧 | 600 KiB 文本 | 4.48 ms / 16.5 MB | 3.66 ms / 12.9 MB | `ArrayBufferWriter` 替代 `MemoryStream.ToArray` |
| `ProtocolReaderV2.Parse` 文本帧 | 600 KiB | 9.09 ms / 4.25 MB | 9.10 ms / 4.85 MB（持平） | UTF-8 缓冲直接交给 `JsonDocument` |
| `EncodePngBgra` | 1024² | 39.5 ms / 7.76 MB | 32.7 ms / 1.04 MB | `Core/Media/ImageCodec.cs` |
| `EncodePngBgra` | 1600² | 105.4 ms / 16.3 MB | 74.1 ms / 2.09 MB | 同上 |
| DIB → PNG 捕获整链 | 1024² / 1600² | 41.2 / 119.7 ms | 37.4 / 87.8 ms | `DibCodec` 经由新编码器 |
| `MediaBlobStore.CommitBytes` | 503 KB PNG | 12.2 ms | 11.2 ms | `TryInspectFileHeader` 复用流式哈希 |
| Begin/Append/Commit（同步入库） | 503 KB / 795 KB | 12.6 / 16.6 ms | 12.5 / 14.1 ms | 同上 |

### 2.2 审查结论（逐项）

- **零拷贝 base64url**：`TryGetBase64UrlDecodedLength` 用「尾位比特为零」判定规范性，替代原来的「再编码一次比字符串」；`Base64UrlCodecTests` 把旧实现原文保留为 oracle，20 000 条随机串 + 空串 / 1 mod 4 / 含 `+ / =` / 非 ASCII 边界全部一致；`TryDecodeBase64Url256` 的 32 字节 + 规范性约束保持。线格式与 `protocol/v2` fixtures 无关联改动。
- **`stackalloc` 栈溢出**：旧 `WriteChunk` 用 `stackalloc byte[type.Length + data.Length]` 为整段 IDAT 算 CRC；把 `3c2e029` 的 `ImageCodec.cs` 单独编译后在 1 MiB 栈线程上编码 1024×512 噪声图，进程以 `0xC00000FD (Stack overflow)` 退出，栈顶正是 `WriteChunk`——**是真实崩溃路径**（剪贴板监听线程默认 1 MiB 栈，任何压缩后 ≥ ~1 MiB 的截图都会触发）。修复为堆上按精确尺寸一次分配、查表 CRC-32 增量计算、逐行喂 `ZLibStream`。回归测试 `EncodesMultiMegabyteIdatOnADefaultStackThread` 在 1 MiB 栈线程上覆盖。
- **输出字节一致**：`ImageCodecEncodeTests` 的 4 组 SHA-256 golden 值，用旧编码器独立重算后逐个相同（12404 / 2329 / 70 / 2175 字节）。去重、像素摘要回声守卫依赖的 PNG 字节没有漂移。
- **不再重复哈希**：`MediaBlobStore.Commit` 用 `PendingMediaWrite` 流式累积的 SHA-256（覆盖的正是写入临时文件的全部字节）代替整读再哈希；`TryInspectFileHeader` 只读头部并保留大小 / 魔数 / 像素预算 / `expectedBytes` 门；哈希不匹配、非图片、MIME 不符仍按原错误码拒绝（`MediaBlobStoreTests` 新增用例）。`ImageThumbnail.DecodeBounded` 只需尺寸，改用头部检查后 UI 线程不再对整个 blob 做 SHA-256。
- **`ReplayWindow`** 直接对字符串 UTF-16 内存做 SHA-256：相等判定语义不变（两帧字符串相等 ⇔ 摘要相等），省掉每帧一次 UTF-8 重编码。唯一差异是含孤立代理项的两帧此前在 UTF-8 替换后可能被判「相同」，现在判「冲突」——这类帧已被严格扫描拒绝，达不到此处。
- 越界提示：`ClipSync.App/Media/ImageThumbnail.cs` 一行改动不在本轮列出的归属清单内，但属媒体层而非 UI 层（`ViewModels/**`、`MainWindow*` 等未触及），且是消除 UI 线程整文件哈希的直接收益，保留并在此记录。

### 2.3 存储基线（`SqliteClipboardEventStore`，未改动）

| 操作 | 10 k 行 | 50 k 行 |
|---|---|---|
| 最近 2000 条（UI 默认） | 6.3 ms | 9.7 ms |
| 最近 50 条 / offset 5000 | 0.38 / 3.0 ms | 0.62 / 4.9 ms |
| 文本检索（罕见词 / 常见词 limit 2000） | 6.2 / 8.8 ms | 44.4 / 9.0 ms |
| `FindLiveContentByHash` 命中 / 未命中 | 0.15 / 0.12 ms | 0.14 / 0.14 ms |
| `GetSyncableEvents` 200 条 / `GetOutboxBatch` 64 | 0.75 / 0.77 ms | 0.81 / 0.65 ms |
| `ApplyPeerAckRanges` / 软删除 / `CleanupAsync` | 0.32 / 2.4 / 4.0 ms | 0.28 / 2.7 / 4.1 ms |
| `StoreAsync` ×100（本地捕获写入） | 214 ms（~2 ms/条） | 222 ms |

`EXPLAIN QUERY PLAN`（检索）：`SEARCH c USING INDEX clips_visible_history_idx (deleted_at=?)` → `SEARCH m USING INDEX sqlite_autoindex_clip_media_1 (event_id=?) LEFT-JOIN` → `USE TEMP B-TREE FOR RIGHT PART OF ORDER BY`。索引命中，最近 N 条不随行数增长；`ORDER BY` 尾键的临时 B 树见 §4。

## 3. Android（`e750c6b`）

| 场景 | 数据规模 | 改前 | 改后 | 改动位置 |
|---|---|---|---|---|
| `ProtocolStrictJson.scan` 分片帧 | 350 K 字符 | 1.13 ms | 0.30 ms | `protocol/ProtocolStrictJson.kt`：逐字符 UTF-8 计数替代 `toByteArray`；值就地校验不进 `StringBuilder` |
| `ProtocolJson.parseEnvelope` 分片帧 | 同上 | 5.71 ms | 1.95 ms | `protocol/Base64Url.kt`：字母表 + 算术长度替代正则 + 丢弃式解码 |
| `SyncWire.decode` 分片帧 | 同上 | 6.02 ms | 1.93 ms | 上两项叠加 |
| 接收链路 decode + `tryDecodeChunk` | 每片 | 5.95 ms | 2.17 ms | 分片只解码一次 |
| `scan` / `decode` 文本帧 | 600 KiB 文本 | 1.96 / 5.43 ms | 0.73 / 2.64 ms | 同 `ProtocolStrictJson` |
| `MediaBlobStore` begin/append/commit | 4 MiB PNG | 10.98 ms | 6.38 ms | `media/MediaBlobStore.kt` 复用流式哈希；`ImageCodec.tryInspectFileHeader` |
| `MediaBlobStore.commitBytes` | 4 MiB | 13.54 ms | 8.70 ms | 同上 |
| `ImageCodec.tryInspectFile` | 4 MiB | 4.20 ms | 3.65 ms | 头部检查与哈希分离 |
| `ImageCodec.hashBytes` | 4 MiB | 2.76 ms | 2.14 ms | `media/HexEncoding.kt` 查表替代 32 次 `String.format` |
| `encodeBase64Url` / `tryDecodeChunk` / `ImageChunks.split` | 256 KiB / 4 MiB | 0.24 / 0.23 / 4.2 ms | 0.45 / 0.36 / 6.0 ms | **未改动**，差异为 JVM 噪声（5–20 次迭代） |

等价性测试：`ProtocolFastPathEquivalenceTest`（base64url 20 000 随机串对照 `java.util.Base64`；帧级接受/拒绝；UTF-8 计数对照编码器含孤立代理项与空串；严格扫描的 13 条拒绝用例含转义/原始混合代理对与转义后重名属性）、`MediaBlobStoreCommitTest`（流式哈希 = 文件哈希；伪造哈希 / 非图片仍拒绝；头部检查与整文件检查字段一致）。

接替说明：前一代理未提交的快照里 `sync/SyncEngine.kt`（`ReplayWindow` 改存字节摘要）、`sync/InMemorySyncRepository.kt` 与 `storage/HistoryTransfer.kt`（十六进制查表）不在本轮 Android 归属范围或属冷路径，已回退到基线，列入 §4。

## 4. 看过但本轮未做的候选

| 候选 | 理由 |
|---|---|
| Android `SyncEngine.ReplayWindow` 摘要改存 `ByteArray`（省每帧 64 字符 hex） | `sync/SyncEngine.kt` 不在本轮归属；收益微小（每帧一次 32 字节格式化） |
| Android `ProtocolStrictJson.utf8ByteCountExceeds` 与 `SyncLimits.utf8BytesExceed` 合并 | 语义相同，但合并要么改 `sync/SyncMessages.kt`（越界），要么让 `protocol` 反向依赖 `sync`；留待 sync 层开放时统一 |
| Android `ProtocolJson.sha256` / `HistoryTransfer.sha256Hex` / `InMemorySyncRepository` 改用 `toLowerHex` | 每次调用只省约 10 µs（32 次 `String.format`），且前两者一处越界一处冷路径 |
| Windows 检索 `ORDER BY … origin_seq DESC, …` 尾键的临时 B 树 | 最近 2000 条 6–10 ms 且不随行数增长；补覆盖索引要改 schema（v5 迁移），收益不成比例 |
| `LIKE '%…%'` 文本检索 50 k 行 44 ms | 子串语义无法用 B 树；FTS5 是功能级改动 |
| Windows/Android base64url 规范性差异（Windows 拒绝非零尾位，Android 沿用 `java.util.Base64` 的宽松接受） | 早于本轮存在，不是性能问题；两端都不改线格式，记录以备协议工作流处理 |
| `ImageChunks.split`（Android）| 已是一次 `copyOfRange` + 一次编码，无明显浪费 |

## 5. UI 层建议（只建议，不实现）

Windows（WPF）
- 历史列表已虚拟化 + 容器回收（首轮）；下一步是 `MainViewModel.RefreshAsync` 改增量 diff（按 `EventId` 复用 `HistoryItemViewModel`），避免每次刷新重建全部条目并触发全列表重绑。
- `FilePathToImageConverter` 的缩略图解码仍在绑定线程：改为 `BitmapImage` 异步下载 / 后台预解码到 `Freeze()` 后的 `BitmapSource`，或在 `ImageThumbnail` 产出时直接缓存 128px 版本。
- 托盘弹窗 4 条固定条目无需处理。

Android（Compose）
- 历史 `LazyColumn` 已有 `key = { it.eventId }`，日期格式化已 `remember`，`format` 分类已在 `HomeClipItem` 预计算——这三项核实过，无需再做。可补 `contentType`（文本 / 图片两种模板）让容器复用更精确。
- 列表项缩略图用 `produceState(contentHash)` 每次进入组合都重新解码：加一层 `LruCache<contentHash, ImageBitmap>`（几十项 × ≤512px），滚回时不再重复解码。
- `HomeUiState` 把 `query` / `formatFilter` 与 `items` 放在同一个 `data class` 里：输入框每个字符都让整个 `HomeScreen` 重组；条目本身因稳定 key + 不可变 `HomeClipItem` 会被跳过，但顶部栏与列表容器仍每键重组。可拆成两个 `StateFlow` 或用 `derivedStateOf` 只让搜索栏跟随 `query`。
- `Grain` / 渐变背景改用 `Modifier.drawWithCache` 缓存画刷；这属实现细节，不触碰纲领的视觉承诺（颗粒、阴影层级、动效曲线原样）。

## 6. 验证

| 套件 | 结果 |
|---|---|
| `windows: dotnet build ClipSync.sln -c Debug` | 0 warning 0 error（`TreatWarningsAsErrors` + `AnalysisLevel latest-recommended` + `AnalysisModeSecurity=All`） |
| `windows: dotnet test`（Debug） | ClipSync.Tests 690 通过 / 5 PerfFact 跳过；ClipSync.App.Tests 339 通过 |
| `android: ./gradlew testDebugUnitTest detekt ktlintCheck assembleDebug` | 通过；850 测试 0 失败 6 跳过（含 3 个 PerfTest）；detekt / ktlint 基线未改，因插入行导致基线行号失配的既有格式违例按 ktlint_official 就地修正 |
| `python scripts/validate-protocol.py` | 通过 |
