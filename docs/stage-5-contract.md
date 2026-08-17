# Stage 5 parallel contract

Status: wave 2 in progress on `feature/stage-4`. Wave 1 committed as `64c5a2b` (5.1 state machine, 5.2 ForegroundService, 5.3 Shizuku, 8.3 doc re-verification). All Android tests green at 247/0 (1 skipped = gated E2E) after wave 1.

Stage 5 = Android background clipboard capability (plan.md 5.1–5.7). Wave 2 covers 5.5 (overlay polling + `OverlayFocusController`), 5.4 (ADB `READ_LOGS` log event mode), and the capability wizard UI (5.1 guidance surface). Stage-5 change log and the wave-2 commit are done by the orchestrator after consolidation.

Hard rules for every agent (plan.md sections 9, 0.2, and the 2026-08-17 re-verification in `docs/android-background-clipboard.md`):

- No Root, no `targetSdk` lowering, no silent adb, no accessibility service, no IME.
- Never log clipboard content, tokens, secrets, nonces, raw logcat lines, target app names, or Shizuku command output.
- Permission granted ≠ READY. Only a real successful probe/read flips state. `READ_LOGS` present is not READY until a matching log signal is actually read.
- The overlay read window must keep `FLAG_NOT_TOUCHABLE` at all times; only `FLAG_NOT_FOCUSABLE` is removed during a read, then immediately restored. 1x1, alpha 0. If a ROM needs a touchable window to read, that mode is UNAVAILABLE — do not trade away touch safety.
- Android 15+: holding `SYSTEM_ALERT_WINDOW` does NOT grant background FGS start; do not assume the overlay lets you launch a service from background. Overlay is a read tool only here.
- Do not start a transparent Activity from the background. Overlay uses `TYPE_APPLICATION_OVERLAY` only, and only after the user granted it.
- Physical-device results stay `NOT_TESTED`. JVM fakes prove logic; instrumentation tests (if any) must skip without the permission/device.
- Write failing tests first. Keep `minSdk 29`, `targetSdk 35`. Do not edit `ClipboardModels.kt` (frozen surface).
- Parallel Gradle runs race; scope test runs with `--tests`, retry once. The orchestrator runs the final full build.

## Shared environment

- `$env:ANDROID_HOME = 'D:\paste-tools\android-sdk'`; JDK 17 on PATH; build via `pwsh scripts/build-android.ps1`.
- `BackgroundClipboardBackend` interface: `val mode`, `probe(): CapabilityReport`, `start(onChanged: (ClipboardChange) -> Unit)`, `stop()`, `readText(): ClipboardReadResult`, `health(): BackendHealth`.
- `ClipboardAccessCoordinator` already accepts a list of backends and a `releaseFocusResource: () -> Unit` hook (currently a no-op). Wave 2 does NOT rewire the coordinator; the orchestrator wires the new backends + focus release during consolidation. Do not edit `ClipboardAccessCoordinator.kt` or `ClipboardWriteCoordinator.kt`.
- `CapabilityState` values: `UNKNOWN`, `READY`, `DEGRADED`, `UNAVAILABLE`, `NEEDS_USER_ACTION`. `BackendHealthState`: `HEALTHY`, `DEGRADED`, `FAILED`, `STOPPED`.
- `ContentHasher` / `Sha256ContentHasher` in the clipboard package.

## Decoupling rule (critical, avoids L/M compile deadlock)

The overlay read primitive is shared by 5.5 and 5.4. To let both agents compile independently:

- Agent L owns `OverlayFocusController` and exposes a public method `fun readText(): ClipboardReadResult`.
- Agent M's `AdbLogOverlayBackend` must NOT reference `OverlayFocusController` by type. It takes a constructor parameter `private val readOverlayText: () -> ClipboardReadResult` (a function value). The orchestrator passes `overlayFocusController::readText` at wiring time.
- Neither agent needs the other's classes to compile. Do not define a shared interface across packages for this.

## File ownership — wave 2

