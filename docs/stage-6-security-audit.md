# Stage 6 security audit

Date: 2026-08-17
Scope: plan.md 阶段 6 — Shizuku UserService minimal privilege, overlay window safety, `READ_LOGS` data minimization, and a repo-wide clipboard-content log leak grep.
Method: source read + grep. No production files were modified. Violations are reported with `file:line` and a concrete fix for the orchestrator.

Verdict key: **PASS** / **VIOLATION** / **N-A**.

---

## A. Shizuku UserService minimal privilege

Surfaces: `android/app/src/main/java/com/clipsync/android/platform/clipboard/shizuku/**`.
There is no `.aidl` file. The UserService binder is a hand-written `Binder.onTransact` plus `ShizukuClipboardBinderContract` opcodes.

### A.1 Binder / interface surface is clipboard-only

| Item | Verdict | Evidence |
|---|---|---|
| Expose clipboard read | **PASS** | `ShizukuClipboardBinderContract.TRANSACTION_READ` (`ShizukuClipboardBinder.kt:15`); `ClipboardUserService.onTransact` `TRANSACTION_READ` (`ClipboardUserService.kt:54-58`); session `readText()` (`ShizukuClipboardBinder.kt:43-63`). |
| Expose clipboard write | **PASS** | `TRANSACTION_WRITE` (`ShizukuClipboardBinder.kt:16`); `onTransact` `TRANSACTION_WRITE` (`ClipboardUserService.kt:59-64`); session `writeText` (`ShizukuClipboardBinder.kt:65-86`). |
| Expose listener register / unregister | **PASS** | `TRANSACTION_ADD_LISTENER` / `TRANSACTION_REMOVE_LISTENER` (`ShizukuClipboardBinder.kt:17-18`); `ClipboardUserService.kt:65-79`; session `addChangedListener` / `removeChangedListener` (`ShizukuClipboardBinder.kt:88-120`). |
| Expose health | **PASS** | `TRANSACTION_PING` (`ShizukuClipboardBinder.kt:19`); `ClipboardUserService.kt:80-85`; session `pingHealth()` (`ShizukuClipboardBinder.kt:122-146`). Reply is a ping code, never clip text. |
| Expose destroy | **PASS** | `TRANSACTION_DESTROY` (`ShizukuClipboardBinder.kt:20`); `ClipboardUserService.kt:86-91` and public `destroy()` (`ClipboardUserService.kt:97-106`). Required Shizuku UserService teardown. |
| No extra UserService opcodes | **PASS** | `onTransact` `when` (`ClipboardUserService.kt:48-93`) handles `INTERFACE_TRANSACTION` plus the six contract codes, then `super.onTransact`. Callback binder (`ShizukuClipboardBinder.kt:159-177`) is `ON_CHANGED` / `ON_CLIPBOARD_DIED` only (change signal, no payload). |
| App-side session API matches | **PASS** | `ShizukuClipboardSession` (`ShizukuRuntime.kt:52-62`) is `readText` / `writeText` / `addChangedListener` / `removeChangedListener` / `pingHealth`. |

Declared public methods on `ClipboardUserService` itself (not inherited `Binder` APIs): `destroy`, `binderDied`. `onTransact` is a protected `Binder` override (`ClipboardUserService.kt:48-94`), not a public Shizuku method. `binderDied` is `IBinder.DeathRecipient` (`ClipboardUserService.kt:108-116`) and only clears the clipboard adapter + notifies the app callback.

System `IClipboard` reflection used *inside* the UserService (`IClipboardReflectionAdapter.kt:126-129`) is `getPrimaryClip` / `setPrimaryClip` / `addPrimaryClipChangedListener` / `removePrimaryClipChangedListener` only.

### A.2 Must not receive network packets

| Item | Verdict | Evidence |
|---|---|---|
| UserService / binder / runtime hold no sockets or HTTP clients | **PASS** | Grep of `shizuku/**` for `Socket`, `OkHttp`, `WebSocket`, `HttpURLConnection`, `ServerSocket`: no hits. `AndroidShizukuRuntime` only binds the Shizuku UserService (`AndroidShizukuRuntime.kt:31-37`, `167-198`). |

### A.3 Must not hold identity keys / pair secrets

