# ClipSync Android

Kotlin/Compose client for ClipSync: Room-backed sync storage (sequence allocation, receive vectors, outbox in one transaction), a pinned-TLS WebSocket sync engine with exponential-backoff reconnect, a foreground service, the four-tier background-read capability ladder (privileged direct read via the built-in privileged host, log sensing + overlay, overlay polling, foreground/manual), image sync (protocol v2), the Bluetooth RFCOMM fallback (bt1 secure channel, text only), and the charter-themed UI in 19 languages.

See the [repository README](../README.md) for the product overview and [`docs/install.md`](../docs/install.md) for the end-user install/pairing guide.

Requirements:

- JDK 17
- Android SDK Platform 35
- Gradle 8.9 (or open this directory in a compatible stable Android Studio)

Run JVM unit tests from this directory:

```powershell
.\gradlew.bat testDebugUnitTest
```

Build the debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Or use the repository scripts from the repo root: `pwsh ./scripts/build-android.ps1` (build + tests) and `pwsh ./scripts/package-android.ps1` (release APK; signing material comes from the `CLIPSYNC_ANDROID_*` environment variables and never enters the repository).

The protocol tests read the shared fixtures from `../protocol/` (`v1/`, `v2/`, `bt1/`).
