# ClipSync Android

Stage 0 contains a minimal Compose health screen, strict protocol fixture parsing,
and platform-independent clipboard capability coordinators with fake-backed unit tests.
It deliberately does not implement real clipboard access, networking, persistence,
privileged read (特权直读), ADB log reading, or overlay behavior yet.

Requirements:

- JDK 17
- Android SDK Platform 35
- Gradle 8.9 (or open this directory in a compatible stable Android Studio)

Run unit tests from this directory:

```powershell
.\gradlew.bat testDebugUnitTest
```

Build the debug APK:

```powershell
.\gradlew.bat assembleDebug
```

The protocol tests read the shared fixtures from `../protocol/v1/fixtures`.
