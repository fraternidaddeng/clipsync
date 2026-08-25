# Phase 0 Bluetooth RFCOMM spike — Windows listener wrapper (SPIKE ONLY, never shipped).
# Runbook: docs/bluetooth-phase0-spike.md; results go into docs/bluetooth-phase0-report-template.md.
[CmdletBinding()]
param(
    [ValidateSet('bt1', 'raw')]
    [string]$Mode = 'bt1',

    # Spike-only 32-byte secret as 64 hex chars; defaults to the fixed public spike value
    # that the Android spike also defaults to. NEVER a real ClipSync pair secret.
    [string]$SecretHex = '',

    [ValidateRange(10, 3600)]
    [int]$AcceptTimeoutSeconds = 300,

    # Phase 2 relies on bonded devices connecting WITHOUT discovery, so the default stays
    # non-discoverable; flip this only while troubleshooting.
    [switch]$Discoverable,

    # Where to tee the full console output for pasting into the report.
    [string]$LogPath = ''
)

$ErrorActionPreference = 'Stop'

if (-not [OperatingSystem]::IsWindows()) {
    throw 'This spike exercises WinRT Bluetooth APIs and only runs on Windows 10 22H2+ with a Bluetooth adapter.'
}

$dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
if (-not $dotnet) {
    throw 'The .NET SDK 8.x is required (https://dotnet.microsoft.com/download/dotnet/8.0). `dotnet` was not found on PATH.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$project = Join-Path $PSScriptRoot 'spike-bt1-windows'
if (-not (Test-Path -LiteralPath (Join-Path $project 'ClipSync.Spike.Bt1Windows.csproj'))) {
    throw "Spike project is missing: $project"
}

if (-not $LogPath) {
    $LogPath = Join-Path $repoRoot ("spike-bt1-windows-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
}

$spikeArgs = @('--mode', $Mode, '--accept-timeout', "$AcceptTimeoutSeconds")
if ($SecretHex) { $spikeArgs += @('--secret-hex', $SecretHex) }
if ($Discoverable) { $spikeArgs += '--discoverable' }

Write-Host "Building and starting the ClipSync phase 0 RFCOMM spike listener (mode=$Mode)..."
Write-Host "Console output is also written to: $LogPath"
Write-Host 'Prerequisite: this PC and the phone must already be bonded in Windows Settings > Bluetooth.'

& dotnet run --project $project -c Release -- @spikeArgs 2>&1 | Tee-Object -FilePath $LogPath
$exitCode = $LASTEXITCODE

Write-Host ''
if ($exitCode -eq 0) {
    Write-Host 'Spike session completed. Copy the SPIKE_RESULT lines (or the whole log) into the phase 0 report.'
} else {
    Write-Host "Spike exited with code $exitCode. The SPIKE_RESULT lines above say which gate failed; record them in the report."
}
exit $exitCode
