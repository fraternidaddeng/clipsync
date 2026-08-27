# ClipSync

[![CI](https://github.com/fraternidaddeng/clipsync/actions/workflows/ci.yml/badge.svg)](https://github.com/fraternidaddeng/clipsync/actions/workflows/ci.yml)

ClipSync is a private, direct peer-to-peer clipboard synchronization tool for Windows and Android. Version **0.1.0** delivers the full product: QR pairing with TLS 1.3 + certificate-fingerprint pinning, LAN/Tailscale sync with exactly-once offline catch-up, history/search/export on both ends, image sync (protocol v2, off by default), a Bluetooth RFCOMM fallback (text only, off by default), the four-tier Android background-read capability ladder, and a charter-themed UI in 19 languages. See `CHANGELOG.md` and `docs/releases/v0.1.0.md` for the release record.

No account service, cloud database, public relay, file transfer, telemetry, or clipboard-content logging is included.

## Prerequisites

- Windows 10 22H2 or Windows 11
- Git
- .NET 8 SDK (the repository is pinned by `global.json`)
- JDK 17
- Android Studio with Android SDK Platform 35 and Platform Tools
- PowerShell 7 is recommended; Windows PowerShell 5.1 is also supported by the bootstrap scripts

The development machine used for the recorded validation has a repository-local .NET 8 SDK, JDK 17, and a repository-local Android SDK. These tool directories are ignored and are not project dependencies. Android JVM tests, APK assembly, and an API 35 emulator launch were verified; physical-device verification was signed off by the user on 2026-08-26 (`docs/manual-qa-results.md`), while the per-device matrix slots in `docs/device-validation-matrix.md` stay `NOT_TESTED` until the structured details are backfilled.

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

A manual start opens the main window (first run shows a five-step onboarding); autostart with `--minimized` stays in the tray. The Windows app captures clipboard content locally, persists it in SQLite, and exposes history/search/delete/clear plus pause/private/retention/source-block settings. Pairing, networked sync, and the Android app are fully functional; end users should follow `docs/install.md`.

Windows Stage 1 acceptance checks:

```powershell
pwsh .\scripts\run-windows-stage1-smoke.ps1
pwsh .\scripts\run-windows-stage1-stress.ps1 -Count 100
```

## Release packaging and install

The minimal distribution chain (stage 7, trimmed) packages both ends into `dist/` (gitignored; publish the files as release artifacts):

```powershell
pwsh .\scripts\package-windows.ps1   # dist\ClipSync-windows-x64.zip  (+ .sha256)
pwsh .\scripts\package-android.ps1   # dist\ClipSync-android.apk      (+ .sha256)
```

- End users download the prebuilt packages from [GitHub Releases](https://github.com/fraternidaddeng/clipsync/releases) and verify each file's SHA-256 against its `.sha256` sidecar (also listed in the release notes) before installing — see `docs/install.md` §2.
- The Windows package is a self-contained portable ZIP (app, dependencies, and the .NET runtime; no installer, no registry writes). On Linux/CI hosts the script adds `-p:EnableWindowsTargeting=true` automatically so the win-x64 payload can be produced and verified there.
- The Android package is a release APK signed exclusively from the `CLIPSYNC_ANDROID_KEYSTORE`, `CLIPSYNC_ANDROID_KEYSTORE_PASSWORD`, `CLIPSYNC_ANDROID_KEY_ALIAS`, and `CLIPSYNC_ANDROID_KEY_PASSWORD` environment variables. The keystore and its passwords never enter the repository. `-Variant Debug` builds a debug-signed test APK instead.
- End users start at the Chinese one-page guide: [docs/install.md](docs/install.md) — prerequisites, LAN/Tailscale setup, QR pairing, Android capability routes, and troubleshooting. A copy ships inside the Windows ZIP.

## Android device inspection

The bootstrap script is read-only by default. It lists adb devices, reports basic platform information, and prints grant/revoke commands for review. It never grants `READ_LOGS` automatically.

```powershell
pwsh .\scripts\android-bootstrap.ps1 -PackageName com.clipsync.android
```

## Repository layout

- `docs/`: frozen product, security, protocol, Android capability, ADR, and verification records.
- `protocol/`: JSON Schemas and shared cross-language fixtures (`v1/`, `v2/`, `bt1/`).
- `windows/`: .NET 8 WPF shell and xUnit tests.
- `android/`: Kotlin/Compose shell and JVM unit tests.
- `scripts/`: repeatable build, validation, and explicit adb inspection commands.

## Branch policy

- `main`: releasable; all current work lands here after its tests and acceptance checks pass.
- Short-lived work branches (`feature/<short-name>` or agent branches) merge into `main` and are then retired. The early-stage `develop` integration branch is no longer used.

## Privacy and security baseline

- Text is the core payload; image sync (protocol v2) ships off by default on both ends, and the Bluetooth fallback carries text only.
- Clipboard text must never enter ordinary logs or telemetry.
- Android special capabilities require visible, revocable user action.
- Network reachability is required; there is no NAT traversal service or public relay.
- TLS certificate pinning and per-pair secrets are mandatory in the pairing/network stages.

See [product scope](docs/product-scope.md), [threat model](docs/threat-model.md), [protocol v1](docs/protocol-v1.md), and the [Stage 1 change log](docs/stage-1-change-log.md) for the normative boundaries and recorded validation.

## License

MIT. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
