# feature/stage-4 谱系记录（移植存档）

移植日：2026-08-24。本文件夹是 `feature/stage-4` 分支（阶段 4–9 开发谱系）关键文档的**逐字存档**，供本分支（Charter UI 主线）做特性移植、实机行为参考和 DoD 对照时查证。

**重要：本文件夹描述的是另一条代码谱系。** 其中的测试计数、commit hash（如 `f436520`、`eff1a4a`）、类名（如 `ClipDatabase`、`Strings.cs`）、以及「已完成 / DEVICE-VERIFIED」结论都属于 `feature/stage-4` 分支的构建，**不代表本分支已落地同等能力**。本分支自己的实机状态以 [`../device-validation-matrix.md`](../device-validation-matrix.md) 为准。

## 内容清单

| 文件 | 内容 | 对本分支的价值 |
|---|---|---|
| `stage-4-change-log.md` | Android 伴侣端（Room、WebSocket 客户端、通知/分享/磁贴）交付记录 | 移植 capture/service/tile 特性时的行为契约参考 |
| `stage-5-change-log.md` | 后台剪贴板能力（Shizuku / ADB 日志 / overlay 轮询）+ MIUI 14 首轮实机 | 后台读取模式设计与 ROM 差异的一手依据 |
| `stage-6-change-log.md` | 可靠性/安全/隐私硬化 + 实机故障注入 + P95 延迟验收 | 硬化项清单与验收线（A→W p95 0.37 s） |
| `stage-7-change-log.md` | 打包发布链（package-release / install / uninstall 脚本、distribution 文档） | 后续做分发链时的范式 |
| `stage-8-change-log.md` | 内置特权宿主（免官方 Shizuku）、0.2.0 签名、真机复测 | SHIZUKU_EVENT 免依赖方案与 MIUI 授权细节 |
| `stage-9-change-log.md` | 剪贴板图片同步（协议 v2，PNG/JPEG）范围与限额 | 图片移植 agent 的范围基准 |
| `dod-status.md` | 第一版 Definition of Done 逐条审计（2026-08-18） | 验收方法论：什么算实机证据、什么不许外推 |
| `device-validation-matrix.md` | 回填后的实机矩阵：Redmi Note 11T Pro / MIUI 14 全部 DEVICE-VERIFIED 行、模拟器轮、诚实 FAIL 结论 | 唯一的真机行为数据（MIUI 剪贴板拒绝、Shizuku 版本坑、开机恢复、P95） |

## 使用规则

- 引用这里的结论时必须注明「stage-4 谱系」，不得抄进本分支的验收文档冒充本分支实测。
- 文档内的 `docs/stage-N-change-log.md` 一类路径指 stage-4 分支上的原始位置；同名文件已随本文件夹一起存档。
- 本文件夹只增不改：如 stage-4 谱系有新记录，整文件重新移植，不在存档上手改。
