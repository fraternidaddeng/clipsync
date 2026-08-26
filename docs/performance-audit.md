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
