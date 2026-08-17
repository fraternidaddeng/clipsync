# Stage 6 parallel contract

Status: wave A in progress on `feature/stage-4`. Base commit: `f436520` (Stage 5 wave 2 + MIUI device verification). Baseline: Android `testDebugUnitTest` 343/0 (1 skipped gated E2E); Windows Debug 0 warn 0 err, Tests 169 + App.Tests 33.

Stage 6 = reliability, security, and privacy hardening (plan.md 阶段 6). Wave A covers: Windows transport input hardening, the unified Android capture policy engine + blacklist, loop/idempotency stress tests, and the three security audits + migration/export design. Static analysis, Windows data hygiene, WorkManager boot check, and Shizuku write-fallback wiring follow in wave B after consolidation.

Hard rules for every agent (plan.md sections 9, 0.2):

- No Root, no `targetSdk` lowering, no silent adb, no accessibility service, no IME.
- Never log or persist clipboard content, tokens, secrets, nonces, raw logcat lines, target app names, or Shizuku command output — including in test names, error messages, and docs.
- Overlay windows keep `FLAG_NOT_TOUCHABLE` always. `ClipboardModels.kt` is frozen.
- Write failing tests first. Keep `minSdk 29`, `targetSdk 35`. All owned tests green before finishing.
- Do not run git commands. Do not edit files owned by another agent. Parallel Gradle/dotnet runs race: scope test runs (`--tests` / `--filter`), retry once on infra-looking failures.

## Shared environment

- Android: `$env:ANDROID_HOME = 'D:\paste-tools\android-sdk'`; JDK 17 on PATH; `cd D:\paste\android; .\gradlew.bat :app:testDebugUnitTest --tests <pattern>`.
- Windows: `$env:DOTNET_ROOT = 'D:\paste-tools\dotnet'; $env:PATH = "$env:DOTNET_ROOT;" + $env:PATH`; `dotnet test D:\paste\windows\ClipSync.sln --filter <pattern>` or scoped project test.

## File ownership — wave A

### Agent W (Windows transport hardening, plan 6 "校验文本大小、JSON 深度、WebSocket 帧大小和连接速率")

Owns: `windows/ClipSync.Peer/**`, `windows/ClipSync.Core/Protocol/**`, and Windows test files for those areas in `windows/ClipSync.Tests/**`. Must not touch `ClipSync.App*`, `ClipSync.Core/Clipboard|Security|Storage`, `E2eHost`.

### Agent P (Android capture policy engine + blacklist, plan §3.4 + 阶段 6 黑名单 + 清空提示)

Owns: `android/.../storage/**`, `android/.../share/**`, `android/.../tile/**`, `android/.../ui/settings/SettingsKeys.kt|SettingsViewModel.kt|SettingsScreen.kt|LocalCapturePolicy.kt`, `android/.../ui/history/HistoryScreen.kt` (clear-hint line only), `res/values/strings.xml`, plus their test files. Must not touch `MainActivity.kt`, `ClipServices.kt`, `sync/**`, `platform/**`, `service/**`, `ui/wizard/**`.

### Agent L (loop/idempotency stress tests, plan 6 "1000 次回环压测")

Owns: NEW test files only, under `android/app/src/test/java/com/clipsync/android/e2e/` and/or `.../sync/`. Reads everything, modifies nothing existing. Avoid exhaustive `when` over enums another agent may extend (use `else`).

### Agent A (security audits + migration/export design; docs + NEW tests only)

Owns: `docs/stage-6-security-audit.md`, `docs/stage-6-migration-export.md` (new), plus NEW test files under `android/app/src/test/**` for audit invariants. Modifies no existing file; violations found are reported in the doc, not fixed inline.

## Consolidation

The orchestrator merges, resolves cross-agent compile issues, runs the full Android + Windows suites, wires wave B, and writes `docs/stage-6-change-log.md`. Physical-device items (P95, sleep/wake, multi-ROM, reboot recovery) stay `NOT_TESTED` until a human runs them.
