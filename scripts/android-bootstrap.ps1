[CmdletBinding()]
param(
    [string]$PackageName = 'com.clipsync.android',
    [string]$Serial
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Error 'adb was not found. Install Android SDK Platform Tools and add it to PATH.'
    exit 2
}

$devices = @(adb devices -l | Select-Object -Skip 1 | Where-Object { $_ -match '\S' })
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($devices.Count -eq 0) {
    Write-Host 'No Android device is visible to adb.'
    exit 1
}

Write-Host 'Visible devices:'
$devices | ForEach-Object { Write-Host "  $_" }

if (-not $Serial) {
    $ready = @($devices | Where-Object { $_ -match '\sdevice\s' })
    if ($ready.Count -ne 1) {
        Write-Host 'Pass -Serial <device-serial> to inspect one authorized device.'
        exit 0
    }
    $Serial = ($ready[0] -split '\s+')[0]
}

$adbArgs = @('-s', $Serial, 'shell')
$sdk = (& adb @adbArgs getprop ro.build.version.sdk).Trim()
$release = (& adb @adbArgs getprop ro.build.version.release).Trim()
$manufacturer = (& adb @adbArgs getprop ro.product.manufacturer).Trim()
$model = (& adb @adbArgs getprop ro.product.model).Trim()
$dumpsys = @(& adb @adbArgs dumpsys package $PackageName 2>$null)
$readLogsLines = @($dumpsys | Select-String 'android.permission.READ_LOGS' | ForEach-Object { $_.Line.Trim() })

$declared = $false
$granted = $false
foreach ($line in $readLogsLines) {
    if ($line -match 'android\.permission\.READ_LOGS') {
        $declared = $true
    }
    if ($line -match 'granted\s*=\s*true') {
        $granted = $true
    }
}

$grantState = if ($granted) {
    'GRANTED'
} elseif ($declared) {
    'DECLARED_NOT_GRANTED'
} elseif ($readLogsLines.Count -gt 0) {
    'SEEN_NOT_GRANTED'
} else {
    'NOT_DECLARED'
}

$grantCommand = "adb -s $Serial shell pm grant $PackageName android.permission.READ_LOGS"
$revokeCommand = "adb -s $Serial shell pm revoke $PackageName android.permission.READ_LOGS"

Write-Host ''
Write-Host "Selected: $Serial ($manufacturer $model, Android $release, API $sdk)"
Write-Host "Package: $PackageName"
Write-Host "READ_LOGS grant state: $grantState"
if ($readLogsLines.Count -gt 0) {
    Write-Host 'READ_LOGS dumpsys lines:'
    $readLogsLines | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host 'READ_LOGS dumpsys lines: not found (package missing or permission not declared).'
}
$deviceLine = ($devices | Where-Object { $_ -like "$Serial*" } | Select-Object -First 1)
$adbAuth = if ($deviceLine -match '\sunauthorized\b') {
    'UNAUTHORIZED'
} elseif ($deviceLine -match '\soffline\b') {
    'OFFLINE'
} elseif ($deviceLine -match '\sdevice\b') {
    'AUTHORIZED'
} else {
    'UNKNOWN'
}
$usbConfig = 'unknown'
$persistUsb = 'unknown'
$adbEnabled = 'unknown'
$officialShizukuPackage = 'moe.shizuku.privileged.api'
$officialShizukuPath = ''
$previousNative = $PSNativeCommandUseErrorActionPreference
$PSNativeCommandUseErrorActionPreference = $false
try {
    $usbConfig = (@(& adb @adbArgs getprop sys.usb.config 2>$null) -join '').Trim()
    $persistUsb = (@(& adb @adbArgs getprop persist.sys.usb.config 2>$null) -join '').Trim()
    $adbEnabled = (@(& adb @adbArgs settings get global adb_enabled 2>$null) -join '').Trim()
    $officialShizukuPath = (@(& adb @adbArgs pm path $officialShizukuPackage 2>$null) -join '').Trim()
} finally {
    $PSNativeCommandUseErrorActionPreference = $previousNative
}
$officialShizukuInstalled = $officialShizukuPath -match '^package:'
$hostStartCommand = "adb -s $Serial shell sh /storage/emulated/0/Android/data/$PackageName/start.sh"
$officialStartCommand = "adb -s $Serial shell sh /storage/emulated/0/Android/data/$officialShizukuPackage/start.sh"

Write-Host ''
Write-Host "Developer / USB debugging: adb=$adbAuth, adb_enabled=$adbEnabled, sys.usb.config=$usbConfig, persist.sys.usb.config=$persistUsb"
if ($adbAuth -eq 'UNAUTHORIZED') {
    Write-Host 'Unlock the phone and accept the RSA fingerprint dialog, then re-run this script.'
}
Write-Host "Bundled privileged host start command (copy yourself; this script never runs it):"
Write-Host "  $hostStartCommand"
Write-Host 'Open ClipSync once first so it can write start.sh, then run the command as shell.'
Write-Host 'After each reboot, start the host again. The wizard Authorize card confirms ClipSync may use it.'
Write-Host "Official Shizuku package ($officialShizukuPackage): $(if ($officialShizukuInstalled) { 'INSTALLED (optional fallback)' } else { 'NOT_INSTALLED (optional)' })"
if ($officialShizukuInstalled) {
    Write-Host 'Optional official Shizuku start command if you prefer that already-running daemon:'
    Write-Host "  $officialStartCommand"
}

Write-Host ''
Write-Host 'This script is read-only. It never runs grant, revoke, or host start.'
Write-Host 'It never downloads Shizuku. The clipboard host is bundled in the ClipSync APK.'
Write-Host 'READ_LOGS cannot be granted by a normal in-app runtime dialog.'
Write-Host 'Install, upgrade, or reboot invalidates the grant. Re-run this inspector and re-probe the app after those events.'
Write-Host 'Copy and run one of these commands yourself if you decide to change the grant:'
Write-Host "  $grantCommand"
Write-Host "  $revokeCommand"
