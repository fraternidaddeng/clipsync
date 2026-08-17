# Stage 6 database migration and local export design

Date: 2026-08-17
Status: design only — next stage implements. No production code in this wave.
Applies to: Android Room `ClipDatabase` and Windows `SqliteClipboardEventStore`.

This document is the implementable contract for versioning, upgrades that must not silently drop history, a user-triggered local export, and a SQLCipher evaluation. It describes *this* schema, not a generic app.

---

## 0. Current state (as of Stage 5 / base `f436520` + wave work)

### 0.1 Android Room

`android/app/src/main/java/com/clipsync/android/storage/ClipDatabase.kt`:

- `@Database(..., version = 1, exportSchema = false)`
- Entities: `clips`, `outbox`, `origin_receive_state`, `peer_cursors`, `local_sequences`, `settings`
- `persistent()` uses `Room.databaseBuilder` with **no** `fallbackToDestructiveMigration()` and **no** `Migration` objects
- `inMemory()` is tests only

`clips` (`ClipEntities.kt` `ClipEntity`) already has the export-relevant columns:

| Column | Kotlin field | Notes |
|---|---|---|
| `event_id` | `eventId` | PK |
| `origin_device_id` | `originDeviceId` | unique with `origin_seq` |
| `origin_seq` | `originSeq` | |
| `kind` | `kind` | default text |
| `content` | `content` | nullable; soft-delete writes `''` |
| `content_hash` | `contentHash` | |
| `source_app` | `sourceApp` | |
| `created_at` | `createdAt` | epoch ms |
| `expires_at` | `expiresAt` | |
| `deleted_at` | `deletedAt` | |
| `terminal_reason` | `terminalReason` | `local_only` / `deleted` / `expired` / `policy_filtered` / `not_found` |

Soft-delete (`ClipDaos.kt`) clears `content`, `content_hash`, and `source_app` when setting `deleted_at` / `terminal_reason`. Export of those rows must not invent a body.

Room today: bumping `version` without a registered `Migration` throws `IllegalStateException` on open (destructive fallback is **not** enabled). That is the correct fail-closed default. `exportSchema = false` means there is no checked-in schema JSON to diff.

### 0.2 Windows SQLite

`windows/ClipSync.Core/Storage/SqliteClipboardEventStore.cs`:

- `CurrentSchemaVersion = 2` (line 10)
- On `InitializeAsync`, reads `PRAGMA user_version` (lines 70-74)
- If `user_version` **>** `CurrentSchemaVersion`, throws `InvalidOperationException` (lines 75-79) — newer file is not opened
- Ordered steps inside a serializable transaction (lines 86-103):
  - `user_version < 1` → `CreateBaselineSchemaAsync` (`clips`, `local_sequences`, `settings`)
  - `user_version < 2` → `ApplySyncSchemaAsync` (`terminal_reason` on `clips`, plus `devices`, `origin_receive_state`, `peer_cursors`, `outbox`)
  - then `PRAGMA user_version = CurrentSchemaVersion`
- Rollback on failure; no `DROP TABLE clips`
- Soft-delete / expire also clear `content` and `content_hash`

Windows already has the versioning pattern Android still needs. Future Windows steps must keep the same “append-only migrations, fail if newer, never wipe `clips`” rule.

---

## 1. Versioning and migration policy

### 1.1 Shared rules (both sides)

1. **History is durable.** A schema upgrade must not `DROP TABLE clips`, truncate `clips`, or enable a destructive fallback that recreates an empty history. Outbox / cursor / receive-state tables may be rebuilt only via an explicit, reviewed migration that copies or reconstructs rows; default is `ALTER TABLE` / `CREATE TABLE IF NOT EXISTS`.
2. **Fail closed on unknown future schemas.** If the file’s version is newer than the running binary, refuse to open (Windows already throws). Do not auto-downgrade. Tell the user to install a build that understands that version, or restore a backup.
3. **Fail closed on missing migration.** If the file’s version is older and no step exists for that gap, refuse to open. Do not skip to “create fresh”.
4. **One step per version integer.** Version N → N+1 is a named, reviewable function. Never jump by applying “whatever the latest CREATE looks like” on an existing file.
5. **Additive first.** Prefer nullable new columns with backfill. Breaking column remames go through add-copy-drop in one transaction, keeping `event_id` / `(origin_device_id, origin_seq)` identity.
6. **Soft-deleted rows stay.** Migrations must not `DELETE FROM clips WHERE deleted_at IS NOT NULL` as a “cleanup”. Tombstones are sync protocol state.
7. **Secrets stay out of `clips`.** Pair secrets, TLS material, and Keystore/DPAPI blobs live in `devices` / platform stores, not in exportable clip rows. Migrations must not copy those into `clips`.
8. **No silent repair that drops rows.** Integrity checks may quarantine a corrupt file (rename aside + user prompt). They must not open an empty replacement under the same path without an explicit user action.

### 1.2 Android — Room `Migration` + `exportSchema = true`

Implement in a later stage, in this order:

