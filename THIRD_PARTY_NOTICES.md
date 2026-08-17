# Third-Party Dependency and License Inventory

This inventory (Stage 0, updated through Stage 5.3) records direct build/runtime/test dependencies currently declared in the repository and major dependencies explicitly planned by `plan.md`. It is not a substitute for the release-time transitive dependency, notice, and source-offer review. Exact license texts and obligations must be collected from the resolved artifacts before distribution.

## Currently declared dependencies

| Component | Declared version | Purpose | License |
|---|---:|---|---|
| .NET / WPF | .NET 8 | Windows application platform | MIT; platform components may carry additional notices |
| ASP.NET Core (framework reference) | 8.x | Windows in-process HTTPS/WebSocket peer endpoint | MIT |
| Microsoft.Data.Sqlite | 8.0.25 | Windows SQLite access (pulls SQLitePCLRaw + native SQLite) | MIT; SQLite is public domain, binding notices to verify at release |
| CommunityToolkit.Mvvm | 8.4.0 | Windows MVVM | MIT |
| Microsoft.Extensions.DependencyInjection | 8.0.1 | Windows composition root | MIT |
| Hardcodet.NotifyIcon.Wpf | 2.0.1 | Windows tray icon | CPOL-1.02 per upstream repository; confirm notice obligations before distribution |
| QRCoder | 1.8.0 | Windows pairing QR rendering | MIT |
| System.Security.Cryptography.ProtectedData | 8.0.0 | DPAPI secret protection | MIT |
| Microsoft.NET.Test.Sdk | 17.11.1 | .NET test host | MIT |
| xUnit.net | 2.9.2 | .NET unit tests | Apache-2.0 |
| xunit.runner.visualstudio | 2.8.2 | Visual Studio/.NET test adapter | Apache-2.0 |
| Coverlet collector | 6.0.2 | .NET coverage | MIT |
| JsonSchema.Net | 7.3.4 | Protocol JSON Schema validation | MIT |
| Android Gradle Plugin | 8.7.3 | Android build tooling | Apache-2.0 |
| Kotlin Gradle/Compose/serialization plugins | 2.0.21 | Kotlin build tooling | Apache-2.0 |
| AndroidX Core KTX | 1.15.0 | Android core APIs | Apache-2.0 |
| AndroidX Activity Compose | 1.10.0 | Compose activity integration | Apache-2.0 |
| Jetpack Compose BOM and libraries | BOM 2024.12.01 | Android UI | Apache-2.0 |
| Material 3 for Compose | selected by BOM | Android UI components | Apache-2.0 |
| AndroidX Lifecycle ViewModel Compose | 2.8.7 | Android ViewModel integration | Apache-2.0 |
| kotlinx.serialization JSON | 1.7.3 | Kotlin JSON parsing | Apache-2.0 |
| kotlinx-coroutines-android | 1.9.0 | Android concurrency | Apache-2.0 |
| OkHttp | 4.12.0 | Android pinned-TLS pairing client (WebSocket sync in stage 4) | Apache-2.0 |
| ML Kit Barcode Scanning | 17.3.0 | Android pairing QR scan | Closed-source Google library under Google APIs/Play Services terms, not an OSS license; camera-permission fallback (pasted payload) keeps pairing usable without it. Review distribution terms before release |
| AndroidX CameraX (camera2, lifecycle, view) | 1.4.1 | Camera preview for QR scanning | Apache-2.0 |
| AndroidX Room runtime / room-ktx | 2.6.1 | Android clip/outbox/cursor persistence | Apache-2.0 |
| AndroidX Room compiler (via KSP) | 2.6.1 | Room annotation processing (build only) | Apache-2.0 |
| com.google.devtools.ksp (Gradle plugin) | 2.0.21-1.0.28 | Kotlin Symbol Processing for Room (build only) | Apache-2.0 |
| JUnit 4 | 4.13.2 | Android/JVM unit tests | EPL-1.0 |
| OkHttp MockWebServer | 4.12.0 | Android pairing client tests (test only) | Apache-2.0 |
| okhttp-tls | 4.12.0 | Test TLS certificates (test only) | Apache-2.0 |
| kotlinx-coroutines-test | 1.9.0 | Android coroutine tests (test only) | Apache-2.0 |
| AndroidX Room room-testing | 2.6.1 | Room test helpers (test only) | Apache-2.0 |
| AndroidX Arch core-testing | 2.2.0 | Architecture Components test helpers (test only) | Apache-2.0 |
| Shizuku API (`dev.rikka.shizuku:api`) | 13.1.5 | Privileged clipboard UserService / Binder client | MIT — verified 2026-08-17 from the Maven Central 13.1.5 POM (`<name>MIT License</name>`) and [RikkaApps/Shizuku-API LICENSE](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE). The Shizuku *manager app* is Apache-2.0; the API/provider artifacts we depend on are MIT. |
| Shizuku Provider (`dev.rikka.shizuku:provider`) | 13.1.5 | `ShizukuProvider` ContentProvider that receives the Shizuku binder | MIT — same artifact family and LICENSE as `dev.rikka.shizuku:api` 13.1.5 |

## Planned direct dependencies

These are architectural selections, not necessarily present in the current build. Versions and licenses must be rechecked when added.

| Component | Planned purpose | Expected license/status |
|---|---|---|
| AndroidX WorkManager | Bounded recovery | Apache-2.0 |

## Reference projects are not dependencies

KDE Connect, ClipShare, SyncClipboard Mobile, UniClipboard, ClipShare ClipboardListener, Syzygy, and LocalSend are behavioral or product references only. Their source, private protocols, branding, and UI assets are not incorporated by this inventory. No reference-project license grants permission to copy code without a file-level and dependency-level review.

## Distribution checklist

Before producing an APK, ZIP, or MSIX:

1. Generate the resolved direct and transitive dependency list for NuGet and Gradle.
2. Compare resolved versions to this inventory and investigate every addition.
3. Include required copyright notices and license texts in the distributed package.
4. Review reciprocal, source-offer, trademark, service-term, native binary, and data-collection obligations.
5. Record the dependency scan and review date in the release change log.
