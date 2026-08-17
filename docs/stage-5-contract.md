# Stage 5 parallel contract

Status: wave 1 in progress on `feature/stage-4` (stage-4 committed as `e4b82a5`; stage-5 work continues on this branch until a stage-5 commit).

Stage 5 = Android background clipboard capability (plan.md 5.1–5.7). Wave 1 covers 5.1 (modes/state machine/capability store), 5.2 (ForegroundService), 5.3 (Shizuku backend), and the plan 8.3 documentation re-verification. Overlay polling (5.5) and ADB log mode (5.4) come in wave 2 because both depend on the OverlayFocusController.

Hard rules for every agent (from plan.md section 9 and 0.2):

- No Root, no `targetSdk` lowering, no silent adb, no hidden permissions, no accessibility service, no IME.
- Never log clipboard content, tokens, secrets, nonces, raw logcat lines, or Shizuku command output.
- All capability claims must be honest: permission granted ≠ READY; only a real successful probe/read/write flips state.
- Physical-device results stay `NOT_TESTED`; JVM fakes + (optional) instrumentation prove logic, not ROM coverage.
- Write failing tests first. Keep `minSdk 29`, `targetSdk 35`.
- Parallel Gradle runs race (daemon stop, KSP cache): scope test runs with `--tests`, retry once; the orchestrator runs the final full build.

## Shared environment

- `$env:ANDROID_HOME = 'D:\paste-tools\android-sdk'`; JDK 17 on PATH; build via `pwsh scripts/build-android.ps1`
- Frozen shared surface (already in `ClipboardModels.kt`, do not re-shape): `ClipboardReadMode`, **`ClipboardWriteMode`** (`PUBLIC_API`, `SHIZUKU_FALLBACK`, `OVERLAY_FALLBACK`, `MANUAL_ONLY`), `CapabilityState` (now incl. **`NEEDS_USER_ACTION`**), `CapabilityReport`, `BackendHealth(State)`, `ClipboardChange`, `ClipboardReadResult`, `ClipboardWriteResult/Outcome`, `ClipboardAccessState`.
- Existing coordinators: `ClipboardAccessCoordinator` (read-mode selection/fallback), `ClipboardWriteCoordinator` (public first, fallback second, loop suppression).
- Sync stack: `SyncController` (`start/stop/status/state`), `createSyncController(pairingStore, repository, scope, onRemoteClipsCommitted)`.
- UI capability cards: `ui/settings/CapabilityStatusProvider` + `ClipServices.capabilities`.

## File ownership — wave 1

### Agent H — 5.1 runtime, mode state machine, capability store

May create/edit only:

- `android/app/src/main/java/com/clipsync/android/platform/clipboard/ClipboardAccessCoordinator.kt`
- `android/app/src/main/java/com/clipsync/android/platform/clipboard/ClipboardWriteCoordinator.kt`
- `android/app/src/main/java/com/clipsync/android/platform/clipboard/ClipboardCapabilityStore.kt` (new)
- `android/app/src/test/java/com/clipsync/android/platform/clipboard/**` (its tests + may extend FakeClipboardBackends.kt additively)

Must:

1. Mode-switch transaction (plan 5.1): stop old backend → release focus resource (callback hook; overlay lands in wave 2) → refresh current content hash → start new backend → bump a persisted `mode_epoch`. Any step failing rolls back to the last known-good mode or `FOREGROUND_ONLY`. No duplicate upload after switch (hash baseline refresh), covered by tests.
2. Read state (`requested_read_mode`, `active_read_mode`, `auto_fallback_allowed`, `last_error_code`, `last_health_at`) persisted via `ClipboardCapabilityStore` (interface + SharedPreferences-backed impl via the existing `KeyValueStore` from pairing, or a local equivalent; JVM-testable with an in-memory map). Never stores clipboard text.
3. Write side: `ClipboardWriteCoordinator` gains independent probe persistence (`write_mode`, public vs fallback last-success/last-error) and must never report `MANUAL_ONLY` while the public writer probes READY.
4. Health tick API: `checkHealth()` already exists — extend so a backend reporting `FAILED`/`NEEDS_USER_ACTION` triggers auto-fallback only when the user allowed it; otherwise state surfaces `NEEDS_USER_ACTION` and stays.
5. `CapabilityReport` handling of `NEEDS_USER_ACTION` (new enum value) in selection order: prefer requested mode; a `NEEDS_USER_ACTION` probe is not selectable but is reported distinctly from `UNAVAILABLE`.

### Agent I — 5.2 ForegroundService and process recovery

May create/edit only:

- `android/app/src/main/java/com/clipsync/android/service/**` (new package)
- `android/app/src/main/java/com/clipsync/android/MainActivity.kt` (service toggle wiring only)
- `android/app/src/main/AndroidManifest.xml` (permissions + service declaration)
- `android/app/src/main/java/com/clipsync/android/ui/settings/**` (service card + toggle surface only; do not rewrite capability plumbing)
- `android/app/src/main/res/values/strings.xml` (additive)
- `android/app/src/test/java/com/clipsync/android/service/**` and additive edits to existing settings tests it breaks

Must:

