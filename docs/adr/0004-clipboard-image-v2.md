# ADR 0004：静态剪贴板图片走协议 v2 与内容寻址 blob

- 状态：接受
- 日期：2026-08-20

## 背景

文本第一版把协议 v1、`clips.content` 和 JSON text frame 冻成纯文本契约。Stage 9 需要同步当前剪贴板项中的静态栅格图片，但不能把 v1 的 `kind=text` 放宽、不能把大图片写入文本字段、也不能把任意 URI 当长期正文。

## 决策

- 图片只作为剪贴板当前项中的静态栅格媒体。MVP 支持 `image/png` 和 `image/jpeg`。Windows 额外从 `CF_DIBV5` / `CF_DIB` 转成 PNG；Android 从 `ClipData` MIME 或 `content://` 流式物化。SVG、HTML、脚本、GIF 动图、视频、HEIC/HEIF、文件 URI、文件夹和图库同步不进入本版本。
- 同一剪贴板状态同时有文本和图片时，优先产生一个通过校验的图片事件；图片不合格时才回退为文本事件，不拆成两个事件。
- 协议 v2 使用 `X-Protocol-Version: 2`、`/v2/peer/sync` 和 `version: 2` envelope。连接必须声明 `image_clip_v2` 才能发送图片正文。认证 transcript 绑定协议版本。已声明支持图片的连接不得降级成 v1 后继续发图片。
- v1 schema、v1 fixtures 和 `/v1/peer/sync` 保持冻结。v1 peer 继续同步文本；图片只产生持久化终止标记：线上发送 `unavailable` + `reason=local_only`，本机把真实原因记为 `unsupported_media`。
- 图片正文仍用 WebSocket JSON text frame，走 `clip_payload_begin` → `clip_payload_chunk*` → `clip_payload_end`。分块是无 padding 的 base64url；单块解码后最多 256 KiB；单图/单批编码最多 16 MiB。二进制 WebSocket 帧留给未来 v3。
- 大字节不进 `clips.content`。编码文件按 SHA-256 内容寻址，先写临时文件，校验 MIME 魔数、尺寸、编码字节和 hash 后再原子改名。数据库用 `media_blobs` + `clip_media` 保存引用。
- `content_hash` 是实际存储/传输的编码字节 SHA-256，不是文件名、URI 或解码像素摘要。像素摘要只用于本机回环抑制。
- MVP 默认保留通过校验的原始 PNG/JPEG 字节（含 EXIF 等元数据）。后续隐私模式再评估去 EXIF 或重新编码。
- 图片自动同步默认关闭；`auto_apply_images` 与文本自动应用独立。图片读/写能力不得从文本 `READY` 推断。

## 限制

- 单张编码最多 16 MiB，解码后最多 32 MP，任一边最多 8192 px。超限只能 `local_only` 或 `drop`，不得静默截断。
- 同时下载最多 2 个；未完成下载保留 24 小时；缩略图最长边 512 px。
- ACK 不得早于 blob 引用与事件同一事务提交。
- 没有实体机证据的图片 backend 不得标 `READY`。

## 后果

优点：文本 peer、旧数据库和 v1 fixtures 不受影响；图片有独立生命周期、限流和终止语义。

代价：两端必须同时理解 v2；混连时图片只推进游标不传正文；Windows DIB 重新编码后字节 hash 会变，回环抑制不能只靠编码 hash。

## 被否决的方案

- 把 `kind=image` 塞进协议 v1 或放宽现有 fixtures。
- 把 PNG/JPEG 当 data URI 或文本 payload 发送。
- 通用文件传输、图库同步或任意 MIME 转发。
- 在 v2 偷渡二进制 WebSocket 帧。
