# Stage 4 parallel contract

Status: in progress on `feature/stage-4`. Agents must stay inside their file ownership. Do not edit another agent's files. Do not implement Stage 5 backends (Shizuku, ADB log, overlay polling). Do not add accounts, cloud, file transfer, or clipboard-content logging.

## Shared environment

- Android SDK: `$env:ANDROID_HOME = 'D:\paste-tools\android-sdk'`
- JDK 17 is on PATH
- Build: `pwsh scripts/build-android.ps1` from repo root
- Protocol fixtures: `protocol/v1/fixtures`
- Auth vectors: `protocol/v1/fixtures/auth/vectors.json`
- Windows reference: `windows/ClipSync.Peer/Sessions/SyncSessionEngine.cs`, `windows/ClipSync.Core/Storage/SqliteClipboardEventStore.Sync.cs`, `windows/ClipSync.Core/Security/PairAuthProof.cs`
- Existing Android pairing trust: `PairingStore` (hosts, port, cert pin, pair secret, trust epoch)
- Existing clipboard coordinator: `ClipboardAccessCoordinator` + `ClipboardWriteCoordinator`
- Room 2.6.1 + KSP already added to Gradle. Do not change plugin versions unless a compile error forces it.

## File ownership

### Agent A — Room storage

May create/edit only:

- `android/app/src/main/java/com/clipsync/android/storage/**`
- `android/app/src/test/java/com/clipsync/android/storage/**`

Must implement Room entities/DAOs/database plus a repository used by later sync/UI:

- `clips`: `event_id`, `origin_device_id`, `origin_seq`, `kind='text'`, `content` (nullable when terminal), `content_hash`, `source_app`, `created_at`, `expires_at`, `deleted_at`, `terminal_reason` (`local_only`/`deleted`/`expired`/`policy_filtered`/`not_found` or null)
- `outbox`: `id`, `peer_id`, `event_id`, `state` (`pending`/`announced`), `attempts`, `next_attempt_at`, `last_error`
- `origin_receive_state`: `origin_device_id` PK, `contiguous_seq`, serialized non-contiguous `received_ranges`
- `peer_cursors`: PK `(peer_id, origin_device_id)`, `received_seq`, `acked_at`
- `local_sequences`: `device_id` PK, `next_seq`
- `settings`: `key`, `value`

Rules:

1. Local capture and remote ingest must allocate/commit `origin_seq` in the same transaction as the clip row, then fan-out outbox.
2. Idempotency key is `(origin_device_id, origin_seq)`. Same identity+hash = already persisted. Different event_id/hash/seq mapping = identity conflict.
3. `contiguous_seq` is the greatest sequence with every value from 1 through it persisted. Receiving seq=12 while 11 is missing must leave contiguous at 10 and record range `{12,12}`.
4. Local delete/clear empties content, sets `deleted_at` + `terminal_reason=deleted`, cancels unacked outbox rows. No remote wipe.
5. Never log clip body, pair secret, or token.
6. Tests first: in-memory Room. Cover local capture, remote ingest, gap cursor, identity conflict, delete tombstone, outbox ack removal, reopen persistence.

Public types the later sync client will call (names may be refined but keep this shape):

```text
ClipRepository
  initialize()
  captureLocalText(text, sourceApp?, nowMs) -> CaptureResult
  ingestRemoteClip(event) -> RemoteStoreResult
  ingestTerminalMarker(marker) -> RemoteStoreResult
  knownVector() -> KnownVector
  outboxPending(peerId) -> List<OutboxEntry>
  markAnnounced / ackRanges
  search(query) -> List<ClipEntry>
  delete(eventId) / clear()
  get/set setting
```

### Agent B — Pair auth + protocol message types

May create/edit only:

- `android/app/src/main/java/com/clipsync/android/protocol/PairAuthProof.kt`
- `android/app/src/main/java/com/clipsync/android/protocol/SyncMessages.kt`
- `android/app/src/test/java/com/clipsync/android/protocol/PairAuthProofTest.kt`
- `android/app/src/test/java/com/clipsync/android/protocol/SyncMessageParseTest.kt`

Do not rewrite `ProtocolJson.kt` or `ProtocolEnvelope.kt` unless a compile error requires a tiny additive helper.

Must:

1. Port `PairAuthProof.Compute/Verify` exactly. Consume `protocol/v1/fixtures/auth/vectors.json` byte-for-byte. UUID bytes are RFC 4122 big-endian from the canonical hex string, not Java UUID MSB/LSB layout.
2. Algorithm string is lowercase `hmac-sha256`.
3. Typed bodies for hello/challenge/auth/known_vector/want_ranges/clip_announce/clip_fetch/clip_payload/ack_ranges/error/ping/pong that parse through existing `ProtocolJson.parseEnvelope` + shared fixtures.
4. Never log nonce, proof, secret, or content.

### Agent C — Foreground read + public writer

May create/edit only:

- `android/app/src/main/java/com/clipsync/android/platform/clipboard/ForegroundClipboardBackend.kt`
- `android/app/src/main/java/com/clipsync/android/platform/clipboard/AndroidPublicClipboardWriter.kt`
- `android/app/src/test/java/com/clipsync/android/platform/clipboard/ForegroundClipboardBackendTest.kt`
- `android/app/src/test/java/com/clipsync/android/platform/clipboard/AndroidPublicClipboardWriterTest.kt`

`ClipboardWriter.kt` already aliases `PublicClipboardWriter = ClipboardWriter`. Implement `AndroidPublicClipboardWriter : ClipboardWriter` using `ClipboardManager.setPrimaryClip`. Do not change coordinator APIs.

Must:

