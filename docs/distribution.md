# ClipSync distribution, pairing, and troubleshooting

Personal-use Windows ↔ Android P2P clipboard sync. No cloud, no stores, sideload only. Artifacts live in `releases/<version>/` after `scripts/package-release.ps1`.

This page is the 10-minute install path and the 3-minute “capability unavailable” lookup. It does not ask you to pipe `curl` into a shell. The Android APK includes a ClipSync-owned privileged host for `SHIZUKU_EVENT`. Official Shizuku is not used. In-app UI: Android follows the system locale (English default, Simplified Chinese `zh-rCN`); Windows strings are Simplified Chinese (`Strings.cs`).

## Windows install (10 minutes, no admin)

1. Unzip `ClipSync-Windows-<version>-win-x64.zip` somewhere writable, for example `%LOCALAPPDATA%\Programs\ClipSync`. Or:

   ```powershell
   pwsh scripts/install-windows.ps1 -ZipPath .\releases\<version>\ClipSync-Windows-<version>-win-x64.zip
   ```

   Optional current-user autostart (HKCU Run only, no scheduled task, no admin):

   ```powershell
   pwsh scripts/install-windows.ps1 -ZipPath <zip> -EnableAutostart
   ```

2. Run `ClipSync.App.exe`. A tray icon appears. The peer endpoint binds without elevation (preferred port `47654`, ephemeral if that port is taken).

3. Open the window from the tray → **Pair new device…** → Android scans that QR.

Data is **not** in the unzip folder. History, pairing keys, `device-id`, and the DPAPI-protected TLS certificate live in `%LOCALAPPDATA%\ClipSync`.

### Uninstall Windows

```powershell
pwsh scripts/uninstall-windows.ps1
```

The script removes the Run key, then **asks** before deleting `%LOCALAPPDATA%\ClipSync`. Use `-ExportTo <folder>` to copy `clipsync.db` first, or `-DeleteData` when you really mean it. It never deletes user data silently.

## Android sideload

1. Copy `ClipSync-Android-<version>.apk` (signed) or `ClipSync-Android-<version>-unsigned.apk` (debug-keyed personal test build) to the phone.
2. Open the APK. On MIUI the installer may show a restriction dialog — tap **继续安装**.
3. Open ClipSync. Notifications are optional: refuse them and you can still browse history (the foreground-service notification may be hidden in the shade).
4. Complete the in-app wizard (or skip cards you do not want). Pairing: **Open ClipSync on your computer, choose "Pair new device", then scan the QR code it shows.**

A signed release APK needs your own keystore at `D:\paste-tools\clipsync-release.keystore` (or `$env:CLIPSYNC_KEYSTORE`). Passwords come from `$env:CLIPSYNC_KEYSTORE_PASSWORD` only. `package-release.ps1` prints a one-time `keytool -genkeypair` command; it never generates the keystore for you.

## Permission guide per mode

| Mode | What it needs | After reboot | How to stop |
|---|---|---|---|
| `FOREGROUND_ONLY` (share / tile / open the app) | Nothing special | Works as soon as you open the app | Disable background sync in settings, or uninstall |
| `SHIZUKU_EVENT` (preferred) | Bundled privileged host started as shell, then authorized in the wizard | **Manual host start.** Open ClipSync once so it writes `Android/data/com.clipsync.android/start.sh`, then run the printed adb command yourself. After each reboot start the host again. MIUI also needs **自启动** if you want `BOOT_COMPLETED` to launch ClipSync at all (see below). | Skip the Shizuku cards or revoke the in-app authorization. ClipSync then stops using the binder (process-level health cycle, no Activity needed). |
| `ADB_LOG_OVERLAY` | `READ_LOGS` via adb **and** overlay consent | `READ_LOGS` is invalidated by install / upgrade / reboot. Re-run `scripts/android-bootstrap.ps1` (print-only) and grant again yourself. Overlay permission usually persists. | `adb shell pm revoke com.clipsync.android android.permission.READ_LOGS`, or turn overlay consent off in the wizard |
| `OVERLAY_POLLING` | Overlay permission **and** in-app overlay consent (`overlayConsented`) | Overlay permission usually persists; consent is stored in app settings. Screen-off / lock pauses polling. | Turn off overlay in system settings or un-check consent in ClipSync |

### Notifications (optional)

Android 13+ may ask for notifications. Refuse: history still works; inbound “copy” actions and the shade notification may be missing. This is not clipboard permission.

### Battery optimization

Skip **Ignore battery optimizations** and the OEM may kill the process after idle. Reopen the app to recover. On MIUI also check 省电 / 后台限制 for ClipSync.

### Overlay consent

System `SYSTEM_ALERT_WINDOW` is not enough. Stage 6 gates `OVERLAY_POLLING` and `ADB_LOG_OVERLAY` on in-app `overlayConsented`. Granting the system toggle without consent does not start those modes.

### `READ_LOGS` (print-only bootstrap)

```powershell
pwsh scripts/android-bootstrap.ps1
```

The script lists devices, checks USB-debugging / adb authorization, and **prints** grant, revoke, and privileged-host start commands. It never executes them and never downloads a third-party APK. Do not pipe `curl` (or any download) into a shell.

### Privileged host (bundled)

The host lives in the ClipSync APK (`clipsync_priv_server`). It is not the official Shizuku manager and does not use that name, icon, or package id.