1. **Turn on schema export** before the first real bump:
   - `@Database(..., version = 1, exportSchema = true)`
   - Gradle `ksp` / `room { schemaDirectory(...) }` pointing at `android/app/schemas/` (or `android/schemas/`)
   - Commit the generated `1.json` so CI can run `androidx.room:room-testing` `MigrationTestHelper`
2. **Keep `version = 1` until a real column/table change exists.** Do not bump solely to “enable export”.
3. **When version becomes 2+**, add:
   ```kotlin
   val MIGRATION_1_2 = object : Migration(1, 2) {
       override fun migrate(db: SupportSQLiteDatabase) {
           // only additive SQL; never DROP clips
       }
   }
   Room.databaseBuilder(...)
       .addMigrations(MIGRATION_1_2)
       // do not call fallbackToDestructiveMigration()
       .build()
   ```
4. **Register every adjacent pair** (`1→2`, `2→3`, …). Room will chain them. Do not add `fallbackToDestructiveMigrationFrom(1)`.
5. **Tests (next stage):** `MigrationTestHelper` creates v1 from the exported JSON, inserts at least one visible clip row and one tombstone, runs `MIGRATION_1_2`, asserts both `event_id`s and the visible body still exist. A second test opens a v1 file with no migration registered and expects the open to fail (guards against someone adding destructive fallback).
6. **In-memory test DB** (`ClipDatabase.inMemory`) can stay without migrations; it is always created at the current entity version.

Suggested first Android bump (only when a column is actually needed): add a table `schema_meta(user_version INTEGER)` is **not** required — Room’s `version` is the source of truth. Do not invent a second version counter.

### 1.3 Windows — `PRAGMA user_version` + ordered steps

Keep the existing skeleton in `InitializeAsync`. For version 3+:

```text
if (schemaVersion > CurrentSchemaVersion) throw
in one transaction:
  if (schemaVersion < 1) CreateBaselineSchema
  if (schemaVersion < 2) ApplySyncSchema
  if (schemaVersion < 3) ApplyV3Async   // new
  PRAGMA user_version = CurrentSchemaVersion
commit
```

Rules for each `ApplyVnAsync`:

- `ALTER TABLE clips ADD COLUMN ...` or `CREATE TABLE` for new satellites
- Parameterized SQL only (already the house style)
- No `DROP TABLE clips`
- After adding a non-null column, `UPDATE` backfill in the same transaction
- Bump `CurrentSchemaVersion` in the same change that adds the step
- Unit test: build a temp file at `user_version = 1` (see existing `SqliteSyncStoreTests` pragma pattern), insert a clip, run `InitializeAsync` on current code, assert the row and `PRAGMA user_version = CurrentSchemaVersion`
- Unit test: set `user_version` to `CurrentSchemaVersion + 1` and assert initialize throws

Do not replace this with “if tables missing, CREATE ALL” on an existing file. That path is only for `schemaVersion < 1` (empty / new file).

### 1.4 Cross-client compatibility

Android Room version and Windows `user_version` are **independent**. They do not need to be equal. The sync protocol already versions envelopes; DB versions are local.

If a future column must be synced, add it to the protocol first (new optional JSON field), then add a local column via migration on each side. Export (below) is a local snapshot, not a wire format.

---

## 2. Local export format

### 2.1 Warning (must appear in UI and file header)

Exports contain clipboard **bodies in plaintext**. They are a user-triggered backup / move-to-new-device tool, not a log, not telemetry, and not an automatic share.

Required product copy (implement later):

- Confirmation dialog before write: the file will include the text of every non-tombstone clip on this device.
- Default path is a user-chosen location (SAF on Android, `SaveFileDialog` on Windows). Never write an export next to the live DB as a side effect of upgrade, sync, or crash.
- After write, do not log the path’s file name if it might include user content; a stable event tag (`export_completed` / `export_failed_{type}`) is enough.
- Do not attach exports to crash reports.

### 2.2 File shape

- Encoding: UTF-8, no BOM
- First line: a JSON **header** object (not a clip)
- Following lines: one JSON object per clip row (**JSON Lines**)
- Extension: `.jsonl` (or `.clipsync.jsonl`)
- No pretty-print (one object per line)

Header (line 1):

```json
{
  "format": "clipsync.export",
  "format_version": 1,
  "exported_at": 0,
  "origin_device_id": "",
  "platform": "android",
  "contains_plaintext_bodies": true
}
```

- `exported_at`: epoch milliseconds
- `platform`: `android` | `windows`
- `contains_plaintext_bodies`: always `true` for format_version 1 so importers and users cannot miss the warning
- Do **not** put pair secrets, certificate PEMs, or DPAPI/Keystore blobs in the header

Each subsequent line is one event:

```json
{
  "event_id": "",
  "origin_device_id": "",
  "origin_seq": 1,
  "kind": "text",
  "content": "",
  "content_hash": "",
  "source_app": null,
  "created_at": 0,
  "expires_at": null,
  "deleted_at": null,
  "terminal_reason": null
}
```

Field rules:

