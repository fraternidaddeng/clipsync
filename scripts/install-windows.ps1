[CmdletBinding()]
param(
    [string]$ZipPath,
    [string]$Destination,
    [switch]$EnableAutostart
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if (-not $Destination -or -not $Destination.Trim()) {
    $Destination = Join-Path $env:LOCALAPPDATA 'Programs\ClipSync'
}

if (-not $ZipPath -or -not $ZipPath.Trim()) {
    $releasesRoot = Join-Path $repoRoot 'releases'
    $latestZip = Get-ChildItem -LiteralPath $releasesRoot -Recurse -Filter 'ClipSync-Windows-*-win-x64.zip' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latestZip) {
        throw 'No -ZipPath given and no ClipSync-Windows-*-win-x64.zip under releases\. Build one with scripts/package-release.ps1.'
    }
    $ZipPath = $latestZip.FullName
    Write-Host "Using latest packaged ZIP: $ZipPath"
}

if (-not (Test-Path -LiteralPath $ZipPath)) {
    throw "ZIP not found: $ZipPath"
}

New-Item -ItemType Directory -Force -Path $Destination | Out-Null
Write-Host "Extracting to $Destination (no admin required)."
Expand-Archive -LiteralPath $ZipPath -DestinationPath $Destination -Force

$exe = Join-Path $Destination 'ClipSync.App.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    throw "Extracted folder does not contain ClipSync.App.exe: $Destination"
}

Write-Host "Installed portable binaries at $Destination"
Write-Host "Data stays in $env:LOCALAPPDATA\ClipSync (history + pairing keys)."
Write-Host 'Launch ClipSync.App.exe, then choose "Pair new device…" and scan the QR from Android.'

if ($EnableAutostart) {
    $runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
    if (-not (Test-Path -LiteralPath $runKey)) {
        New-Item -Path $runKey -Force | Out-Null
    }
    Set-ItemProperty -LiteralPath $runKey -Name 'ClipSync' -Value "`"$exe`""
    Write-Host "Autostart enabled for the current user (HKCU Run). No admin, no scheduled task."
} else {
    Write-Host 'Autostart not changed. Re-run with -EnableAutostart to add a current-user Run key.'
}

Write-Host ''
Write-Host '10-minute path: unzip (done) → run ClipSync.App.exe → Pair new device… → scan QR on the phone.'
exit 0