### Agent L — 5.5 OverlayFocusController + OverlayPollingBackend

Create/edit only:

- `android/app/src/main/java/com/clipsync/android/platform/clipboard/overlay/OverlayFocusController.kt` (new)
- `android/app/src/main/java/com/clipsync/android/platform/clipboard/overlay/OverlayPollingBackend.kt` (new)
- `android/app/src/test/java/com/clipsync/android/platform/clipboard/overlay/**`
- `android/app/src/main/AndroidManifest.xml` — ONLY additive `SYSTEM_ALERT_WINDOW` uses-permission (re-read before edit; Agent N may also touch the manifest)
- `android/app/src/main/res/values/strings.xml` — additive strings only

Must:

1. `OverlayFocusController`: single-thread serialize all overlay reads (a lock/queue). Window is 1x1, alpha 0, `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE` when idle; a read removes only `FLAG_NOT_FOCUSABLE`, keeps `FLAG_NOT_TOUCHABLE`, retries up to 3 times at 25–50 ms, then restores the focus flag immediately. `readText(): ClipboardReadResult`. Pause active listener notion is the coordinator's job; here just serialize + never drop touch-safety.
2. Wrap all `WindowManager` / `Settings.canDrawOverlays` / `ClipboardManager` access behind a small injectable seam (interface in this file) so JVM tests fake it. Do not construct real Android views in unit tests.
3. If `canDrawOverlays` is false → `readText` returns `Failure("OVERLAY_PERMISSION_MISSING")` (maps to NEEDS_USER_ACTION at the backend probe). If a ROM requires a touchable window (fake seam signals it) → mark UNAVAILABLE; never drop `FLAG_NOT_TOUCHABLE`.
4. `OverlayPollingBackend : BackgroundClipboardBackend`, `mode = OVERLAY_POLLING`. Coroutine/Handler-driven poll, default 800–1000 ms, user-adjustable 500–2000 ms (constructor param). Each tick reads via the controller, compares hash to last; only a change emits `ClipboardChange`. Pause when screen off / keyguard locked / service unhealthy (inject a `fun canPollNow(): Boolean` seam). probe(): READY only when overlay permission present and screen interactive; NEEDS_USER_ACTION when permission missing; never claims READY without permission. Stable error codes as companion consts.
5. Tests (JVM, faked seams): permission missing → NEEDS_USER_ACTION; touchable-required ROM → UNAVAILABLE and touch flag never dropped; read serialization (concurrent readText calls do not interleave); poll emits only on hash change; poll paused when screen off; focus flag restored after read and after exception.

### Agent M — 5.4 ADB log event mode + bootstrap

Create/edit only:

- `android/app/src/main/java/com/clipsync/android/platform/clipboard/adblog/AdbLogOverlayBackend.kt` (new)
- `android/app/src/main/java/com/clipsync/android/platform/clipboard/adblog/LogcatClipboardEventReader.kt` (new)
- `android/app/src/main/java/com/clipsync/android/platform/clipboard/adblog/ClipboardLogParsers.kt` (new; versioned ROM parsers)
- `android/app/src/test/java/com/clipsync/android/platform/clipboard/adblog/**`
- `scripts/android-bootstrap.ps1` (enhance; keep it read-only/no-auto-grant)
- `android/app/src/main/AndroidManifest.xml` — ONLY additive `READ_LOGS` uses-permission with a comment that it cannot be granted via a normal runtime dialog (re-read before edit; L and N also touch the manifest)
- `android/app/src/main/res/values/strings.xml` — additive strings only

Must:

