# 阶段 6 变更记录

日期：2026-08-17（代码面）/ 2026-08-18（实机验证轮，见文末）
状态：**代码面完成，全部 JVM/单测绿；MIUI 单机实机验证通过主链路与故障注入**。基线提交 `f436520`（阶段 5 wave 2）。本阶段用 Grok 4.6 多子代理并行完成，编排端合并、消解冲突、收口。

- Android `:app:testDebugUnitTest`：**411 tests / 0 failures / 1 skipped**（skipped 为门控 E2E 用例本体）；`assembleDebug` 成功；`detekt` / `ktlintCheck` 基线通过（exit 0）。
- Windows Debug：**0 警告 0 错误**（`TreatWarningsAsErrors` + .NET analyzers 开启）；Tests **191/191**、App.Tests **33/33**。
- 协议 fixtures：`validate-protocol.ps1` 通过（12 valid + 15 invalid 协议、5 valid + 7 invalid 配对，含新增 rate-limit fixture）。

## 阶段目标（plan.md 阶段 6）

可靠性、安全和隐私硬化：输入校验、参数化查询、密钥保护、剪贴板不入日志、Shizuku/overlay/READ_LOGS 最小权限审计、回环压测、一键暂停/清空、黑名单、迁移与导出、故障注入、静态分析、SQLCipher 评估。

## 构建环境

与阶段 4/5 相同（.NET 8.0.419 在 `D:\paste-tools\dotnet`；Android SDK 在 `D:\paste-tools\android-sdk`；JDK 17）。新增：`androidx.work:work-runtime-ktx:2.10.5`；Detekt 1.23.8 + ktlint-gradle 14.2.0（仅 `detekt`/`ktlintCheck` 任务，不挂在 `assembleDebug`/测试上）；`.NET analyzers`（`windows/Directory.Build.props`）。一键静态分析：`pwsh scripts/static-analysis.ps1`。

## 多 agent 协同方式

按 `docs/stage-6-contract.md` 的文件所有权并行，编排端串行收口：

- **Wave A（4 个并行子代理，Grok 4.6）**：Agent W（Windows 传输输入硬化）、Agent P（Android 统一捕获策略引擎 + 黑名单 + 清空提示）、Agent L（1000 次回环/幂等压测）、Agent A（三项安全审计 + 迁移导出设计 + 不变量测试）。
- **Wave B1（3 个并行子代理）**：Agent O（overlay/健康 4 项违规修复）、Agent Q（rate-limit 错误码在协议/schema/Android 的一致性）、Agent M（迁移/导出脚手架）。
- **Wave B（1 个子代理）**：Agent S（WorkManager 开机有界健康检查 + Shizuku 写回退接线 seam）。
- **Wave B2（1 个子代理）**：Agent T（静态分析工具链 + 基线 + 漏洞扫描）。
- **编排端**：接 Shizuku 写回退 provider（MainActivity 一行）、复核并消解跨代理编译竞态（`RoomSchemaContractTest` 等中途红跑都是并行竞态，最终全量绿）、处置 CVE、写审计与本记录。子代理并行跑 Gradle/dotnet 会互相看到未完成的编译产物；只以合并后的全量重跑为准。

## 交付内容

### Windows 传输输入硬化（Agent W，plan 6 校验项）

- 文本大小 1 MiB、JSON 深度 16、WebSocket 帧 7 MiB 上限**原已存在**；补齐了 announce/payload 大小与深度拒绝的测试。
- **新增按 IP 滑动窗口限流** `SlidingWindowRateLimiter`（可注入时钟，无新依赖）：`POST /v1/pair/confirm` 10 次/分钟/IP → HTTP 429 `PAIRING_RATE_LIMITED`；同步 WebSocket accept 30 次/分钟/IP → HTTP 429 `RATE_LIMITED`（不升级）。失败的配对尝试也计入。
- Windows Tests 169 → **184**（Agent M 又 +7 到 191）。

### rate-limit 错误码一致性（Agent Q）

- `protocol/v1/pairing.schema.json` error enum 增加 `PAIRING_RATE_LIMITED`，新增 valid fixture。
- Android `PairingErrorCodes.RATE_LIMITED`；`PairingConfirmClient` 显式处理 HTTP 429（`pairing_error` 体或 `{"error":"RATE_LIMITED"}` → `Denied(PAIRING_RATE_LIMITED)`，不再落到泛化 protocol violation）；`PairingViewModel` 映射到独立 `PairingFailure.RATE_LIMITED`，`PairingScreen` 给出用户可读提示。
- `docs/protocol-v1.md` 记录两个限流阈值与新错误码。

