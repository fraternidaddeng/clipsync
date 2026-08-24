# 剪剪相传 · Clip It Forward (code name: ClipSync)

剪剪相传（English: Clip It Forward; code name ClipSync — namespaces, packages, and artifact names keep the code name）is a private, direct peer-to-peer clipboard sync project for Windows and Android. There is no account service, cloud database, public relay, file transfer, telemetry, or clipboard-content logging.

The repository has completed Stages 0–8 (text sync, pairing, capture modes, signed 0.2.0) and Stage 9 image protocol v2 (`/v2/peer/sync`, `image_clip_v2`). Progress and device evidence live in [`docs/dod-status.md`](docs/dod-status.md) and [`docs/device-validation-matrix.md`](docs/device-validation-matrix.md). `plan.md` is the historical plan, not the live tracker.

Android UI ships English (`values`) plus Simplified Chinese (`values-zh-rCN`). Windows UI copy is Simplified Chinese in `ClipSync.App/Strings.cs`.

## Prerequisites

- Windows 10 22H2 or Windows 11
- Git
- .NET 8 SDK (pinned by `global.json` to 8.0.419)
- JDK 17
- Android Studio with Android SDK Platform 35 and Platform Tools
- PowerShell 7 is recommended; Windows PowerShell 5.1 is also supported
- Protocol validation: `pip install -r requirements.txt` (`jsonschema`, `referencing`)

Release APK signing requires `CLIPSYNC_KEYSTORE` and `CLIPSYNC_KEYSTORE_PASSWORD`. Missing credentials fail `assembleRelease` instead of emitting a debug-signed “release” APK.

## Build and test

```powershell
pwsh .\scripts\build-windows.ps1
pwsh .\scripts\build-android.ps1
pwsh .\scripts\validate-protocol.ps1
```

The Windows app starts in the notification area. It captures text (and, when enabled, images), persists them in SQLite, and exposes history/search/delete/clear plus pause/private/retention settings. Pairing is QR + TLS certificate pin; Android is always the dialer and Windows is always the listener.

## Protocol

- [`docs/protocol-v1.md`](docs/protocol-v1.md) — normative text sync (`/v1/peer/sync`)
- [`docs/protocol-v2.md`](docs/protocol-v2.md) — image clips (`/v2/peer/sync`)
- [`docs/adr/0003-clipboard-image-v2.md`](docs/adr/0003-clipboard-image-v2.md) — accepted image design
- Shared schemas and fixtures: `protocol/v1/`, `protocol/v2/`

## Android device inspection

```powershell
pwsh .\scripts\android-bootstrap.ps1 -PackageName com.clipsync.android
```

The bootstrap script is read-only by default. It never grants `READ_LOGS` automatically.

## Repository layout

- `docs/`: product, security, protocol, ADRs, and verification records
- `protocol/v1/` and `protocol/v2/`: JSON Schema and shared fixtures
- `windows/`: .NET 8 WPF shell, peer endpoint, and xUnit tests
- `android/`: Kotlin/Compose app, Room storage, WebSocket sync, and JVM tests
- `scripts/`: repeatable build, validation, and adb inspection commands

## Branch policy

- `main`: releasable, reviewed stages only
- `develop`: integration branch for the current stage
- `feature/<short-name>`: scoped work branches merged into `develop`

## Privacy and security baseline

- Clipboard bodies, blobs, nonces, proofs, pair secrets, tokens, and keys must never enter ordinary logs
- Android special capabilities require visible, revocable user action
- Network reachability is required; there is no NAT traversal service or public relay
- TLS certificate pinning and per-pair secrets are mandatory
- Image sync is explicit and off by default

See [product scope](docs/product-scope.md), [threat model](docs/threat-model.md), and the protocol docs above.

## License

MIT. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
