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
$readLogs = (& adb @adbArgs dumpsys package $PackageName 2>$null | Select-String 'android.permission.READ_LOGS').Line

Write-Host ''
Write-Host "Selected: $Serial ($manufacturer $model, Android $release, API $sdk)"
Write-Host "READ_LOGS declaration/grant line: $(if ($readLogs) { $readLogs.Trim() } else { 'not found' })"
Write-Host ''
Write-Host 'No permissions were changed.'
Write-Host 'Review and run one of these commands manually if you decide to grant or revoke READ_LOGS:'
Write-Host "  adb -s $Serial shell pm grant $PackageName android.permission.READ_LOGS"
Write-Host "  adb -s $Serial shell pm revoke $PackageName android.permission.READ_LOGS"