### Android 统一捕获策略引擎 + 黑名单（Agent P，plan §3.4 + 阶段 6）

- 新增 `storage/CapturePolicy.kt`（纯 JVM）：输入 sourceApp、UTF-8 字节数、策略快照（paused/private/黑名单开关/用户黑名单）；输出放行或稳定拒绝码。
- **在 `ClipRepository.captureLocalText` 内部强制**——所有本地 backend（share/tile/foreground/Shizuku）的唯一写入路径都经过它，被拒绝的捕获不分配 `origin_seq`、不写 outbox。远端 `ingestRemoteClip` 不受影响。
- 内置黑名单（精确、区分大小写，12 项：Bitwarden/LastPass/1Password/KeePass2Android×2/Dashlane/KeePassDX/Proton Pass/Google&Microsoft&Authy Authenticator/Samsung Pass），内部来源标签 `share`/`shizuku`/`tile`/`qs_tile` 与 null 永不匹配包规则。
- 用户可加黑名单：设置项 `capture_blacklist_enabled`（默认开）、`capture_blacklist_extra`（逗号分隔，忽略空白/含空格项）。新增 `CaptureRejectReason.BLOCKED_SOURCE`/`POLICY_PAUSED`；share 阻断有独立 toast。
- History 清空按钮下新增提示：只清本机历史，不远程删除 Windows。

### 回环/幂等压测（Agent L，plan 6）

- `e2e/LoopSuppressionStressTest`（1000 次本地捕获→ack 清 outbox→远端 ingest→`InboundClipApplier` 经写协调器应用→`shouldSuppressCapture` 恰好吞一次回声）：末态 `search("").size==2000`、两个 origin 序列连续、outbox 清空、无带 source 的回声行。
- `ModeSwitchIdempotencyTest`（100 次模式切换：切换后同内容不回调、新哈希只落一次、`modeEpoch` 严格自增）。
- `AckIdempotencyTest`（重复 ackRanges 不腐蚀 outbox/游标）。全部非门控，~1.3s。

### overlay/健康硬化（Agent O，修 Agent A 审计 4 项违规）

1. **overlay 同意未强制** → `overlayConsented` 为 false 时，`OVERLAY_POLLING`/`ADB_LOG_OVERLAY` 从选择与回退中剔除（即便已授 `SYSTEM_ALERT_WINDOW`）；前台/Shizuku 不受影响。
2. **overlay 窗口从不移除** → 新增 `OverlayFocusController.detach()`（幂等、同锁），在协调器 `stop()`、backend stop、模式切走、`MainActivity.onStop()/onDestroy()` 调用；始终不丢 `FLAG_NOT_TOUCHABLE`。
3. **无生产健康周期** → `ClipboardHealthLoop`（10s，resumed 才跑，onStop 取消）周期调用 `checkHealth()`，撤销授权在一个周期内降级。
4. **撤销后仍轮询读取** → overlay 轮询在 `OVERLAY_PERMISSION_MISSING`/健康 FAILED 时停止调度并 detach，重新探测通过后才恢复。

### 迁移与导出脚手架（Agent M，plan 6）

- Android：`ClipDatabase` `exportSchema=true` + `room.schemaLocation`（生成 `app/schemas/.../1.json` 已入库）；`VERSION=1` + `MIGRATIONS=emptyArray()` + `addMigrations` + 不加破坏性回退 + “版本号不带 Migration 上调即违约”契约测试。
- Windows：`SqliteClipboardEventStore` 保留 `PRAGMA user_version`（当前 `SchemaVersion=2`，因同步 schema 已是 v2）+ 有序迁移 seam（缺步抛错，绝不新建库丢历史）；re-init 幂等。
- 双端 `ClipExport.kt` / `ClipboardExport.cs`：从公开只读类型编码 JSON Lines（键序固定，含正文），文档头明示导出含明文正文、仅限用户触发、绝不写日志。字段缺口（`kind`/`deleted_at`/`terminal_reason` 不在冻结公开类型上）已记录。
- 设计文档 `docs/stage-6-migration-export.md`。

### WorkManager 开机健康检查 + Shizuku 写回退（Agent S + 编排端）