| Item | Verdict | Evidence |
|---|---|---|
| No identity keys, pair secrets, tokens, or nonces on the UserService / binder | **PASS** | Grep of `shizuku/**` for pair-secret / identity-key / nonce / token stores: no hits. Binder arguments are interface token, clip text (write/read only), listener `IBinder`, and error-code strings (`ShizukuErrorCodes.kt:6-13`). `AndroidShizukuRuntime.UserServiceArgs` (`AndroidShizukuRuntime.kt:31-37`) is component name, `daemon(false)`, process suffix, version `1`. |

### A.4 Must not execute arbitrary shell commands

| Item | Verdict | Evidence |
|---|---|---|
| No `Runtime.exec` / `ProcessBuilder` / shell string API on the UserService | **PASS** | Grep of `shizuku/**` for `Runtime.exec`, `ProcessBuilder`, `Os.exec`: no hits. Adapter uses `ServiceManager.getService("clipboard")` reflection (`ClipboardUserService.kt:203-223`), not a shell. |

### A.5 Must not log clipboard text

| Item | Verdict | Evidence |
|---|---|---|
| UserService logs | **PASS** | Sole `Log` call is `Log.w(TAG, "shell identity align failed: ${error.javaClass.simpleName}")` (`ClipboardUserService.kt:303`). Class name only. Error codes are stable tags (`ShizukuErrorCodes.kt:5`). |

### A.6 Arguments that cross the binder

| Direction | Payload | Notes |
|---|---|---|
| App → UserService `WRITE` | interface token + UTF-16 string (clip body) | Necessary for privileged write fallback. |
| UserService → App `READ` reply | status int; on text, the clip body; on failure, error-code string | Necessary for privileged read. Body is not logged. |
| App → UserService `ADD_LISTENER` | interface token + callback `IBinder` | Callback later receives opcode only (`TRANSACTION_ON_CHANGED` / `ON_CLIPBOARD_DIED`), no clip body (`ClipboardUserService.kt:266-284`). |
| `REMOVE_LISTENER` / `PING` / `DESTROY` | interface token; ping replies with int code | No clip body. |

### A.7 Shizuku audit summary

UserService surface matches the plan (read / write / listener / health / destroy). No network, secrets, or arbitrary shell. Locked by `ShizukuUserServiceSurfaceAuditTest`.

---

## B. Overlay (`platform/clipboard/overlay/**`)

### B.1 Window always `FLAG_NOT_TOUCHABLE`

| Item | Verdict | Evidence |
|---|---|---|
| Idle spec keeps the flag | **PASS** | `idleSpec()` flags = `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE` (`OverlayFocusController.kt:121-127`). |
| Read spec keeps the flag | **PASS** | `readSpec()` flags = `FLAG_NOT_TOUCHABLE` only — focusable for the read, still not touchable (`OverlayFocusController.kt:129-135`). |
| Apply path refuses a drop | **PASS** | `applyWindow` `require(spec.flags and FLAG_NOT_TOUCHABLE != 0)` (`OverlayFocusController.kt:114-117`). |
| Platform copies spec flags; no clear | **PASS** | `AndroidOverlayPlatform.attachOrUpdateWindow` assigns `layout.flags = spec.flags` (`OverlayFocusController.kt:226`). No `and FLAG_NOT_TOUCHABLE.inv()`. |
| Touchable-required ROM is unavailable | **PASS** | `requiresTouchableWindowToRead()` (`OverlayFocusController.kt:71-74`, `214`); polling probe `UNAVAILABLE` (`OverlayPollingBackend.kt:59`). |

No path removes `FLAG_NOT_TOUCHABLE`.

### B.2 Transparent and 1×1

| Item | Verdict | Evidence |
|---|---|---|
| Size | **PASS** | `WINDOW_WIDTH_PX = 1`, `WINDOW_HEIGHT_PX = 1` (`OverlayFocusController.kt:147-148`); both specs use them. |
| Alpha | **PASS** | `WINDOW_ALPHA = 0f` (`OverlayFocusController.kt:149`); view `overlay.alpha = 0f` (`OverlayFocusController.kt:231`); `PixelFormat.TRANSLUCENT` (`OverlayFocusController.kt:220`). |
| Type | **PASS** | `TYPE_APPLICATION_OVERLAY` (`OverlayFocusController.kt:126`, `134`, `153`). |

### B.3 Created only after user consent

Trace:

