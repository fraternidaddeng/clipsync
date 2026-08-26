# Third-Party Dependency and License Inventory

This inventory (Stage 0, updated through 2026-08-26) records direct build/runtime/test dependencies currently declared in the repository. It is not a substitute for the release-time transitive dependency, notice, and source-offer review. Exact license texts and obligations must be collected from the resolved artifacts before distribution.

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
| Android Gradle Plugin | 8.7.3 | Android build tooling | Apache-2.0 |
| Kotlin Gradle/Compose/serialization plugins | 2.0.21 | Kotlin build tooling | Apache-2.0 |
| KSP Gradle plugin | 2.0.21-1.0.28 | Room annotation processing (build only) | Apache-2.0 |
| detekt Gradle plugin | 1.23.8 | Kotlin static analysis (build only) | Apache-2.0 |
| ktlint Gradle plugin (org.jlleitschuh) | 14.2.0 | Kotlin formatting checks (build only) | MIT |
| AndroidX Core KTX | 1.15.0 | Android core APIs | Apache-2.0 |
| AndroidX AppCompat | 1.7.0 | Per-app locale replay for the 19-language UI | Apache-2.0 |
| AndroidX Activity Compose | 1.10.0 | Compose activity integration | Apache-2.0 |
| Jetpack Compose BOM and libraries | BOM 2024.12.01 | Android UI | Apache-2.0 |
| Material 3 for Compose | selected by BOM | Android UI components | Apache-2.0 |
| AndroidX Lifecycle ViewModel/Runtime Compose | 2.8.7 | Android ViewModel/lifecycle integration | Apache-2.0 |
| Room (runtime, ktx, compiler; room-testing in tests) | 2.6.1 | Android persistence (sync store, history, inbox) | Apache-2.0 |
| AndroidX WorkManager | 2.10.0 | Bounded boot-recovery health check | Apache-2.0 |
| kotlinx.serialization JSON | 1.7.3 | Kotlin JSON parsing | Apache-2.0 |
| kotlinx-coroutines-android | 1.9.0 | Android concurrency | Apache-2.0 |
| OkHttp | 4.12.0 | Android pinned-TLS pairing client and WebSocket sync | Apache-2.0 |
| ML Kit Barcode Scanning | 17.3.0 | Android pairing QR scan | Closed-source Google library under Google APIs/Play Services terms, not an OSS license; camera-permission fallback (pasted payload) keeps pairing usable without it. Review distribution terms before release |
| AndroidX CameraX (camera2, lifecycle, view) | 1.4.1 | Camera preview for QR scanning | Apache-2.0 |
| Shizuku API (`dev.rikka.shizuku:api` + `:provider`) | 13.1.5 | Binder bridge for the privileged direct-read backend (user-facing name is 特权直读/内置特权宿主; this inventory keeps the library's upstream legal name) | MIT (verified from the pinned artifact POM and upstream `RikkaApps/Shizuku-API` LICENSE) |
| JUnit 4 | 4.13.2 | Android/JVM unit tests | EPL-1.0 |
| Robolectric | 4.14.1 | Android JVM tests (test only) | MIT |
| AndroidX Test (core, core-ktx, ext-junit, runner, rules) | 1.6.1 / 1.2.1 / 1.6.2 | Unit and instrumentation test infrastructure (test only) | Apache-2.0 |
| OkHttp MockWebServer | 4.12.0 | Android pairing client tests (test only) | Apache-2.0 |
| okhttp-tls | 4.12.0 | Test TLS certificates (test only) | Apache-2.0 |
| kotlinx-coroutines-test | 1.9.0 | Android coroutine tests (test only) | Apache-2.0 |
| Python jsonschema + referencing + cryptography (pip, CI only) | latest at run time | Protocol schema/fixture validation (`scripts/validate-protocol.py`); never distributed | MIT / MIT / Apache-2.0 OR BSD-3-Clause |

## Planned direct dependencies

None currently. The dependencies previously listed here as planned (Room, AndroidX WorkManager, Shizuku API) have since been added and moved into the table above with their verified licenses. JsonSchema.Net was removed from the Windows projects when protocol validation moved to the Python tooling above.

## Reference projects are not dependencies

KDE Connect, ClipShare, SyncClipboard Mobile, UniClipboard, ClipShare ClipboardListener, Syzygy, and LocalSend are behavioral or product references only. Their source, private protocols, branding, and UI assets are not incorporated by this inventory. No reference-project license grants permission to copy code without a file-level and dependency-level review.

## Distribution checklist

Before producing an APK, ZIP, or MSIX:

1. Generate the resolved direct and transitive dependency list for NuGet and Gradle.
2. Compare resolved versions to this inventory and investigate every addition.
3. Include required copyright notices and license texts in the distributed package.
4. Review reciprocal, source-offer, trademark, service-term, native binary, and data-collection obligations.
5. Record the dependency scan and review date in the release change log.