- `androidx.work:work-runtime-ktx:2.10.5`；`BootHealthCheckWorker`（唯一 OneTimeWork，默认 androidx.startup 初始化，无 manifest 改动）+ 纯函数 `BootHealthCheck.decide`：**cap=3**，`runAttemptCount<3` 未运行→retry；到顶仍未运行→置“需要恢复”+复用既有恢复通知；崩溃→failure，绝不无限重启。
- Shizuku 写回退：`ClipServices.writeCoordinator` 用 `DeferredWriteFallback` 占位（`probe()` 在 provider 未就绪时为 `UNAVAILABLE`），编排端在 `MainActivity` 用 `ClipServices.writeFallbackProvider = { (clipboardBackends.shizuku as? ShizukuClipboardBackend)?.fallbackWriter() }` 接线，复用读 backend 的同一 Shizuku 运行时（不新建第二条连接）。**公开写永远第一位**：仅当回退 `probe()==READY` 才调用，Shizuku 未授权时行为与纯公开写完全一致（JVM 测试固化）。

### 静态分析（Agent T，plan 6）

- Detekt 1.23.8（`config/detekt/detekt.yml` + `baseline.xml` 基线 249 项）、ktlint-gradle 14.2.0（基线 1822 项，未全库重排版）；均为独立任务，不阻断测试构建。
- .NET analyzers：`AnalysisLevel=latest`、`AnalysisMode=Recommended`、`AnalysisModeSecurity=All`，CA2100/CA5350/CA5351/CA3075 强制为错误；构建仍 0 警告。真实修复：两处 outbox SQL 改字面量 + 绑定 `$state`（CA2100）、P/Invoke `DefaultDllImportSearchPaths(System32)`（CA5392）；窄范围注释豁免：占位符 SQL 的 CA2100、故意 TLS1.2/1.3 pin 的 CA5398。
- `scripts/static-analysis.ps1` 一键跑 detekt+ktlint+.NET 构建+漏洞扫描。

## 安全审计结论（`docs/stage-6-security-audit.md`）

- Shizuku UserService 最小权限：**PASS**（binder 仅读/写/listener/health/destroy，不收网络、不放密钥、不执行任意 shell、不记正文）。
- overlay：flags/尺寸/不可触摸 **PASS**；生命周期 4 项违规已由 Agent O 修复并测试固化。
- READ_LOGS 数据最小化：**PASS**（仅内存、不落盘/上传/入 CapabilityReport，未匹配不触发，bootstrap 不代授）。
- 剪贴板正文日志泄漏 grep（双端）：**无命中**。

## 依赖漏洞（已接受/跟踪）

`SQLitePCLRaw.lib.e_sqlite3` 2.1.6（经 `Microsoft.Data.Sqlite` 8.0.25 传递）报 CVE-2025-6965（SQLite < 3.50.2 内存腐败，High）。整个 2.1.x 线均受影响，唯一修复是 3.x native 大版本（对 MS.Data.Sqlite 8.0.x 属不受支持的破坏性升级）。**本应用不可达**：所有 SQL 均第一方、固定 schema、全参数化；peer 输入是校验过的 JSON 再映射到绑定参数，无攻击者可控 SQL 进入 SQLite。已在 csproj 内联跟踪，待 MS 发布补丁 8.x native 或验证 SQLitePCLRaw 3.53.3 后升级。详见安全审计文档 E 节。

## plan.md 阶段 6 任务对照

| 任务 | 结论 |
|---|---|
| 敏感设置/令牌用 DPAPI/Keystore | **done**（阶段 3/4 交付；审计未发现回归） |
| 校验文本大小/JSON 深度/WS 帧/连接速率 | **done**（Agent W；限流为新增） |
| 参数化查询 | **done**（审计 + CA2100 修复两处拼接） |
| 剪贴板不进日志/异常/崩溃转储/遥测 | **done**（审计 grep 无命中） |
| Shizuku UserService 最小权限审计 | **done**（PASS + 反射不变量测试） |
| overlay 安全与可用性审计 | **done**（4 违规已修 + 测试） |
| READ_LOGS 数据最小化审计 | **done**（PASS + 不变量测试） |
| 每 backend 1000 次回环压测 | **done**（Agent L，JVM） |
| 一键暂停 + 一键清空本机历史 + 提示 | **done**（暂停在设置/通知；清空 + 不远程删除提示） |
| 密码/银行/自定义黑名单 | **done**（Agent P；内置 12 项 + 用户可加） |
| 数据库迁移与导出格式 | **done（脚手架）**（版本机制 + JSONL 导出 + 设计文档；真实 Migration 步骤待首次升版） |
| Windows 睡眠/唤醒、Android 切网、任一 peer 重启故障注入 | **partially（实机 2026-08-18）**：Windows 进程重启、手机 Wi‑Fi 断开恢复、手机整机重启均恰好一次恢复；Windows 睡眠/唤醒未测 |
| 静态分析（.NET analyzers、Ktlint/Detekt、依赖扫描） | **done** |
| SQLCipher 评估 | **deferred**（plan 允许；见迁移文档，native 构建风险，不阻塞 MVP） |

