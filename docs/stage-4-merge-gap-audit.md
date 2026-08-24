# Stage-4 merge gap audit

Date: 2026-08-24
Compared: `origin/feature/stage-4` (tip `ced3bd3`) vs `origin/cursor/implement-charter-ui-1991` (tip `05b15f5`).
Merge base: `768fd1c` (Stage 0–3 baseline) — both branches fork from the same baseline and evolved in parallel.
While this audit ran, three port commits landed on this branch and are reflected in the tables below:
`c2f2bd1` (protocol v2 wire contract), `05b15f5` (stage 4–9 lineage docs), `4ab06c4` (platform-constraint doc).

Raw tree diff (`git diff --stat origin/cursor/implement-charter-ui-1991...origin/feature/stage-4`, measured at
tip `1fe5c07`): 425 files, ~54k insertions. That number heavily overstates the real gap, because this branch **re-implemented** large
parts of stage-4 in its own architecture (TLS peer stack, `SyncEngine`/`SyncSupervisor`, `HistoryTransfer`,
boot restore, capability wizard, tray, notifications). Of the 254 stage-4 files with no same-path counterpart
here, most have a functional equivalent under a different path. This audit lists only the areas where
stage-4 has something this branch genuinely lacks.

Excluded as instructed: already-ported dead code. `android/.../media/ImageCodec.kt`,
`android/.../media/MediaLimits.kt`, and `android/.../platform/clipboard/ClipboardMediaReader.kt` are
byte-identical on both branches — ported but unwired here until image sync lands.

## 1. Image sync (stage-4 commit `28e354a`, hardened by `7fc15bd`) — the dominant gap

Protocol v2 clipboard image sync for PNG/JPEG with gallery share and history previews. Nothing of the
active chain exists on this branch (this branch's own docs commit `1fe5c07` acknowledges the full v2
implementation lives on `feature/stage-4`).

| Feature | stage-4 location | implement status | merge priority |
|---|---|---|---|
| Protocol v2 spec: envelope/messages schemas, 38 fixtures (incl. media samples, auth vectors), generator script | `protocol/v2/**`, `scripts/generate-protocol-v2-fixtures.py`, `docs/protocol-v2.md`, `docs/adr/0003-clipboard-image-v2.md` | **Present** — ported by `c2f2bd1` (ADR renumbered 0004, `validate-protocol.py` extended); no client code consumes it yet | Done — was the P0 prerequisite |
| Android media pipeline: chunking, thumbnails, blob store | `android/.../media/ImageChunks.kt`, `ImageThumbnail.kt`, `MediaBlobStore.kt` (+ blob GC from `7fc15bd`) | Missing (only dead-code `ImageCodec.kt`/`MediaLimits.kt` ported) | **P0** |
| Android v2 wire layer: strict JSON parse, v2 messages/writer, chunked payload in session engine | `android/.../protocol/ProtocolStrictJson.kt`, `SyncMessages.kt`, `SyncMessageWriter.kt`; chunk handling in `android/.../sync/SyncSessionEngine.kt` | Missing; this branch's `sync/SyncEngine.kt` + `protocol/ProtocolEnvelope.kt` are text/v1-only | **P0** — needs adaptation, not copy: sync engines differ structurally |
| Android DB v1→v2 migration: image/blob columns, thumbnail refs | `android/app/schemas/.../ClipDatabase/2.json`, `storage/ClipEntities.kt`, `ClipDatabase.kt`, `ClipDaos.kt` | Missing; `ClipSyncDatabase` still schema v1 | **P0** — must be rewritten against this branch's Room schema |
| Gallery image share into sync | `android/.../share/ShareCaptureHelper.kt`, `ShareReceiverActivity.kt` (image path), `res/xml/file_paths.xml` (FileProvider), manifest entries | This branch's `platform/entry/ShareTextIntentHandler.kt` is text-only | **P1** |
| Android history image previews + image settings (size cap, toggle) | `android/.../ui/history/HistoryScreen.kt`, `HistoryViewModel.kt`, `ui/settings/*` (image keys) | Missing; `ui/home/` has no image rendering | **P1** — restyle to charter, don't port UI verbatim |
| Windows media pipeline: DIB↔PNG/JPEG codecs, chunking, blob store, limits | `windows/ClipSync.Core/Media/DibCodec.cs`, `ImageChunks.cs`, `ImageCodec.cs`, `MediaBlobStore.cs`, `MediaLimits.cs` + `windows/ClipSync.Tests/Media/MediaBlobStoreTests.cs` | Missing entirely (no `Media/` folder) | **P0** |
| Windows v2 reader + image capture | `windows/ClipSync.Core/Protocol/ProtocolReaderV2.cs`, `Win32ClipboardAdapter.cs` (CF_DIB capture), `ClipboardDataAccessor.cs` | Missing; adapter is text-only | **P0** |
| Windows image UI: thumbnails, preview converter, detail image view | `windows/ClipSync.App/Media/BitmapFile.cs`, `ImageThumbnail.cs`, `Converters/FilePathToImageConverter.cs`, `DetailWindow.xaml(.cs)` | Missing | **P1** — restyle to charter tokens |