1. Open ClipSync once so it can write `start.sh`.
2. Copy the command printed by `scripts/android-bootstrap.ps1` and run it yourself as shell.
3. In the wizard, use **Authorize privileged host**.

Official Shizuku is not a supported backend. Stage 6 numbers on official 13.5.4 are historical only.

### MIUI 自启动 (boot recovery)

Stage 6 reboot test: `BootCompletedReceiver` is registered, but MIUI without **自启动** never delivered `BOOT_COMPLETED` (no process / no notification for 70 seconds; WorkManager’s bounded boot check therefore never ran). **Fallback that did work:** open the app → foreground service returns → two-way sync in ~1 second, once.

To recover automatically after reboot on MIUI: Settings → ClipSync → **自启动** (and battery unrestricted), then also restart the bundled privileged host. Without 自启动, opening the app is the recovery path.

## What comes back after reboot vs what you do by hand

| Item | After phone reboot | After Windows reboot |
|---|---|---|
| Pairing keys / history | Stay on device | Stay in `%LOCALAPPDATA%\ClipSync` |
| Windows peer endpoint | — | Starts with the app (or HKCU Run if you enabled it) |
| Android foreground service | Needs `BOOT_COMPLETED` (MIUI: grant **自启动**) **or** you open the app | — |
| Privileged host daemon | Manual `start.sh` after every reboot | — |
| Privileged-host authorization | In-app confirm after the host is running | — |
| `READ_LOGS` | Re-grant after reboot / upgrade / reinstall | — |
| Overlay permission | Usually kept | — |
| Overlay consent / wizard choices | Kept in app settings | — |

## Rollback

`package-release.ps1` keeps the two most recent `releases/<version>/` folders. It only deletes older ones if you pass `-Prune` (it always prints what it would delete).

- **Windows:** unzip the older ZIP (or point the Run key at the older `ClipSync.App.exe`). Data in `%LOCALAPPDATA%\ClipSync` is not in the ZIP, so history and pairing survive.
- **Android:** installing an older APK over a newer one may require uninstall first. **Uninstalling the Android app deletes on-device history and pairing.** Export or finish a sync before you uninstall. The Windows database is not deleted by an Android uninstall.

There is no silent auto-update.

## Fault lookup (3 minutes)

Open ClipSync → Status / wizard. Match the visible label to one row. You should not need logs.

| What you see | Missing piece | Exact fix |
|---|---|---|
| **Windows unreachable** / Network not Connected | Phone cannot reach the PC peer | Confirm both on the same LAN/VPN; Windows ClipSync is running (tray icon); Windows firewall allows inbound TCP on the advertised port (default `47654`); pairing QR hosts still match the PC’s current IPs. On the PC, Pair new device… and scan again if the address changed. |
| **Needs recovery** | Android sync process was killed | Open ClipSync (this alone recovered FGS on MIUI). Then: battery unrestricted, MIUI **自启动**, optional ignore-battery-optimizations. Do not treat this as a clipboard-permission failure. |
| **Shizuku not running** / `SHIZUKU_NOT_RUNNING` / binder card not ready | Privileged host is down | `pwsh scripts/android-bootstrap.ps1` prints the start command. Open ClipSync once, then run `adb shell sh /storage/emulated/0/Android/data/com.clipsync.android/start.sh` yourself. After every reboot, do this again. |
| **Shizuku not authorized** / `SHIZUKU_NOT_AUTHORIZED` | Host is running but ClipSync has not confirmed use | Wizard → Authorize privileged host. |
| **Shizuku not installed** | Legacy label; the host is bundled | Treat as not running: start the bundled host, or skip and use overlay / foreground-only. |
| **READ_LOGS not granted** / `ADB_LOG_READ_LOGS_NOT_GRANTED` | Privilege missing | `pwsh scripts/android-bootstrap.ps1` then copy the printed `adb … pm grant … READ_LOGS` command. There is no in-app dialog. Re-check after install, upgrade, or reboot. |
| **Overlay not consented** / overlay mode missing even though the system toggle is on | In-app consent is off | Complete the overlay wizard card (consent + system “display over other apps”). Stage 6 will not start overlay or ADB-log-overlay without `overlayConsented`. |
| **Overlay permission missing** | System `SYSTEM_ALERT_WINDOW` off | Open the wizard overlay card → system settings → allow ClipSync to draw over other apps. |
| **Battery restricted** / process dies after idle / Needs recovery after sitting unused | OEM battery policy | Wizard → Ignore battery optimizations; MIUI: 无限制 / no 后台限制; grant **自启动** if you want boot recovery. Reopen the app as the immediate fix. |
| History works, no shade notification | Notification permission refused | Optional. History stays usable. Re-enable notifications in system settings if you want inbound copy actions in the shade. |
| ADB-log mode stays degraded after grant | ROM has no parseable clipboard log signal | Expected on the verified MIUI 14 device (`ADB_LOG_NO_HEALTHY_SIGNAL`). Use Shizuku or overlay polling instead. |

## Verify a download

Each artifact has a sibling `<artifact>.sha256`. The folder also has `SHA256SUMS.txt`.

```powershell
Get-FileHash -Algorithm SHA256 .\ClipSync-Windows-<version>-win-x64.zip
```

The lowercase hex should match the sidecar.

## Related

- Stage 6 verified behavior: [stage-6-change-log.md](stage-6-change-log.md) (实机验证轮).
- Capability design: [android-background-clipboard.md](android-background-clipboard.md).
- Licenses: [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
