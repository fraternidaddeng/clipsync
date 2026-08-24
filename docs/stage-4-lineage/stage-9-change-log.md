# 阶段 9 变更记录

日期：2026-08-20

状态：**实现中，未提交。图片能力未标 DEVICE-VERIFIED / READY。** 本阶段只扩展静态剪贴板 PNG/JPEG，不改造成通用文件传输。协议 v1 文本合同保持冻结。

## 范围

- 仅当前剪贴板项中的静态栅格图：`image/png`、`image/jpeg`。
- Windows：PNG / `CF_DIBV5` / `CF_DIB`；Android：`ClipData` 图片 MIME 与 `content://`，物化到 app-private blob，不把 URI 当正文。
- 混合文本+图片：优先一个通过校验的图片事件；不合格才回退文本；不拆成两个事件。
- 图片同步默认关闭；`auto_apply_images` 与文本自动应用独立。
- UserService 不传整张 Bitmap。`ProtocolReader.Parse` 仍只吃 v1。
- 没有设备实测的图片 backend 不得标 READY。

## 已落地（代码，未宣称真机可用）

- `docs/adr/0003-clipboard-image-v2.md`、`docs/protocol-v2.md`、`protocol/v2` schema 与 valid/invalid/auth/media fixture。
- Windows SQLite `user_version` 3：`media_blobs` + `clip_media`，add-copy-rename，不 `DROP clips` 当 destructive wipe。
- Android Room `VERSION` 1→2：`MIGRATION_1_2` 只 `CREATE` 两张新表；无 destructive fallback。
- `MediaBlobStore`：tmp `*.part` → 原子提交；内容寻址 SHA-256。
- 协议 v2：`X-Protocol-Version: 2`、`/v2/peer/sync`、`image_clip_v2`、HMAC 前缀 `ClipSync/v2/auth\n` + `0x00 || INT64_BE(2)`。
- 分块：base64url 无填充、256 KiB、16 MiB、最多 2 路下载；ACK 在 blob+clip 同一事务之后。
- v1 peer 遇到图片：`unavailable` + 本地 `local_only` / `unsupported_media`，文本同步继续。
- 导出 `format_version: 2`：仅当导出集含图片时写 header；空库与纯文本导出保持无 header，兼容既有测试。
- Android：FileProvider URI 写回、设置页两个开关、历史复制走 `writeImage`、通知复制按 kind 分流。
- Windows 监听默认 session options 为 v2；入站 `/v1/peer/sync` 仍按 header 落到 v1。Android 生产 dialer 走 v2。
- 相册「发送到 Windows」读取 `ACTION_SEND` `EXTRA_STREAM`（PNG/JPEG），物化后走 `captureLocalImage` + outbox；图片分享失败不回退成 URI/说明文字。图片同步关闭时提示打开设置。
- 两端历史列表和详情显示 512 px 缩略图，不再只有「图片 image/png …」这类文本占位。

## 明确未做 / 不得宣称

- 图片读/写能力未做实体机矩阵，**不得**标 IMAGE_READ/WRITE READY。
- Overlay / ADB / UserService 图片路径仍是文本信号 + 应用内物化；MIUI overlay 图片为 NOT_TESTED。
- 不提交、不推送，除非另行要求。
