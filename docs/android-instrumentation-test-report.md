# Android 仪器化测试报告（androidTest）

- 日期：2026-08-24
- 分支：`cursor/implement-charter-ui-1991`
- 范围：本次新增的 Android 仪器化测试套件（`android/app/src/androidTest`），以及在云端 Linux VM 上尝试 `./gradlew connectedDebugAndroidTest` 的完整、诚实的结果记录。

## 新增测试（从 feature/stage-4 移植并适配）

stage-4 血统分支带有两个仪器化测试（`ClipDatabaseMigrationTest`、`ClipDaoSqliteTest`），但它们针对的是 stage-4 的 `ClipDatabase` 类结构。本分支的存储层是 `ClipSyncDatabase` / `ClipEventDao` 等，因此按«移植意图、重写目标»的方式适配：

| 测试类 | 用例数 | 移植来源 | 证明什么 |
|---|---|---|---|
| `storage/ClipSyncDatabaseMigrationTest` | 2 | stage-4 `ClipDatabaseMigrationTest` | ①用已提交的 `schemas/1.json` 建 v1 库、跑真实 `MIGRATION_1_2`、由 Room 逐字段对照 `schemas/2.json` 校验（表、列、`index_clip_media_content_hash` 索引），旧 `clips`/`local_sequences` 行原样保留，新媒体表立即可写；②走生产 `ClipSyncDatabase.build()` 打开 v1 文件（WAL + 全量 MIGRATIONS 数组），DAO 能读出迁移前的行并写入 `media_blobs`。 |
| `storage/ClipSyncDaoSqliteTest` | 3 | stage-4 `ClipDaoSqliteTest` | 对 SQLite 方言敏感的查询在真机 framework SQLite 上运行：`LIKE ... ESCAPE '\'` 转义搜索、`cleanup` 的 `WITH` CTE 与 `LIMIT -1 OFFSET` 截断、outbox JOIN 批次生命周期（pending→announced→reset→按区间 ack 删除）、媒体 GC 子查询（孤儿 `clip_media` 清理解锁 `media_blobs` 回收）。 |
| `sync/ClipboardSyncServiceSmokeTest` | 1 | 新增（stage-4 无此测试） | 生产入口 `ClipboardSyncService.start()` 在真实 system server 上完成 `connectedDevice` 类型前台提升（`RunningServiceInfo.foreground == true` 且无 `FGS_START_DENIED`），整套真实栈（Keystore 配对存取、Room 仓库、无配对的 supervisor）随服务启动；`stop()` 后服务退出运行列表、`serviceRunning`/`connectionStates` 状态流复位。 |

配套构建改动：`app/build.gradle.kts` 新增 `androidTest` 依赖（`androidx.test` runner/rules/core、`room-testing`），并把 `schemas/` 挂为 androidTest assets 供 `MigrationTestHelper` 读取。

## 模拟器执行结果（如实记录）

执行环境：云端 Linux VM（4 vCPU / 15 GiB，内核 6.12.94+），Android SDK cmdline-tools + emulator 37.1.11，AVD 为 `system-images;android-35;google_apis;x86_64`（Android 15，Pixel 6 皮肤，无窗口模式）。

### 嵌套 KVM：失败（宿主内核缺陷，非本仓库问题）

`/dev/kvm` 存在且 `emulator -accel-check` 报告 “KVM (version 12) is installed and usable”，但模拟器创建首个 vCPU 时宿主内核直接触发 BUG，qemu 挂死在 0% CPU，客户机永远不会开始引导：

```text
kernel BUG at arch/x86/kvm/x86.c:702!
RIP: 0010:kvm_spurious_fault+0xe/0x10
  vmx_vcpu_create+0xa7/0x490
  kvm_arch_vcpu_create+0x1e1/0x2f0
  kvm_vm_ioctl_create_vcpu+0x180/0x4f0
```

即：本 VM 对外暴露了嵌套虚拟化标志，但 KVM 实际不可用。硬件加速模拟器在此环境不可能运行。

### 软件模拟（QEMU TCG，`-accel off`）：可引导，但 Gradle 官方任务被启动超时卡死

- 引导：`sys.boot_completed=1` 约需 13 分钟。
- `./gradlew connectedDebugAndroidTest` 共尝试 3 次，全部失败，失败点各有记录：
  1. 第一次：安装即失败 `Can't find service: package` —— 引导风暴中 system_server 崩溃重启（logcat 见 `DeadSystemException: The system died`）。
  2. 第二次：两个 APK 安装成功（约 5.5 分钟），随后 `Test run failed to complete. Instrumentation run failed due to Process crashed`，0 个用例开跑。根因在 logcat：`ActivityManager: Process ... failed to complete startup` → `Killing ... bg anr`。TCG 下冷启动的应用进程赶不上 Android 写死的 10 秒进程 attach 期限；google_apis 镜像自带的 GMS 全家桶同时在 ANR 风暴（`com.android.phone`、`googlequicksearchbox`、`gms.persistent` 接连被杀），进一步抢占本就只有软件模拟的 CPU。
  3. 第三次（系统沉淀后重试）：同样的启动 ANR。原因是 Gradle 每次运行都重装 APK，重装会重置 AOT 编译产物，冷启动路径每次都重新踩同一个 10 秒死线。
- **该失败是纯环境速度产物，不指向任何产品或测试缺陷**：卡死点在测试进程 attach 之前，与被测代码无关。

### 绕行执行：全部 6 个用例在模拟器上通过

规避 TCG 冷启动死线的步骤（全部命令可复现）：

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell cmd package compile -m speed -f com.clipsync.android
adb shell cmd package compile -m speed -f com.clipsync.android.test
adb shell svc bluetooth disable   # 停掉模拟器上崩溃循环的蓝牙栈
# 等 GMS ANR 风暴平息后逐类执行：
adb shell am instrument -w -e class <测试类> com.clipsync.android.test/androidx.test.runner.AndroidJUnitRunner
```

| 测试类 | 结果 | 用时 |
|---|---|---|
| `ClipSyncDatabaseMigrationTest` | **OK (2 tests)** | 17.3 s |
| `ClipSyncDaoSqliteTest` | **OK (3 tests)** | 4.3 s |
| `ClipboardSyncServiceSmokeTest` | **OK (1 test)** | 2.9 s |

即 Room 1→2 迁移、DAO 真 SQLite 行为、前台服务启停冒烟，**6/6 在 Android 15 模拟器（软件模拟）上全部通过**。前台服务测试顺带证明了 API 35 上仪器化进程可以直接做前台提升（无需前台 Activity 兜底）。

### 对 CI / 真机的预期

- 在有真实 KVM 的 runner（GitHub Actions 上 `enable-kvm` 的 Linux runner、macOS runner）或真机上，`./gradlew connectedDebugAndroidTest` 应直接绿：三次失败全部发生在进程启动阶段而非测试体内，且全部用例已在同一对 APK 上通过。
- 本报告不声称整机可用性：仪器化套件覆盖的是存储迁移与服务生命周期边界；剪贴板后端等仍按 `docs/device-validation-matrix.md` 走实体机 QA。

## 同批 JVM 侧结果（保证仪器化改动没有破坏任何东西）

| 检查 | 结果 |
|---|---|
| `./gradlew :app:testDebugUnitTest` | 500 个用例，0 失败 |
| `./gradlew :app:detekt :app:ktlintCheck` | 通过（新测试文件已按 ktlint 格式化） |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | 通过 |