## plan.md 阶段 6 验收对照

- 断网 30 分钟恢复只出现一次：**NOT_TESTED**（需实机/故障注入；回环幂等已在 JVM 固化）。
- 任一端崩溃重启不损坏数据库：**partially**（Windows re-init 幂等 + 迁移 seam 有测试；杀进程后磁盘完整性需实机）。
- 撤销/清空/过期/黑名单有自动化测试：**done**。
- 安全测试不能伪造设备 ID/旧令牌/错证书读取：**done**（阶段 3/4 配对与 pin 测试 + 本阶段限流）。
- 撤销 Shizuku/overlay/READ_LOGS 后一个健康周期内更新：**done（Activity 前台）**；仅 FGS 存活时的撤销降级待未来 service 侧健康循环。

## 实机验证轮（2026-08-18，Redmi Note 11T Pro / MIUI 14 / API 33）

手机重启过一次（Shizuku 需重跑 `start.sh`，符合预期）。安装含全部阶段 6 改动的 debug APK 后逐项验证，全部只核对 content hash，不读正文：

| 项目 | 结果 |
|---|---|
| 回声抑制修复（阶段 5 遗留缺陷） | **PASS**：Windows→Android 恰好 1 行（远端 ingest），6 秒窗口后 **0 条 `shizuku` 回声行**（修复前为重复行） |
| 双向同步回归 | **PASS**：两个方向均 ~1 秒、恰好一次 |
| 5.7 自测按钮 | **PASS**：`Test background read: OK · SHIZUKU_EVENT`（真实 Shizuku binder 往返随机令牌）；`Test background write: OK · PUBLIC_API`；令牌未落库、未上传 |
| Status 页 | **PASS**：Network=Connected，无 unreachable 残留 |
| 故障注入：Windows 进程重启 | **PASS**：杀掉再启动 Windows 端，首次复制立即到达，恰好一次 |
| 故障注入：手机 Wi‑Fi 断开→恢复 | **PASS**：`svc wifi disable/enable`，恢复后 11 秒重连到达，恰好一次 |
| 整机重启 + 开机恢复 | **代码侧 PASS，MIUI 投递受限**：`BootCompletedReceiver` 已启用注册，但 MIUI 默认无「自启动」权限，BOOT_COMPLETED 从未投递（开机 70 秒后无进程/无通知，WorkManager 检查因此无从触发）。**兜底路径 PASS**：打开应用即恢复 FGS，随后双向 ~1 秒恰好一次。要真正开机自启需用户在 MIUI 设置手动授予自启动 |
| Shizuku 13.5.4 | 授权在重启+重装后保留；重启后需 `adb shell sh .../start.sh` 重新拉起（既有已知项） |

仍未测：Windows 睡眠/唤醒、断网 30 分钟长恢复、多 ROM、正式 P95 统计、MIUI 授予自启动后的 BOOT_COMPLETED→WorkManager 路径。

## 已知限制 / 交接

- **故障注入与实机验收全部 NOT_TESTED**：睡眠/唤醒、切网、peer 重启、断网 30 分钟、杀进程磁盘完整性、多 ROM P95——需要人工插机。
- **Shizuku 写回退、WorkManager 开机恢复**：逻辑 + JVM 测试就绪，真实行为需 Shizuku 授权设备与真实重启验证。
- **服务侧健康循环缺失**：撤销授权在仅 FGS 存活（Activity 不在前台）时，靠下次 resume 才降级。
- **迁移仅脚手架**：真实 `Migration(1,2)` / Windows v3 步骤、导出的 tombstone 字段与 header、导入、SAF/保存对话框未做。
- **SQLite CVE 已接受**：见上；不可达但仍在扫描里显示为 High。
- **静态分析基线庞大**（detekt 249 / ktlint 1822）：新代码受检，存量未整改；后续可分批清理基线。
- 本阶段全部改动**已提交为一个 commit**（用户要求分阶段打检查点）。
