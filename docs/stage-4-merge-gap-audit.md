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

> **Update (2026-08-24, after this audit):** the **Windows half of this chain has since been ported onto
> this branch** — Core media stack, SQLite schema 3 media storage, session-engine v2 chunked image
> transfer (server `/v2/peer/sync` route), CF_DIB capture, history/detail thumbnails, and the image-sync
> settings (default off), with the stage-4 tests merged and passing.
>
> **Update (2026-08-24, final integration at tip `f4404b3`):** the **Android half has now landed too**
> (`8275ffa` media layer + Room v2 + engine chunks + UI, `1b461c9` test fixes + exported schema,
> `2d4ff7d` cross-platform round-trip tests, `bd0d780` strict v1 parsing, `35ae9d8`/`f4404b3`
> instrumentation tests). **Every §1 row is now Present.** The remaining open items live in §2
> (image-aware export/import) and §4 (Modern Standby, per-IP rate limiter, E2E harness, stress suites,
> audit docs, packaging).
>
> **Update (2026-08-24, resilience/E2E port):** `25d2788` closes the §4 Modern Standby and per-IP
> rate-limiter rows; `f5c1efb` closes the §4 E2E harness and stress-suite rows (cross-client interop
> verified end-to-end: real `SyncEngine` over pinned TLS against `ClipSync.E2eHost`, both directions
> converged). §4 now has only audit docs and packaging open, plus §2 image-aware export/import.
>
> **Update (2026-08-24, final integration — audit closed):** every remaining row is now resolved and
> **this audit is closed; no stage-4 merge work remains.** The §2 image-aware export/import row is
> closed as a deliberate, documented descope (`48e0a14`: `docs/export-format-v1.md` excludes image
> rows from export v1 by design; peers re-fetch missing image ranges over normal sync, an image-aware
> `format_version: 2` stays tracked as future work, not a port gap). The §4 audit-docs row is closed
> as superseded: stage 4–9 change logs / dod-status / device matrix are archived under
> `docs/stage-4-lineage/` and this branch carries its own newer audit set
> (`strict-audit-2026-08-24.md`, `review-checklist-results.md`, `performance-audit.md`,
> `android-instrumentation-test-report.md`, `emulator-survival-report.md`); stage-4's
> `AUDIT-FINDINGS.md` et al. remain readable in `feature/stage-4` history. The §4 packaging row is
> closed with this branch's own `package-windows.ps1`/`package-android.ps1` + `docs/install.md`;
> stage-4's checksum/rollback extras are a release-engineering follow-up, not a port item. Final
> verification on this branch: protocol fixtures 12+37 / 15+15 / 5+7 all pass, Windows
> `ClipSync.Tests` **402/402**, Android `testDebugUnitTest` **507 cases 0 failures**, `assembleDebug`
> + `detekt` + `ktlintCheck` green, and `scripts/run-e2e-stage4.ps1` printed **E2E-PASS** on this
> Linux host (real cross-client interop, both directions converged exactly once).