1. Wizard stores `WizardChoices.overlayConsented` (default `false`, `WizardModels.kt:31`) via `WizardViewModel.setOverlayConsented` (`WizardViewModel.kt:93-97`) / skip overlay (`WizardViewModel.kt:49-50`).
2. UI derives `overlayEnabled = choices.overlayConsented && overlayReady` (`WizardViewModel.kt:186-197`). That flag is **display-only**.
3. `BackgroundClipboardBackends.build` always constructs `OverlayFocusController` (`BackgroundClipboardBackends.kt:89-91`). Construction does not attach a window (comment at `BackgroundClipboardBackends.kt:13-15`).
4. First attach is `OverlayFocusController.applyWindow` → `AndroidOverlayPlatform.attachOrUpdateWindow` (`OverlayFocusController.kt:114-118`, `216-237`), reached from `doReadText()` after `canDrawOverlays()` (`OverlayFocusController.kt:66-77`).
5. Callers of `readText()` / attach:
   - `OverlayPollingBackend.start` → `refreshBaseline` (`OverlayPollingBackend.kt:75-79`, `109-113`)
   - `OverlayPollingBackend.onTick` (`OverlayPollingBackend.kt:130`)
   - `AdbLogOverlayBackend` injected `overlayController::readText` (`BackgroundClipboardBackends.kt:95-98`)
   - `ClipboardAccessCoordinator.switchTo` `nextBackend.readText()` (`ClipboardAccessCoordinator.kt:171`)
6. `MainActivity` starts the coordinator with `preferredReadMode` and `autoFallbackAllowed` only (`MainActivity.kt:126-148`). It never reads `overlayConsented`.
7. `WizardChoices` defaults `preferredReadMode = SHIZUKU_EVENT` and `autoFallbackAllowed = true` (`WizardModels.kt:26-27`). If Shizuku is not `READY` and `SYSTEM_ALERT_WINDOW` is granted, `selectAndStart` can start `OVERLAY_POLLING` (`ClipboardAccessCoordinator.kt:106-125`, `269-274`) and attach the window.

| Item | Verdict | Evidence |
|---|---|---|
| Overlay attach gated on `overlayConsented` | **VIOLATION** | Consent is persisted and shown (`WizardViewModel.kt:197`, `WizardSettings.kt:74-76`) but not consulted by `MainActivity.kt:126-136`, `ClipboardAccessCoordinator`, `OverlayPollingBackend`, or `OverlayFocusController`. System `canDrawOverlays()` is the only gate (`OverlayFocusController.kt:67-70`). |

**Fix suggestion:** In `BackgroundClipboardBackends.build` / `MainActivity` coordinator assembly, omit or no-op the overlay and ADB-overlay backends unless `wizardChoices.overlayConsented` is true. In `ClipboardAccessCoordinator.selectAndStart`, treat `OVERLAY_POLLING` and `ADB_LOG_OVERLAY` as missing when consent is false (do not `readText()` / `start()` them). Keep `overlayEnabled` as the same conjunction the wizard already computes.

### B.4 Removed on stop / background / pause / permission-revoked / abnormal exit

`OverlayFocusController.releaseFocus()` (`OverlayFocusController.kt:57-64`) only reapplies idle flags. It does **not** call `OverlayPlatformSeam.detachWindow()` (`OverlayFocusController.kt:179`, implementation `279-288`). The controller has no public detach. `BackgroundClipboardBackends.coordinator` wires `releaseFocusResource = overlayController::releaseFocus` (`BackgroundClipboardBackends.kt:70`).