1. `ClipboardSyncService`: `foregroundServiceType="connectedDevice"`, permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_NETWORK_STATE`, `ACCESS_NETWORK_STATE`; start with `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`; catch `MissingForegroundServiceTypeException`/`SecurityException` and surface a stable error state (no crash).
2. Service owns the `SyncController` while running (Activity releases it): user explicitly starts "background sync" from Settings; stopping returns to Activity-scoped sync. `START_STICKY` as supplement; killed → status shows "needs recovery", never fake-online.
3. Notification (channel + ongoing): actions pause-all / sync-now / open-status; no clipboard text in any notification. `POST_NOTIFICATIONS` denial keeps the FGS legal (FGS notification exempt) but in-app status must explain reduced visibility.
4. Network callback (`ConnectivityManager.NetworkCallback`) nudges reconnect on network regain (calls controller start/status; no busy loop).
5. Extract service logic into a plain `ServiceOrchestrator` class for JVM tests (service shell stays thin). Tests: start/stop transitions, type-missing exception path, notification action intents, killed→recovery state, no-text-in-notification assertion.
6. BOOT_COMPLETED: implement receiver gated behind settings key `boot_recovery_enabled` (default false; UI toggle in Settings), but keep it manifest-disabled unless the setting is on (enable/disable component at runtime). Failure to start FGS from boot must degrade to a notification request, never crash-loop.

### Agent J — 5.3 Shizuku event backend (read) + write fallback

May create/edit only:

- `android/app/src/main/java/com/clipsync/android/platform/clipboard/shizuku/**` (new subpackage: backend, UserService, binder adapter, error codes)
- `android/app/build.gradle.kts` (add `dev.rikka.shizuku:api` + `dev.rikka.shizuku:provider`, current stable version)
- `android/app/src/main/AndroidManifest.xml` — ONLY the Shizuku provider `<provider>` block (coordinate: Agent I also edits the manifest; keep your edit to that single additive block; if you hit a merge conflict on write, re-read the file first and re-apply additively)
- `THIRD_PARTY_NOTICES.md` (Shizuku entries with license verified from the artifacts)
- `android/app/src/test/java/com/clipsync/android/platform/clipboard/shizuku/**`

Must:

1. `ShizukuClipboardBackend : BackgroundClipboardBackend` with `mode = SHIZUKU_EVENT`. Seven stable error codes: `SHIZUKU_NOT_INSTALLED`, `SHIZUKU_NOT_RUNNING`, `SHIZUKU_NOT_AUTHORIZED`, `SHIZUKU_BINDER_DEAD`, `SHIZUKU_USERSERVICE_DEAD`, `CLIPBOARD_BINDER_DEAD`, `SHIZUKU_API_MISMATCH`. Probe maps not-installed/not-running/not-authorized to `NEEDS_USER_ACTION`, binder/API failures to `UNAVAILABLE`/`DEGRADED` per plan 5.3.
2. UserService (shell UID): minimal binder surface — read text, write text, add/remove primary-clip-changed listener, health ping. No network, no secrets, no arbitrary shell.
3. Hidden `IClipboard` access via a versioned reflection adapter for API 29–35 signatures (`getPrimaryClip`/`setPrimaryClip`/`addPrimaryClipChangedListener` arg shapes per API level), isolated in one adapter file with unit tests per API shape (reflection against fake interfaces on JVM; real binder only on device).
4. Listener callback only signals "changed"; the backend then reads, hashes (existing `ContentHasher`), and forwards — no network from the callback.
5. `linkToDeath` + exponential rebind; after rebind refresh the content hash baseline (no false "user copied").
6. Write fallback: expose a `ClipboardWriter` implementation for `ClipboardWriteCoordinator` fallback slot; never the default writer.
7. JVM tests with a faked Shizuku facade: all seven error codes, authorization flow, listener signal→read→hash pipeline, death→rebind→baseline refresh, write fallback result mapping. Device/instrumentation tests may exist but must skip without Shizuku.

### Agent K — plan 8.3 documentation re-verification (docs only)

May create/edit only:

- `docs/android-background-clipboard.md`

Must:

1. Use web search/fetch to re-verify, as of today: Android clipboard background-read restrictions (10+ unchanged?), foreground service `connectedDevice` type requirements, Android 14 FGS type enforcement, Android 15/16 `BOOT_COMPLETED` FGS restrictions (is `connectedDevice` still allowed from boot?), `POST_NOTIFICATIONS` semantics, `TYPE_APPLICATION_OVERLAY` focus/touch flag behavior. Record the verification date and any behavior deltas relevant to stage 5 in the document (a dated "阶段 5 前核对" section).
2. Verify the four pinned third-party references in plan.md 8.2 still resolve (KDE Connect, ClipShare, SyncClipboard Mobile, UniClipboard at their pinned commits); note check results (do not edit plan.md).
3. Do not overstate: official docs decide the baseline; third-party repos only prove combinations existed.

## Out of scope wave 1

Overlay polling backend, OverlayFocusController, ADB `READ_LOGS` mode and its bootstrap script changes, capability wizard UI, stage-5 change log. No stage-6 hardening.
