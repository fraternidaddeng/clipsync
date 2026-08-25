[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$solution = Join-Path $repoRoot 'windows\ClipSync.sln'

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw '.NET 8 SDK is required, but dotnet was not found on PATH.'
}

$sdkList = @(dotnet --list-sdks)
if ($LASTEXITCODE -ne 0 -or $sdkList.Count -eq 0) {
    throw '.NET 8 SDK is required. Only a runtime, or no SDK, is currently installed.'
}

# On non-Windows hosts the WPF projects (net8.0-windows) are compile-checked via
# EnableWindowsTargeting, but their test bodies can only execute on Windows
# (see docs/verification-without-device.md). CI's windows-latest job runs the full set.
$isWindowsHost = $IsWindows -or ($PSVersionTable.PSEdition -eq 'Desktop')
$extraArgs = @()
if (-not $isWindowsHost) {
    $extraArgs += '-p:EnableWindowsTargeting=true'
}

dotnet restore $solution @extraArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

dotnet build $solution --configuration $Configuration --no-restore @extraArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($isWindowsHost) {
    dotnet test $solution --configuration $Configuration --no-build
    exit $LASTEXITCODE
}

Write-Host 'Non-Windows host: running the cross-platform suite only (ClipSync.Tests); ClipSync.App.Tests requires Windows.'
dotnet test (Join-Path $repoRoot 'windows' 'ClipSync.Tests' 'ClipSync.Tests.csproj') --configuration $Configuration --no-build
exit $LASTEXITCODE