## 2. Export / import — exists on BOTH branches

Both branches implement JSONL export with idempotent merge-import keyed on `origin_device_id + origin_seq`,
derived from the same plan.md stage-6 requirement. Not a merge gap per se.

| Feature | stage-4 location | implement status | merge priority |
|---|---|---|---|
| Text history JSONL export/import, Android | `android/.../storage/ClipExport.kt`, `ClipImport.kt` (SAF picker in settings) | **Present** — own impl: `storage/HistoryTransfer.kt` + 偏好 数据 card (commit `3c53350`) | None — verify field-level compat if users migrate between branch builds |
| Text history JSONL export/import, Windows | `windows/ClipSync.Core/Storage/ClipboardExport.cs`, `ClipboardImport.cs`, `SqliteClipboardEventStore.Import.cs` | **Present** — own impl: `HistoryExportFormat.cs` + `SqliteClipboardEventStore.Transfer.cs` + `docs/export-format-v1.md` (fuller spec than stage-4's) | None |
| Image-aware export/import (media blobs in transfer) | `28e354a` extensions to the four files above | Missing — follows image sync | **P1**, bundled with §1 |

## 3. Boot restore — exists on BOTH branches (ported)

| Feature | stage-4 location | implement status | merge priority |
|---|---|---|---|
| Opt-in BOOT_COMPLETED restore chain + WorkManager health check + honest recovery notification | `android/.../service/BootCompletedReceiver.kt`, `BootHealthCheck.kt`, `BootHealthCheckWorker.kt`, `BootRecoveryNotifier.kt` | **Present** — `sync/BootCompletedReceiver.kt`, `BootHealthCheckWorker.kt`, `BootRestore.kt` + `BootRestoreTest` (commits `adbab01`, `675580c`, `fd53dc1`, merge `9fc6ec3`) | None |

## 4. Other stage-4 features missing here

| Feature | stage-4 location | implement status | merge priority |
|---|---|---|---|
| Windows Modern Standby suspend/resume: `PowerRegisterSuspendResumeNotification` + suspend-window session gating (no half-alive pre-sleep redials) | `windows/ClipSync.App/Power/SessionPowerCoordinator.cs`, `SessionPowerMonitor.cs`, `Win32SuspendResumeNotificationSource.cs` + tests (commits `02ec72`-era, `3c7f5f9`) | Partial — own `Peer/Resilience/SyncResilienceController.cs` + `WindowsSystemStateEvents.cs` cover SystemEvents sleep/wake + network flaps, but **no Modern Standby API and no suspend-window gating** | **P1** — most laptops use Modern Standby; SystemEvents alone misses it |
| Windows per-IP connection rate limiting on PeerServer | `windows/ClipSync.Peer/Server/SlidingWindowRateLimiter.cs` + wiring in `PeerServer.cs` + tests | Partial — has `AuthThrottle` (per-device auth lockout) and `MaxConcurrentSessions`, but no pre-auth per-IP limiter | **P2** |
| Protocol v1 strict-parse hardening fixtures (2026-08-21 audit): duplicate JSON property, explicit null, nesting depth, payload batch caps, challenge/auth field validation, `expected_errors.json`, pairing rate-limited error | `protocol/v1/fixtures/invalid/*` (23 new), `android/.../protocol/ProtocolStrictJson.kt`, `windows/.../ProtocolValidation.cs` (+359) | Partial — own invalid-fixture set (17 + pairing) covers unknown fields/dup event ids, not the strict-parse cases | **P2** — port fixtures first, drive parser fixes from failures |
| Windows history detail window (full text view, copy-from-detail; image view after §1) | `windows/ClipSync.App/DetailWindow.xaml(.cs)` + `HistoryExportAndDetailViewModelTests.cs` | Missing — history rows only | **P2** — rebuild in charter visual language |
| Cross-platform E2E harness: headless Windows peer host + scripted stage-4 E2E run | `windows/ClipSync.E2eHost/` + `scripts/run-e2e-stage4.ps1` | Missing — closest is JVM `sync/WindowsAndroidSyncChainTest.kt` | **P2** — main tool for verifying an image-sync port |
| Android stress/idempotency JVM suites: loop-suppression stress, mode-switch idempotency, ack idempotency, cross-client E2E | `android/app/src/test/.../e2e/*.kt` | Partial — has `SyncEngineTest`, `SyncSupervisorTest`, `WindowsAndroidSyncChainTest`; no stress/idempotency depth | **P2** — must be rewritten against `SyncEngine` |
| Android instrumentation tests + schema-v2 assets: real-SQLite DAO test, Room migration test | `android/app/src/androidTest/.../ClipDaoSqliteTest.kt`, `ClipDatabaseMigrationTest.kt`, `schemas/.../2.json` | Missing — no `androidTest` source set; schema v1 only | **P2** — becomes mandatory the moment §1 adds a migration |
| Static analysis chain: detekt + ktlint configs/baselines, Windows analyzers | `android/config/detekt/*`, `android/config/ktlint/*`, `scripts/static-analysis.ps1`, `windows/.editorconfig`, `windows/Directory.Build.props` | Missing — CI builds/tests but runs no linters | **P2** — baselines must be regenerated for this tree |
| Audit & status docs: DoD audit, security audit, stage contracts, migration-export design, distribution guide | `AUDIT-FINDINGS.md` (675 lines), `docs/stage-6-security-audit.md`, `docs/stage-{4,5,6}-contract.md`, `docs/stage-6-migration-export.md`, `docs/distribution.md` | Partial — `05b15f5` archived stage 4–9 change logs, `dod-status.md`, and the device matrix under `docs/stage-4-lineage/`; the audit findings, security audit, contracts, and distribution guide remain unported | **P3** — reference material; cherry-pick as history, don't rewrite |
| Release packaging with checksums + rollback retention, install/uninstall scripts | `scripts/package-release.ps1`, `install-windows.ps1`, `uninstall-windows.ps1` | Partial — own `package-windows.ps1`/`package-android.ps1` + `docs/install.md`; no checksum/rollback logic | **P3** |
| Misc tooling | `requirements.txt`, `tools/ProtocolValidator/README.md`, `windows/ClipSync.App/AssemblyInfo.cs`, `windows/ClipSync.App/Strings.cs` | `Strings.cs` N/A (this branch is Chinese-first in-place); rest trivial | **P3** |

## 5. Not gaps (re-implemented here, do not port)

- Sync engine/controller, outbox drain, echo suppression → `sync/SyncEngine.kt`, `SyncSupervisor.kt`, `ClipOutbox.kt`, `InboundFrameGateTest`.
- Inbound clip notification + auto-apply + pause/private gating → `platform/notify/*`, commits `d767905`, `37465e3`, `7f5e51e`.
- QS tile / share entry points (text) → `platform/entry/*`.
- Capability wizard → `ui/health/CapabilityWizard.kt` + `CapabilityRoutes.kt`.
- Retention + tombstone delete propagation → `ClipSyncRepository.kt`, `SyncSettingsStore.kt`.
- Shizuku/privileged-host/overlay/adb-log backends → ported in commits `b25c5e2`, `459b038`, `9b361c8` (adb-log fixtures live inline in `ClipboardLogFixtures.kt` instead of `.txt` resources).
- Bounded WebSocket frames → Windows `ProtocolLimits.MaxWebSocketTextMessageBytes`; Android commit `ec6d79e`.
- zh-rCN resources → this branch is Chinese-first in default resources.

## 6. Cleanup debt noted in passing (this branch, not stage-4)

Duplicate legacy stubs coexist with the ported real implementations and should be deleted when convenient:
`platform/clipboard/AdbLogOverlayBackend.kt` vs `platform/clipboard/adblog/AdbLogOverlayBackend.kt`,
`platform/clipboard/OverlayPollingBackend.kt` vs `platform/clipboard/overlay/OverlayPollingBackend.kt`,
`platform/clipboard/ShizukuClipboardBackend.kt` vs `platform/clipboard/shizuku/ShizukuClipboardBackend.kt`.

## Suggested merge order

1. ~~§1 protocol layer (`protocol/v2/**` + docs)~~ — done (`c2f2bd1`).
2. §1 Windows media pipeline + v2 reader; §1 Android media pipeline + strict JSON (adapt to `SyncEngine`).
3. §1 DB migrations on both ends + androidTest migration coverage (§4).
4. §1 capture/share/UI surfaces, restyled to the charter.
5. §4 Modern Standby power source (drop-in alongside existing resilience controller).
6. §4 rate limiter, v1 strict-parse fixtures, E2E harness, static analysis, docs.
