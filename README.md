# ClipSync · 剪剪相传

[![CI](https://github.com/fraternidaddeng/clipsync/actions/workflows/ci.yml/badge.svg)](https://github.com/fraternidaddeng/clipsync/actions/workflows/ci.yml)

**English** | [简体中文](README.zh-CN.md) | [日本語](README.ja.md)

**Private, peer-to-peer clipboard sync between Windows and Android.** Copy on one device, paste on the other — text and images travel directly between your own two devices over an encrypted connection. No account, no cloud, no relay, no telemetry.

ClipSync pairs like Bluetooth (scan a QR code, compare certificate fingerprints once), syncs over your LAN or a Tailscale network, and is deliberately honest about what it can and cannot do — especially around Android's background-clipboard restrictions.

> The v0.1.0 product line is complete on `main`. The latest published build is the [v0.1.0-rc.2 pre-release](https://github.com/fraternidaddeng/clipsync/releases) (portable Windows ZIP + release-signed Android APK, each with a SHA-256 sidecar).

## Features

### Sync

- **Two-way, near real time.** Content is persisted locally first, then exchanged between paired devices. After a disconnect, missed items catch up in order with exactly-once delivery — no duplicates, no echo loops.
- **Text and images.** Image sync (protocol v2, chunked transfer, thumbnails in history) is **on by default** since 2026-08-28, and received images are **auto-applied to the local clipboard by default** too; both toggles are independent and can be switched off at any time. Text larger than 1 MiB stays local and is never silently truncated.
- **History on both ends.** Searchable history, delete, retention limits (count and age), export/import, and sensitive-source exclusion.
- **You stay in control.** Pause sync, private mode, pause auto-capture, and — on Android — a master "background sync service" switch that truly stops the service until you turn it back on (no resurrection on app reopen or reboot).

### Pairing and encryption

- **QR pairing with fingerprint verification.** Windows shows a QR code containing only the address, port, certificate fingerprint, and a one-time token — never the pairing secret. Both screens display the fingerprint; you compare them visually and confirm once.
- **TLS 1.3 with certificate-fingerprint pinning** on every connection; a per-pair secret is protected by Windows DPAPI and the Android Keystore. Pin mismatch never downgrades.
- **Local networks only.** Devices talk over your LAN or a Tailscale network. There is no cloud database, public relay, or NAT-traversal service — if the devices can't reach each other, they don't sync. That is a design boundary, not a bug.
- **Revocable trust.** Remove a device on either end and the connection drops immediately. If a peer's fingerprint changes (reinstall, new phone), Android requires an explicit "I verified — replace pairing" confirmation.

### Android background reading — an honest capability ladder

Android 10+ forbids ordinary apps from reading the clipboard in the background, so ClipSync splits the capability into four tiers. The Conduit page probes each tier truthfully — "read" and "write-back" are probed separately, and a tier only shows READY after a real read test passes:

| Tier | Requires | Notes |
|---|---|---|
| **Privileged direct read** | ClipSync's built-in privileged host, started once from a PC via adb (USB, or wireless debugging on Android 11+; one click from the Windows app) | Best experience: instant background capture, even with the screen off |
| **Log sensing + overlay** | One adb `READ_LOGS` grant + overlay permission | Copy-paste command provided in-app |
| **Overlay polling** | Overlay permission only | No PC needed; costs battery and second-level latency |
| **Foreground / manual** | Nothing | Share sheet, quick tile, notification copy — always available |

Every permission is granted through a visible, explained, revocable flow; revoke one and ClipSync downgrades automatically to the tiers that still work. The app never runs adb silently.

### Bluetooth fallback (opt-in)

When no IP route works (AP isolation, VPN/TUN capturing all traffic, router down), already-paired devices can keep syncing **text** over Bluetooth RFCOMM. The link runs the bt1 secure channel — mutual HMAC-SHA-256 authentication from the existing pairing secret plus per-connection AES-256-GCM — so system Bluetooth pairing is only a carrier, never a substitute for ClipSync's own pairing and revocation. Off by default on both ends; ClipSync switches back to IP automatically once it recovers. Images do not travel over Bluetooth.

### Interface

- Five-step first-run onboarding on both platforms (pairing, background-read routes, permissions — with a "set up later" exit on every step).
- Day/night themes (follow system or manual override) under a bespoke design system ([design charter](docs/design/DESIGN-CHARTER.md)).
- **19 interface languages**, including right-to-left Arabic; source language is Simplified Chinese.

## Download and install

Grab prebuilt packages from [GitHub Releases](https://github.com/fraternidaddeng/clipsync/releases):

| File | Purpose |
|---|---|
| `ClipSync-windows-x64.zip` | Windows portable package (self-contained, .NET runtime included, no installer) |
| `ClipSync-android.apk` | Release-signed Android APK |
| `*.sha256` | SHA-256 checksum for each file |

1. **Verify the checksums** against the `.sha256` sidecars (they are also listed in the release notes): `Get-FileHash` on Windows, `sha256sum -c` on Linux/macOS.
2. **Windows** (Windows 10 22H2 or 11, x64): unzip anywhere and run `ClipSync.App.exe`. The binaries are **not code-signed**, so SmartScreen may warn on first launch — verify the SHA-256, then choose "More info → Run anyway". Allow the firewall prompt for private networks (TCP `47654`, UDP `47653` discovery). Uninstall = delete the folder plus the data directory; no registry residue.
3. **Android** (Android 10 / API 29 or later, no root): install the APK from Releases and allow "install unknown apps" when prompted. Debug builds are signed differently and cannot be installed over the release build.

The full step-by-step guide — network setup, Tailscale, proxy (Clash/Surge) caveats, every Android tier, and troubleshooting — is [docs/install.md](docs/install.md) (Chinese; a copy ships inside the Windows ZIP).

## Quick start

1. **Connect the devices**: same LAN, or install Tailscale on both and add your `100.x.y.z` address as an extra listen address on Windows (Conduit → Network → Connection card).
2. **Pair**: Windows → Conduit → "Pair new device" shows a QR code. Android → Conduit → scan it. Compare the certificate fingerprint shown on both screens, then confirm on both ends. Done — about two minutes.
3. **Copy something.** Text and images now sync both ways; anything copied while offline catches up in order on reconnect.
4. **Optional — best Android experience**: enable **privileged direct read** so background copies upload instantly even with the screen off. Enable Developer Options and USB debugging (or wireless debugging on Android 11+), then use the Windows app's one-click "Start privileged direct read" (or copy the one-line start command from the Android Conduit page). Note: the channel closes when the phone reboots — re-run the start command from the PC (no new RSA confirmation needed unless you revoked the debugging authorization).
5. Prefer no PC at all? Use **overlay polling**, or just the always-available share sheet / quick tile / notification copy.

## Privacy and security

- Content moves only between devices you explicitly paired and fingerprint-verified, over TLS 1.3 with pinned certificates (Bluetooth fallback runs its own authenticated, encrypted channel).
- No account, no cloud storage, no relay servers, no telemetry, no crash uploads.
- Clipboard text never enters logs or notifications — enforced by dedicated tests; diagnostics exports are safe to share.
- Android backups and device-to-device migration are fully excluded (clipboard history is sensitive plaintext).
- Deletion is local-first: deleting on one device does not remotely recall content that already reached the other (Windows→Android tombstone propagation exists for synced deletions of Windows items).
- Details: [threat model](docs/threat-model.md) · [product scope and non-goals](docs/product-scope.md) · protocols [v1](docs/protocol-v1.md), [v2 (images)](docs/protocol-v2.md), [bt1 (Bluetooth)](docs/protocol-bt1.md).

## Current status, honestly

- **Physical-device QA was signed off by the user on 2026-08-26** ([docs/manual-qa-results.md](docs/manual-qa-results.md)). The per-device matrix ([docs/device-validation-matrix.md](docs/device-validation-matrix.md)) stays `NOT_TESTED` until structured details are backfilled, so there is no per-ROM compatibility promise yet.
- Automated coverage is extensive (1,400+ tests across both platforms plus cross-client end-to-end runs in CI), but a release is not the same thing as device validation — release notes say so explicitly.
- **Windows binaries are unsigned** — expect SmartScreen; verify the SHA-256 before running.
- **Android must be installed from the release-signed APK** on GitHub Releases; debug APKs use a different signature and cannot upgrade over it.
- The **privileged direct-read** channel does not survive a phone reboot — restart it from the PC (one click on Windows). With wireless debugging, the phone's `IP:port` drifts after screen-off/network changes; reconnect with the current value, no re-pairing needed.
- Not planned, by design: iOS/macOS/Linux clients, file transfer (use LocalSend), accounts, cloud relay, NAT traversal, telemetry. See [product scope](docs/product-scope.md).

## Build from source

Prerequisites: Git, .NET 8 SDK (pinned by `global.json`), JDK 17, Android SDK Platform 35, PowerShell 7 (recommended).

```powershell
pwsh ./scripts/build-windows.ps1       # build + test the Windows app
pwsh ./scripts/build-android.ps1       # build + test the Android app
pwsh ./scripts/validate-protocol.ps1   # validate shared protocol fixtures

pwsh ./scripts/package-windows.ps1     # dist/ClipSync-windows-x64.zip (+ .sha256)
pwsh ./scripts/package-android.ps1     # dist/ClipSync-android.apk    (+ .sha256)
```

Release APK signing reads the `CLIPSYNC_ANDROID_*` environment variables (keystore never enters the repository); see [docs/install.md §10](docs/install.md) for packaging, signing, and the tag-driven release workflow.

Repository layout:

- `docs/` — product, security, protocol, Android-capability, ADR, and verification records
- `protocol/` — JSON Schemas and shared cross-language fixtures (`v1/`, `v2/`, `bt1/`)
- `windows/` — .NET 8 WPF app and xUnit tests
- `android/` — Kotlin/Compose app and JVM unit tests
- `scripts/` — repeatable build, validation, packaging, and explicit adb inspection commands

Branch policy: `main` is releasable; work lands there after its tests and acceptance checks pass.

## Documentation

- [Install and pairing guide](docs/install.md) (Chinese) — the end-user path
- [Product scope](docs/product-scope.md) · [Threat model](docs/threat-model.md)
- [Design charter](docs/design/DESIGN-CHARTER.md) — the UI's normative record
- [Android background clipboard](docs/android-background-clipboard.md) — how the capability ladder works
- [CHANGELOG](CHANGELOG.md) · [v0.1.0 release record](docs/releases/v0.1.0.md) · [Releases](https://github.com/fraternidaddeng/clipsync/releases)

## Contributing and license

ClipSync is a personal project with a deliberately frozen scope — features must serve capture, transport, recovery, retrieval, or trust/privacy ([scope rules](docs/product-scope.md)). Bug reports via GitHub Issues are welcome; please never include clipboard contents in reports (the built-in diagnostics export is already scrubbed).

MIT — see [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
