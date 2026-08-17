# 阶段 1 变更记录

日期：2026-08-12  
状态：已完成（Windows 本地剪贴板核心）

## 交付范围

- Win32 `AddClipboardFormatListener` listener 在独立 STA 消息线程运行，读取 `CF_UNICODETEXT`，处理剪贴板占用重试、空/非文本内容和关闭清理。
- 读取使用 `GetClipboardSequenceNumber` 去除同一系统剪贴板版本的重复通知；事件投递到 WPF dispatcher 后才进入 Core 策略和 SQLite。
- Core 策略保留 Unicode、CRLF/LF 和精确正文，执行 UTF-8 1 MiB 限制、2 秒哈希去重、暂停、私密模式、来源进程通配符黑名单和写回抑制。
- SQLite v1 使用 WAL、外键和参数化 SQL；本地序号分配与正文插入在同一事务中，先落库再预留发布接口；删除、清空和保留期限清理保留最小 tombstone。
- WPF 历史列表支持搜索、复制、删除、清空、刷新；托盘菜单支持打开和退出；设置持久化暂停、私密模式、保留天数和来源黑名单。
- 应用启动时只创建后台服务和托盘，不显示主窗口；托盘打开后才显示历史界面。

## 平台实现说明

计划中的“message-only window”不能直接用于 `WM_CLIPBOARDUPDATE`：Windows 的广播路径会排除 `HWND_MESSAGE` 父窗口。当前实现因此使用零尺寸 `WS_POPUP` 顶层 HWND，并设置 `WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE`，不会激活、显示任务栏按钮或打开主窗口。这个行为由真实 Windows smoke 和窗口样式测试覆盖。

## 验证

- `pwsh scripts/build-windows.ps1 -Configuration Debug`：0 警告、0 错误；Core `35/35`、App `22/22`。
- `pwsh scripts/build-windows.ps1 -Configuration Release`：0 警告、0 错误；Core `35/35`、App `22/22`。
- `pwsh scripts/run-windows-stage1-smoke.ps1`：最终复跑捕获延迟 `132 ms`，重启后 `PERSISTED=1`；启动检查保持运行且主窗口句柄为 `0`。
- `pwsh scripts/run-windows-stage1-stress.ps1 -Count 100`：`STORED=100 UNIQUE_SEQUENCES=100`。脚本使用 120 ms 的用户节奏；25 ms 无等待的合成洪泛可能被 Windows 合并广播，不作为用户体验验收。
- Core 单元测试覆盖黑名单、暂停/私密、去重、大小限制、写回抑制和事务故障回滚；Win32 测试覆盖序列号重复通知、Unicode、占用重试、native buffer 边界、生命周期和异常隔离。
- `pwsh scripts/validate-protocol.ps1`：合法 fixture `12` 个、非法/语义边界 fixture `15` 个全部符合预期。
- `git diff --check`：通过。

## 未完成项

阶段 1 不包含配对、HTTPS/WebSocket、远端 outbox 或 Android Room；这些属于阶段 2 及以后。实体 Android ROM 能力仍为 `NOT_TESTED`，不能用 API 35 模拟器结果替代。
