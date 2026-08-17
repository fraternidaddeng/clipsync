# 阶段 0 变更记录

日期：2026-08-12  
状态：已完成（2026-08-12）；本文件保留阶段 0 的审计记录。

## 本批文档交付

- 冻结产品范围与明确不做项：`docs/product-scope.md`。
- 建立资产、信任边界、威胁、控制和剩余风险基线：`docs/threat-model.md`。
- 记录 Android 10+ 四档读取能力、独立写回、权限、切换与安全约束：`docs/android-background-clipboard.md`。
- 建立至少四类 ROM 的实体机验证矩阵；所有未执行结果明确为 `NOT_TESTED`：`docs/device-validation-matrix.md`。
- 记录直接 P2P 与原生客户端技术决策：`docs/adr/0001-direct-p2p.md`、`docs/adr/0002-native-clients.md`。
- 选择 MIT 作为项目许可证，并建立当前/计划依赖清单：根目录 `LICENSE`、`THIRD_PARTY_NOTICES.md`。

上述交付分别服务于传输、恢复、捕获以及信任/隐私。未加入账号、云服务、公共 Relay 或文件传输功能。

## 验证状态

本批最初只负责文档和许可证；阶段 0 汇总随后完成协议、代码、脚本和 CI 验收。

- 文档链接和文件存在性：待阶段 0 汇总验证。
- Windows lint/test/build：由阶段 0 汇总执行并记录实际命令、退出码与环境。
- Android lint/test/build：由阶段 0 汇总执行并记录实际命令、退出码与环境。
- 协议 JSON Schema/fixture 校验：由阶段 0 汇总执行。
- Windows/Android 空白窗口手工启动：尚未在本批执行。
- Android 实体机能力验证：`NOT_TESTED`；没有伪造 ROM、权限、锁屏或延迟结果。

所有阶段 0 失败项均已修复并补测试后进入阶段 1。

## 已知限制与后续动作

- 实体设备型号和系统版本尚未提供；四类 ROM 覆盖仍是阶段 5 前的硬性实测要求。
- 阶段 5 开始前必须重新核对 Android 官方限制、当前 `targetSdk` 行为与第三方固定引用，并将核对日期写入 Android 能力文档。
- `THIRD_PARTY_NOTICES.md` 是阶段 0 依赖盘点，不替代发布前基于锁文件/产物生成的完整传递依赖与许可证报告。
- 协议文档和 fixtures 由并行任务维护；协议发布后只能版本化迁移。
## 汇总验证（2026-08-12）

阶段 0 汇总执行结果：

- `pwsh scripts/build-windows.ps1`：通过。`.NET SDK 8.0.419` 使用仓库外显式 PATH 的临时工具目录运行；3 个项目 0 警告、0 错误；xUnit `14/14` 通过。
- `pwsh scripts/validate-protocol.ps1`：通过。`12` 个合法 fixture 被接受，`15` 个非法/语义边界 fixture 被拒绝。
- `git diff --check`：通过。
- Windows 空白窗口：进程启动后保持运行、窗口标题为 `ClipSync`、Windows 报告 `Responding=True`，随后由验收脚本关闭。
- Android SDK Platform 35、Build Tools 34/35 与 Platform Tools 已安装到仓库忽略的临时工具目录，并安装 Microsoft OpenJDK 17.0.20。`pwsh scripts/build-android.ps1` 最终通过：41 个 Gradle 任务完成，Android JVM 单元测试 `16/16` 通过，`assembleDebug` 生成约 24 MiB 的 debug APK。期间修复了 Kotlin DSL 的 `Test.systemProperty` 配置、过期的 `parseToJsonElement` import，以及普通请求范围被错误套用 `received_ranges` 游标约束的问题。
- Android 空白窗口：debug APK 在 API 35 Google APIs x86_64 模拟器完成冷启动；`am start -W` 返回 `Status: ok`，`MainActivity` 为可见的 `topResumedActivity`，crash buffer 没有本应用崩溃。该结果只证明空工程可运行，不计入实体 ROM 或后台剪贴板能力覆盖；四类 ROM 仍为 `NOT_TESTED`。

结论：阶段 0 的仓库、规格、协议、两端工程、自动化构建测试和空白窗口运行验收均已实现，可以进入阶段 1。Android 实体 ROM 能力仍明确为 `NOT_TESTED`，不得用模拟器结果替代。
