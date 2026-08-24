# Android 10+ 后台剪贴板能力基线

状态：阶段 0 设计与验证骨架。本文不宣称任何 ROM 已通过实体机验证；当前记录见 [device-validation-matrix.md](device-validation-matrix.md)。

## 平台约束

Android 10 起，后台应用通常不能读取剪贴板；当前有焦点的应用和默认输入法属于系统例外。前台服务可以维持网络连接和调度，但不会自动获得剪贴板读取权限。写入与读取必须分开探测：AOSP 通常允许无焦点应用调用公开写入 API，但锁屏和 OEM 行为可能不同。

因此 ClipSync 将“检测复制变化”与“读取正文”拆开，并始终保留无特殊权限的手动路径。

## 四档读取能力

| 优先级 | 模式 | 变化信号 | 正文读取 | 用户前提 | 失败处理 |
|---:|---|---|---|---|---|
| 1 | `SHIZUKU_EVENT` | Shizuku UserService 注册 `IOnPrimaryClipChangedListener` | 通过系统 `IClipboard` Binder | 安装/启动 Shizuku并明确授权 | Binder/UserService 死亡后重探测；按策略降级 |
| 2 | `ADB_LOG_OVERLAY` | `READ_LOGS` 下识别 `ClipboardService` 信号 | 已授权的透明 overlay 短暂获取焦点 | 用户执行明确 adb 授权并开启 overlay | 未实际匹配信号不得标 `READY`；未知格式降级 |
| 3 | `OVERLAY_POLLING` | 800–1,000 ms 轮询比较哈希 | 同一透明 overlay 短暂获取焦点 | 用户明确开启 overlay；通知和电池策略按引导设置 | 息屏/锁屏暂停；失败降频或转手动 |
| 4 | `FOREGROUND_ONLY` | 分享、磁贴或打开 App | 公开 `ClipboardManager` | 无特殊权限 | 永久可用的手动出口 |

默认首选 Shizuku。用户可选择允许自动降级或仅提醒；应用不得在未获用户许可时偷偷启用 overlay。

## 写回能力

`ClipboardWriteCoordinator` 始终先尝试公开 `ClipboardManager.setPrimaryClip()`。只有公开写入被系统/OEM 拒绝、丢弃或因锁屏策略失败时，才可尝试已经授权的 Shizuku 或 overlay 回退。读取 backend 变化不得关闭仍可用的公开写入。

远端内容必须先进入 Android 收件箱。自动写回不可用时，事件保持“未应用”状态并提供通知或历史条目，让用户在前台复制。

## 能力状态与切换

读取协调器至少保存：

- `requested_read_mode`
- `active_read_mode`
- `auto_fallback_allowed`
- `last_error_code`
- `last_health_at`

`CapabilityReport` 分开报告 `read_state` 与 `write_state`，并包含系统版本、授权状态、最近成功时间和稳定错误码；不得包含正文、原始 logcat、目标应用名或命令输出。

切换事务顺序固定为：停止旧 listener → 释放旧 overlay → 刷新当前内容哈希 → 启动新 backend → 写入 mode epoch。失败时回滚到已知可用模式或 `FOREGROUND_ONLY`，以防切换被误判为新复制。

## Overlay 安全约束

