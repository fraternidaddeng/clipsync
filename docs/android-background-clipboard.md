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

## 阶段 5 前核对（2026-08-17）

核对日：2026-08-17。工程仍为 `minSdk 29`、`targetSdk 35`。以下以当日 `developer.android.com` 页面为准；第三方仓库只核 HTTP 是否仍能打开固定 commit，不据此改写平台基线。相对 [plan.md](../plan.md) 8.1–8.2 的假设，**官方约束未出现会推翻 wave-1 设计的变化**。

### 官方约束

| 核对项 | 来源 | 相对 plan.md 的结论 | 阶段 5 影响 |
|---|---|---|---|
| Android 10+ 后台剪贴板读取 | [Privacy changes in Android 10 — Limited access to clipboard data](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data)；旁证 [Secure clipboard handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling) | **未变。** 仍写明：除非应用是默认 IME 或当前拥有焦点，否则 Android 10+ 不能访问剪贴板。前台服务本身不构成读取例外。Android 12 起读取会弹出系统 toast、Android 13 起系统会定时清空剪贴板，这是附加隐私行为，不是放宽后台读取。 | 四档读取与“检测/读正文拆开”仍必要。FGS `connectedDevice` 只保活网络与调度，不能标为后台可读。 |
| FGS 类型 `connectedDevice` 前提 | [Foreground service types — Connected device](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device) | **未变。** 须声明 `foregroundServiceType="connectedDevice"`、`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`，并满足至少一个运行前提；清单声明 `CHANGE_NETWORK_STATE` 仍在官方许可列表中（另有 Wi-Fi/NFC/IR、蓝牙运行时权限或 USB 授权等路径）。用途仍是与外部设备的蓝牙/NFC/IR/USB/**网络**交互。 | wave-1 用 `CHANGE_NETWORK_STATE`（及 `ACCESS_NETWORK_STATE`）满足网络前提仍然成立。缺类型/缺权限须捕获异常，不得崩溃。 |
| Android 14 FGS 类型强制 | [Foreground service types are required](https://developer.android.com/about/versions/14/changes/fgs-types-required#connected-device)；[Changes to foreground services — Android 14](https://developer.android.com/develop/background-work/services/fgs/changes) | **未变。** `targetSdk >= 34` 必须在清单声明类型并申请对应权限；`startForeground()` 未声明类型抛 `MissingForegroundServiceTypeException`；缺类型权限抛 `SecurityException`。类型权限为 normal、默认授予、用户不可撤销。推荐 `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`。 | 与 Agent I 设计一致：显式传类型、覆盖缺类型/缺权限异常路径。 |
| Android 15 `BOOT_COMPLETED` 启动 FGS | [Android 15 — Restrictions on BOOT_COMPLETED broadcast receivers launching foreground services](https://developer.android.com/about/versions/15/behavior-changes-15#boot-completed-fgs-launch-restrictions)；兼容框架 [FGS_BOOT_COMPLETED_RESTRICTIONS](https://developer.android.com/about/versions/15/reference/compat-framework-changes) | **未变，且允许名单写明含 `connectedDevice`。** 禁止从 `BOOT_COMPLETED` 启动的类型为：`dataSync`、`camera`、`mediaPlayback`、`phoneCall`、`mediaProjection`、`microphone`（后者自 14 起）。尝试启动这些类型抛 `ForegroundServiceStartNotAllowedException`。兼容变更明确**仍允许** `location`、`connectedDevice`、`remoteMessaging`、`health`、`systemExempted`、`specialUse`。`connectedDevice` 类型页本身没有“禁止从 boot 启动”的注记（对比 `dataSync`/`mediaPlayback` 等页有注记）。 | `boot_recovery` 继续用 `connectedDevice` 从开机广播拉起 FGS，官方基线仍允许。失败须降级为通知请求、禁止崩溃循环；OEM 与未来 target SDK 仍须实测，不能把官方允许写成 ROM 保证。 |
| Android 16 / 17 是否收紧 boot 类型 | [Android 16 targeting changes](https://developer.android.com/about/versions/16/behavior-changes-16)、[Android 16 all-apps changes](https://developer.android.com/about/versions/16/behavior-changes-all)；[FGS changes — Android 16](https://developer.android.com/develop/background-work/services/fgs/changes)；[Android 17 targeting](https://developer.android.com/about/versions/17/behavior-changes-17)、[Android 17 all-apps](https://developer.android.com/about/versions/17/behavior-changes-all) | **官方未再收紧 `BOOT_COMPLETED` 的 FGS 类型名单。** Android 16/17 均已有公开行为变更页。16 的 FGS 相关变化是：与 FGS 并发的 Job/WorkManager 开始计入 job 配额。17 的 FGS 相关变化是后台音频收紧，与剪贴板/boot 类型名单无关。两版均未把 `connectedDevice` 列入 boot 禁止类型，也未新增后台剪贴板读取限制。 | 不改变 `boot_recovery` 设计。当前 `targetSdk 35` 仍走 Android 15 那份允许名单。日后若升到 36/37，须再核官方页；17 的 `ACCESS_LOCAL_NETWORK` 只在 target 37 时影响局域网发现，不属于本阶段剪贴板能力。 |
| `POST_NOTIFICATIONS` 与 FGS | [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) | **未变，措辞需准确。** Android 13+ 对“非豁免通知（**包括 FGS 通知**）”引入该运行时权限。拒绝后应用不能往通知栏发普通通知；FGS 相关提示仍出现在 Task Manager，但**不会出现在通知栏**。文档没有把“可以不带通知启动 FGS”写成合法路径：`startForeground()` 仍须提供 Notification。权限本身不是启动 FGS 的前提。 | 拒绝 `POST_NOTIFICATIONS` 不得阻止用户开启后台同步；应用内状态须说明“服务在跑、通知栏不可见”。不要把 FGS 通知写成完全豁免。 |
| Overlay 类型与焦点/触摸 flag | [`TYPE_APPLICATION_OVERLAY`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)、[`FLAG_NOT_FOCUSABLE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_FOCUSABLE)、[`FLAG_NOT_TOUCHABLE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE) | **未变。** Overlay 仍需 `SYSTEM_ALERT_WINDOW`，位于普通 Activity 之上、状态栏/IME 之下。`FLAG_NOT_FOCUSABLE` 使窗口永不取得按键焦点（事件落到后面的可聚焦窗口）。`FLAG_NOT_TOUCHABLE` 使窗口永不接收触摸。Android 12+ 对“穿过 `FLAG_NOT_TOUCHABLE` 窗口的触摸”有安全过滤：`TYPE_APPLICATION_OVERLAY` **不是** trusted window；`alpha == 0` 的全透明窗口属于允许穿透的情形之一。 | 空闲 1×1、`alpha 0`、默认 `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCHABLE`，读取瞬间只去掉 `FLAG_NOT_FOCUSABLE`、始终保留 `FLAG_NOT_TOUCHABLE`，与官方语义一致。某 ROM 若必须去掉 `FLAG_NOT_TOUCHABLE` 才能读，仍标不支持。 |

相邻、不改变 wave-1 基线的官方点：

- Android 15 收窄了“持有 `SYSTEM_ALERT_WINDOW` 即可从后台启动 FGS”的豁免：target 15+ 还须**已经有可见**的 `TYPE_APPLICATION_OVERLAY` 窗口，否则抛 `ForegroundServiceStartNotAllowedException`（见 [Android 15 行为变更](https://developer.android.com/about/versions/15/behavior-changes-15) 中 SYSTEM_ALERT_WINDOW 一节）。`BOOT_COMPLETED` 是另一条独立豁免，不依赖 SAW。wave-1 由用户在 Settings 前台开启服务，不受该收窄影响；wave-2 overlay 不得假定“有悬浮窗权限就能从后台拉起 FGS”。
- `BOOT_COMPLETED` 从 Android 12 起仍是后台启动 FGS 的例外之一（[Restrictions on starting a FGS from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)），再叠加 Android 15 的类型白名单。

### 固定第三方引用（仅核 HTTP）

plan.md 8.2 的四份固定引用，按 pinned commit 做 `HEAD`，只记录 HTTP 成败，不核对许可证或行为是否仍最优。

| 引用 | 固定 URL | HTTP |
|---|---|---|
| KDE Connect ClipboardListener（`ddaa9d9`） | https://github.com/KDE/kdeconnect-android/blob/ddaa9d93804364f34ec8ab5ad467b019337866b6/src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardListener.kt | 200 |
| KDE Connect ClipboardFloatingActivity（`ddaa9d9`） | https://github.com/KDE/kdeconnect-android/blob/ddaa9d93804364f34ec8ab5ad467b019337866b6/src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardFloatingActivity.java | 200 |
| ClipShare README（`a5a7fa3`） | https://github.com/aa2013/ClipShare/blob/a5a7fa389f412dd25e09264f82fe84030b01b3f8/README.md | 200 |
| SyncClipboard Mobile clipboardProxy / overlay / Shizuku（`05f3bfe`） | https://github.com/Jeric-X/syncclipboard-mobile/blob/05f3bfe750aca7041941290460dd3d61190fe048/src/utils/clipboardProxy.ts ；https://github.com/Jeric-X/syncclipboard-mobile/blob/05f3bfe750aca7041941290460dd3d61190fe048/modules/clipboard-overlay/android/src/main/java/expo/modules/clipboardoverlay/ClipboardOverlayModule.kt ；https://github.com/Jeric-X/syncclipboard-mobile/blob/05f3bfe750aca7041941290460dd3d61190fe048/modules/shizuku-clipboard/android/src/main/java/expo/modules/shizukuclipboard/ShizukuClipboardModule.kt | 三份均为 200 |
| UniClipboard Android 访问文档（`cc64c8a`） | https://github.com/UniClipboard/UniClipboard/blob/cc64c8acb92bbad5e72f0457a9efb1bfab7885ed/docs-site/content/docs/zh/mobile/android-access.mdx | 200 |

这些仓库只证明“检测与读取分离、Shizuku / overlay / 公开 API 回退”等组合曾经存在。官方文档仍决定基线；任一模式仍须过实体机矩阵后才能标 `READY`。

### 对 wave-1 设计的结论

| 设计 | 是否被本次官方核对推翻 |
|---|---|
| FGS `connectedDevice` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + `CHANGE_NETWORK_STATE` | 否 |
| `BOOT_COMPLETED` 恢复（设置项默认关、失败降级通知） | 否；`connectedDevice` 仍在 Android 15 允许名单，16/17 官方页未再收紧 |
| Shizuku 事件读取 / 写回回退 | 否；平台仍禁止普通后台读剪贴板，Shizuku 仍是用户授权的特权路径，不是系统例外 |
| Overlay `TYPE_APPLICATION_OVERLAY` + 默认双 flag、读取时只撤 `FLAG_NOT_FOCUSABLE` | 否；与官方焦点/触摸语义及 Android 12+ 穿透规则一致（wave-2 实现） |

本次核对**不**把任何 ROM 标为已验证。