1. `ForegroundClipboardBackend` implements `BackgroundClipboardBackend` with `mode = FOREGROUND_ONLY`. Uses ordinary `ClipboardManager` only while the process is considered foreground/visible. `start(onChanged)` registers `OnPrimaryClipChangedListener`; `stop()` unregisters. Empty/non-text = no event. Hash via existing `ContentHasher`.
2. Probe/health must not claim background read is READY. Foreground-only READY means the public API is usable while visible.
3. Writer records success / system-rejected / timeout with stable error codes. Never log clip text.
4. Tests use fakes/mocks of ClipboardManager if instrumentation is unavailable; JVM tests of hashing, empty rejection, listener start/stop, and write result mapping are required. If a real ClipboardManager cannot be constructed on the JVM, extract a small `ClipboardOs` interface in the same two implementation files (not in `ClipboardModels.kt`) so tests inject a fake OS.

## Wave 2 — after A/B/C

A/B/C landed. `ClipRepository`, `PairAuthProof`, `SyncMessages.parse`, `ForegroundClipboardBackend`, and `AndroidPublicClipboardWriter` are ready. Wave 2 implements the Stage 4 companion surface.

### Agent D — WebSocket sync (dialer)

May create/edit only:

- `android/app/src/main/java/com/clipsync/android/sync/**`
- `android/app/src/test/java/com/clipsync/android/sync/**`
- `android/app/src/main/java/com/clipsync/android/protocol/SyncMessageWriter.kt` (new file only; `SyncMessages` has parse, no encode)

Android is always the dialer. Reuse `PairingStore` for hosts/port/cert pin/secret/epoch/local device id. Persist via `ClipRepository` only. Pin TLS exactly like `PairingConfirmClient`.

Handshake: `hello` → `challenge`/`auth` → `known_vector` → `want_ranges` → announce/fetch/payload → `ack_ranges`. Application ping every 30s; 3 unanswered pongs close. Reconnect backoff 1,2,4,8,16,30s capped at 5 minutes. New session calls `resetOutboxToPending`. Identity conflict / auth failure close the socket. Never log content, nonce, proof, or secret.

Tests: fake transport or MockWebServer + okhttp-tls. Cover auth success, wrong epoch, gap cursor want/ack, hash-local skip fetch, payload without announce = out of order, reconnect drain.

### Agent E — History UI, share, tile, notifications

May create/edit:

- `android/app/src/main/java/com/clipsync/android/ui/history/**`
- `android/app/src/main/java/com/clipsync/android/ui/settings/**`
- `android/app/src/main/java/com/clipsync/android/share/**`
- `android/app/src/main/java/com/clipsync/android/tile/**`
- `android/app/src/main/java/com/clipsync/android/notify/**`
- `android/app/src/main/java/com/clipsync/android/ui/HealthScreen.kt`
- `android/app/src/main/java/com/clipsync/android/MainActivity.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/res/**` (strings only unless a new layout is required)
- matching tests under `android/app/src/test/java/com/clipsync/android/{ui,share,tile,notify}/**`

Do not edit `sync/**`, `storage/**`, or pairing JSON/client.

Must:

1. History list + search + copy/delete/clear via `ClipRepository`. Copy uses `AndroidPublicClipboardWriter` / coordinator, not a raw clipboard call from the ViewModel if a writer is available.
2. Settings: pause, private mode, `auto_apply_remote`, capability cards that separately show network / service / read / write. Do not paint one green as all-green.
3. `ACTION_SEND` text share target writes `captureLocalText` with `paired_peer_id`.
4. Quick Settings Tile “send current clipboard” via `ForegroundClipboardBackend.readText()` then capture.
5. Notification action “copy to clipboard” for inbound items when auto-apply is off or public write failed. `POST_NOTIFICATIONS` is optional; refuse it must not crash.
6. After pairing succeeds, write `SETTING_PAIRED_PEER_ID` from `PairingStore.peer().deviceId` into `ClipRepository` (observe `PairingUiState.Paired` in `MainActivity`; do not rewrite pairing crypto).
7. Empty / oversized / unpaired / Windows unreachable must show explicit UI states.

ForegroundService (`connectedDevice`) is Stage 5. Wave 2 may start sync from the Activity/Application scope when paired; do not invent a fake “always background” service.

## Agent rules

- Write failing tests first, then implementation.
- Run `pwsh scripts/build-android.ps1` after your work if the environment is available.
- If a needed type lives in another agent's files and is missing, define a minimal local interface in your package and document it; do not invent a second Room schema or a second HMAC format.
- Keep `minSdk 29`, `targetSdk 35`.

## Agent A notes

- JVM unit tests drive `ClipRepository` through an in-memory `ClipPersistence` that mirrors the Room tables. `Room.inMemoryDatabaseBuilder` is implemented on `ClipDatabase` for device/instrumentation use, but Android SQLite is a stub on the JVM and Robolectric is not a test dependency (Agent A cannot edit Gradle). Room KSP still compiles and type-checks the DAO SQL.
- `peer_cursors` keeps the contract columns `received_seq` + `acked_at`, plus serialized `received_ranges` so gap acks match protocol v1 (same shape as Windows `OriginReceiveState`).
- `captureLocalText` takes an optional `peerId`. If omitted, outbox fan-out uses settings key `paired_peer_id`. Pairing peers are not stored in Room (no `devices` table).
- Extra methods for the later WebSocket client: `getSyncableEvents`, `findLiveContentByHash`, `resetOutboxToPending`, `getPeerCursors`.

## Wave 3

Docs (Agent G) wrote `docs/stage-4-change-log.md` and refreshed README / THIRD_PARTY_NOTICES. Code-surface work from waves 1–2 is recorded there. End-to-end results are **not** filled in this contract; the orchestrator owns the `<!-- E2E_RESULTS_PENDING -->` section in the change log, including the Windows re-run after E2eHost lands.
