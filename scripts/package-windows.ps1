<#
.SYNOPSIS
Publishes the Windows app as a self-contained portable ZIP (stage 7 minimal
distribution chain).

.DESCRIPTION
Produces dist/ClipSync-windows-x64.zip: a win-x64 self-contained Release
publish of ClipSync.App (app + .NET runtime + all dependencies, no installer,
no machine-wide state) plus LICENSE, THIRD_PARTY_NOTICES.md and the user
install guide. A matching .sha256 file is written next to the ZIP so users
can verify the download (see docs/install.md).

Runs on Windows and, for CI/verification, on Linux/macOS hosts too: WPF
targets net8.0-windows, so non-Windows hosts get -p:EnableWindowsTargeting=true
automatically. The resulting payload is identical; only Windows can run it.

.EXAMPLE
pwsh ./scripts/package-windows.ps1
pwsh ./scripts/package-windows.ps1 -Version 0.7.0
#>
[CmdletBinding()]
param(
    # Optional version stamp applied to the published assemblies.
    [string]$Version,
    # Keep the unzipped staging folder under dist/ for inspection.
    [switch]$KeepStaging
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$project = Join-Path $repoRoot 'windows/ClipSync.App/ClipSync.App.csproj'
$distDir = Join-Path $repoRoot 'dist'
$stagingRoot = Join-Path $distDir 'staging-windows'
$stagingDir = Join-Path $stagingRoot 'ClipSync'
$zipName = 'ClipSync-windows-x64.zip'
$zipPath = Join-Path $distDir $zipName

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw '.NET 8 SDK is required, but dotnet was not found on PATH.'
}

$sdkList = @(dotnet --list-sdks)
if ($LASTEXITCODE -ne 0 -or $sdkList.Count -eq 0) {
    throw '.NET 8 SDK is required. Only a runtime, or no SDK, is currently installed.'
}

# $IsWindows does not exist on Windows PowerShell 5.1, which is always Windows.
$onWindows = $PSVersionTable.PSVersion.Major -lt 6 -or $IsWindows

if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null

$publishArgs = @(
    'publish', $project,
    '--configuration', 'Release',
    '--runtime', 'win-x64',
    '--self-contained', 'true',
    '--output', $stagingDir
)
if ($Version) {
    $publishArgs += "-p:Version=$Version"
}
if (-not $onWindows) {
    $publishArgs += '-p:EnableWindowsTargeting=true'
}

& dotnet @publishArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# The portable ZIP ships licence texts and the install guide; debug symbols stay out.
Get-ChildItem -LiteralPath $stagingDir -Filter '*.pdb' -Recurse | Remove-Item -Force
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination (Join-Path $stagingDir 'LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $repoRoot 'THIRD_PARTY_NOTICES.md') -Destination $stagingDir
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/install.md') -Destination $stagingDir

if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
# Compress the folder itself so the archive extracts into a single ClipSync/ directory.
Compress-Archive -Path $stagingDir -DestinationPath $zipPath

$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$zipPath.sha256" -Value "$hash *$zipName" -Encoding ascii

if (-not $KeepStaging) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}

$sizeMb = [math]::Round((Get-Item -LiteralPath $zipPath).Length / 1MB, 1)
Write-Host "Portable package : $zipPath ($sizeMb MB)"
Write-Host "SHA-256          : $hash"
Write-Host 'Install guide    : docs/install.md (a copy ships inside the ZIP)'