- 空闲窗口为 1x1、alpha 0、`TYPE_APPLICATION_OVERLAY`。
- 默认同时使用 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`。
- 读取瞬间只移除 `FLAG_NOT_FOCUSABLE`，始终保留 `FLAG_NOT_TOUCHABLE`，最多短重试后立即恢复。
- 息屏、Keyguard 锁定、全局暂停或服务不健康时停止轮询并释放焦点。
- 如果某 ROM 只有移除 `FLAG_NOT_TOUCHABLE` 才能读取，该 backend 必须标为不支持。
- 不从后台启动透明 Activity，不采集触摸、键盘、屏幕或其他应用 UI。

## 权限与恢复

- `READ_LOGS` 不能通过普通运行时权限对话框授予。bootstrap 脚本只显示状态并打印可复制的授权/撤销命令，默认不执行。
- 授权存在不等于能力健康；ADB 模式必须实际匹配变更信号。
- Shizuku、`READ_LOGS`、overlay、通知和电池优化分别显示用途、风险、状态与恢复动作。
- 安装、升级、重启、权限撤销和 ROM 策略变化后重新 probe。
- 网络状态、进程/服务状态、后台读取状态和后台写回状态必须分别展示。

## 进程死亡与恢复

同步前台服务对进程死亡的恢复契约（plan 5.2）：

- **`START_STICKY` 仅作为补充**：`ClipboardSyncService.onStartCommand` 只在前台提升成功后返回 `START_STICKY`，让系统在内存回收杀进程后择机重建服务。前台提升被系统拒绝（如 OEM 的 FGS-from-background 策略）时返回 `START_NOT_STICKY`、停止服务并发出“需要恢复”通知——不崩溃、不进入粘性重启循环、不伪造在线状态。
- **可靠状态只在 Room**：来源序号、outbox、ack cursor 全部落库，服务不依赖任何内存队列。粘性重启后 `launchSyncStack` 从零重建整个栈（`SyncSupervisor`、网络回调、retention 清理循环），启动时的 outbox drain 追平服务不在期间排队的分享/磁贴条目；网络恢复触发的立即重连回调也随之重新注册。
- **单一数据库句柄**：进程内唯一的 Room 句柄由 `SyncStore` 单例持有并懒加载。服务侧 `ClipboardSyncService.repositoryProvider` 构建的 `RoomSyncRepository` 包装 `SyncStore.repository()`，UI 侧（`MainActivity` 的历史 gateway 与 retention 清理）同样只经 `SyncStore.repository()` 取句柄；进程重建后由第一个调用方重新创建，之后全进程共享。Room 的 invalidation tracker 只通知注册在同一实例上的观察者，第二个句柄会让历史界面看不到引擎的写入——因此生产代码禁止直接调用 `ClipSyncDatabase.build`（仅 `SyncStore` 内部与测试可用）。已审计：当前生产代码中 `ClipSyncDatabase.build` / `Room.databaseBuilder` 均只有 `SyncStore`（经 `ClipSyncDatabase`）这一条路径，无重复句柄。

## 已知 ROM 差异（待验证）

以下均为待测假设，而不是兼容声明：

- AOSP/Pixel 是公开 API 与 ClipboardService 行为的基线。
- OneUI、MIUI/HyperOS、ColorOS/OriginOS 可能改变 logcat 标签、后台存活、锁屏写入和 overlay 焦点行为。
- 部分 OriginOS 版本可能无法通过已知日志格式检测复制。
- Shizuku 在设备重启后可能需要用户恢复运行或重新授权。

所有组合当前均为 `NOT_TESTED`；只有记录 ROM/API、权限前提、错误码、复现步骤与 P95 数据后才能声明支持。

## 依据

官方基线：

- [Android 10 clipboard privacy changes](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data)
- [Foreground service type: connected device](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [Android 14 foreground service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required#connected-device)
- [Android 15 foreground service changes](https://developer.android.com/about/versions/15/behavior-changes-15#boot-completed-fgs-launch-restrictions)
- [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [`TYPE_APPLICATION_OVERLAY`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)
- [AOSP WindowManager flags](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/WindowManager.java)
- [AOSP Android 10 ClipboardService](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/services/core/java/com/android/server/clipboard/ClipboardService.java)

固定版本的第三方行为参考（只用于研究，不复制未经许可的代码）：

- [KDE Connect ClipboardListener (`ddaa9d9`)](https://github.com/KDE/kdeconnect-android/blob/ddaa9d93804364f34ec8ab5ad467b019337866b6/src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardListener.kt)
- [KDE Connect ClipboardFloatingActivity (`ddaa9d9`)](https://github.com/KDE/kdeconnect-android/blob/ddaa9d93804364f34ec8ab5ad467b019337866b6/src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardFloatingActivity.java)
- [ClipShare Android clipboard notes (`a5a7fa3`)](https://github.com/aa2013/ClipShare/blob/a5a7fa389f412dd25e09264f82fe84030b01b3f8/README.md#android-%E5%89%AA%E8%B4%B4%E6%9D%BF%E7%9B%91%E5%90%AC%E8%AF%B4%E6%98%8E)
- [UniClipboard Android access (`cc64c8a`)](https://github.com/UniClipboard/UniClipboard/blob/cc64c8acb92bbad5e72f0457a9efb1bfab7885ed/docs-site/content/docs/zh/mobile/android-access.mdx)

开始阶段 5 前必须重新核对官方行为、当前 `targetSdk` 与以上固定引用，并在本文记录核对日期。
