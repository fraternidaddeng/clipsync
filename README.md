# ClipSync

ClipSync is a private, direct peer-to-peer clipboard synchronization project for Windows and Android. The repository has completed **Stage 3** (Windows peer endpoint, QR pairing, and device trust). **Stage 4** Android companion is implemented on `feature/stage-4`: OkHttp WebSocket sync, Room history UI, share target, Quick Settings tile, and inbound notifications. End-to-end pairing/sync is pending wave 3. Physical Android ROM coverage remains `NOT_TESTED`.

No account service, cloud database, public relay, file transfer, telemetry, or clipboard-content logging is included.

## Prerequisites

- Windows 10 22H2 or Windows 11
- Git
- .NET 8 SDK (the repository is pinned by `global.json`)
- JDK 17
- Android Studio with Android SDK Platform 35 and Platform Tools
- PowerShell 7 is recommended; Windows PowerShell 5.1 is also supported by the bootstrap scripts

The development machine used for the recorded validation has a repository-local .NET 8 SDK, JDK 17, and a repository-local Android SDK. These tool directories are ignored and are not project dependencies. Android JVM tests, APK assembly, and an API 35 emulator launch were verified; physical Android ROM coverage remains explicitly `NOT_TESTED`.

## Build and test

Windows:

```powershell
pwsh .\scripts\build-windows.ps1
```

Android:

```powershell
pwsh .\scripts\build-android.ps1
```

Protocol fixtures:

```powershell
pwsh .\scripts\validate-protocol.ps1
```

The Windows app starts in the notification area without opening its main window. It captures pure text locally, persists it in SQLite, and exposes history/search/delete/clear and pause/private/retention/source-block settings. Pairing and networking intentionally begin in Stage 2; the Android app remains the Stage 0 capability shell.

Windows Stage 1 acceptance checks:

```powershell
pwsh .\scripts\run-windows-stage1-smoke.ps1
pwsh .\scripts\run-windows-stage1-stress.ps1 -Count 100
```

## Android device inspection

The bootstrap script is read-only by default. It lists adb devices, reports basic platform information, and prints grant/revoke commands for review. It never grants `READ_LOGS` automatically.

```powershell
pwsh .\scripts\android-bootstrap.ps1 -PackageName com.clipsync.android
```

## Repository layout

- `docs/`: frozen product, security, protocol, Android capability, ADR, and verification records.
- `protocol/v1/`: JSON Schema and shared cross-language fixtures.
- `windows/`: .NET 8 WPF shell and xUnit tests.
- `android/`: Kotlin/Compose shell and JVM unit tests.
- `scripts/`: repeatable build, validation, and explicit adb inspection commands.

## Branch policy

- `main`: releasable, reviewed stages only.
- `develop`: integration branch for the current stage.
- `feature/<short-name>`: scoped work branches merged into `develop`.

Stage completion requires its tests and acceptance checks to pass before merging `develop` into `main`. This repository was initialized on `main`; branches are created when the first commit exists so they point at an auditable baseline.

## Privacy and security baseline

- Only pure text is in scope for v1.
- Clipboard text must never enter ordinary logs or telemetry.
- Android special capabilities require visible, revocable user action.
- Network reachability is required; there is no NAT traversal service or public relay.
- TLS certificate pinning and per-pair secrets are mandatory in the pairing/network stages.

See [product scope](docs/product-scope.md), [threat model](docs/threat-model.md), [protocol v1](docs/protocol-v1.md), and the [Stage 1 change log](docs/stage-1-change-log.md) for the normative boundaries and recorded validation.

## License

MIT. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
