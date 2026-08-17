# Android 实体机验证矩阵

状态：阶段 0 设备盘点模板。**尚未提供或连接任何实体设备，以下结果全部为 `NOT_TESTED`，不得视为兼容性声明。**

## 状态定义

- `NOT_TESTED`：没有在符合描述的实体设备上执行。
- `READY`：实测满足该 backend 的功能、安全和延迟标准。
- `DEGRADED`：部分功能可用且存在明确降级路径。
- `UNAVAILABLE`：该组合不能安全工作。
- `NEEDS_USER_ACTION`：缺少用户可执行的授权或恢复步骤。

模拟器结果可以补充 API 行为，但不得替代 ROM 覆盖。

## 目标设备组合

| 槽位 | 系统族 | 实体设备/型号 | Android/API | 锁屏策略 | Shizuku | `READ_LOGS` + overlay | Overlay polling | 当前状态 |
|---|---|---|---|---|---|---|---|---|
| D1 | AOSP/Pixel | 待提供 | 待记录 | 待记录 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| D2 | OneUI | 待提供 | 待记录 | 待记录 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| D3 | MIUI/HyperOS | 待提供 | 待记录 | 待记录 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| D4 | ColorOS/OriginOS | 待提供 | 待记录 | 待记录 | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |

如果实际能获得的四台设备不能覆盖四个系统族，必须保留缺口说明，不能用同一 ROM 的多台设备冒充覆盖。

## 每台设备必须记录

- 制造商、型号、ROM 名称/版本、Android 版本、API、补丁级别。
- 安装方式、目标 SDK、通知权限、电池优化、自启动/最近任务锁定状态。
- 屏幕点亮、息屏、Keyguard 锁定与解锁策略。
- Shizuku：未安装、未启动、未授权、已授权、Binder 重启及设备重启后的状态。
- adb：`READ_LOGS` 授予/撤销命令、实际信号匹配、解析器版本；不得保存真实正文或整段 logcat。
- overlay：授权/撤销、窗口创建与释放、始终不可触摸、焦点恢复、读取耗时。
- 网络：同 Wi-Fi、切换网络、断网恢复、杀进程与重启。
- 上行、入站、自动写回、回环抑制、断线补同步和下一步提示。
- 每个声明可用档位的样本数、P50/P95、失败数和稳定错误码。

## 最低验收数据（阶段 5）

| 模式 | ROM 组合数 | 延迟要求 | 额外要求 |
|---|---:|---|---|
| Shizuku event | 至少 2 | Wi-Fi P95 ≤ 1.5 s | 公开 writer 优先；100 次循环不回传 |
| ADB log + overlay | 至少 2 | P95 ≤ 2 s | 授权撤销/未知格式后 10 s 内转 `DEGRADED` |
| Overlay polling | 至少 3 | P95 ≤ 轮询间隔 + 1 s | 无残留窗口、持续焦点或无界唤醒 |
| Foreground/manual | 所有无特殊权限设备 | 功能性验收 | 分享、磁贴、通知复制及断线补同步不被阻塞 |

## 执行记录

目前无实体机执行记录。首个测试开始时，为每台设备新增带日期的记录，包含操作者、构建 commit、前提、步骤、原始计数（不含剪贴板正文）、结论和已知缺口。