| Item | Verdict | Evidence |
|---|---|---|
| Stop (overlay polling backend) | **VIOLATION** | `OverlayPollingBackend.stop` calls `controller.releaseFocus()` (`OverlayPollingBackend.kt:82-88`), leaving the 1×1 window attached. |
| Stop (coordinator) | **VIOLATION** | `ClipboardAccessCoordinator.stop` (`ClipboardAccessCoordinator.kt:97-104`) stops the backend but does not call `releaseFocusResource()`. If the active mode is `ADB_LOG_OVERLAY`, `AdbLogOverlayBackend.stop` (`AdbLogOverlayBackend.kt:84-88`) does not release or detach the overlay used to read the body. |
| Background / pause | **VIOLATION** | `MainActivity` has `onDestroy` only (`MainActivity.kt:499-507`); no `onPause` / `onStop` / process-lifecycle hook. Screen-off / keyguard pauses polling and `releaseFocus()` (`OverlayPollingBackend.kt:126-128`) but does not remove the window. `TYPE_APPLICATION_OVERLAY` outlives the activity. |
| Permission revoked | **VIOLATION** | `doReadText` returns `OVERLAY_PERMISSION_MISSING` without detaching (`OverlayFocusController.kt:67-70`). `OverlayPollingBackend.health` / `probe` leave `READY` (`OverlayPollingBackend.kt:97-98`, `57-58`) but do not detach. `onTick` still calls `readText()` after revoke (`OverlayPollingBackend.kt:122-130`). |
| Abnormal exit (process death) | **N-A** | A killed process drops its overlay views. No extra in-process teardown. |
| Mode switch releases focus | **PASS** (flags only) | `switchTo` / `rollback` call `releaseFocusResource()` (`ClipboardAccessCoordinator.kt:170`, `199`) — idle flags, not remove. |
| Activity destroy stops coordinator | **PASS** (partial) | `MainActivity.onDestroy` → `clipboardAccess?.stop()` (`MainActivity.kt:499-501`). Removal still missing (see stop rows). |

**Fix suggestion:** Add `OverlayFocusController.detach()` that calls `platform.detachWindow()`. Call it from `releaseFocusResource` (or a new `releaseOverlayResource`), `OverlayPollingBackend.stop`, `AdbLogOverlayBackend.stop` (via injected closer), `ClipboardAccessCoordinator.stop`, permission-missing `doReadText` / `health` when a window exists, and `MainActivity.onPause` / `onDestroy`. After revoke, `onTick` must return without `readText()`.

### B.5 Production health cycle

| Item | Verdict | Evidence |
|---|---|---|
| Coordinator health loop exists in production | **VIOLATION** | `ClipboardAccessCoordinator.checkHealth()` (`ClipboardAccessCoordinator.kt:68-95`) is only invoked from unit tests, not `MainActivity` or `ClipboardSyncService`. Probe/health *APIs* flip in one call (see tests); the app never schedules that call. |

**Fix suggestion:** Schedule `checkHealth()` on a bounded interval from the clipboard owner (activity + service) and on permission / lifecycle callbacks so revoke degrades within one cycle as plan.md 阶段 6 验收 requires.

---

## C. `READ_LOGS` data minimization (`platform/clipboard/adblog/**`)

### C.1 Raw logcat lines only in memory

| Item | Verdict | Evidence |
|---|---|---|
| Reader keeps lines off disk | **PASS** | `LogcatClipboardEventReader` comment and design (`LogcatClipboardEventReader.kt:32-34`). `acceptLine` parses then drops the string (`LogcatClipboardEventReader.kt:109-122`); retained state is `ClipboardLogMatch` (family + parser version, `ClipboardLogParsers.kt:15-18`) plus counters / timestamps. |
| Production source is a process stream | **PASS** | `ProcessLogcatLineSourceFactory` (`LogcatClipboardEventReader.kt:228-244`): `Process` + `BufferedReader` on stdout; `close()` destroys the process. No `File` / `FileOutputStream`. |
| Bounded tags from process start | **PASS** | `defaultCommand` (`LogcatClipboardEventReader.kt:248-263`): `logcat -T <stamp>` plus known clipboard tags and `*:S`. |

### C.2 Never persisted / uploaded / put into crash reports or `CapabilityReport`

| Item | Verdict | Evidence |
|---|---|---|
| `CapabilityReport` fields | **PASS** | `AdbLogOverlayBackend.probe` (`AdbLogOverlayBackend.kt:57-72`) writes mode, state, `systemVersion`, `ClipboardAuthorization("read_logs", granted)`, `lastReadSuccessAtEpochMillis`, and a stable `errorCode`. `CapabilityReport` (`ClipboardModels.kt:33-42`) has no line / body field. |
| Crash / telemetry | **PASS** | No Crashlytics / Sentry / Firebase / cloud crash reporter in `android/app/src/main`. Plan 0.2: no default telemetry. |
| Upload of raw lines | **PASS** | Adblog package has no HTTP / multipart / share-of-logcat path. |

### C.3 Parser mismatch prefers no-trigger

