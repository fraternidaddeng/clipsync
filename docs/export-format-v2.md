# ClipSync History Export Format v2

Status: image-aware extension of [export-format-v1.md](export-format-v1.md), closing the open P1 item
in `stage-4-merge-gap-audit.md` §2. v1 stays frozen and readable forever; v2 is a strict superset that
adds image clip records. Changes to published fields or semantics require a new `format_version` and
readers that still accept v1 and v2.

## 1. What changed relative to v1

Everything in the v1 document still holds — purpose, JSON Lines container, merge-import semantics,
the events-only / never-secrets rule, and every text-record field. v2 adds exactly three things:

1. `kind` may now be `"image"` (protocol v2 / ADR 0004 clips). v1 readers reject image records by
   design; that is why the version bumps.
2. Live image records carry a `media` object with the blob metadata and, when the encoded bytes are
   available at export time, the bytes themselves embedded as base64 (capped at 16 MiB, the same
   limit protocol v2 and storage enforce).
3. `terminal_reason` gains `unsupported_media` (already legal in both databases and on the v2 wire).

The export still never contains pair secrets, tokens, TLS material, device rows, cursors, outbox
rows, or settings. Image bytes in the file are plaintext exactly like text content is — the UI keeps
the same「导出内容为明文，请妥善保管」warning.

## 2. Version negotiation

- **Writers** emit the lowest version that can represent the database: `format_version: 1` when no
  image rows and no `unsupported_media` tombstones exist (so older builds can still import the file),
  and `format_version: 2` otherwise.
- **Readers** accept `format_version` 1 and 2 and validate each clip record against the rules of the
  header's declared version: a v1 file that carries `kind: "image"`, a `media` object, or an
  `unsupported_media` reason is malformed and rejects the whole file.
- Unknown versions are rejected with `UNSUPPORTED_VERSION`, never guessed at.

## 3. Header record

Identical to v1 except `format_version` may be `2`:

```json
{"type":"header","format":"clipsync-history","format_version":2,"exported_at_ms":1724500000000,"exporting_device_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","platform":"windows","event_count":124}
```

`event_count` counts **all** clip records that follow — text and image, live and terminal.

## 4. Clip records

### 4.1 Text records

Byte-identical to v1. In a v2 file a text record must not carry a `media` member.

### 4.2 Live image record

```json
{"type":"clip","event_id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","origin_device_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","origin_seq":13,"kind":"image","content":null,"content_hash":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","source_app":"mspaint","created_at_ms":1724500000000,"expires_at_ms":null,"deleted_at_ms":null,"terminal_reason":null,"media":{"mime_type":"image/png","encoded_bytes":95,"pixel_width":1,"pixel_height":1,"data_base64":"iVBORw0KGgo..."}}
```

| Field | Type | Rules |
|---|---|---|
| `kind` | string | Constant `"image"`. |
| `content` | null | Always `null`: image events have no text body (mirrors both database schemas). |
| `content_hash` | string | Lowercase SHA-256 hex of the **encoded image bytes** — the content-addressed blob hash. 64 lowercase hex characters. |
| `media` | object | Required on live image records, forbidden everywhere else. |

The `media` object:

| Field | Type | Rules |
|---|---|---|
| `mime_type` | string | `"image/png"` or `"image/jpeg"`. Anything else rejects the record. |
| `encoded_bytes` | integer | 1 … 16 MiB (16,777,216), the protocol-v2 encoded-image cap. |
| `pixel_width` | integer | 1 … 8192. |
| `pixel_height` | integer | 1 … 8192; `width × height` ≤ 33,554,432 pixels. |
| `data_base64` | string, optional | Standard RFC 4648 base64 (with padding) of the encoded image bytes. Omitted on metadata-only records (§5). |

When `data_base64` is present the importer must, before any write:

- decode strictly (invalid alphabet or padding rejects the file);
- require the decoded length to equal `encoded_bytes` and stay within the 16 MiB cap
  (`CONTENT_TOO_LARGE` above the cap, so a crafted file cannot smuggle payloads past the protocol
  limits);
- recompute SHA-256 over the decoded bytes and require it to equal `content_hash`
  (`HASH_MISMATCH` otherwise);