| Field | Source | Notes |
|---|---|---|
| `event_id` | `clips.event_id` | UUID string as stored |
| `origin_device_id` | `clips.origin_device_id` | |
| `origin_seq` | `clips.origin_seq` | integer ≥ 1 |
| `kind` | `clips.kind` | first version: `text` only |
| `content` | `clips.content` | plaintext; empty string if tombstone already cleared the body |
| `content_hash` | `clips.content_hash` | hex SHA-256 of the **current** `content` bytes; empty if body was cleared |
| `source_app` | `clips.source_app` | JSON `null` if unset |
| `created_at` | `clips.created_at` | epoch ms |
| `expires_at` | `clips.expires_at` | epoch ms or `null` |
| `deleted_at` | `clips.deleted_at` | epoch ms or `null` |
| `terminal_reason` | `clips.terminal_reason` | `null` or one of `local_only`, `deleted`, `expired`, `policy_filtered`, `not_found` |

Include tombstones (rows with `deleted_at` / `terminal_reason`) so a restore can keep sync identity. Their `content` will usually already be empty because both sides wipe the body on delete/expire.

Sort: `origin_device_id ASC`, `origin_seq ASC` for stable diffs.

Do not export `outbox`, `peer_cursors`, `devices`, or settings. Those are device-local sync / trust state. A later “move device” feature can add a separate, secret-bearing archive with its own warning; it is out of scope here.

### 2.3 Triggers and API sketch (next stage)

- Android: Settings action → SAF `CreateDocument` → `ClipDao` query all rows (including deleted) → write lines on `Dispatchers.IO`. Gate on an explicit button, not wizard finish, not boot.
- Windows: Settings / File menu → `SaveFileDialog` → `SELECT` all `clips` columns → write lines.
- Progress: count of lines written, never a preview of `content`.
- Failure: delete the partial file if the write did not finish; do not leave a truncated JSONL that looks complete (write to `*.tmp` then replace).

Import is **not** required for this design. If added later: verify `content_hash` against `content` for non-empty bodies; skip rows whose `(origin_device_id, origin_seq)` already exist; never overwrite a live body with an empty tombstone from an older export without user confirmation.

### 2.4 Size and privacy

- Respect the existing 1 MiB per-clip cap on import; export writes whatever is already stored.
- Do not filter by blacklist at export time unless the UI offers “visible history only” vs “full including tombstones”. Default: full table, because tombstones matter for identity. The confirmation text must say both live bodies and deletion markers are included.

---

## 3. SQLCipher evaluation (optional hardening)

Plan.md 阶段 6: evaluate SQLCipher; if native builds are stable, add later; **do not block MVP**.

### 3.1 What it would buy

At-rest encryption of `clipsync.db` / the Windows SQLite file so a copied DB file is not plaintext history. Threat model already lists this as remaining risk (local disk, backup, device unlock). TLS already covers the peer link; SQLCipher does not replace pairing or revocation.

### 3.2 Cost and native-build risk

| Side | Today | SQLCipher path | Risk |
|---|---|---|---|
| Android Room | stock `androidx.sqlite` / platform SQLite | `net.zetetic:sqlcipher-android` + `SupportFactory` on `Room.databaseBuilder` | Extra `.so` ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`); Play/side-load size; NDK mismatch with `minSdk 29` / `targetSdk 35` |
| Windows | `Microsoft.Data.Sqlite` + SQLitePCLRaw bundle | SQLCipher-enabled PCLRaw provider or a custom native binary | Must not break the portable ZIP / non-admin install; extra native RID (`win-x64`; ARM64 later) |

Key storage: Android Keystore / Windows DPAPI wrapping a random DB key. The key must not go into `settings`, logs, Shizuku, or the export header.

Migration from plaintext: `sqlcipher_export` (or copy-to-new-file) as an **opt-in** Settings action, not a silent rewrite on first launch after upgrade. A failed convert must leave the original file intact.

### 3.3 Verdict

**Do not block MVP.** Ship the current unencrypted SQLite + Room stores. Revisit SQLCipher when:

1. Both native artifacts build in CI for the supported ABIs/RIDs
2. There is a tested plaintext → encrypted convert that cannot drop `clips`
3. Unlock / key-loss UX is specified (forgotten key = history unreadable; that is acceptable only if the user opted in)

Until then, local export (section 2) plus OS disk encryption (BitLocker / file-based encryption) is the documented at-rest story.

---

## 4. Implementation checklist (next stage)

- [ ] Android: `exportSchema = true`, commit `1.json`, Gradle `schemaDirectory`
- [ ] Android: first real bump uses `Migration(1, 2)` only; no destructive fallback
- [ ] Android: `MigrationTestHelper` keeps a visible row and a tombstone
- [ ] Windows: next schema change is `ApplyV3Async` + `CurrentSchemaVersion = 3`; keep “newer than supported” throw
- [ ] Windows: init test from `user_version = 1` fixture with a clip row
- [ ] Export: JSONL + header; user picker; confirmation that bodies are plaintext
- [ ] Export: include `deleted_at` / `terminal_reason`; do not resurrect cleared bodies
- [ ] SQLCipher: stay optional; no default convert