| Item | Verdict | Evidence |
|---|---|---|
| Unknown / non-exact message does not match | **PASS** | `VersionedTagParser.match` requires tag ∈ set **and** message ∈ exact set (`ClipboardLogParsers.kt:102-110`). `matchKnownChange` returns null if no parser matches (`ClipboardLogParsers.kt:38-46`). Unknown format stays `DEGRADED` / `ADB_LOG_NO_HEALTHY_SIGNAL` (`AdbLogOverlayBackend.kt:146-148`). |

### C.4 Revocation flips probe within one health cycle

| Item | Verdict | Evidence |
|---|---|---|
| Backend `probe` / `health` after revoke | **PASS** | `diagnose` (`AdbLogOverlayBackend.kt:140-144`): if `sawGrant` then `DEGRADED` + `ADB_LOG_READ_LOGS_REVOKED`, else `NEEDS_USER_ACTION`. Neither is `READY`. `onLogSignal` returns before overlay read when not granted (`AdbLogOverlayBackend.kt:113-116`). |
| App-level one health cycle | **VIOLATION** | Same as B.5: `checkHealth()` is never scheduled in production, so coordinator fallback / UI may stay stale until something else probes. |

**Fix suggestion:** Same health scheduler as B.5. After revoke, `AdbLogOverlayBackend.health` / `probe` already leave `READY`; the coordinator must call them.

### C.5 `scripts/android-bootstrap.ps1` never auto-grants

| Item | Verdict | Evidence |
|---|---|---|
| Script is read-only | **PASS** | Builds grant/revoke strings (`scripts/android-bootstrap.ps1:63-64`) and `Write-Host` them (`:80-82`). States “This script is read-only. It never runs grant or revoke.” (`:77`). No `adb ... pm grant` invocation; no `Invoke-Expression` of those strings. |

---

## D. Repo-wide clipboard-content log leak grep

Rules: Android `Log.*` / `println` / exception messages must not carry clip text. Windows `LocalDiagnostics.Write` / `Debug.WriteLine` / exception messages under `ClipSync.App` and `ClipSync.Core` must be event tags, never bodies.

### D.1 Android (`android/app/src/main`)

| Location | Verdict | Notes |
|---|---|---|
| `ClipboardUserService.kt:303` | **PASS** | `Log.w` + exception simple name. |
| All other `Log.` / `println` in main | **PASS** | Only other `println` is a test E2E marker (`CrossClientSyncE2eTest.kt`), not main. |
| `SyncLogger` (`SyncSessionEngine.kt`, `SyncController.kt`) | **PASS** | Event tags only (`frame_rejected`, `received`, `peer_error`, `session_ready`, …). `SyncLogger.NoOp` default (`SyncSessionModels.kt:43-47`). |
| Exception messages in clipboard / storage / shizuku | **PASS** | Failures use stable error codes, not clip bodies. |

**Hits carrying clip text:** none.

### D.2 Windows `ClipSync.App` and `ClipSync.Core`

`LocalDiagnostics.Write` (`windows/ClipSync.App/Diagnostics/LocalDiagnostics.cs:7-18`) appends a single `code` string to `CLIPSYNC_DIAGNOSTICS_PATH` when set.

| Call | Verdict |
|---|---|
| `App.xaml.cs:72` `listener_started` | **PASS** |
| `App.xaml.cs:117` `peer_start_failed_{exception.GetType().Name}` | **PASS** |
| `App.xaml.cs:187` `remote_applied` | **PASS** |
| `App.xaml.cs:194` `remote_apply_failed_{exception.GetType().Name}` | **PASS** |
| `App.xaml.cs:231` `text_changed` | **PASS** |
| `App.xaml.cs:236` `capture_stored` | **PASS** |
| `App.xaml.cs:241` `capture_rejected_{rejected.Reason}` | **PASS** (enum tag) |
| `App.xaml.cs:246` `capture_failed_{exception.GetType().Name}` | **PASS** |
| `App.xaml.cs:247` `Debug.WriteLine` + exception type name | **PASS** |
| `App.xaml.cs:252` `adapter_fault_{operation}_{exception type}` | **PASS** |
| `PeerSyncHost.cs:120` `peer_server_started_port_{port}` | **PASS** (port, not body) |
| `ClipSync.Core` `Debug.WriteLine` / `LocalDiagnostics` | **N-A** | No matches. |

