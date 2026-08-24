# ClipSync History Export Format v1

Status: Stage 6 hardening deliverable (plan.md 阶段 6「设计数据库迁移和导出格式；升级不能静默丢历史」).
Changes to published fields or semantics require a new `format_version` and readers that still accept v1.

## 1. Purpose and scope

The export file is a device-local backup of the clipboard event history. It exists so that:

- upgrades, reinstalls, and machine moves never silently lose history;
- a user can carry history between their own Windows and Android installs;
- import is a **merge**, idempotent on the event identity `origin_device_id + origin_seq`, so importing
  the same file twice — or importing on a device that already holds part of the history through normal
  sync — never duplicates events.

The export contains **events only**. It is not a pairing or settings backup and never includes secrets:

- no pair secrets, tokens, challenges, or auth proofs;
- no TLS certificates, private keys, or certificate fingerprints;
- no device table rows (display names, trust epochs, last-seen times);
- no peer cursors, outbox rows, or settings.

The file **does** contain clipboard text in the clear — that is its purpose. Both clients must say so in
the UI next to the export action, and the file must never be written to logs or telemetry.

## 2. Container: JSON Lines

- Encoding: UTF-8, no BOM. Suggested extension: `.jsonl`.
- One JSON object per line, separated by `\n`. Writers must not pretty-print; readers must ignore empty
  trailing lines.
- Line 1 is the **header** record. Every following non-empty line is one **clip** record.
- Streaming-friendly by construction: both clients read and write line by line and never need the whole
  file in memory at once, though the current implementations may buffer for transactional import.

JSON Lines was chosen over a single JSON bundle because it streams, appends, and survives truncation
inspection trivially, and both clients already ship strict per-object JSON parsers.

### 2.1 Header record

```json
{"type":"header","format":"clipsync-history","format_version":1,"exported_at_ms":1724500000000,"exporting_device_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","platform":"windows","event_count":123}
```

| Field | Type | Rules |
|---|---|---|
| `type` | string | Constant `"header"`. |
| `format` | string | Constant `"clipsync-history"`. Readers reject any other value. |
| `format_version` | integer | Constant `1` for this document. Readers reject other values instead of guessing. |
| `exported_at_ms` | integer | Unix epoch milliseconds when the export was produced. Informational. |
| `exporting_device_id` | string | The local device ID of the exporting install. Informational; not an identity claim. |
| `platform` | string | `"windows"` or `"android"`. Informational. |
| `event_count` | integer | Number of clip records that follow. Readers may use it for progress display; a mismatch with the actual line count fails the import before any write. |

### 2.2 Clip record

```json
{"type":"clip","event_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","origin_device_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","origin_seq":12,"kind":"text","content":"hello","content_hash":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","source_app":"chrome","created_at_ms":1724500000000,"expires_at_ms":null,"deleted_at_ms":null,"terminal_reason":null}
```

| Field | Type | Rules |
|---|---|---|
| `type` | string | Constant `"clip"`. |
| `event_id` | string | UUID, lowercase canonical form. Globally unique per event. |
| `origin_device_id` | string | Device that created the event. With `origin_seq` this is the idempotency key. |
| `origin_seq` | integer | ≥ 1. Monotonic per origin device. |
| `kind` | string | `"text"` in v1. Readers reject unknown kinds. |
| `content` | string or null | The clipboard text. `null` exactly when `terminal_reason` is set. UTF-8 size ≤ 1 MiB (the same cap the sync protocol enforces). |
| `content_hash` | string or null | Lowercase SHA-256 hex of the UTF-8 bytes of `content`. `null` exactly when `terminal_reason` is set. Importers recompute and reject mismatches. |
| `source_app` | string or null | Producing process/app name, if it was recorded. Never present on terminal records. |
| `created_at_ms` | integer | Unix epoch milliseconds of capture (or of terminalization for terminal records). |
| `expires_at_ms` | integer or null | Planned expiry, when one was set. |
| `deleted_at_ms` | integer or null | Non-null exactly when `terminal_reason` is set. |
| `terminal_reason` | string or null | One of `local_only`, `deleted`, `expired`, `policy_filtered`, `not_found`. A terminal record is a tombstone: identity without content. |

Terminal records are exported so an import cannot resurrect events the user deleted, and so sequence
gaps stay explainable after a restore. Their content columns were already erased in the database; the
export mirrors that.

## 3. Export semantics

- Export writes **all** rows of the `clips` table — live and terminal — ordered by
  `origin_device_id, origin_seq` so the output is deterministic for a given database state.
- Export is read-only; it never mutates the database.
- The 1 MiB per-event content cap already holds for every stored row; the exporter asserts rather than
  truncates (truncation would silently change content identity).

## 4. Import (merge) semantics

Import validates the entire file first and applies it in **one transaction** — a malformed file changes
nothing. Validation failures (bad header, unknown `format_version`, malformed line, hash mismatch,
`event_count` mismatch, oversize content) reject the whole file with a stable error, never a partial write.

Per clip record, against the local `clips` table:

1. **Same `(origin_device_id, origin_seq)` exists with the same `event_id`** → skip, count as `skipped`.
   (Live vs. terminal state of the existing row wins; import never revives a local tombstone and never
   erases local content in favour of an imported tombstone.)
2. **Same key exists with a different `event_id`**, or the row is live with a different `content_hash`,
   or the `event_id` exists under a different key → count as `conflict`, do not touch the existing row.
   Conflicts are reported in the result summary; the import still applies all non-conflicting records.
3. **Key absent** → insert the record exactly as exported (live row or terminal marker), count as
   `imported`, and advance the local receive vector (`origin_receive_state.Accept(seq)`) so the
   `known_vector` exchanged with peers stays truthful about what is persisted.

Additional rules:

- If `origin_device_id` equals the importing device's own ID, the local sequence allocator is advanced
  past the highest imported sequence so future local captures can never collide with restored events.
- Import performs **no outbox fan-out and no network sends**. Peers that lack the events discover the
  gap through the normal `known_vector` / `want_ranges` exchange and pull them like any other missing
  range. Restoring a backup must not surprise-broadcast years of history.
- Import never touches devices, pairings, cursors, or settings, because the file cannot contain them.
- The result surfaced to the user is `imported / skipped / conflicts` counts.

## 5. Relationship to database migrations

The export format is intentionally *not* the database schema. Schema migrations (Windows
`PRAGMA user_version` steps, Android Room `Migration` objects) remain the upgrade path; the export file
is the recovery path when a migration cannot run (downgrade, corrupted store, device move). v1 of the
format carries every column both schemas agree on today; columns added by future schema versions must
either be derivable on import or wait for `format_version: 2`.

## 6. Security notes

- Exports are written only where the user explicitly chooses (file save dialog on Windows, Storage
  Access Framework document on Android). The apps never write exports to fixed world-readable paths.
- The clip content in the file is plaintext by design; the UI labels the action accordingly
  (「导出内容为明文，请妥善保管」).
- Import parses with the same strict JSON discipline as the sync protocol (no duplicate keys relied
  upon, strict types, bounded depth) and enforces the 1 MiB content cap before insert, so a crafted
  file cannot smuggle oversized payloads past the protocol limits.