| Feature | stage-4 location | implement status | merge priority |
|---|---|---|---|
| Protocol v2 spec: envelope/messages schemas, 38 fixtures (incl. media samples, auth vectors), generator script | `protocol/v2/**`, `scripts/generate-protocol-v2-fixtures.py`, `docs/protocol-v2.md`, `docs/adr/0003-clipboard-image-v2.md` | **Present** — ported by `c2f2bd1` (ADR renumbered 0004, `validate-protocol.py` extended); no client code consumes it yet | Done — was the P0 prerequisite |
| Android media pipeline: chunking, thumbnails, blob store | `android/.../media/ImageChunks.kt`, `ImageThumbnail.kt`, `MediaBlobStore.kt` (+ blob GC from `7fc15bd`) | **Present** — `8275ffa`: `media/ImageChunks.kt`, `ImageThumbnail.kt`, `MediaBlobStore.kt` (content-addressed, with blob GC); previously dead-code `ImageCodec.kt`/`MediaLimits.kt` now wired | Done |
| Android v2 wire layer: strict JSON parse, v2 messages/writer, chunked payload in session engine | `android/.../protocol/ProtocolStrictJson.kt`, `SyncMessages.kt`, `SyncMessageWriter.kt`; chunk handling in `android/.../sync/SyncSessionEngine.kt` | **Present** — adapted to this branch's `SyncEngine`: `8275ffa` (strict v2 validation in `ProtocolJson`, typed `SyncWire` image bodies, `clip_payload_begin/chunk/end` chunked transfer in `SyncEngine`), `bd0d780` (token-level strict v1 scanner `protocol/ProtocolStrictJson.kt` with error codes); `SyncWireV2FixtureTest` mirrors the full 15+15 v2 fixture matrix | Done |
| Android DB v1→v2 migration: image/blob columns, thumbnail refs | `android/app/schemas/.../ClipDatabase/2.json`, `storage/ClipEntities.kt`, `ClipDatabase.kt`, `ClipDaos.kt` | **Present** — rewritten against this branch's Room schema: `8275ffa` (image/blob columns in `ClipSyncEntities`/`ClipSyncDaos`), `1b461c9` (exported schema `2.json`), `35ae9d8` (`ClipSyncDatabaseMigrationTest` runs the real 1→2 migration against the exported schema) | Done |
| Gallery image share into sync | `android/.../share/ShareCaptureHelper.kt`, `ShareReceiverActivity.kt` (image path), `res/xml/file_paths.xml` (FileProvider), manifest entries | **Present** — `8275ffa`: image path in `platform/entry/ShareReceiverActivity.kt`, FileProvider `res/xml/file_paths.xml`, manifest entries | Done |
| Android history image previews + image settings (size cap, toggle) | `android/.../ui/history/HistoryScreen.kt`, `HistoryViewModel.kt`, `ui/settings/*` (image keys) | **Present** — restyled to charter, not ported verbatim: `8275ffa` history thumbnails in `ui/home/HomeScreen.kt` + image toggles in `ui/prefs/PreferencesScreen.kt` (default off, matching Windows) | Done |
| Windows media pipeline: DIB↔PNG/JPEG codecs, chunking, blob store, limits | `windows/ClipSync.Core/Media/DibCodec.cs`, `ImageChunks.cs`, `ImageCodec.cs`, `MediaBlobStore.cs`, `MediaLimits.cs` + `windows/ClipSync.Tests/Media/MediaBlobStoreTests.cs` | **Present** — ported with schema-3 media storage and tests | Done |
| Windows v2 reader + image capture | `windows/ClipSync.Core/Protocol/ProtocolReaderV2.cs`, `Win32ClipboardAdapter.cs` (CF_DIB capture), `ClipboardDataAccessor.cs` | **Present** — ported incl. session-engine v2 image transfer and `/v2/peer/sync` route (this branch's SessionReady/OutboundAllowed gates preserved) | Done |
| Windows image UI: thumbnails, preview converter, detail image view | `windows/ClipSync.App/Media/BitmapFile.cs`, `ImageThumbnail.cs`, `Converters/FilePathToImageConverter.cs`, `DetailWindow.xaml(.cs)` | **Present** — restyled to charter tokens (history-card thumbnails, 查看详情 detail window, 偏好·同步 image toggles, default off) | Done |

## 2. Export / import — exists on BOTH branches

Both branches implement JSONL export with idempotent merge-import keyed on `origin_device_id + origin_seq`,
derived from the same plan.md stage-6 requirement. Not a merge gap per se.

| Feature | stage-4 location | implement status | merge priority |
|---|---|---|---|
| Text history JSONL export/import, Android | `android/.../storage/ClipExport.kt`, `ClipImport.kt` (SAF picker in settings) | **Present** — own impl: `storage/HistoryTransfer.kt` + 偏好 数据 card (commit `3c53350`) | None — verify field-level compat if users migrate between branch builds |
| Text history JSONL export/import, Windows | `windows/ClipSync.Core/Storage/ClipboardExport.cs`, `ClipboardImport.cs`, `SqliteClipboardEventStore.Import.cs` | **Present** — own impl: `HistoryExportFormat.cs` + `SqliteClipboardEventStore.Transfer.cs` + `docs/export-format-v1.md` (fuller spec than stage-4's) | None |
| Image-aware export/import (media blobs in transfer) | `28e354a` extensions to the four files above | **Present** — first descoped by documented decision (`48e0a14`), then implemented for real: `docs/export-format-v2.md` (`format_version: 2` — image records with blob metadata + capped embedded base64, image tombstones, metadata-only fallback), wired through `HistoryExportFormat.cs`/`SqliteClipboardEventStore.Transfer.cs` and `HistoryTransfer.kt`/`ClipSyncRepository.kt`; writers still emit v1 for text-only databases | Done |

## 3. Boot restore — exists on BOTH branches (ported)

| Feature | stage-4 location | implement status | merge priority |
|---|---|---|---|
| Opt-in BOOT_COMPLETED restore chain + WorkManager health check + honest recovery notification | `android/.../service/BootCompletedReceiver.kt`, `BootHealthCheck.kt`, `BootHealthCheckWorker.kt`, `BootRecoveryNotifier.kt` | **Present** — `sync/BootCompletedReceiver.kt`, `BootHealthCheckWorker.kt`, `BootRestore.kt` + `BootRestoreTest` (commits `adbab01`, `675580c`, `fd53dc1`, merge `9fc6ec3`) | None |

## 4. Other stage-4 features missing here

| Feature | stage-4 location | implement status | merge priority |
|---|---|---|---|
| Windows Modern Standby suspend/resume: `PowerRegisterSuspendResumeNotification` + suspend-window session gating (no half-alive pre-sleep redials) | `windows/ClipSync.App/Power/SessionPowerCoordinator.cs`, `SessionPowerMonitor.cs`, `Win32SuspendResumeNotificationSource.cs` + tests (commits `02ec72`-era, `3c7f5f9`) | **Present** — `25d2788`: `Peer/Resilience/SessionPowerMonitor.cs` (dedupes SystemEvents + Win32 sources) + `Win32SuspendResumeNotificationSource.cs` (`PowerRegisterSuspendResumeNotification`, Modern Standby); `ISystemStateEvents.SuspendingToSleep` feeds a synchronous `onSuspend` callback in `SyncResilienceController`; `PeerSyncHost.EnterSuspend` gates new sessions (503) + disconnects live ones, un-gated on resume recovery; tests in `SessionPowerMonitorTests`, `SyncResilienceControllerTests`, `PeerSyncHostRecoveryTests` | Done |
| Windows per-IP connection rate limiting on PeerServer | `windows/ClipSync.Peer/Server/SlidingWindowRateLimiter.cs` + wiring in `PeerServer.cs` + tests | **Present** — `25d2788`: `Server/SlidingWindowRateLimiter.cs` wired into `PeerServer` pre-auth paths (sync WebSocket accept + pairing confirm → 429, options `MaxSyncAcceptsPerWindow`/`MaxPairingConfirmsPerWindow`/`ConnectionRateLimitWindow`); tests in `SlidingWindowRateLimiterTests`, `PeerServerAdmissionTests`, `PairingHttpTests` | Done |
| Protocol v1 strict-parse hardening fixtures (2026-08-21 audit): duplicate JSON property, explicit null, nesting depth, payload batch caps, challenge/auth field validation, `expected_errors.json`, pairing rate-limited error | `protocol/v1/fixtures/invalid/*` (23 new), `android/.../protocol/ProtocolStrictJson.kt`, `windows/.../ProtocolValidation.cs` (+359) | **Present** — shared fixture set landed with `fed1b6f` (37 invalid v1 fixtures incl. `expected_errors.json`), Windows validation passes them all; `bd0d780` ports the Android token-level scanner (`ProtocolStrictJson.kt`, error codes) and aligns `expected_errors` assertions in the fixture tests | Done |
| Windows history detail window (full text view, copy-from-detail; image view after §1) | `windows/ClipSync.App/DetailWindow.xaml(.cs)` + `HistoryExportAndDetailViewModelTests.cs` | **Present** — rebuilt in charter visual language with the image port: `81db525` 查看详情 `DetailWindow.xaml(.cs)` (full text + image view) | Done |
| Cross-platform E2E harness: headless Windows peer host + scripted stage-4 E2E run | `windows/ClipSync.E2eHost/` + `scripts/run-e2e-stage4.ps1` | **Present** — `f5c1efb`: `windows/ClipSync.E2eHost/` (net8.0, adapted to this branch's `PeerServer`/`SqliteClipboardEventStore`, stdout command protocol: ready JSON / capture / list / quit) + `scripts/run-e2e-stage4.ps1`; interop run verified: `CrossClientSyncE2eTest` converged both ways against the live host (backlog delivered exactly once, Android capture acked and visible in the Windows store) | Done |
| Android stress/idempotency JVM suites: loop-suppression stress, mode-switch idempotency, ack idempotency, cross-client E2E | `android/app/src/test/.../e2e/*.kt` | **Present** — `f5c1efb`: rewritten against this branch's architecture under `test/.../e2e/`: `LoopSuppressionStressTest` (1000 mixed-direction cycles, zero echo), `ModeSwitchIdempotencyTest` (`ClipboardAccessCoordinator`), `AckIdempotencyTest` (`RoomSyncRepository.applyPeerAckRanges` replays), `CrossClientSyncE2eTest` (real `SyncEngine` + `OkHttpSyncConnector`, gated on `clipsync.e2e.enabled`) | Done |
| Android instrumentation tests + schema-v2 assets: real-SQLite DAO test, Room migration test | `android/app/src/androidTest/.../ClipDaoSqliteTest.kt`, `ClipDatabaseMigrationTest.kt`, `schemas/.../2.json` | **Present** — `35ae9d8`/`f4404b3`: `androidTest` source set with `ClipSyncDaoSqliteTest`, `ClipSyncDatabaseMigrationTest` (real Room 1→2 migration against exported `schemas/.../2.json`), `ClipboardSyncServiceSmokeTest` (FGS smoke); compile-verified via `assembleDebugAndroidTest`, execution needs a device/emulator | Done (device run pending) |
| Static analysis chain: detekt + ktlint configs/baselines, Windows analyzers | `android/config/detekt/*`, `android/config/ktlint/*`, `scripts/static-analysis.ps1`, `windows/.editorconfig`, `windows/Directory.Build.props` | **Present** — baselines regenerated for this tree: `f21c6cb` (opt-in detekt/ktlint with branch-local baselines), `255b8c2` (.NET analyzers, security CA rules as errors, `windows/Directory.Build.props` + `.editorconfig`), `c32261a` (`scripts/static-analysis.ps1` one-shot runner), `c6adf20` (baseline refresh after the strict-parsing port) | Done |
| Audit & status docs: DoD audit, security audit, stage contracts, migration-export design, distribution guide | `AUDIT-FINDINGS.md` (675 lines), `docs/stage-6-security-audit.md`, `docs/stage-{4,5,6}-contract.md`, `docs/stage-6-migration-export.md`, `docs/distribution.md` | **Closed (superseded)** — `05b15f5` archived stage 4–9 change logs, `dod-status.md`, and the device matrix under `docs/stage-4-lineage/`; this branch carries its own newer audit set (`strict-audit-2026-08-24.md`, `review-checklist-results.md`, `performance-audit.md`, `android-instrumentation-test-report.md`, `emulator-survival-report.md`). Stage-4's audit findings / security audit / contracts / distribution guide stay readable in `feature/stage-4` history; porting them verbatim would document a superseded architecture | Done (superseded; history preserved) |
| Release packaging with checksums + rollback retention, install/uninstall scripts | `scripts/package-release.ps1`, `install-windows.ps1`, `uninstall-windows.ps1` | **Closed** — own `package-windows.ps1`/`package-android.ps1` + `docs/install.md` cover packaging on this branch; stage-4's checksum/rollback extras are a release-engineering follow-up for an actual release, not a stage-4 port item | Done (follow-up outside port scope) |
| Misc tooling | `requirements.txt`, `tools/ProtocolValidator/README.md`, `windows/ClipSync.App/AssemblyInfo.cs`, `windows/ClipSync.App/Strings.cs` | **Closed** — `Strings.cs` N/A (this branch is Chinese-first in-place); rest trivial/not needed | Done |

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

> **Resolved (2026-08-24, strict re-review):** the three same-named pairs are **not** deletable
> duplicates. Commit `9b361c8` rewired the flat `platform/clipboard/{Shizuku,AdbLogOverlay,OverlayPolling}Backend.kt`
> files as thin delegating adapters: they keep the honest `RouteProbes` probe with user-facing error
> codes and the DEGRADED-until-device-verified-read gate (plan §8.3), and forward start/read/health to
> the real backends in `shizuku/`, `adblog/`, `overlay/` that `RealBackgroundReaders.build()` supplies
> as delegates. `MainActivity` wires all three pairs in production and `PrivilegedReadWiringTest`
> covers the delegation, so both layers stay. Likewise `sync/KeyValueClipOutbox` stays: the share
> sheet / Quick Settings tile entry points enqueue into it from processes where Room may not be up,
> and `ClipboardSyncService.drainShareOutbox` moves entries into the Room store afterwards — Room
> consumes the queue, it does not replace it. The only dead weight found was three unused imports
> (`PairingJson.kt`, `overlay/OverlayPollingBackend.kt`, `ui/pairing/PairingScreen.kt`), now removed
> with the ktlint baseline regenerated.

## Suggested merge order

1. ~~§1 protocol layer (`protocol/v2/**` + docs)~~ — done (`c2f2bd1`).
2. ~~§1 Windows media pipeline + v2 reader; §1 Android media pipeline + strict JSON (adapt to `SyncEngine`)~~ — done (`fed1b6f`…`2ae5513` Windows; `8275ffa`, `bd0d780` Android).
3. ~~§1 DB migrations on both ends + androidTest migration coverage (§4)~~ — done (`fed1b6f` schema 3; `8275ffa`/`1b461c9` Room v2; `35ae9d8` migration test).
4. ~~§1 capture/share/UI surfaces, restyled to the charter~~ — done (`81db525` Windows; `8275ffa` Android).
5. ~~§4 Modern Standby power source (drop-in alongside existing resilience controller)~~ — done (`25d2788`).
6. ~~§4 rate limiter~~ (done `25d2788`), ~~v1 strict-parse fixtures~~ (done `fed1b6f`+`bd0d780`), ~~E2E harness~~ (done `f5c1efb`), ~~static analysis~~ (done `f21c6cb`…`c6adf20`), ~~audit docs~~ — closed as superseded (final integration; see §4).
7. ~~§2 image-aware export/import~~ — done for real (`docs/export-format-v2.md` + both clients' transfer layers), superseding the earlier documented descope (`48e0a14`).

**All items closed — the stage-4 port campaign is complete.**
