[CmdletBinding()]
param(
    [string]$InstallDir,
    [switch]$DeleteData,
    [string]$ExportTo
)

$ErrorActionPreference = 'Stop'

if (-not $InstallDir -or -not $InstallDir.Trim()) {
    $InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\ClipSync'
}

$dataDir = Join-Path $env:LOCALAPPDATA 'ClipSync'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'

Write-Host 'Removing current-user autostart (HKCU Run\ClipSync), if present.'
if (Test-Path -LiteralPath $runKey) {
    Remove-ItemProperty -LiteralPath $runKey -Name 'ClipSync' -ErrorAction SilentlyContinue
}

if (Test-Path -LiteralPath $InstallDir) {
    $exe = Join-Path $InstallDir 'ClipSync.App.exe'
    if (Test-Path -LiteralPath $exe) {
        Write-Host "Removing portable binaries at $InstallDir"
        Remove-Item -LiteralPath $InstallDir -Recurse -Force
    } else {
        Write-Host "Install dir exists but has no ClipSync.App.exe; leaving $InstallDir untouched."
    }
} else {
    Write-Host "No portable install folder at $InstallDir"
}

function Copy-ClipSyncDatabase {
    param([string]$SourceDir, [string]$TargetDir)
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    $copied = $false
    foreach ($name in @('clipsync.db', 'clipsync.db-wal', 'clipsync.db-shm')) {
        $src = Join-Path $SourceDir $name
        if (Test-Path -LiteralPath $src) {
            Copy-Item -LiteralPath $src -Destination (Join-Path $TargetDir $name) -Force
            $copied = $true
        }
    }
    if (-not $copied) {
        Write-Warning "No clipsync.db (or wal/shm) found under $SourceDir"
    } else {
        Write-Host "Exported database files to $TargetDir"
    }
}

if ($ExportTo -and $ExportTo.Trim()) {
    if (Test-Path -LiteralPath $dataDir) {
        Copy-ClipSyncDatabase -SourceDir $dataDir -TargetDir $ExportTo.Trim()
    } else {
        Write-Host "Nothing to export; $dataDir does not exist."
    }
}

if (-not (Test-Path -LiteralPath $dataDir)) {
    Write-Host "No user data at $dataDir"
    exit 0
}

$shouldDelete = [bool]$DeleteData
if (-not $shouldDelete) {
    if ([Console]::IsInputRedirected) {
        Write-Host "Leaving $dataDir in place (non-interactive). Pass -DeleteData to remove history and keys, optionally with -ExportTo first."
        exit 0
    }
    Write-Host ''
    Write-Host "User data is still at $dataDir (clipsync.db, device-id, peer-certificate.bin)."
    Write-Host 'Deleting this folder removes clipboard history AND pairing keys. The Windows app will look like a new device.'
    if (-not $ExportTo) {
        $exportAnswer = Read-Host 'Copy clipsync.db somewhere first? Enter a folder path, or press Enter to skip'
        if ($exportAnswer -and $exportAnswer.Trim()) {
            Copy-ClipSyncDatabase -SourceDir $dataDir -TargetDir $exportAnswer.Trim()
        }
    }
    $confirm = Read-Host "Delete $dataDir now? [y/N]"
    $shouldDelete = $confirm -match '^[yY]'
}

if ($shouldDelete) {
    Write-Host "Deleting $dataDir"
    Remove-Item -LiteralPath $dataDir -Recurse -Force
} else {
    Write-Host "Data left in place at $dataDir"
}

exit 0