1. `AdbLogOverlayBackend : BackgroundClipboardBackend`, `mode = ADB_LOG_OVERLAY`. Constructor takes `readOverlayText: () -> ClipboardReadResult` (DO NOT import OverlayFocusController). The log reader only signals "changed"; body is read via `readOverlayText()`, hashed with `ContentHasher`, then emitted.
2. `LogcatClipboardEventReader`: reads a bounded logcat stream from process start (inject the process/stream via a seam so JVM tests feed canned lines; do not spawn a real `logcat` in unit tests). Keep only in-memory recent state; never persist or upload raw lines. 150 ms debounce + single-flight on change signals.
3. `ClipboardLogParsers`: versioned parsers for AOSP, OneUI, MIUI/HyperOS, ColorOS/OriginOS clipboard-change log signatures. Unknown format → do NOT trigger (no false positives). Store anonymized matched/unmatched fixtures in the test source set, not real logcat.
4. Probe honesty: `READ_LOGS` declared/granted is NOT READY. READY requires the reader to have actually matched a known signal recently; otherwise DEGRADED/NEEDS_USER_ACTION. On permission revoked or 10 s no healthy signal → DEGRADED with a stable code (coordinator handles fallback).
5. `android-bootstrap.ps1`: extend the existing read-only inspector to also print (not run) the exact `pm grant ... READ_LOGS` and `revoke` commands per selected serial, show current grant state, and require explicit human action. Never auto-grant. Re-probe note: install/upgrade/reboot invalidates the grant.
6. Tests (JVM, faked seams + fixtures): each ROM parser matches its own fixtures and rejects foreign/unknown lines; debounce/single-flight; signal→readOverlayText→hash→emit pipeline; permission-revoked → DEGRADED; unknown-format never emits; probe not READY until a real match.

### Agent N — capability wizard UI (5.1 guidance)

Create/edit only:

- `android/app/src/main/java/com/clipsync/android/ui/wizard/**` (new)
- `android/app/src/main/java/com/clipsync/android/MainActivity.kt` (add a wizard entry/tab or post-pair launch; keep existing tabs and paired_peer_id wiring intact)
- `android/app/src/main/res/values/strings.xml` — additive strings only
- `android/app/src/test/java/com/clipsync/android/ui/wizard/**`

Must:

1. A capability wizard shown after first pairing (and reachable from Settings). Steps, each explaining purpose / risk / skip consequence, each independently checkable: notifications, foreground service, ignore battery optimizations, overlay (`SYSTEM_ALERT_WINDOW`), `READ_LOGS` (adb-only, cannot be granted in-app), Shizuku binder + Shizuku authorization. Do not bury any of these; each is a distinct card with a status and an action.
2. User choices surfaced (persist via a `WizardChoices`/settings seam you define in `ui/wizard`, backed by the existing settings store through an injected interface — do NOT edit storage or settings-plumbing files owned elsewhere; define a minimal `WizardSettings` interface in your package and a no-op/in-memory impl for tests): preferred read mode (default SHIZUKU_EVENT), allow auto-fallback, polling interval, background auto-upload on/off, background auto-apply on/off. Default: do not enable overlay without explicit consent; write defaults to public API (no overlay required for write).
3. Pure presentation + a `WizardViewModel` that takes injected capability probes (define minimal function-type seams like `() -> CapabilityState` per capability; the orchestrator passes real probes at wiring — do not import Shizuku/overlay backends directly). Four separate live indicators must never collapse into one green.
4. `READ_LOGS` card explicitly says it is granted only via adb (`android-bootstrap.ps1`), never an in-app dialog, and re-checks after install/upgrade/reboot.
5. Tests (JVM): step completion logic, skip consequences, four-indicator independence, defaults (SHIZUKU_EVENT preferred, overlay off unless consented, write defaults to public API), interval clamp 500–2000 ms.

## Wiring done by orchestrator (not the agents)

After L/M/N land, the orchestrator: constructs the backend list (Shizuku, AdbLogOverlay with `overlayController::readText`, OverlayPolling, Foreground) into `ClipboardAccessCoordinator`, passes `overlayController`'s release as `releaseFocusResource`, connects wizard probes to real backends, runs the full build, writes `docs/stage-5-change-log.md`, commits.

## Out of scope wave 2

Coordinator/writer rewrite, actual multi-ROM device runs, stage-6 hardening, stage-5 change log (orchestrator writes it).