- inspect the magic bytes and header dimensions and require them to match the declared
  `mime_type` / `pixel_width` / `pixel_height` (the same PNG/JPEG inspection the sync ingress runs).

### 4.3 Image tombstone

An image event the origin terminalized (deleted, expired, `unsupported_media`, …) exports as a
tombstone with image identity but no content, exactly parallel to text tombstones:

```json
{"type":"clip","event_id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd","origin_device_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","origin_seq":14,"kind":"image","content":null,"content_hash":null,"source_app":null,"created_at_ms":1724500002000,"expires_at_ms":null,"deleted_at_ms":1724500002000,"terminal_reason":"deleted"}
```

Same rules as a v1 tombstone (`content`, `content_hash`, `source_app` null; `deleted_at_ms` set),
plus: no `media` member. Tombstones carry no blob metadata — the bytes were already erased in the
exporting store, and the export mirrors that. Importing an image tombstone never resurrects bytes,
and an imported tombstone never erases a live local row (v1 rule 1 unchanged).

`terminal_reason` in v2 is one of `local_only`, `deleted`, `expired`, `policy_filtered`,
`not_found`, `unsupported_media` — for text and image records alike (`unsupported_media` markers for
image announces are recorded on text-kind rows by the current stores; that is why its presence alone
forces `format_version: 2`).

## 5. Metadata-only image records

The exporter embeds the encoded bytes whenever the blob file is present on disk and within the
16 MiB cap. When the bytes are unavailable (blob file missing or unreadable), it writes the record
**without** `data_base64` — a metadata-only record: identity, hash, MIME, dimensions, size, but no
pixels.

Import of a metadata-only record restores the event row and the blob metadata, and marks the
event-to-blob link `missing` unless a blob with the same hash already exists locally (content
addressing makes that check exact). The entry keeps its place in history and its sequence stays
covered by the receive vector; the bytes themselves are not recoverable from this file. Peers do
not re-send events the vector already covers, so a metadata-only import is an inventory/tombstone
restore, not a byte restore — the honest trade documented here rather than silently dropping the
event (which is what v1 did for every image).

## 6. Export semantics (delta over v1 §3)

- Export writes **all** rows of the `clips` table — text and image, live and terminal — ordered by
  `origin_device_id, origin_seq`.
- For each live image row the exporter joins the blob metadata (`media_blobs` via `clip_media`) and
  reads the encoded bytes from the content-addressed blob store; a missing or unreadable blob file
  degrades that record to metadata-only instead of failing the export.
- Export remains read-only and never mutates the database or the blob store.

## 7. Import (merge) semantics (delta over v1 §4)

Whole-file validation before a single write, one transaction, idempotent on
`(origin_device_id, origin_seq)`, no outbox fan-out, receive vector advanced, own-sequence allocator
bumped — all unchanged. Per live image record that is actually inserted:

1. If the record embeds bytes, commit them into the content-addressed blob store (idempotent by
   hash; the store re-validates MIME magic, dimensions, size, and hash on commit exactly like the
   sync ingress), then write the `media_blobs` metadata row and a `ready` event-to-blob link.
2. If the record is metadata-only, write the `media_blobs` metadata row (never downgrading an
   existing `ready` row) and link the event `ready` when the blob already exists locally, `missing`
   otherwise.
3. Blobs committed inside an import that later rolls back are unreferenced files with fresh
   timestamps; the ordinary blob GC (with its grace period) reclaims them.

Error codes are the v1 set, applied to images as described in §4.2. There are no new codes.

## 8. Compatibility matrix

| File | v1 reader (old build) | v2 reader (this change) |
|---|---|---|
| v1, text only | imports | imports (validated under v1 rules) |
| v2, text only | rejected (`UNSUPPORTED_VERSION`) — writers avoid this by emitting v1 when possible | imports |
| v2 with images | rejected (`UNSUPPORTED_VERSION`) — correct: it cannot represent image records | imports; embedded bytes restored, metadata-only records restored without bytes |
| v1 claiming `kind: "image"` | rejected (`MALFORMED_RECORD`) | rejected (`MALFORMED_RECORD`) |