`OnClipboardTextChanged` receives `e.Text` (`App.xaml.cs:223`) and passes it to capture; it does not write `e.Text` to diagnostics.

**Hits carrying clip text:** none.

`ClipSync.Peer` `PeerLog` (outside the requested App/Core scope) logs type / code / counts, not bodies. Noted only.

---

## E. Dependency vulnerability finding (accepted / tracked)

`dotnet list package --vulnerable --include-transitive` (via `scripts/static-analysis.ps1`) reports one High finding:

| Package | Version | Advisory | Verdict |
|---|---|---|---|
| `SQLitePCLRaw.lib.e_sqlite3` (transitive via `Microsoft.Data.Sqlite` 8.0.25) | 2.1.6 | [GHSA-2m69-gcr7-jv3q](https://github.com/advisories/GHSA-2m69-gcr7-jv3q) / CVE-2025-6965 — SQLite < 3.50.2 aggregate-term memory corruption | **ACCEPTED / TRACKED** |

- **Whole 2.1.x line is affected** (advisory: `<= 2.1.11`), so no same-major pin fixes it. The only patched native is the `SQLitePCLRaw` 3.x provider (`lib.e_sqlite3` 3.53.3), which is an unsupported major bump for `Microsoft.Data.Sqlite` 8.0.x (the EF team calls bumping the bundle to v3 a breaking change, not a patch). Verified locally: pinning 2.1.11 stays flagged; a 3.0.5 bundle leaves a mixed native graph while the transitive 2.1.6 remains in the scan.
- **Not reachable in this app.** CVE-2025-6965 needs an attacker-controlled aggregate query whose term count exceeds the column count. ClipSync issues only first-party, fixed-schema, fully parameterized SQL (`SqliteClipboardEventStore*`, Room DAOs); peer input arrives as validated JSON and is mapped to bound parameters, never concatenated into SQL. No untrusted SQL text reaches SQLite.
- **Upgrade path:** adopt the patched native when Microsoft ships a patched `Microsoft.Data.Sqlite` 8.x, or validate `SQLitePCLRaw` 3.53.3 (bundle 3.x) end-to-end (the storage tests already pass against a 3.x native in a spike). Tracked inline in `windows/ClipSync.Core/ClipSync.Core.csproj`.

---

## F. Violation index (orchestrator)

1. **Overlay consent not enforced** — `MainActivity.kt:126-136`, `WizardViewModel.kt:197` (UI only). Gate `OVERLAY_POLLING` / `ADB_LOG_OVERLAY` start and `readText` on `overlayConsented`.
2. **Overlay window never detached** — `OverlayFocusController.kt:57-64` (release ≠ remove); `OverlayFocusController.kt:67-70` (permission miss); `OverlayPollingBackend.kt:82-88`; `AdbLogOverlayBackend.kt:84-88`; `ClipboardAccessCoordinator.kt:97-104`. Add `detach()` and call it on stop / pause / revoke.
3. **No production health cycle** — `ClipboardAccessCoordinator.kt:68` unused from main. Schedule `checkHealth()` so Shizuku / overlay / `READ_LOGS` revoke leaves `READY` in one cycle (plan 阶段 6 验收).
4. **Overlay poll still calls `readText` after revoke** — `OverlayPollingBackend.kt:122-130`. Check `canDrawOverlays()` and detach / return.

No Shizuku surface, log-leak, bootstrap auto-grant, or adblog persist/upload violations found.

**Resolution (Stage 6 wave B):** all four overlay/health violations above are FIXED and locked with tests. `overlayConsented` now gates `OVERLAY_POLLING` / `ADB_LOG_OVERLAY` selection and fallback (`MainActivity` → `BackgroundClipboardBackends.build`); `OverlayFocusController.detach()` is called on coordinator `stop()`, backend stop, mode switch, and `MainActivity.onStop()`/`onDestroy()`; a 10s cancellable `ClipboardHealthLoop` runs `checkHealth()` while resumed; overlay polling stops scheduling and detaches on `OVERLAY_PERMISSION_MISSING` / health `FAILED` and resumes only after a successful re-check. Remaining gap: revoke while ONLY the foreground service (not the activity) is alive still relies on the next activity resume — a service-side health loop is deferred to a future stage.
